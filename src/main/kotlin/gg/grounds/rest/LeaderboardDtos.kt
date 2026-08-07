package gg.grounds.rest

import org.eclipse.microprofile.openapi.annotations.media.Schema

@Schema(name = "SubmitScoreRequest", description = "One player's score on a board.")
data class SubmitScoreRequest(
    @get:Schema(
        description = "Player UUID, Mojang format with dashes.",
        examples = ["6f1d2b3c-0000-4000-8000-000000000001"],
        required = true,
    )
    val playerId: String?,
    @get:Schema(
        description = "The value to submit. How it combines with the stored score is `mode`.",
        examples = ["1400"],
        required = true,
    )
    val score: Long?,
    @get:Schema(
        description =
            "REPLACE overwrites, ACCUMULATE adds, MAX keeps the higher of the two. " +
                "Required — there is no safe default, since each mode is the wrong answer " +
                "for the other two kinds of board.",
        enumeration = ["REPLACE", "ACCUMULATE", "MAX"],
        required = true,
    )
    val mode: String?,
    @get:Schema(
        description = "Pin to a season. Omitted means the board's active season.",
        examples = ["s0"],
    )
    val seasonId: String? = null,
    @get:Schema(
        description =
            "Deduplication token. The same key within the service's idempotency window " +
                "returns the earlier outcome instead of submitting again, so a retry cannot " +
                "double-count.",
        examples = ["<matchId>:<playerId>"],
    )
    val idempotencyKey: String? = null,
)

@Schema(name = "SubmitScoreResponse", description = "The board after the submission.")
data class SubmitScoreResponse(
    @get:Schema(description = "The player's score once the mode was applied.")
    val effectiveScore: Long,
    @get:Schema(description = "1-indexed rank after the update. 0 when not on the board.")
    val rank: Int,
    @get:Schema(description = "The season the submission was attributed to.") val seasonId: String,
    @get:Schema(
        description = "True when the idempotency key matched an earlier submit and nothing changed."
    )
    val deduplicated: Boolean,
)

@Schema(name = "LeaderboardEntry", description = "One row of a board.")
data class LeaderboardEntryResponse(
    @get:Schema(description = "1-indexed rank within the board.") val rank: Int,
    @get:Schema(description = "Player UUID.") val playerId: String,
    @get:Schema(description = "The player's score.") val score: Long,
    @get:Schema(
        description = "When this player last changed their score, as an ISO-8601 instant.",
        examples = ["2026-08-07T09:41:12Z"],
    )
    val lastUpdated: String,
)

@Schema(name = "TopResponse", description = "The top of a board for one season.")
data class TopResponse(
    @get:Schema(description = "Entries, best first.") val entries: List<LeaderboardEntryResponse>,
    @get:Schema(description = "The season these entries belong to.") val seasonId: String,
)

@Schema(name = "PlayerRankResponse", description = "One player's standing on a board.")
data class PlayerRankResponse(
    @get:Schema(description = "1-indexed rank.") val rank: Int,
    @get:Schema(description = "The player's score.") val score: Long,
    @get:Schema(description = "The season this standing belongs to.") val seasonId: String,
)

@Schema(name = "SeasonResetRequest", description = "Which season closes, and what opens after it.")
data class SeasonResetRequest(
    @get:Schema(
        description =
            "The season being closed. Naming it rather than saying \"the current one\" is " +
                "what makes a retry safe: a second call for a season that already closed is a " +
                "no-op rather than a second rollover.",
        examples = ["s0"],
        required = true,
    )
    val seasonId: String?,
    @get:Schema(description = "The season to open.", examples = ["s1"], required = true)
    val newSeasonId: String?,
)

@Schema(name = "SeasonResetResponse", description = "What the reset did.")
data class SeasonResetResponse(
    @get:Schema(
        description = "True when this call closed the season. False when it had already closed."
    )
    val closed: Boolean,
    @get:Schema(description = "How many entries were archived out of the live board.")
    val archivedEntries: Int,
)
