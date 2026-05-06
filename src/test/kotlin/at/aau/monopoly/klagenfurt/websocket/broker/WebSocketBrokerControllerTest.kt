package at.aau.monopoly.klagenfurt.websocket.broker

import at.aau.monopoly.klagenfurt.controller.GameController
import at.aau.monopoly.klagenfurt.messaging.dtos.GameAction
import at.aau.monopoly.klagenfurt.messaging.dtos.GameEvent
import at.aau.monopoly.klagenfurt.messaging.dtos.LobbyEvent
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.messaging.simp.SimpMessagingTemplate

class WebSocketBrokerControllerTest {

    private fun createController(): Triple<WebSocketBrokerController, GameController, SimpMessagingTemplate> {
        val messagingTemplate = Mockito.mock(SimpMessagingTemplate::class.java)
        val gameController = GameController()
        val controller = WebSocketBrokerController(messagingTemplate, gameController)
        return Triple(controller, gameController, messagingTemplate)
    }

    private fun captureMessages(
        messagingTemplate: SimpMessagingTemplate,
        expectedCount: Int
    ): List<Pair<String, Any>> {
        val destinationCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(messagingTemplate, Mockito.times(expectedCount))
            .convertAndSend(destinationCaptor.capture(), payloadCaptor.capture())
        return destinationCaptor.allValues.zip(payloadCaptor.allValues)
    }

    private fun captureLastMessages(
        messagingTemplate: SimpMessagingTemplate,
        count: Int
    ): List<Pair<String, Any>> {
        val destinationCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(messagingTemplate, Mockito.atLeastOnce())
            .convertAndSend(destinationCaptor.capture(), payloadCaptor.capture())
        val allMessages = destinationCaptor.allValues.zip(payloadCaptor.allValues)
        return allMessages.takeLast(count)
    }

    @Test
    fun `createGame should broadcast created event to game topics and lobby`() {
        val (controller, gameController, messagingTemplate) = createController()

        controller.createGame(Player(id = "host-1", name = "Alice", iconId = ""))

        val gameId = gameController.listGameIds().single()
        val messages = captureMessages(messagingTemplate, 3)

        val gameTopicEvent = messages.first { it.first == "/topic/game/$gameId" }.second as GameEvent
        val tempTopicEvent = messages.first { it.first == "/topic/game/host-1" }.second as GameEvent
        val lobbyEvent = messages.first { it.first == "/topic/lobby" }.second as LobbyEvent

        assertEquals("GAME_CREATED", gameTopicEvent.event)
        assertEquals(gameId, gameTopicEvent.gameId)
        assertEquals("lindwurm", gameTopicEvent.gameState!!.players.single().iconId)
        assertEquals("GAME_CREATED", tempTopicEvent.event)
        assertEquals(gameId, tempTopicEvent.gameId)
        assertEquals("LOBBY_UPDATE", lobbyEvent.event)
        assertEquals(1, lobbyEvent.games.size)
    }

    @Test
    fun `joinGame should emit error when game does not exist`() {
        val (controller, _, messagingTemplate) = createController()

        controller.joinGame(GameAction(gameId = "missing-game", playerId = "player-1"))

        val messages = captureMessages(messagingTemplate, 1)
        val event = messages.single().second as GameEvent

        assertEquals("/topic/game/missing-game", messages.single().first)
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Game not found"))
    }

    @Test
    fun `joinGame should use default name and icon when payload omits them`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", iconId = "woerthersee"))

        controller.joinGame(
            GameAction(
                gameId = gameState.gameId,
                playerId = "player-2",
                payload = mutableMapOf<String, String>()
            )
        )

        val messages = captureMessages(messagingTemplate, 2)
        val gameEvent = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent
        val lobbyEvent = messages.first { it.first == "/topic/lobby" }.second as LobbyEvent

        assertEquals("PLAYER_JOINED", gameEvent.event)
        assertEquals("player-2", gameEvent.gameState!!.players[1].name)
        assertEquals("lindwurm", gameEvent.gameState.players[1].iconId)
        assertEquals(2, lobbyEvent.games.single().playerCount)
    }

    @Test
    fun `startGame should emit error when game does not exist`() {
        val (controller, _, messagingTemplate) = createController()

        controller.startGame(GameAction(gameId = "missing-game"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Game not found"))
    }

    @Test
    fun `startGame should advance turn and broadcast started event`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))

        controller.startGame(GameAction(gameId = gameState.gameId))

        val messages = captureMessages(messagingTemplate, 2)
        val gameEvent = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent

        assertEquals("GAME_STARTED", gameEvent.event)
        assertEquals(GamePhase.ROLLING, gameState.phase)
        assertEquals("Bob", gameState.currentPlayer!!.name)
        assertTrue(gameEvent.message!!.contains("Bob"))
    }

    @Test
    fun `handleAction should emit error when game does not exist`() {
        val (controller, _, messagingTemplate) = createController()

        controller.handleAction(GameAction(gameId = "missing-game", action = "ROLL_DICE"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Game not found"))
    }

    @Test
    fun `handleAction should roll dice and move phase to buying`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        // Start the game to set phase to ROLLING
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("DICE_ROLLED", event.event)
        assertEquals(GamePhase.BUYING, gameState.phase)
        assertNotNull(gameState.lastDiceRoll)
        assertTrue(gameState.lastDiceRoll!!.die1 in 1..6)
        assertTrue(gameState.lastDiceRoll!!.die2 in 1..6)
        assertTrue(event.message!!.contains("Alice rolled"))
    }

    @Test
    fun `handleAction should end turn and broadcast next player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "END_TURN"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("TURN_ENDED", event.event)
        assertEquals("Bob", gameState.currentPlayer!!.name)
        assertTrue(event.message!!.contains("Bob"))
    }

    @Test
    fun `handleAction should emit error for unknown action`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame()

        controller.handleAction(GameAction(gameId = gameState.gameId, action = "DO_SOMETHING"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Unknown action"))
    }

    @Test
    fun `getGameState should send snapshot for existing game`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame()

        controller.getGameState(GameAction(gameId = gameState.gameId))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("STATE_SNAPSHOT", event.event)
        assertEquals(gameState.gameId, event.gameState!!.gameId)
        assertNull(event.message)
    }

    @Test
    fun `getGameState should send error for missing game`() {
        val (controller, _, messagingTemplate) = createController()

        controller.getGameState(GameAction(gameId = "missing-game"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertNull(event.gameState)
        assertEquals("Game not found.", event.message)
    }

    @Test
    fun `listGames should broadcast all games to lobby`() {
        val (controller, gameController, messagingTemplate) = createController()
        val waitingGame = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(waitingGame.gameId, Player(id = "host-1", name = "Alice"))
        val startedGame = gameController.createGame(hostPlayerId = "host-2")
        gameController.joinGame(startedGame.gameId, Player(id = "host-2", name = "Bob"))
        gameController.getGameState(startedGame.gameId)!!.advanceTurn()

        controller.listGames(GameAction())

        val lobbyEvent = captureMessages(messagingTemplate, 1).single().second as LobbyEvent

        assertEquals("LOBBY_UPDATE", lobbyEvent.event)
        assertEquals(2, lobbyEvent.games.size)
    }


    @Test
    fun `closeGame should broadcast closed event and lobby update`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        controller.closeGame(GameAction(gameId = gameState.gameId, playerId = "host-1"))

        val messages = captureMessages(messagingTemplate, 2)
        val event = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent
        val lobbyEvent = messages.first { it.first == "/topic/lobby" }.second as LobbyEvent

        assertEquals("GAME_CLOSED", event.event)
        assertEquals(gameState.gameId, event.gameState!!.gameId)
        assertTrue(gameController.listGameIds().isEmpty())
        assertEquals("LOBBY_UPDATE", lobbyEvent.event)
    }

    @Test
    fun `closeGame should emit error when player is not host`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        controller.closeGame(GameAction(gameId = gameState.gameId, playerId = "intruder"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Only the host"))
    }


    @Test
    fun `handleAction ROLL_DICE should emit error when not in ROLLING phase`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        // Manually set to BUYING phase
        gameState.phase = GamePhase.BUYING

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))

        val messages = captureLastMessages(messagingTemplate, 1)
        val event = messages.single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Dice can only be rolled during the rolling phase"))
    }

    @Test
    fun `joinGame should use custom name and icon from payload`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        controller.joinGame(
            GameAction(
                gameId = gameState.gameId,
                playerId = "player-2",
                payload = mutableMapOf("name" to "CustomBob", "iconId" to "gti")
            )
        )

        val messages = captureLastMessages(messagingTemplate, 2)
        val gameEvent = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent

        assertEquals("PLAYER_JOINED", gameEvent.event)
        assertEquals("CustomBob", gameEvent.gameState!!.players[1].name)
        assertEquals("gti", gameEvent.gameState.players[1].iconId)
    }

    @Test
    fun `createGame should normalize blank iconId to default`() {
        val (controller, gameController, messagingTemplate) = createController()

        controller.createGame(Player(id = "host-1", name = "Alice", iconId = "   "))

        val gameId = gameController.listGameIds().single()
        val messages = captureMessages(messagingTemplate, 3)

        val gameEvent = messages.first { it.first == "/topic/game/$gameId" }.second as GameEvent
        assertEquals("lindwurm", gameEvent.gameState!!.players.single().iconId)
    }

    @Test
    fun `listGames broadcasts to lobby topic`() {
        val (controller, gameController, messagingTemplate) = createController()
        val game1 = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(game1.gameId, Player(id = "host-1", name = "Alice"))

        controller.listGames(GameAction())

        val messages = captureMessages(messagingTemplate, 1)
        assertEquals("/topic/lobby", messages.single().first)
        val lobbyEvent = messages.single().second as LobbyEvent
        assertEquals("LOBBY_UPDATE", lobbyEvent.event)
        assertEquals(1, lobbyEvent.games.size)
    }

    @Test
    fun `handleAction should broadcast last dice roll with both dice values`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertNotNull(event.gameState!!.lastDiceRoll)
        assertEquals(GamePhase.BUYING, event.gameState.phase)
        assertTrue(event.message!!.contains("rolled"))
        assertTrue(event.message.contains("="))
    }

    @Test
    fun `getGameState returns full game state with all players`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameController.joinGame(gameState.gameId, Player(id = "player-3", name = "Charlie"))

        controller.getGameState(GameAction(gameId = gameState.gameId))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("STATE_SNAPSHOT", event.event)
        assertEquals(3, event.gameState!!.players.size)
        assertEquals("Alice", event.gameState.players[0].name)
        assertEquals("Bob", event.gameState.players[1].name)
        assertEquals("Charlie", event.gameState.players[2].name)
    }

    @Test
    fun `createGame initializes board with 40 fields`() {
        val (controller, gameController) = createController().let { it.first to it.second }

        controller.createGame(Player(id = "host-1", name = "Alice"))

        val gameId = gameController.listGameIds().single()
        val gameState = gameController.getGameState(gameId)
        assertEquals(40, gameState!!.fields.size)
        assertEquals(0, gameState.currentPlayerIndex)
    }

    @Test
    fun `joinGame should include player in game state event`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        controller.joinGame(GameAction(gameId = gameState.gameId, playerId = "player-2", payload = mutableMapOf("name" to "Bob")))

        val messages = captureMessages(messagingTemplate, 2)
        val gameEvent = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent
        assertEquals(2, gameEvent.gameState!!.players.size)
        assertTrue(gameEvent.message!!.contains("Bob joined"))
    }

    @Test
    fun `handleAction ROLL_DICE should emit error when player is not current player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))

        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "wrong-player",
                action = "ROLL_DICE"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertEquals(gameState, event.gameState)
        assertTrue(event.message!!.contains("It is not your turn"))
    }

    @Test
    fun `joinGame should normalize blank icon from payload to default`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        controller.joinGame(
            GameAction(
                gameId = gameState.gameId,
                playerId = "player-2",
                payload = mutableMapOf(
                    "name" to "Bob",
                    "iconId" to "   "
                )
            )
        )

        val messages = captureMessages(messagingTemplate, 2)
        val event = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent

        assertEquals("PLAYER_JOINED", event.event)
        assertEquals("Bob", event.gameState!!.players[1].name)
        assertEquals("lindwurm", event.gameState.players[1].iconId)
    }

    @Test
    fun `createGame should preserve non blank iconId`() {
        val (controller, gameController, messagingTemplate) = createController()

        controller.createGame(Player(id = "host-1", name = "Alice", iconId = "gti"))

        val gameId = gameController.listGameIds().single()
        val messages = captureMessages(messagingTemplate, 3)

        val event = messages.first { it.first == "/topic/game/$gameId" }.second as GameEvent

        assertEquals("GAME_CREATED", event.event)
        assertEquals("gti", event.gameState!!.players.single().iconId)
    }

    @Test
    fun `startGame should update lobby list with current game state`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))

        controller.startGame(GameAction(gameId = gameState.gameId))

        val messages = captureMessages(messagingTemplate, 2)
        val lobbyEvent = messages.first { it.first == "/topic/lobby" }.second as LobbyEvent

        assertEquals("LOBBY_UPDATE", lobbyEvent.event)
        // Game is now started but still appears in lobby (listAllGames returns all games)
        assertEquals(1, lobbyEvent.games.size)
        assertEquals(gameState.gameId, lobbyEvent.games.single().gameId)
    }


    @Test
    fun `handleAction END_TURN should not check player ownership`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "not-current-player",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("TURN_ENDED", event.event)
        assertEquals("Bob", event.gameState!!.currentPlayer!!.name)
    }

    @Test
    fun `handleAction ROLL_DICE with cheat payload true should force double six`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        // Spiel starten/Runde voranschreiten lassen (Phase = ROLLING)
        gameState.advanceTurn()

        // Act: Aktion mit Cheat-Flag senden
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        // Assert: Prüfen, ob der If-Zweig (die1=6, die2=6) ausgeführt wurde
        assertEquals("DICE_ROLLED", event.event)
        assertEquals(GamePhase.BUYING, gameState.phase)
        assertNotNull(gameState.lastDiceRoll)

        assertEquals(6, gameState.lastDiceRoll!!.die1, "Die 1 muss bei Cheat exakt 6 sein")
        assertEquals(6, gameState.lastDiceRoll!!.die2, "Die 2 muss bei Cheat exakt 6 sein")
    }

    @Test
    fun `handleAction ROLL_DICE with explicit cheat false should roll normal dice`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        // Spiel starten/Runde voranschreiten lassen (Phase = ROLLING)
        gameState.advanceTurn()

        // Act: Aktion mit explizitem Cheat = false senden
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "false")
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        // Assert: Prüfen, ob der Else-Zweig (Zufall 1..6) ausgeführt wurde
        assertEquals("DICE_ROLLED", event.event)
        assertNotNull(gameState.lastDiceRoll)

        assertTrue(gameState.lastDiceRoll!!.die1 in 1..6, "Die 1 muss zwischen 1 und 6 liegen")
        assertTrue(gameState.lastDiceRoll!!.die2 in 1..6, "Die 2 muss zwischen 1 und 6 liegen")
    }
}
