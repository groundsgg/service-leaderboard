package gg.grounds.api

import com.google.protobuf.Timestamp
import gg.grounds.auth.AuthGuard
import gg.grounds.domain.LeaderboardRepository
import gg.grounds.domain.SubmitMode as DomainSubmitMode
import gg.grounds.domain.SubmitOutcome
import gg.grounds.grpc.leaderboard.GetPlayerRankReply
import gg.grounds.grpc.leaderboard.GetPlayerRankRequest
import gg.grounds.grpc.leaderboard.GetTopReply
import gg.grounds.grpc.leaderboard.GetTopRequest
import gg.grounds.grpc.leaderboard.LeaderboardEntry
import gg.grounds.grpc.leaderboard.LeaderboardServiceGrpc
import gg.grounds.grpc.leaderboard.SeasonResetReply
import gg.grounds.grpc.leaderboard.SeasonResetRequest
import gg.grounds.grpc.leaderboard.SubmitMode
import gg.grounds.grpc.leaderboard.SubmitScoreReply
import gg.grounds.grpc.leaderboard.SubmitScoreRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.quarkus.grpc.GrpcService
import jakarta.inject.Inject
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * gRPC entry-point. Translates proto requests to repository calls; the domain layer owns the actual
 * ranking + idempotency semantics.
 */
@GrpcService
class LeaderboardGrpcService
@Inject
constructor(
    private val repo: LeaderboardRepository,
    @param:ConfigProperty(name = "grounds.leaderboard.top.limit-cap") private val topLimitCap: Int,
) : LeaderboardServiceGrpc.LeaderboardServiceImplBase() {

    override fun submitScore(
        request: SubmitScoreRequest,
        responseObserver: io.grpc.stub.StreamObserver<SubmitScoreReply>,
    ) {
        try {
            val playerId = parsePlayerId(request.playerId)
            val boardId = requireNonEmpty(request.boardId, "board_id")
            val mode = requireKnownMode(request.mode)
            val seasonId = request.seasonId.ifEmpty { repo.activeSeason(boardId) }
            val idempotencyKey = request.idempotencyKey.takeIf { it.isNotEmpty() }

            val outcome: SubmitOutcome =
                repo.submitScore(
                    boardId = boardId,
                    seasonId = seasonId,
                    playerId = playerId,
                    score = request.score,
                    mode = mode,
                    idempotencyKey = idempotencyKey,
                )

            val reply =
                SubmitScoreReply.newBuilder()
                    .setEffectiveScore(outcome.effectiveScore)
                    .setRank(outcome.rank)
                    .setSeasonId(seasonId)
                    .setDeduplicated(outcome.deduplicated)
                    .build()
            responseObserver.onNext(reply)
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            LOG.errorf(
                e,
                "submitScore failed (board=%s, player=%s)",
                request.boardId,
                request.playerId,
            )
            responseObserver.onError(Status.INTERNAL.withDescription(e.message).asException())
        }
    }

    override fun getTop(
        request: GetTopRequest,
        responseObserver: io.grpc.stub.StreamObserver<GetTopReply>,
    ) {
        try {
            val boardId = requireNonEmpty(request.boardId, "board_id")
            val limit = request.limit.let { if (it <= 0) 100 else it }.coerceAtMost(topLimitCap)
            val seasonId = request.seasonId.ifEmpty { repo.activeSeason(boardId) }

            val entries =
                repo.getTop(boardId, seasonId, limit).map { e ->
                    LeaderboardEntry.newBuilder()
                        .setRank(e.rank)
                        .setPlayerId(e.playerId.toString())
                        .setScore(e.score)
                        .setLastUpdated(toProtoTimestamp(e.lastUpdatedEpochMs))
                        .build()
                }
            responseObserver.onNext(
                GetTopReply.newBuilder().addAllEntries(entries).setSeasonId(seasonId).build()
            )
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            LOG.errorf(e, "getTop failed (board=%s)", request.boardId)
            responseObserver.onError(Status.INTERNAL.withDescription(e.message).asException())
        }
    }

    override fun getPlayerRank(
        request: GetPlayerRankRequest,
        responseObserver: io.grpc.stub.StreamObserver<GetPlayerRankReply>,
    ) {
        try {
            val playerId = parsePlayerId(request.playerId)
            val boardId = requireNonEmpty(request.boardId, "board_id")
            val seasonId = request.seasonId.ifEmpty { repo.activeSeason(boardId) }

            val rank = repo.getPlayerRank(boardId, seasonId, playerId)
            val reply =
                if (rank == null) {
                    GetPlayerRankReply.newBuilder().setFound(false).setSeasonId(seasonId).build()
                } else {
                    GetPlayerRankReply.newBuilder()
                        .setFound(true)
                        .setRank(rank.rank)
                        .setScore(rank.score)
                        .setSeasonId(seasonId)
                        .build()
                }
            responseObserver.onNext(reply)
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            LOG.errorf(
                e,
                "getPlayerRank failed (board=%s, player=%s)",
                request.boardId,
                request.playerId,
            )
            responseObserver.onError(Status.INTERNAL.withDescription(e.message).asException())
        }
    }

    override fun seasonReset(
        request: SeasonResetRequest,
        responseObserver: io.grpc.stub.StreamObserver<SeasonResetReply>,
    ) {
        try {
            // Admin-only. Subject suffix `:platform-admin` or
            // `:leaderboard-admin` passes; everyone else gets
            // PERMISSION_DENIED. JWT validation already happened in
            // GroundsAuthInterceptor, so AuthContext.current() is set
            // when auth is enabled.
            AuthGuard.requireAdmin("seasonReset")
            val boardId = requireNonEmpty(request.boardId, "board_id")
            val seasonId = requireNonEmpty(request.seasonId, "season_id")
            val newSeasonId = requireNonEmpty(request.newSeasonId, "new_season_id")

            val result = repo.seasonReset(boardId, seasonId, newSeasonId)
            responseObserver.onNext(
                SeasonResetReply.newBuilder()
                    .setClosed(result.closed)
                    .setArchivedEntries(result.archivedEntries)
                    .build()
            )
            responseObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            responseObserver.onError(e)
        } catch (e: Exception) {
            LOG.errorf(e, "seasonReset failed (board=%s)", request.boardId)
            responseObserver.onError(Status.INTERNAL.withDescription(e.message).asException())
        }
    }

    private fun parsePlayerId(raw: String): UUID =
        try {
            UUID.fromString(requireNonEmpty(raw, "player_id"))
        } catch (_: IllegalArgumentException) {
            throw Status.INVALID_ARGUMENT.withDescription("player_id must be a UUID")
                .asRuntimeException()
        }

    private fun requireNonEmpty(value: String, field: String): String {
        if (value.isEmpty()) {
            throw Status.INVALID_ARGUMENT.withDescription("$field must not be empty")
                .asRuntimeException()
        }
        return value
    }

    private fun requireKnownMode(mode: SubmitMode): DomainSubmitMode =
        when (mode) {
            SubmitMode.SUBMIT_MODE_REPLACE -> DomainSubmitMode.REPLACE
            SubmitMode.SUBMIT_MODE_ACCUMULATE -> DomainSubmitMode.ACCUMULATE
            SubmitMode.SUBMIT_MODE_MAX -> DomainSubmitMode.MAX
            SubmitMode.SUBMIT_MODE_UNSPECIFIED,
            SubmitMode.UNRECOGNIZED ->
                throw Status.INVALID_ARGUMENT.withDescription(
                        "mode must be REPLACE, ACCUMULATE, or MAX"
                    )
                    .asRuntimeException()
        }

    private fun toProtoTimestamp(epochMs: Long): Timestamp {
        val secs = epochMs / 1000
        val nanos = ((epochMs % 1000) * 1_000_000).toInt()
        return Timestamp.newBuilder().setSeconds(secs).setNanos(nanos).build()
    }

    companion object {
        private val LOG = Logger.getLogger(LeaderboardGrpcService::class.java)
    }
}
