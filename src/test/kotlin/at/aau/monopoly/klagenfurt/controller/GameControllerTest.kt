package at.aau.monopoly.klagenfurt.controller

import at.aau.monopoly.klagenfurt.model.Player
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GameControllerTest {

    @Test
    fun `createGame should create a new game with initialized board and decks`() {
        val controller = GameController()

        val game = controller.createGame()

        assertNotNull(game)
        assertTrue(game.gameId.isNotBlank())
        assertEquals(40, game.fields.size)
        assertEquals(16, game.chanceCards.size)
        assertEquals(16, game.communityChestCards.size)
        assertTrue(game.players.isEmpty())
    }

    @Test
    fun `joinGame should add player to existing game`() {
        val controller = GameController()
        val game = controller.createGame()
        val player = Player(id = "1", name = "Alice")

        val updatedGame = controller.joinGame(game.gameId, player)

        assertEquals(1, updatedGame.players.size)
        assertEquals("Alice", updatedGame.players[0].name)
        assertEquals("1", updatedGame.players[0].id)
    }

    @Test
    fun `joinGame should throw exception when game does not exist`() {
        val controller = GameController()
        val player = Player(id = "1", name = "Alice")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.joinGame("unknown-game", player)
        }

        assertTrue(exception.message!!.contains("not found"))
    }

    @Test
    fun `joinGame should not allow duplicate player ids`() {
        val controller = GameController()
        val game = controller.createGame()
        val player = Player(id = "1", name = "Alice")

        controller.joinGame(game.gameId, player)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.joinGame(game.gameId, Player(id = "1", name = "AliceAgain"))
        }

        assertTrue(exception.message!!.contains("already in game"))
    }

    @Test
    fun `joinGame should not allow more than max players`() {
        val controller = GameController()
        val game = controller.createGame()

        repeat(controller.maxPlayersPerGame) { index ->
            controller.joinGame(
                game.gameId,
                Player(id = "player-$index", name = "Player $index")
            )
        }

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.joinGame(game.gameId, Player(id = "overflow", name = "Overflow"))
        }

        assertTrue(exception.message!!.contains("already full"))
    }

    @Test
    fun `getGameState should return existing game`() {
        val controller = GameController()
        val game = controller.createGame()

        val foundGame = controller.getGameState(game.gameId)

        assertNotNull(foundGame)
        assertEquals(game.gameId, foundGame?.gameId)
    }

    @Test
    fun `getGameState should return null for unknown game id`() {
        val controller = GameController()

        val foundGame = controller.getGameState("unknown-game")

        assertNull(foundGame)
    }

    @Test
    fun `removeGame should remove existing game and return true`() {
        val controller = GameController()
        val game = controller.createGame()

        val removed = controller.removeGame(game.gameId)

        assertTrue(removed)
        assertNull(controller.getGameState(game.gameId))
    }

    @Test
    fun `removeGame should return false when game does not exist`() {
        val controller = GameController()

        val removed = controller.removeGame("unknown-game")

        assertFalse(removed)
    }

    @Test
    fun `listGameIds should return all active game ids`() {
        val controller = GameController()
        val game1 = controller.createGame()
        val game2 = controller.createGame()

        val ids = controller.listGameIds()

        assertEquals(2, ids.size)
        assertTrue(ids.contains(game1.gameId))
        assertTrue(ids.contains(game2.gameId))
    }

    // ─── Host / Close / Lobby ────────────────────────────────────────────────

    @Test
    fun `createGame should record hostPlayerId`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "host-1")

        assertEquals("host-1", game.hostPlayerId)
    }

    @Test
    fun `closeGame should remove the game when called by host`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "host-1")

        val closed = controller.closeGame(game.gameId, "host-1")

        assertEquals(game.gameId, closed.gameId)
        assertNull(controller.getGameState(game.gameId))
    }

    @Test
    fun `closeGame should throw when called by non-host`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "host-1")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.closeGame(game.gameId, "some-other-player")
        }

        assertTrue(exception.message!!.contains("Only the host"))
        // Game should still exist
        assertNotNull(controller.getGameState(game.gameId))
    }

    @Test
    fun `closeGame should throw when game does not exist`() {
        val controller = GameController()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.closeGame("unknown-game", "host-1")
        }

        assertTrue(exception.message!!.contains("not found"))
    }

    @Test
    fun `listOpenGames should return only games in WAITING phase`() {
        val controller = GameController()
        val game1 = controller.createGame(hostPlayerId = "host-1")
        controller.joinGame(game1.gameId, Player(id = "host-1", name = "Alice"))
        val game2 = controller.createGame(hostPlayerId = "host-2")
        controller.joinGame(game2.gameId, Player(id = "host-2", name = "Bob"))

        // Start game1 so it leaves WAITING
        controller.getGameState(game1.gameId)!!.advanceTurn()

        val openGames = controller.listOpenGames()

        assertEquals(1, openGames.size)
        assertEquals(game2.gameId, openGames[0].gameId)
        assertEquals("Bob", openGames[0].hostPlayerName)
        assertEquals(1, openGames[0].playerCount)
    }

    @Test
    fun `listOpenGames should return empty when no games exist`() {
        val controller = GameController()

        val openGames = controller.listOpenGames()

        assertTrue(openGames.isEmpty())
    }
}