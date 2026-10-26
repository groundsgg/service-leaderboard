package gg.grounds.rest

import gg.grounds.domain.LeaderboardRepository
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Reads across boards: every board one player is on, and every board there is. A profile page and a
 * rankings index need these; reading them per board would mean knowing every board id first.
 *
 * Two root resources whose class paths are fully literal up to the variable: JAX-RS picks the root
 * resource by its class path alone, and `/v1/leaderboards/{boardId}` ([LeaderboardResource]) would
 * otherwise win and read `players` or `boards` as a board id. A board named `players` or `boards`
 * is shadowed by these; no board uses either name. Authenticated by [WorkloadAuthFilter] like
 * everything under `/v1/leaderboards`.
 */
@Path("/v1/leaderboards/players/{playerId}")
@Tag(name = "Leaderboards", description = "Persistent player rankings per board and season.")
@Produces(MediaType.APPLICATION_JSON)
class PlayerBoardsResource @Inject constructor(private val repo: LeaderboardRepository) {

    @GET
    @Operation(
        summary = "Read every board one player is on",
        description =
            "Rank and score per board, each in that board's active season unless `seasonId` " +
                "pins one season for all of them. A player who never scored reads as an empty " +
                "list, not a 404. Sorted by board id.",
    )
    @APIResponse(responseCode = "200", description = "The player's standings.")
    @APIResponse(responseCode = "400", description = "`playerId` is not a UUID.")
    fun playerBoards(
        @PathParam("playerId") playerId: String?,
        @QueryParam("seasonId") seasonId: String?,
    ): PlayerBoardsResponse {
        val player =
            playerId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw InvalidRequestException("playerId must be a UUID.")
        return PlayerBoardsResponse(
            repo.getPlayerBoards(player, seasonId?.takeIf { it.isNotBlank() }).map {
                PlayerBoardResponse(
                    boardId = it.boardId,
                    seasonId = it.seasonId,
                    rank = it.rank,
                    score = it.score,
                )
            }
        )
    }
}

@Path("/v1/leaderboards/boards")
@Tag(name = "Leaderboards", description = "Persistent player rankings per board and season.")
@Produces(MediaType.APPLICATION_JSON)
class BoardListResource @Inject constructor(private val repo: LeaderboardRepository) {

    @GET
    @Operation(
        summary = "List the boards",
        description =
            "Every board with at least one entry in its active season, with how many players " +
                "it ranks there. Sorted by board id.",
    )
    @APIResponse(responseCode = "200", description = "The boards.")
    fun boards(): BoardsResponse =
        BoardsResponse(
            repo.listBoards().map {
                BoardSummaryResponse(
                    boardId = it.boardId,
                    seasonId = it.seasonId,
                    players = it.players,
                )
            }
        )
}
