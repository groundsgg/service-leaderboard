package gg.grounds.persistence

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import javax.sql.DataSource
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Forgets idempotency keys once they are older than the idempotency window.
 *
 * Every rated result leaves one row per player, so without this the ledger only ever grows. The
 * window has to outlive every redelivery the system can produce (see `application.properties`):
 * pruning a key early is what would let a late duplicate count twice.
 *
 * Several instances may run this at once (the satellite copy and the core consumer share one
 * schema); a DELETE by age is safe to repeat.
 */
@ApplicationScoped
class IdempotencyPruner
@Inject
constructor(
    private val dataSource: DataSource,
    @param:ConfigProperty(name = "grounds.leaderboard.submit.idempotency-window")
    private val window: Duration,
    @param:ConfigProperty(name = "grounds.leaderboard.submit.idempotency-prune.enabled")
    private val enabled: Boolean,
) {

    @Scheduled(
        every = "{grounds.leaderboard.submit.idempotency-prune.every}",
        delayed = "1m",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun scheduledPrune() {
        if (!enabled) return
        try {
            val removed = prune()
            if (removed > 0)
                LOG.infof("Pruned idempotency keys (removed=%d, window=%s)", removed, window)
        } catch (e: Exception) {
            LOG.warnf(e, "Idempotency prune failed, will retry on the next run")
        }
    }

    /** Deletes every key last seen before now minus the window; returns how many went. */
    fun prune(): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "DELETE FROM leaderboard_submit_idempotency WHERE seen_at < NOW() - make_interval(secs => ?)"
                )
                .use { ps ->
                    ps.setLong(1, window.seconds)
                    ps.executeUpdate()
                }
        }

    private companion object {
        val LOG: Logger = Logger.getLogger(IdempotencyPruner::class.java)
    }
}
