package gg.grounds.support

import java.sql.DriverManager
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * A real Postgres and a real JetStream broker for the integration tests.
 *
 * Containers by default (CI has Docker). `TEST_POSTGRES_URL` (a JDBC URL with user and password)
 * and `TEST_NATS_URL` point the tests at existing instances instead, for a machine without Docker.
 * Started once per JVM and shared.
 */
object TestInfra {

    val postgresUrl: String by lazy {
        System.getenv("TEST_POSTGRES_URL")
            ?: PostgreSQLContainer<Nothing>("postgres:17-alpine").run {
                start()
                "$jdbcUrl&user=$username&password=$password"
            }
    }

    val natsUrl: String by lazy {
        System.getenv("TEST_NATS_URL")
            ?: GenericContainer<Nothing>("nats:2.15.1-alpine").run {
                withCommand("-js")
                withExposedPorts(4222)
                waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))
                start()
                "nats://$host:${getMappedPort(4222)}"
            }
    }

    /** A fresh `leaderboard` schema with the production migrations applied. */
    fun freshLeaderboardSchema(): PGSimpleDataSource {
        DriverManager.getConnection(postgresUrl).use { c ->
            c.createStatement().use { s ->
                s.execute("DROP SCHEMA IF EXISTS leaderboard CASCADE")
                s.execute("CREATE SCHEMA leaderboard")
                s.execute("SET search_path TO leaderboard")
                migrations().forEach { s.execute(it) }
            }
        }
        return PGSimpleDataSource().apply {
            setURL(postgresUrl)
            currentSchema = "leaderboard"
        }
    }

    private fun migrations(): List<String> =
        listOf("V1__create_leaderboard.sql", "V2__leaderboard_entries_player_idx.sql").map { name ->
            TestInfra::class.java.getResource("/db/migration/$name")!!.readText()
        }
}
