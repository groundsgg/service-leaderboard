package gg.grounds.rest

import gg.grounds.domain.LeaderboardRepository
import gg.grounds.domain.PlayerRank
import gg.grounds.domain.SeasonResetResult
import gg.grounds.domain.SubmitMode
import gg.grounds.domain.SubmitOutcome
import gg.grounds.domain.TopEntry
import jakarta.ws.rs.core.SecurityContext
import java.security.Principal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The HTTP surface against a fake board. What is being pinned here is translation, not ranking: the
 * season a call lands in when the caller did not name one, what counts as a bad request, and who is
 * allowed to end a season.
 */
class LeaderboardResourceTest {

    private val repo = FakeRepository()
    private val resource = LeaderboardResource(repo, topLimitCap = 1000)

    @Test
    fun `a submission with no season lands in the board's active season`() {
        repo.activeSeason = "s7"

        val response =
            resource.submitScore(BOARD, SubmitScoreRequest(PLAYER.toString(), 1400, "REPLACE"))

        assertEquals("s7", response.seasonId)
        assertEquals("s7", repo.lastSubmitSeason)
        assertEquals(1400, response.effectiveScore)
    }

    @Test
    fun `a submission that names a season is not redirected to the active one`() {
        repo.activeSeason = "s7"

        val response =
            resource.submitScore(
                BOARD,
                SubmitScoreRequest(PLAYER.toString(), 1400, "REPLACE", seasonId = "s3"),
            )

        assertEquals("s3", response.seasonId)
        assertEquals("s3", repo.lastSubmitSeason)
    }

    @Test
    fun `the mode is passed through, not defaulted`() {
        resource.submitScore(BOARD, SubmitScoreRequest(PLAYER.toString(), 5, "accumulate"))

        assertEquals(SubmitMode.ACCUMULATE, repo.lastSubmitMode)
    }

    @Test
    fun `an unknown mode is a bad request rather than a silent replace`() {
        val error =
            assertThrows<InvalidRequestException> {
                resource.submitScore(BOARD, SubmitScoreRequest(PLAYER.toString(), 5, "AVERAGE"))
            }

        assertTrue(error.message!!.contains("REPLACE"))
        assertNull(repo.lastSubmitMode)
    }

    @Test
    fun `a missing mode is a bad request`() {
        assertThrows<InvalidRequestException> {
            resource.submitScore(BOARD, SubmitScoreRequest(PLAYER.toString(), 5, null))
        }
    }

    @Test
    fun `a malformed player id never reaches the board`() {
        assertThrows<InvalidRequestException> {
            resource.submitScore(BOARD, SubmitScoreRequest("not-a-uuid", 5, "REPLACE"))
        }

        assertNull(repo.lastSubmitMode)
    }

    @Test
    fun `a blank idempotency key is treated as none at all`() {
        // An empty string is what a caller sends when it meant to send nothing; passing it
        // through would make every such submission dedup against the others.
        resource.submitScore(
            BOARD,
            SubmitScoreRequest(PLAYER.toString(), 5, "REPLACE", idempotencyKey = "  "),
        )

        assertNull(repo.lastIdempotencyKey)
    }

    @Test
    fun `top is capped server-side rather than refused`() {
        resource.top(BOARD, limit = 100_000, seasonId = null)

        assertEquals(1000, repo.lastTopLimit)
    }

    @Test
    fun `a non-positive limit falls back to the default page`() {
        resource.top(BOARD, limit = 0, seasonId = null)

        assertEquals(100, repo.lastTopLimit)
    }

    @Test
    fun `top renders the last update as an instant`() {
        repo.top = listOf(TopEntry(rank = 1, playerId = PLAYER, score = 9, lastUpdatedEpochMs = 0))

        val response = resource.top(BOARD, limit = 10, seasonId = null)

        assertEquals("1970-01-01T00:00:00Z", response.entries.single().lastUpdated)
    }

    @Test
    fun `a player with no entry is a 404, not a rank of zero`() {
        repo.playerRank = null

        assertThrows<NotRankedException> {
            resource.playerRank(BOARD, PLAYER.toString(), seasonId = null)
        }
    }

    @Test
    fun `a season reset from a non-admin caller is refused`() {
        assertThrows<ForbiddenException> {
            resource.seasonReset(
                BOARD,
                SeasonResetRequest("s0", "s1"),
                subject("system:serviceaccount:stage:service-match"),
            )
        }

        assertNull(repo.lastResetClosing)
    }

    @Test
    fun `a season reset with auth disabled is refused too`() {
        // WorkloadAuthFilter hands every caller `local-development` when auth is off. That must
        // not be an admin, or a local run would be the one place a season can be ended by
        // accident.
        assertThrows<ForbiddenException> {
            resource.seasonReset(
                BOARD,
                SeasonResetRequest("s0", "s1"),
                subject(WorkloadAuthFilter.LOCAL_DEVELOPMENT_SUBJECT),
            )
        }
    }

    @Test
    fun `an admin caller may end a season`() {
        repo.resetResult = SeasonResetResult(closed = true, archivedEntries = 42)

        val response =
            resource.seasonReset(
                BOARD,
                SeasonResetRequest("s0", "s1"),
                subject("system:serviceaccount:platform-admin:platform-admin"),
            )

        assertTrue(response.closed)
        assertEquals(42, response.archivedEntries)
        assertEquals("s0", repo.lastResetClosing)
        assertEquals("s1", repo.lastResetOpening)
    }

    @Test
    fun `a reset that closes nothing is reported, not hidden`() {
        repo.resetResult = SeasonResetResult(closed = false, archivedEntries = 0)

        val response =
            resource.seasonReset(
                BOARD,
                SeasonResetRequest("s0", "s1"),
                subject("system:serviceaccount:api:leaderboard-admin"),
            )

        assertFalse(response.closed)
    }

    @Test
    fun `a reset missing the closing season is a bad request`() {
        assertThrows<InvalidRequestException> {
            resource.seasonReset(
                BOARD,
                SeasonResetRequest(null, "s1"),
                subject("system:serviceaccount:api:leaderboard-admin"),
            )
        }
    }

    private fun subject(name: String): SecurityContext =
        object : SecurityContext {
            override fun getUserPrincipal(): Principal = Principal { name }

            override fun isUserInRole(role: String): Boolean = false

            override fun isSecure(): Boolean = true

            override fun getAuthenticationScheme(): String = "Bearer"
        }

    private class FakeRepository : LeaderboardRepository {
        var activeSeason: String = "s0"
        var top: List<TopEntry> = emptyList()
        var playerRank: PlayerRank? = PlayerRank(rank = 1, score = 10)
        var resetResult: SeasonResetResult = SeasonResetResult(closed = true, archivedEntries = 0)

        var lastSubmitSeason: String? = null
        var lastSubmitMode: SubmitMode? = null
        var lastIdempotencyKey: String? = null
        var lastTopLimit: Int? = null
        var lastResetClosing: String? = null
        var lastResetOpening: String? = null

        override fun activeSeason(boardId: String): String = activeSeason

        override fun submitScore(
            boardId: String,
            seasonId: String,
            playerId: UUID,
            score: Long,
            mode: SubmitMode,
            idempotencyKey: String?,
        ): SubmitOutcome {
            lastSubmitSeason = seasonId
            lastSubmitMode = mode
            lastIdempotencyKey = idempotencyKey
            return SubmitOutcome(effectiveScore = score, rank = 1, deduplicated = false)
        }

        override fun getTop(boardId: String, seasonId: String, limit: Int): List<TopEntry> {
            lastTopLimit = limit
            return top
        }

        override fun getPlayerRank(boardId: String, seasonId: String, playerId: UUID): PlayerRank? =
            playerRank

        override fun seasonReset(
            boardId: String,
            closingSeasonId: String,
            newSeasonId: String,
        ): SeasonResetResult {
            lastResetClosing = closingSeasonId
            lastResetOpening = newSeasonId
            return resetResult
        }
    }

    private companion object {
        const val BOARD = "bedwars.solos"
        val PLAYER: UUID = UUID.fromString("6f1d2b3c-0000-4000-8000-000000000001")
    }
}
