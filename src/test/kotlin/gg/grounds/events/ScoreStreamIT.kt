package gg.grounds.events

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import gg.grounds.domain.LeaderboardRepository
import gg.grounds.domain.SubmitMode
import gg.grounds.persistence.IdempotencyPruner
import gg.grounds.persistence.PostgresLeaderboardRepository
import gg.grounds.rest.LeaderboardResource
import gg.grounds.support.TestInfra
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The stream consumer and the ledger against a real JetStream and a real Postgres: these are the
 * failure modes the move to an asynchronous score path has to survive.
 */
class ScoreStreamIT {

    private lateinit var dataSource: DataSource
    private lateinit var repo: LeaderboardRepository
    private lateinit var nats: Connection
    private val stream = "LEADERBOARD_SCORES_${UUID.randomUUID().toString().take(8).uppercase()}"
    private val registry = SimpleMeterRegistry()

    @BeforeEach
    fun setUp() {
        dataSource = TestInfra.freshLeaderboardSchema()
        repo = PostgresLeaderboardRepository(dataSource)
        nats = Nats.connect(TestInfra.natsUrl)
        // Production provisions the stream from grounds-pulumi; the test makes an equivalent one.
        nats
            .jetStreamManagement()
            .addStream(
                StreamConfiguration.builder()
                    .name(stream)
                    .subjects(ScoreStreamConsumer.SUBJECT)
                    .storageType(StorageType.Memory)
                    .build()
            )
    }

    @AfterEach
    fun tearDown() {
        nats.jetStreamManagement().deleteStream(stream)
        nats.close()
    }

    private fun consumer(
        repository: LeaderboardRepository = repo,
        retryDelay: Duration = Duration.ofMillis(200),
    ) =
        ScoreStreamConsumer(
            nats = NatsConnector(TestInfra.natsUrl),
            resource = LeaderboardResource(repository, topLimitCap = 1000),
            json = jacksonObjectMapper(),
            registry = registry,
            enabled = true,
            stream = stream,
            durable = "leaderboard-writer",
            batchSize = 50,
            retryDelay = retryDelay,
        )

    private fun publish(
        board: String,
        player: UUID,
        score: Long,
        mode: String,
        key: String?,
        msgId: String,
    ) {
        val body =
            """{"playerId":"$player","score":$score,"mode":"$mode","idempotencyKey":${key?.let { "\"$it\"" } ?: "null"}}"""
        nats
            .jetStream()
            .publish(
                "leaderboard.score.submitted.$board",
                Headers().add("Nats-Msg-Id", msgId),
                body.toByteArray(),
            )
    }

    private fun score(board: String, player: UUID): Long? =
        repo.getPlayerRank(board, "s0", player)?.score

    private fun drainUntilIdle(
        consumer: ScoreStreamConsumer,
        timeout: Duration = Duration.ofSeconds(10),
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        var idle = 0
        while (System.nanoTime() < deadline && idle < 3) {
            if (consumer.drain() == 0) idle++ else idle = 0
        }
    }

    @Test
    fun `scores published while no consumer runs are buffered and drained later`() {
        val players = List(3) { UUID.randomUUID() }
        players.forEachIndexed { i, p -> publish("duel", p, 100L + i, "REPLACE", "m1:$p", "m1:$p") }

        drainUntilIdle(consumer())

        players.forEachIndexed { i, p -> assertEquals(100L + i, score("duel", p)) }
    }

    @Test
    fun `a duplicate delivery does not count twice, even on an accumulating board`() {
        val player = UUID.randomUUID()
        // Two different message ids, so the stream keeps both: this is the redelivery the
        // stream's own deduplication cannot catch (a publish retried after its window).
        publish("wins", player, 1, "ACCUMULATE", "m7:$player", "a")
        publish("wins", player, 1, "ACCUMULATE", "m7:$player", "b")

        drainUntilIdle(consumer())

        assertEquals(1L, score("wins", player))
        assertEquals(
            1.0,
            registry.counter("leaderboard.consumer.messages", "outcome", "applied").count(),
        )
        assertEquals(
            1.0,
            registry.counter("leaderboard.consumer.messages", "outcome", "deduplicated").count(),
        )
    }

    @Test
    fun `a database failure is retried and applied once the database is back`() {
        val player = UUID.randomUUID()
        var failuresLeft = 2
        val flaky =
            object : LeaderboardRepository by repo {
                override fun activeSeason(boardId: String): String {
                    if (failuresLeft-- > 0) throw IllegalStateException("database unreachable")
                    return repo.activeSeason(boardId)
                }
            }
        publish("duel", player, 42, "REPLACE", "m2:$player", "m2:$player")

        drainUntilIdle(consumer(flaky), Duration.ofSeconds(10))

        assertEquals(42L, score("duel", player))
        assertEquals(
            2.0,
            registry.counter("leaderboard.consumer.messages", "outcome", "retry").count(),
        )
    }

    @Test
    fun `an invalid score is terminated and does not block the next one`() {
        val player = UUID.randomUUID()
        publish("duel", player, 5, "REPLACE", key = null, msgId = "no-key")
        publish("duel", player, 6, "REPLACE", "m3:$player", "m3:$player")

        drainUntilIdle(consumer())

        assertEquals(6L, score("duel", player))
        assertEquals(
            1.0,
            registry.counter("leaderboard.consumer.messages", "outcome", "invalid").count(),
        )
    }

    @Test
    fun `two concurrent submits with one key apply once`() {
        val player = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(8) {
            pool.submit {
                start.await()
                repo.submitScore("wins", "s0", player, 1, SubmitMode.ACCUMULATE, "m9:$player")
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(1L, score("wins", player))
    }

    @Test
    fun `the pruner forgets keys older than the window and keeps recent ones`() {
        val old = UUID.randomUUID()
        val recent = UUID.randomUUID()
        repo.submitScore("duel", "s0", old, 1, SubmitMode.REPLACE, "old")
        repo.submitScore("duel", "s0", recent, 1, SubmitMode.REPLACE, "recent")
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "UPDATE leaderboard_submit_idempotency SET seen_at = NOW() - INTERVAL '9 days' WHERE idempotency_key = 'old'"
                )
            }
        }

        val removed = IdempotencyPruner(dataSource, Duration.ofDays(8), enabled = true).prune()

        assertEquals(1, removed)
        assertEquals(
            listOf("recent"),
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT idempotency_key FROM leaderboard_submit_idempotency")
                        .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            },
        )
    }
}
