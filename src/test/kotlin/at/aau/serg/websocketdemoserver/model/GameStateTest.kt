package at.aau.serg.websocketdemoserver.model

import at.aau.serg.websocketdemoserver.model.enums.GamePhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GameStateTest {

    @Test
    fun `currentPlayer should return null when no players exist`() {
        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard()
        )

        assertNull(gameState.currentPlayer)
    }

    @Test
    fun `currentPlayer should return player at currentPlayerIndex`() {
        val players = mutableListOf(
            Player(id = "1", name = "Alice"),
            Player(id = "2", name = "Bob")
        )

        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            players = players,
            currentPlayerIndex = 1
        )

        assertEquals("Bob", gameState.currentPlayer?.name)
    }

    @Test
    fun `advanceTurn should move to next player`() {
        val players = mutableListOf(
            Player(id = "1", name = "Alice"),
            Player(id = "2", name = "Bob")
        )

        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            players = players,
            currentPlayerIndex = 0,
            phase = GamePhase.WAITING
        )

        gameState.advanceTurn()

        assertEquals(1, gameState.currentPlayerIndex)
        assertEquals("Bob", gameState.currentPlayer?.name)
        assertEquals(GamePhase.ROLLING, gameState.phase)
    }

    @Test
    fun `advanceTurn should wrap around to first player`() {
        val players = mutableListOf(
            Player(id = "1", name = "Alice"),
            Player(id = "2", name = "Bob")
        )

        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            players = players,
            currentPlayerIndex = 1
        )

        gameState.advanceTurn()

        assertEquals(0, gameState.currentPlayerIndex)
        assertEquals("Alice", gameState.currentPlayer?.name)
        assertEquals(GamePhase.ROLLING, gameState.phase)
    }

    @Test
    fun `advanceTurn should only change phase when no players exist`() {
        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            phase = GamePhase.WAITING
        )

        gameState.advanceTurn()

        assertEquals(0, gameState.currentPlayerIndex)
        assertNull(gameState.currentPlayer)
        assertEquals(GamePhase.ROLLING, gameState.phase)
    }

    @Test
    fun `isGameOver should return true when no active players remain`() {
        val players = mutableListOf(
            Player(id = "1", name = "Alice", money = 0),
            Player(id = "2", name = "Bob", money = 0)
        )

        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            players = players
        )

        assertTrue(gameState.isGameOver())
    }

    @Test
    fun `isGameOver should return true when only one active player remains`() {
        val players = mutableListOf(
            Player(id = "1", name = "Alice", money = 500),
            Player(id = "2", name = "Bob", money = 0)
        )

        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            players = players
        )

        assertTrue(gameState.isGameOver())
    }

    @Test
    fun `isGameOver should return false when more than one active player remains`() {
        val players = mutableListOf(
            Player(id = "1", name = "Alice", money = 500),
            Player(id = "2", name = "Bob", money = 300)
        )

        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            players = players
        )

        assertFalse(gameState.isGameOver())
    }

    @Test
    fun `isGameOver should return false when player has no money but still owns property`() {
        val bankruptSafePlayer = Player(
            id = "1",
            name = "Alice",
            money = 0,
            ownedPropertyIds = mutableListOf(1)
        )
        val secondPlayer = Player(
            id = "2",
            name = "Bob",
            money = 100
        )

        val gameState = GameState(
            gameId = "game-1",
            fields = BoardFactory.createDefaultBoard(),
            players = mutableListOf(bankruptSafePlayer, secondPlayer)
        )

        assertFalse(gameState.isGameOver())
    }
}