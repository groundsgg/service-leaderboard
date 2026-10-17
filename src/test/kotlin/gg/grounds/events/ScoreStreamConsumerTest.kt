package gg.grounds.events

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import gg.grounds.domain.LeaderboardRepository
import gg.grounds.domain.SubmitMode
import gg.grounds.domain.SubmitOutcome
import gg.grounds.rest.LeaderboardResource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** What a stream message turns into: applied, deduplicated, discarded, or left for a retry. */
class ScoreStreamConsumerTest {

    private val repo: LeaderboardRepository = mock { on { activeSeason(any()) } doReturn "s1" }
    private val consumer =
        ScoreStreamConsumer(
            nats = NatsConnector("nats://unused:4222"),
            resource = LeaderboardResource(repo, topLimitCap = 1000),
            json = jacksonObjectMapper(),
            registry = SimpleMeterRegistry(),
            enabled = true,
            stream = "LEADERBOARD_SCORES",
            durable = "leaderboard-writer",
            batchSize = 10,
            retryDelay = Duration.ofSeconds(5),
        )

    private fun body(key: String? = "m1:$PLAYER", player: String = PLAYER.toString()) =
        """{"playerId":"$player","score":2512,"mode":"REPLACE","idempotencyKey":${key?.let { "\"$it\"" } ?: "null"}}"""
            .toByteArray()

    @Test
    fun `a score is applied to the board named by the rest of the subject`() {
        whenever(repo.submitScore(any(), any(), any(), any(), any(), anyOrNull()))
            .doReturn(SubmitOutcome(2512, 1, deduplicated = false))

        val outcome = consumer.handle("leaderboard.score.submitted.bedwars.solos", body())

        assertEquals(ScoreStreamConsumer.Outcome.APPLIED, outcome)
        verify(repo)
            .submitScore(
                eq("bedwars.solos"),
                eq("s1"),
                eq(PLAYER),
                eq(2512L),
                eq(SubmitMode.REPLACE),
                eq("m1:$PLAYER"),
            )
    }

    @Test
    fun `a redelivered score is acknowledged as a duplicate, not applied again`() {
        whenever(repo.submitScore(any(), any(), any(), any(), any(), anyOrNull()))
            .doReturn(SubmitOutcome(2512, 1, deduplicated = true))

        assertEquals(
            ScoreStreamConsumer.Outcome.DEDUPLICATED,
            consumer.handle("leaderboard.score.submitted.duel", body()),
        )
    }

    @Test
    fun `a score without an idempotency key is discarded, because a redelivery would count twice`() {
        assertEquals(
            ScoreStreamConsumer.Outcome.INVALID,
            consumer.handle("leaderboard.score.submitted.duel", body(key = null)),
        )
        verify(repo, never()).submitScore(any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `unreadable or invalid scores are discarded instead of retried forever`() {
        assertEquals(
            ScoreStreamConsumer.Outcome.INVALID,
            consumer.handle("leaderboard.score.submitted.duel", "not json".toByteArray()),
        )
        assertEquals(
            ScoreStreamConsumer.Outcome.INVALID,
            consumer.handle("leaderboard.score.submitted.duel", body(player = "not-a-uuid")),
        )
        assertEquals(
            ScoreStreamConsumer.Outcome.INVALID,
            consumer.handle("leaderboard.score.submitted.", body()),
        )
    }

    @Test
    fun `a database failure is thrown so the message is redelivered`() {
        whenever(repo.submitScore(any(), any(), any(), any(), any(), anyOrNull()))
            .doThrow(RuntimeException(SQLException("connection refused")))

        assertThrows<RuntimeException> {
            consumer.handle("leaderboard.score.submitted.duel", body())
        }
    }

    private companion object {
        val PLAYER: UUID = UUID.fromString("6f1d2b3c-0000-4000-8000-000000000001")
    }
}
