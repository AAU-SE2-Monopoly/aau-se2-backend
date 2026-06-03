package at.aau.monopoly.klagenfurt.controller

import at.aau.monopoly.klagenfurt.model.PaymentSource
import at.aau.monopoly.klagenfurt.model.PendingPayment
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
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
        assertEquals("lindwurm", updatedGame.players[0].iconId)
    }

    @Test
    fun `joinGame should retain provided player icon`() {
        val controller = GameController()
        val game = controller.createGame()
        val player = Player(id = "1", name = "Alice", iconId = "ironman")

        val updatedGame = controller.joinGame(game.gameId, player)

        assertEquals(1, updatedGame.players.size)
        assertEquals("ironman", updatedGame.players[0].iconId)
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
    fun `joinGame should allow rejoin with same player id during WAITING (wifi recovery)`() {
        val controller = GameController()
        val game = controller.createGame()
        val player = Player(id = "1", name = "Alice")

        controller.joinGame(game.gameId, player)

        // Same player ID re-joining during WAITING should NOT throw —
        // it should silently succeed (wifi-drop recovery).
        assertDoesNotThrow {
            controller.joinGame(game.gameId, Player(id = "1", name = "AliceAgain"))
        }
        // Existing player identity (name, icon) is preserved.
        val gameState = controller.getGameState(game.gameId)!!
        assertEquals(1, gameState.players.size)
        assertEquals("Alice", gameState.players[0].name)
    }

    @Test
    fun `joinGame should not allow more than max players`() {
        val controller = GameController()
        val game = controller.createGame()

        repeat(controller.maxPlayersPerGame) { index ->
            controller.joinGame(
                game.gameId,
                Player(id = "player-$index", name = "Player $index", iconId = "icon-$index")
            )
        }

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.joinGame(game.gameId, Player(id = "overflow", name = "Overflow", iconId = "icon-overflow"))
        }

        assertTrue(exception.message!!.contains("already full"))
    }

    @Test
    fun `joinGame should reject player with already taken icon`() {
        val controller = GameController()
        val game = controller.createGame()
        controller.joinGame(game.gameId, Player(id = "1", name = "Alice", iconId = "ironman"))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.joinGame(game.gameId, Player(id = "2", name = "Bob", iconId = "ironman"))
        }

        assertTrue(exception.message!!.contains("already taken"))
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
    fun `listAllGames should return all games`() {
        val controller = GameController()
        val game1 = controller.createGame(hostPlayerId = "host-1")
        controller.joinGame(game1.gameId, Player(id = "host-1", name = "Alice"))
        val game2 = controller.createGame(hostPlayerId = "host-2")
        controller.joinGame(game2.gameId, Player(id = "host-2", name = "Bob"))

        // Start game1 so it leaves WAITING
        controller.getGameState(game1.gameId)!!.advanceTurn()

        val allGames = controller.listAllGames()

        assertEquals(2, allGames.size)
        val game1Info = allGames.first { it.gameId == game1.gameId }
        assertEquals("Alice", game1Info.hostPlayerName)
        assertEquals(1, game1Info.playerCount)
        val game2Info = allGames.first { it.gameId == game2.gameId }
        assertEquals("Bob", game2Info.hostPlayerName)
        assertEquals(1, game2Info.playerCount)
    }

    @Test
    fun `listOpenGames should return empty when no games exist`() {
        val controller = GameController()

        val openGames = controller.listAllGames()

        assertTrue(openGames.isEmpty())
    }

    @Test
    fun `listOpenGames should use Unknown when host has not joined yet`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "host-1")

        val allGames = controller.listAllGames()

        assertEquals(1, allGames.size)
        assertEquals(game.gameId, allGames[0].gameId)
        assertEquals("Unknown", allGames[0].hostPlayerName)
    }

    @Test
    fun `joinGame should allow rejoin with same player id during InProgress (app restart)`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "1")
        controller.joinGame(game.gameId, Player(id = "1", name = "Alice", iconId = "icon1"))
        controller.joinGame(game.gameId, Player(id = "2", name = "Bob", iconId = "icon2"))
        // Start the game — phase becomes ROLLING
        controller.getGameState(game.gameId)!!.advanceTurn()

        // Same player ID re-joining during InProgress should succeed silently.
        assertDoesNotThrow {
            controller.joinGame(game.gameId, Player(id = "1", name = "AliceReconnect", iconId = "icon1"))
        }
        val gameState = controller.getGameState(game.gameId)!!
        assertEquals(2, gameState.players.size)
        assertEquals("Alice", gameState.players[0].name) // existing identity preserved
    }

    @Test
    fun `joinGame should reject fresh player when game is InProgress`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "1")
        controller.joinGame(game.gameId, Player(id = "1", name = "Alice", iconId = "icon1"))
        controller.joinGame(game.gameId, Player(id = "2", name = "Bob", iconId = "icon2"))
        // Start the game
        controller.getGameState(game.gameId)!!.advanceTurn()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            controller.joinGame(game.gameId, Player(id = "3", name = "Intruder", iconId = "icon3"))
        }

        assertTrue(exception.message!!.contains("not a participant"))
    }

    @Test
    fun `closeGame should succeed even when game has already started`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "host-1")
        controller.joinGame(game.gameId, Player(id = "host-1", name = "Alice"))
        // Start the game
        controller.getGameState(game.gameId)!!.advanceTurn()

        val closed = controller.closeGame(game.gameId, "host-1")

        assertEquals(game.gameId, closed.gameId)
        // Game should be removed
        assertNull(controller.getGameState(game.gameId))
    }

    @Test
    fun `bankrupt player rejoin preserves eliminated state for spectating`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "p1")
        controller.joinGame(game.gameId, Player(id = "p1", name = "Alice", money = 0))
        controller.joinGame(game.gameId, Player(id = "p2", name = "Bob", iconId = "woerthersee"))
        game.players[0].eliminated = true

        val rejoined = controller.joinGame(game.gameId, Player(id = "p1", name = "Alice"))

        assertTrue(rejoined.players[0].eliminated)
        assertEquals(0, rejoined.players[0].money)
        assertEquals(2, rejoined.players.size)
    }

    @Test
    fun `player with pending rent still owes after rejoin`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "p1")
        controller.joinGame(game.gameId, Player(id = "p1", name = "Alice"))
        controller.joinGame(game.gameId, Player(id = "p2", name = "Bob", iconId = "woerthersee"))
        game.phase = GamePhase.PAYING_RENT
        game.pendingPayment = PendingPayment(amount = 200, source = PaymentSource.RENT,
            sourceFieldId = 1, creditorPlayerId = "p2")

        val rejoined = controller.joinGame(game.gameId, Player(id = "p1", name = "Alice"))

        assertEquals(200, rejoined.pendingPayment?.amount)
        assertEquals(GamePhase.PAYING_RENT, rejoined.phase)
    }

    @Test
    fun `rejoin does not duplicate player in players list`() {
        val controller = GameController()
        val game = controller.createGame(hostPlayerId = "p1")
        controller.joinGame(game.gameId, Player(id = "p1", name = "Alice"))
        controller.joinGame(game.gameId, Player(id = "p2", name = "Bob", iconId = "woerthersee"))
        game.players[0].eliminated = true

        val rejoined = controller.joinGame(game.gameId, Player(id = "p1", name = "AliceRejoin"))
        assertEquals(2, rejoined.players.size)
        assertEquals("Alice", rejoined.players[0].name)
    }
}
