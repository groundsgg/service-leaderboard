package gg.grounds.rest

import gg.grounds.auth.AuthGuard
import gg.grounds.domain.LeaderboardRepository
import gg.grounds.domain.SubmitMode
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.SecurityContext
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * The leaderboard over HTTP. A translator, like the gRPC adapter beside it: ranking, idempotency
 * and season resolution all live in [LeaderboardRepository].
 *
 * Everything under `/v1/leaderboards` is authenticated by [WorkloadAuthFilter]; the season reset is
 * additionally admin-only, checked here because it is the one decision that depends on *which*
 * workload is calling rather than merely that one is.
 */
@Path("/v1/leaderboards/{boardId}")
@Tag(name = "Leaderboards", description = "Persistent player rankings per board and season.")
@Produces(MediaType.APPLICATION_JSON)
class LeaderboardResource
@Inject
constructor(
    private val repo: LeaderboardRepository,
    @param:ConfigProperty(name = "grounds.leaderboard.top.limit-cap") private val topLimitCap: Int,
) {

    @POST
    @Path("/scores")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Submit a score",
        description =
            "Combines the submitted value with the player's stored score according to `mode`. " +
                "Send an `idempotencyKey` if the caller can retry — the leaderboard is one of " +
                "the few places where a retried write is visible to players forever.",
    )
    @APIResponse(responseCode = "200", description = "The board after the submission.")
    @APIResponse(responseCode = "400", description = "Malformed player id, mode or board.")
    fun submitScore(
        @PathParam("boardId") boardId: String?,
        request: SubmitScoreRequest?,
    ): SubmitScoreResponse {
        val board = requireBoardId(boardId)
        val body = request ?: throw InvalidRequestException("A request body is required.")
        val playerId = parsePlayerId(body.playerId)
        val score = body.score ?: throw InvalidRequestException("score is required.")
        val mode = parseMode(body.mode)
        val seasonId = body.seasonId?.takeIf { it.isNotBlank() } ?: repo.activeSeason(board)

        val outcome =
            repo.submitScore(
                boardId = board,
                seasonId = seasonId,
                playerId = playerId,
                score = score,
                mode = mode,
                idempotencyKey = body.idempotencyKey?.takeIf { it.isNotBlank() },
            )

        return SubmitScoreResponse(
            effectiveScore = outcome.effectiveScore,
            rank = outcome.rank,
            seasonId = seasonId,
            deduplicated = outcome.deduplicated,
        )
    }

    @GET
    @Path("/top")
    @Operation(
        summary = "Read the top of a board",
        description =
            "Entries best-first. `limit` is capped server-side, so a caller asking for " +
                "everything gets the ceiling rather than an error.",
    )
    @APIResponse(responseCode = "200", description = "The top entries for the season.")
    fun top(
        @PathParam("boardId") boardId: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
        @QueryParam("seasonId") seasonId: String?,
    ): TopResponse {
        val board = requireBoardId(boardId)
        val season = seasonId?.takeIf { it.isNotBlank() } ?: repo.activeSeason(board)
        val capped = (if (limit <= 0) DEFAULT_TOP_LIMIT else limit).coerceAtMost(topLimitCap)

        val entries =
            repo.getTop(board, season, capped).map {
                LeaderboardEntryResponse(
                    rank = it.rank,
                    playerId = it.playerId.toString(),
                    score = it.score,
                    lastUpdated = Instant.ofEpochMilli(it.lastUpdatedEpochMs).toString(),
                )
            }
        return TopResponse(entries = entries, seasonId = season)
    }

    @GET
    @Path("/players/{playerId}")
    @Operation(
        summary = "Read one player's standing",
        description = "404 when the player has never scored on this board in this season.",
    )
    @APIResponse(responseCode = "200", description = "The player's rank and score.")
    @APIResponse(responseCode = "404", description = "The player has no entry on this board.")
    fun playerRank(
        @PathParam("boardId") boardId: String?,
        @PathParam("playerId") playerId: String?,
        @QueryParam("seasonId") seasonId: String?,
    ): PlayerRankResponse {
        val board = requireBoardId(boardId)
        val player = parsePlayerId(playerId)
        val season = seasonId?.takeIf { it.isNotBlank() } ?: repo.activeSeason(board)

        val rank =
            repo.getPlayerRank(board, season, player)
                ?: throw NotRankedException(
                    "No entry for $player on board $board in season $season."
                )
        return PlayerRankResponse(rank = rank.rank, score = rank.score, seasonId = season)
    }

    @POST
    @Path("/season-resets")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Roll the board to a new season",
        description =
            "Archives the closing season's entries and opens a new one. Admin-only: the " +
                "caller's ServiceAccount must be one of the admin accounts. Idempotent — " +
                "closing a season that already closed reports `closed: false` and changes nothing.",
    )
    @APIResponse(responseCode = "200", description = "What the reset did.")
    @APIResponse(responseCode = "403", description = "The caller is not an admin ServiceAccount.")
    fun seasonReset(
        @PathParam("boardId") boardId: String?,
        request: SeasonResetRequest?,
        @Context securityContext: SecurityContext,
    ): SeasonResetResponse {
        val subject = securityContext.userPrincipal?.name.orEmpty()
        if (!AuthGuard.isAdminSubject(subject)) {
            throw ForbiddenException("A season reset requires an admin ServiceAccount.")
        }

        val board = requireBoardId(boardId)
        val body = request ?: throw InvalidRequestException("A request body is required.")
        val closing = requireNonBlank(body.seasonId, "seasonId")
        val opening = requireNonBlank(body.newSeasonId, "newSeasonId")

        val result = repo.seasonReset(board, closing, opening)
        return SeasonResetResponse(closed = result.closed, archivedEntries = result.archivedEntries)
    }

    private fun requireBoardId(raw: String?): String = requireNonBlank(raw, "boardId")

    private fun requireNonBlank(raw: String?, field: String): String =
        raw?.takeIf { it.isNotBlank() }
            ?: throw InvalidRequestException("$field must not be empty.")

    private fun parsePlayerId(raw: String?): UUID {
        val value = requireNonBlank(raw, "playerId")
        return try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("playerId must be a UUID.")
        }
    }

    private fun parseMode(raw: String?): SubmitMode {
        val value = requireNonBlank(raw, "mode")
        return try {
            SubmitMode.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("mode must be REPLACE, ACCUMULATE or MAX.")
        }
    }

    private companion object {
        const val DEFAULT_TOP_LIMIT = 100
    }
}
