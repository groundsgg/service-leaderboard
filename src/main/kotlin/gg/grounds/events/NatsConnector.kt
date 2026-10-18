package gg.grounds.events

import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import io.quarkus.runtime.ShutdownEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * One NATS connection, opened on first use and only by the score consumer.
 *
 * Lazy and failure-tolerant on purpose: the HTTP API is this service's main job and must start and
 * serve without a broker. Credentials, where the broker wants them, are part of the URL.
 */
@ApplicationScoped
class NatsConnector(@param:ConfigProperty(name = "nats.url") private val natsUrl: String) {

    @Volatile private var connection: Connection? = null

    @Synchronized
    fun connection(): Connection? {
        connection?.let { existing ->
            if (existing.status != Connection.Status.CLOSED) return existing
        }
        return try {
            val options =
                Options.Builder()
                    .server(natsUrl)
                    .connectionName("service-leaderboard")
                    .maxReconnects(-1)
                    .reconnectWait(Duration.ofSeconds(2))
                    .connectionListener(
                        ConnectionListener { _, type ->
                            LOG.infof("NATS connection event (event=%s)", type)
                        }
                    )
                    .build()
            Nats.connect(options).also {
                connection = it
                LOG.info("Connected to NATS")
            }
        } catch (error: Exception) {
            // The URL may carry credentials, so it is never logged.
            LOG.errorf(error, "Failed to connect to NATS")
            connection = null
            null
        }
    }

    fun onStop(@Observes event: ShutdownEvent) {
        try {
            connection?.close()
        } catch (error: Exception) {
            LOG.warnf(error, "Failed to close the NATS connection")
        } finally {
            connection = null
        }
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(NatsConnector::class.java)
    }
}
