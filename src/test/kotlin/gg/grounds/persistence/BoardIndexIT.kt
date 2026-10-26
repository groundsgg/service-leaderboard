package gg.grounds.persistence

import gg.grounds.domain.BoardSummary
import gg.grounds.domain.PlayerBoard
import gg.grounds.domain.SubmitMode
import gg.grounds.rest.InvalidRequestException
import gg.grounds.rest.PlayerBoardsResource
import gg.grounds.support.TestInfra
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The cross-board reads against a real Postgres: which season each board is read in, and ranks. */
class BoardIndexIT {

    private lateinit var repo: PostgresLeaderboardRepository

    @BeforeEach
    fun setUp() {
        repo = PostgresLeaderboardRepository(TestInfra.freshLeaderboardSchema())
    }

    private fun score(board: String, player: UUID, score: Long, season: String = "s0") =
        repo.submitScore(board, season, player, score, SubmitMode.REPLACE, idempotencyKey = null)

    @Test
    fun `a player's boards carry the rank they hold on each`() {
        score("bedwars-solo", ALICE, 2600)
        score("bedwars-solo", BOB, 2700)
        score("bedwars-solo.wins", ALICE, 12)
        score("bedwars-solo.wins", BOB, 3)
        score("getdown", BOB, 2500)

        assertEquals(
            listOf(
                PlayerBoard("bedwars-solo", "s0", rank = 2, score = 2600),
                PlayerBoard("bedwars-solo.wins", "s0", rank = 1, score = 12),
            ),
            repo.getPlayerBoards(ALICE, seasonId = null),
        )
    }

    @Test
    fun `each board is read in its own active season`() {
        score("bedwars-solo", ALICE, 2600, season = "s0")
        repo.seasonReset("bedwars-solo", "s0", "s1")
        score("bedwars-solo", ALICE, 2510, season = "s1")
        score("getdown", ALICE, 2400, season = "s0")

        assertEquals(
            listOf(
                PlayerBoard("bedwars-solo", "s1", rank = 1, score = 2510),
                PlayerBoard("getdown", "s0", rank = 1, score = 2400),
            ),
            repo.getPlayerBoards(ALICE, seasonId = null),
        )
    }

    @Test
    fun `a named season is read on every board`() {
        score("bedwars-solo", ALICE, 2600, season = "s0")
        repo.seasonReset("bedwars-solo", "s0", "s1")
        score("bedwars-solo", ALICE, 2510, season = "s1")

        assertEquals(
            listOf(PlayerBoard("bedwars-solo", "s0", rank = 1, score = 2600)),
            repo.getPlayerBoards(ALICE, seasonId = "s0"),
        )
    }

    @Test
    fun `a player who never scored is on no boards`() {
        score("bedwars-solo", BOB, 2700)

        assertTrue(repo.getPlayerBoards(ALICE, seasonId = null).isEmpty())
    }

    @Test
    fun `the board list counts players in the active season only`() {
        score("bedwars-solo", ALICE, 2600)
        score("bedwars-solo", BOB, 2700)
        repo.seasonReset("bedwars-solo", "s0", "s1")
        score("bedwars-solo", ALICE, 2510, season = "s1")
        score("getdown", BOB, 2500)

        assertEquals(
            listOf(
                BoardSummary("bedwars-solo", "s1", players = 1),
                BoardSummary("getdown", "s0", 1),
            ),
            repo.listBoards(),
        )
    }

    @Test
    fun `a malformed player id is a bad request`() {
        assertThrows<InvalidRequestException> {
            PlayerBoardsResource(repo).playerBoards("not-a-uuid", seasonId = null)
        }
    }

    private companion object {
        val ALICE: UUID = UUID.fromString("6f1d2b3c-0000-4000-8000-00000000000a")
        val BOB: UUID = UUID.fromString("6f1d2b3c-0000-4000-8000-00000000000b")
    }
}
