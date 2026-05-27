package gg.grounds.domain

import gg.grounds.grpc.leaderboard.SubmitMode
import java.util.UUID

/**
 * Domain port. Implementation in `persistence` package backs this with Postgres; tests can swap a
 * fake. Idempotency, season-resolution and rank-computation all happen below this interface so the
 * gRPC layer stays a thin translator.
 */
interface LeaderboardRepository {

    /**
     * Resolve the currently-active season for a board. Returns the default season ("s0") for boards
     * that have never been reset.
     */
    fun activeSeason(boardId: String): String

    /**
     * Apply a score submission. Honours [mode] (REPLACE / ACCUMULATE / MAX) and idempotency-keys
     * (same key within the configured window → returns the previously-computed outcome).
     */
    fun submitScore(
        boardId: String,
        seasonId: String,
        playerId: UUID,
        score: Long,
        mode: SubmitMode,
        idempotencyKey: String?,
    ): SubmitOutcome

    /**
     * Top N entries on a board for a given season, ranked DESC by score. Rank field on each entry
     * is 1-indexed and reflects the position within the returned slice — equivalent to global rank
     * when querying from rank 1.
     */
    fun getTop(boardId: String, seasonId: String, limit: Int): List<TopEntry>

    /**
     * Single-player rank + score on a board. Null when the player has no entry for that (board,
     * season).
     */
    fun getPlayerRank(boardId: String, seasonId: String, playerId: UUID): PlayerRank?

    /**
     * Roll the active season forward. Idempotent: if [closingSeasonId] is already closed (active
     * season != closingSeasonId), returns `closed=false` without touching state.
     */
    fun seasonReset(
        boardId: String,
        closingSeasonId: String,
        newSeasonId: String,
    ): SeasonResetResult
}

data class SubmitOutcome(val effectiveScore: Long, val rank: Int, val deduplicated: Boolean)

data class TopEntry(
    val rank: Int,
    val playerId: UUID,
    val score: Long,
    val lastUpdatedEpochMs: Long,
)

data class PlayerRank(val rank: Int, val score: Long)

data class SeasonResetResult(val closed: Boolean, val archivedEntries: Int)
