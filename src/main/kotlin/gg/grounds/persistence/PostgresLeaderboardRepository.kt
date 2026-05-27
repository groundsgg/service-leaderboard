package gg.grounds.persistence

import gg.grounds.domain.LeaderboardRepository
import gg.grounds.domain.PlayerRank
import gg.grounds.domain.SeasonResetResult
import gg.grounds.domain.SubmitOutcome
import gg.grounds.domain.TopEntry
import gg.grounds.grpc.leaderboard.SubmitMode
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
        // Idempotency: if we've seen this key within the window, return
        // the prior outcome. Lookup happens before the score-write so
        // a successful dedup never touches the leaderboard table.
        if (idempotencyKey != null) {
            dataSource.connection.use { c ->
                lookupIdempotency(c, idempotencyKey)?.let { prior ->
                    return SubmitOutcome(
                        effectiveScore = prior,
                        rank = computeRank(c, boardId, seasonId, prior),
                        deduplicated = true,
                    )
                }
            }
        }

        return dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val effective = applyMode(c, boardId, seasonId, playerId, score, mode)
                if (idempotencyKey != null) {
                    recordIdempotency(c, idempotencyKey, boardId, playerId, effective)
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
                SubmitMode.SUBMIT_MODE_REPLACE ->
                    """
                INSERT INTO leaderboard_entries (board_id, season_id, player_id, score, last_updated)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (board_id, season_id, player_id) DO UPDATE
                SET score = EXCLUDED.score, last_updated = NOW()
                RETURNING score
            """
                SubmitMode.SUBMIT_MODE_ACCUMULATE ->
                    """
                INSERT INTO leaderboard_entries (board_id, season_id, player_id, score, last_updated)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (board_id, season_id, player_id) DO UPDATE
                SET score = leaderboard_entries.score + EXCLUDED.score, last_updated = NOW()
                RETURNING score
            """
                SubmitMode.SUBMIT_MODE_MAX ->
                    """
                INSERT INTO leaderboard_entries (board_id, season_id, player_id, score, last_updated)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (board_id, season_id, player_id) DO UPDATE
                SET score = GREATEST(leaderboard_entries.score, EXCLUDED.score),
                    last_updated = CASE WHEN EXCLUDED.score > leaderboard_entries.score THEN NOW() ELSE leaderboard_entries.last_updated END
                RETURNING score
            """
                else -> error("unreachable — guarded at the gRPC layer")
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

    private fun recordIdempotency(
        c: Connection,
        key: String,
        boardId: String,
        playerId: UUID,
        effectiveScore: Long,
    ) {
        c.prepareStatement(
                """
                INSERT INTO leaderboard_submit_idempotency (idempotency_key, board_id, player_id, effective_score)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """
                    .trimIndent()
            )
            .use { ps ->
                ps.setString(1, key)
                ps.setString(2, boardId)
                ps.setObject(3, playerId)
                ps.setLong(4, effectiveScore)
                ps.executeUpdate()
            }
    }

    companion object {
        private val LOG = Logger.getLogger(PostgresLeaderboardRepository::class.java)
        /** Default season name applied to boards that have never been reset. */
        private const val DEFAULT_SEASON = "s0"
    }
}
