package gg.grounds.persistence

import gg.grounds.domain.BoardSummary
import gg.grounds.domain.LeaderboardRepository
import gg.grounds.domain.PlayerBoard
import gg.grounds.domain.PlayerRank
import gg.grounds.domain.SeasonResetResult
import gg.grounds.domain.SubmitMode
import gg.grounds.domain.SubmitOutcome
import gg.grounds.domain.TopEntry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class PostgresLeaderboardRepository @Inject constructor(private val dataSource: DataSource) :
    LeaderboardRepository {

    override fun activeSeason(boardId: String): String =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "SELECT season_id FROM leaderboard_active_seasons WHERE board_id = ?"
                )
                .use { ps ->
                    ps.setString(1, boardId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) else DEFAULT_SEASON
                    }
                }
        }

    override fun submitScore(
        boardId: String,
        seasonId: String,
        playerId: UUID,
        score: Long,
        mode: SubmitMode,
        idempotencyKey: String?,
    ): SubmitOutcome {
        return dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                // Idempotency: claim the key first, in the same transaction as the write. A
                // concurrent submit with the same key blocks on the primary key until this one
                // commits and then claims nothing, so two deliveries of one event (the HTTP path
                // and the stream consumer, or two consumer replicas) can never both apply it. A
                // read-then-insert check could not promise that.
                if (
                    idempotencyKey != null &&
                        !claimIdempotency(c, idempotencyKey, boardId, playerId)
                ) {
                    c.rollback()
                    val prior =
                        lookupIdempotency(c, idempotencyKey)
                            ?: error("idempotency key $idempotencyKey vanished after a conflict")
                    val rank = computeRank(c, boardId, seasonId, prior)
                    c.commit()
                    return@use SubmitOutcome(prior, rank, deduplicated = true)
                }
                val effective = applyMode(c, boardId, seasonId, playerId, score, mode)
                if (idempotencyKey != null) {
                    recordEffectiveScore(c, idempotencyKey, effective)
                }
                val rank = computeRank(c, boardId, seasonId, effective)
                c.commit()
                SubmitOutcome(effective, rank, deduplicated = false)
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    override fun getTop(boardId: String, seasonId: String, limit: Int): List<TopEntry> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT player_id, score, last_updated
                    FROM leaderboard_entries
                    WHERE board_id = ? AND season_id = ?
                    ORDER BY score DESC, last_updated ASC
                    LIMIT ?
                    """
                        .trimIndent()
                )
                .use { ps ->
                    ps.setString(1, boardId)
                    ps.setString(2, seasonId)
                    ps.setInt(3, limit)
                    ps.executeQuery().use { rs ->
                        val out = ArrayList<TopEntry>(limit)
                        var rank = 1
                        while (rs.next()) {
                            out.add(
                                TopEntry(
                                    rank = rank++,
                                    playerId = rs.getObject("player_id", UUID::class.java),
                                    score = rs.getLong("score"),
                                    lastUpdatedEpochMs = rs.getTimestamp("last_updated").time,
                                )
                            )
                        }
                        out
                    }
                }
        }

    override fun getPlayerRank(boardId: String, seasonId: String, playerId: UUID): PlayerRank? =
        dataSource.connection.use { c ->
            // Two queries: read the player's score, then count how many
            // entries beat it. Single-pass with a window function would
            // be cleaner but Postgres optimises this pattern well with
            // the (board, season, score DESC) index.
            val score =
                c.prepareStatement(
                        "SELECT score FROM leaderboard_entries WHERE board_id = ? AND season_id = ? AND player_id = ?"
                    )
                    .use { ps ->
                        ps.setString(1, boardId)
                        ps.setString(2, seasonId)
                        ps.setObject(3, playerId)
                        ps.executeQuery().use { rs ->
                            if (rs.next()) rs.getLong(1) else return@use null
                        }
                    } ?: return null

            val rank = computeRank(c, boardId, seasonId, score)
            PlayerRank(rank = rank, score = score)
        }

    override fun getPlayerBoards(playerId: UUID, seasonId: String?): List<PlayerBoard> =
        dataSource.connection.use { c ->
            // Rank as in getPlayerRank (ties share a rank), one correlated count per board the
            // player is on. A player is on a handful of boards, each count an index range scan.
            c.prepareStatement(
                    """
                    SELECT e.board_id, e.season_id, e.score,
                           (SELECT COUNT(*) + 1 FROM leaderboard_entries o
                            WHERE o.board_id = e.board_id AND o.season_id = e.season_id
                              AND o.score > e.score) AS rank
                    FROM leaderboard_entries e
                    LEFT JOIN leaderboard_active_seasons a ON a.board_id = e.board_id
                    WHERE e.player_id = ? AND e.season_id = COALESCE(?, a.season_id, '$DEFAULT_SEASON')
                    ORDER BY e.board_id
                    """
                        .trimIndent()
                )
                .use { ps ->
                    ps.setObject(1, playerId)
                    ps.setString(2, seasonId)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    PlayerBoard(
                                        boardId = rs.getString("board_id"),
                                        seasonId = rs.getString("season_id"),
                                        rank = rs.getInt("rank"),
                                        score = rs.getLong("score"),
                                    )
                                )
                            }
                        }
                    }
                }
        }

    override fun listBoards(): List<BoardSummary> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    """
                    SELECT e.board_id, e.season_id, COUNT(*) AS players
                    FROM leaderboard_entries e
                    LEFT JOIN leaderboard_active_seasons a ON a.board_id = e.board_id
                    WHERE e.season_id = COALESCE(a.season_id, '$DEFAULT_SEASON')
                    GROUP BY e.board_id, e.season_id
                    ORDER BY e.board_id
                    """
                        .trimIndent()
                )
                .use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    BoardSummary(
                                        boardId = rs.getString("board_id"),
                                        seasonId = rs.getString("season_id"),
                                        players = rs.getInt("players"),
                                    )
                                )
                            }
                        }
                    }
                }
        }

    override fun seasonReset(
        boardId: String,
        closingSeasonId: String,
        newSeasonId: String,
    ): SeasonResetResult =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current =
                    c.prepareStatement(
                            "SELECT season_id FROM leaderboard_active_seasons WHERE board_id = ?"
                        )
                        .use { ps ->
                            ps.setString(1, boardId)
                            ps.executeQuery().use { rs ->
                                if (rs.next()) rs.getString(1) else DEFAULT_SEASON
                            }
                        }
                if (current != closingSeasonId) {
                    // Already rolled past closingSeasonId — no-op.
                    c.commit()
                    return@use SeasonResetResult(closed = false, archivedEntries = 0)
                }

                // The old season's rows stay in leaderboard_entries (they're
                // already keyed by season_id). All we do is advance the
                // active-season pointer. Archiving here means logically
                // making the new season "current" — historical reads keep
                // working by passing the closed season_id explicitly.
                val archived =
                    c.prepareStatement(
                            "SELECT COUNT(*) FROM leaderboard_entries WHERE board_id = ? AND season_id = ?"
                        )
                        .use { ps ->
                            ps.setString(1, boardId)
                            ps.setString(2, closingSeasonId)
                            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                        }

                c.prepareStatement(
                        """
                        INSERT INTO leaderboard_active_seasons (board_id, season_id)
                        VALUES (?, ?)
                        ON CONFLICT (board_id) DO UPDATE SET season_id = EXCLUDED.season_id, rolled_at = NOW()
                        """
                            .trimIndent()
                    )
                    .use { ps ->
                        ps.setString(1, boardId)
                        ps.setString(2, newSeasonId)
                        ps.executeUpdate()
                    }
                c.commit()
                SeasonResetResult(closed = true, archivedEntries = archived)
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }

    private fun applyMode(
        c: Connection,
        boardId: String,
        seasonId: String,
        playerId: UUID,
        score: Long,
        mode: SubmitMode,
    ): Long {
        val sql =
            when (mode) {
                SubmitMode.REPLACE ->
                    """
                INSERT INTO leaderboard_entries (board_id, season_id, player_id, score, last_updated)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (board_id, season_id, player_id) DO UPDATE
                SET score = EXCLUDED.score, last_updated = NOW()
                RETURNING score
            """
                SubmitMode.ACCUMULATE ->
                    """
                INSERT INTO leaderboard_entries (board_id, season_id, player_id, score, last_updated)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (board_id, season_id, player_id) DO UPDATE
                SET score = leaderboard_entries.score + EXCLUDED.score, last_updated = NOW()
                RETURNING score
            """
                SubmitMode.MAX ->
                    """
                INSERT INTO leaderboard_entries (board_id, season_id, player_id, score, last_updated)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (board_id, season_id, player_id) DO UPDATE
                SET score = GREATEST(leaderboard_entries.score, EXCLUDED.score),
                    last_updated = CASE WHEN EXCLUDED.score > leaderboard_entries.score THEN NOW() ELSE leaderboard_entries.last_updated END
                RETURNING score
            """
            }
        return c.prepareStatement(sql.trimIndent()).use { ps ->
            ps.setString(1, boardId)
            ps.setString(2, seasonId)
            ps.setObject(3, playerId)
            ps.setLong(4, score)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "applyMode RETURNING did not produce a row" }
                rs.getLong(1)
            }
        }
    }

    private fun computeRank(c: Connection, boardId: String, seasonId: String, score: Long): Int =
        c.prepareStatement(
                "SELECT COUNT(*) + 1 FROM leaderboard_entries WHERE board_id = ? AND season_id = ? AND score > ?"
            )
            .use { ps ->
                ps.setString(1, boardId)
                ps.setString(2, seasonId)
                ps.setLong(3, score)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }

    private fun lookupIdempotency(c: Connection, key: String): Long? =
        c.prepareStatement(
                "SELECT effective_score FROM leaderboard_submit_idempotency WHERE idempotency_key = ?"
            )
            .use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }

    /** True when this transaction now owns [key]; false when an earlier submit already did. */
    private fun claimIdempotency(c: Connection, key: String, boardId: String, playerId: UUID) =
        c.prepareStatement(
                """
                INSERT INTO leaderboard_submit_idempotency (idempotency_key, board_id, player_id, effective_score)
                VALUES (?, ?, ?, 0)
                ON CONFLICT (idempotency_key) DO NOTHING
                """
                    .trimIndent()
            )
            .use { ps ->
                ps.setString(1, key)
                ps.setString(2, boardId)
                ps.setObject(3, playerId)
                ps.executeUpdate() == 1
            }

    private fun recordEffectiveScore(c: Connection, key: String, effectiveScore: Long) {
        c.prepareStatement(
                "UPDATE leaderboard_submit_idempotency SET effective_score = ? WHERE idempotency_key = ?"
            )
            .use { ps ->
                ps.setLong(1, effectiveScore)
                ps.setString(2, key)
                ps.executeUpdate()
            }
    }

    companion object {
        private val LOG = Logger.getLogger(PostgresLeaderboardRepository::class.java)
        /** Default season name applied to boards that have never been reset. */
        private const val DEFAULT_SEASON = "s0"
    }
}
