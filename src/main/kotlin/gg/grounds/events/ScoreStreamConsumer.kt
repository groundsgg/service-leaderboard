package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.rest.InvalidRequestException
import gg.grounds.rest.LeaderboardResource
import gg.grounds.rest.SubmitScoreRequest
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Applies score submissions from the LEADERBOARD_SCORES stream.
 *
 * A message is the HTTP submit body (`SubmitScoreRequest`) on `leaderboard.score.submitted.<board>`
 * and goes through the very same [LeaderboardResource.submitScore] the HTTP API uses, so both
 * transports agree on validation, season resolution and idempotency.
 *
 * Delivery is at-least-once, so every message must carry an idempotency key: a redelivery (a crash
 * between commit and ack, a duplicate publish) then deduplicates in the database instead of
 * counting twice. A message without one, or one that can never be applied, is terminated rather
 * than retried forever. A transient failure (the database) is redelivered after a delay.
 *
 * One message in flight at a time (`max_ack_pending = 1`): a REPLACE board must see a player's
 * scores in the order they were published, and a retried message must not be overtaken by the next.
 */
@ApplicationScoped
class ScoreStreamConsumer
@Inject
constructor(
    private val nats: NatsConnector,
    private val resource: LeaderboardResource,
    private val json: ObjectMapper,
    private val registry: MeterRegistry,
    @param:ConfigProperty(name = "grounds.leaderboard.consumer.enabled")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.leaderboard.consumer.stream") private val stream: String,
    @param:ConfigProperty(name = "grounds.leaderboard.consumer.durable")
    private val durable: String,
    @param:ConfigProperty(name = "grounds.leaderboard.consumer.batch-size")
    private val batchSize: Int,
    @param:ConfigProperty(name = "grounds.leaderboard.consumer.retry-delay")
    private val retryDelay: Duration,
) {

    enum class Outcome {
        APPLIED,
        DEDUPLICATED,
        INVALID,
        RETRY,
    }

    @Volatile private var subscription: JetStreamSubscription? = null

    @Scheduled(
        every = "{grounds.leaderboard.consumer.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun poll() {
        if (!enabled) return
        drain()
    }

    /** Processes up to one batch; returns how many messages were handled. */
    fun drain(): Int {
        val sub = subscription() ?: return 0
        var handled = 0
        while (handled < batchSize) {
            val message =
                try {
                    sub.fetch(1, FETCH_TIMEOUT).firstOrNull()
                } catch (error: Exception) {
                    LOG.warnf(error, "Fetch failed on %s, resubscribing", stream)
                    subscription = null
                    return handled
                } ?: break
            process(message)
            handled++
        }
        if (handled == 0) verifyConsumer()
        return handled
    }

    /**
     * An idle poll checks the durable still exists. A pull from a consumer that was deleted (the
     * stream recreated, an operator's `nats consumer rm`) just returns nothing, so without this the
     * consumer would sit idle forever while scores pile up.
     */
    private fun verifyConsumer() {
        try {
            // Asked of the server, not of the subscription, which may answer from its cache.
            checkNotNull(nats.connection()).jetStreamManagement().getConsumerInfo(stream, durable)
        } catch (error: Exception) {
            LOG.warnf(
                "Consumer %s on %s is gone, resubscribing: %s",
                durable,
                stream,
                error.message,
            )
            subscription = null
        }
    }

    private fun process(message: Message) {
        val outcome =
            try {
                handle(message.subject, message.data)
            } catch (error: Exception) {
                LOG.warnf(
                    error,
                    "Score submission failed, will retry (subject=%s)",
                    message.subject,
                )
                Outcome.RETRY
            }
        when (outcome) {
            Outcome.APPLIED,
            Outcome.DEDUPLICATED -> message.ack()
            Outcome.INVALID -> message.term()
            Outcome.RETRY -> message.nakWithDelay(retryDelay)
        }
        registry
            .counter("leaderboard.consumer.messages", "outcome", outcome.name.lowercase())
            .increment()
    }

    /**
     * Applies one message. Throws on a transient failure (the caller redelivers); returns
     * [Outcome.INVALID] for anything a redelivery could not fix.
     */
    fun handle(subject: String, data: ByteArray): Outcome {
        val boardId = subject.removePrefix(SUBJECT_PREFIX)
        if (!subject.startsWith(SUBJECT_PREFIX) || boardId.isEmpty()) {
            LOG.errorf("Discarding a score on an unexpected subject (subject=%s)", subject)
            return Outcome.INVALID
        }
        val request =
            try {
                json.readValue(data, SubmitScoreRequest::class.java)
            } catch (error: Exception) {
                LOG.errorf(
                    "Discarding an unreadable score (subject=%s): %s",
                    subject,
                    error.message,
                )
                return Outcome.INVALID
            }
        if (request.idempotencyKey.isNullOrBlank()) {
            LOG.errorf("Discarding a score without an idempotency key (subject=%s)", subject)
            return Outcome.INVALID
        }
        return try {
            val response = resource.submitScore(boardId, request)
            if (response.deduplicated) Outcome.DEDUPLICATED else Outcome.APPLIED
        } catch (error: InvalidRequestException) {
            LOG.errorf("Discarding an invalid score (subject=%s): %s", subject, error.message)
            Outcome.INVALID
        }
    }

    private fun subscription(): JetStreamSubscription? {
        subscription?.let {
            return it
        }
        val connection = nats.connection() ?: return null
        return try {
            val options =
                PullSubscribeOptions.builder()
                    .stream(stream)
                    .durable(durable)
                    .configuration(
                        ConsumerConfiguration.builder()
                            .ackPolicy(AckPolicy.Explicit)
                            .ackWait(ACK_WAIT)
                            .maxAckPending(1)
                            .build()
                    )
                    .build()
            connection.jetStream().subscribe(SUBJECT, options).also {
                subscription = it
                LOG.infof("Consuming %s on %s (durable=%s)", SUBJECT, stream, durable)
            }
        } catch (error: Exception) {
            // Most likely the stream or this service's NATS user is not provisioned yet. The HTTP
            // API is unaffected; the next poll tries again.
            LOG.warnf(error, "Cannot consume %s on %s", SUBJECT, stream)
            null
        }
    }

    companion object {
        const val SUBJECT_PREFIX = "leaderboard.score.submitted."
        const val SUBJECT = "leaderboard.score.submitted.>"
        private val FETCH_TIMEOUT: Duration = Duration.ofMillis(500)
        private val ACK_WAIT: Duration = Duration.ofSeconds(30)
        private val LOG: Logger = Logger.getLogger(ScoreStreamConsumer::class.java)
    }
}
