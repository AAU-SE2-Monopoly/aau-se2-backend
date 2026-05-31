package at.aau.monopoly.klagenfurt.websocket.broker

import at.aau.monopoly.klagenfurt.controller.GameController
import at.aau.monopoly.klagenfurt.messaging.dtos.GameAction
import at.aau.monopoly.klagenfurt.messaging.dtos.GameEvent
import at.aau.monopoly.klagenfurt.messaging.dtos.LobbyEvent
import at.aau.monopoly.klagenfurt.model.DiceRoll
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.PaymentSource
import at.aau.monopoly.klagenfurt.model.PendingPayment
import at.aau.monopoly.klagenfurt.model.card.ChanceCard
import at.aau.monopoly.klagenfurt.model.card.CommunityChestCard
import at.aau.monopoly.klagenfurt.model.enums.CardAction
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.messaging.support.MessageBuilder
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.security.Principal
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField

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
        expectedCount: Int
    ): List<Pair<String, Any>> {
        val destinationCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(messagingTemplate, Mockito.atLeast(expectedCount))
            .convertAndSend(destinationCaptor.capture(), payloadCaptor.capture())
        val allMessages = destinationCaptor.allValues.zip(payloadCaptor.allValues)
        return allMessages.takeLast(expectedCount)
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
    fun `startGame should broadcast started event when no current player exists`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        controller.startGame(GameAction(gameId = gameState.gameId))

        val messages = captureMessages(messagingTemplate, 2)
        val gameEvent = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent

        assertEquals("GAME_STARTED", gameEvent.event)
        assertNull(gameState.currentPlayer)
        assertTrue(gameEvent.message!!.contains("null"))
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
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE",payload = mutableMapOf("cheat" to "false")))

        val destinationCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(messagingTemplate, Mockito.times(1))
            .convertAndSend(destinationCaptor.capture(), payloadCaptor.capture())
        val event = payloadCaptor.value as GameEvent

        assertEquals("DICE_ROLLED", event.event)
        assertEquals(GamePhase.BUYING, gameState.phase)
        assertNotNull(gameState.lastDiceRoll)
        assertTrue(gameState.lastDiceRoll!!.die1 in 1..6)
        assertTrue(gameState.lastDiceRoll!!.die2 in 1..6)
        assertTrue(event.message!!.contains("Alice rolled"))
    }

    @Test
    fun `handleAction ROLL_DICE should emit error when game has no current player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("It is not your turn"))
    }

    @Test
    fun `handleAction ROLL_DICE should grant pass go bonus when player wraps around board`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", position = 35, money = 1500))
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("DICE_ROLLED", event.event)
        assertEquals(7, gameState.currentPlayer!!.position)
        assertEquals(1700, gameState.currentPlayer!!.money)
        assertTrue(event.message!!.contains("passed Go"))
    }

    @Test
    fun `handleAction should end turn and broadcast next player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.phase = GamePhase.BUYING

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
    fun `onSessionDisconnect should not remove players or broadcast when user is missing`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val event = SessionDisconnectEvent(
            this,
            MessageBuilder.withPayload(ByteArray(0)).build(),
            "session-1",
            CloseStatus.NORMAL
        )

        controller.onSessionDisconnect(event)

        assertEquals(1, gameState.players.size)
        Mockito.verifyNoInteractions(messagingTemplate)
    }

    @Test
    fun `onSessionDisconnect should not remove players or broadcast when user is present`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val user = Principal { "Alice" }
        val event = SessionDisconnectEvent(
            this,
            MessageBuilder.withPayload(ByteArray(0)).build(),
            "session-1",
            CloseStatus.NORMAL,
            user
        )

        controller.onSessionDisconnect(event)

        assertEquals(1, gameState.players.size)
        Mockito.verifyNoInteractions(messagingTemplate)
    }

    @Test
    fun `handleAction ROLL_DICE should emit error when not in ROLLING phase`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
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

        val payCaptor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(messagingTemplate, Mockito.times(1))
            .convertAndSend(Mockito.any(String::class.java), payCaptor.capture())
        val event = payCaptor.value as GameEvent
        assertNotNull(event.gameState!!.lastDiceRoll)
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

        controller.joinGame(
            GameAction(
                gameId = gameState.gameId,
                playerId = "player-2",
                payload = mutableMapOf("name" to "Bob")
            )
        )

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
    fun `joinGame should emit error when game has already started`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.advanceTurn()

        controller.joinGame(
            GameAction(
                gameId = gameState.gameId,
                playerId = "intruder",
                payload = mutableMapOf("name" to "Intruder")
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertEquals(gameState.gameId, event.gameId)
        assertNotNull(event.gameState)
        assertEquals(gameState.gameId, event.gameState!!.gameId)
        assertEquals(gameState.players.size, event.gameState.players.size)
        assertTrue(event.message!!.contains("not a participant"))
    }

    @Test
    fun `joinGame should emit error when game is full`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        repeat(gameController.maxPlayersPerGame) { index ->
            gameController.joinGame(
                gameState.gameId,
                Player(id = "player-$index", name = "Player $index")
            )
        }

        controller.joinGame(
            GameAction(
                gameId = gameState.gameId,
                playerId = "overflow-player",
                payload = mutableMapOf("name" to "Overflow")
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertEquals(gameState.gameId, event.gameId)
        assertNotNull(event.gameState)
        assertEquals(gameState.gameId, event.gameState!!.gameId)
        assertEquals(5, event.gameState.players.size)
        assertTrue(event.message!!.contains("already full"))
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
        assertEquals(1, lobbyEvent.games.size)
        assertEquals(gameState.gameId, lobbyEvent.games.single().gameId)
    }

    @Test
    fun `handleAction END_TURN should reject non-current player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "not-current-player",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("not your turn"))
    }

    @Test
    fun `handleAction END_TURN should succeed when not in buying phase`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.phase = GamePhase.TURN_END

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("TURN_ENDED", event.event)
        assertEquals(GamePhase.ROLLING, event.gameState!!.phase)
        assertEquals("Bob", event.gameState.currentPlayer!!.name)
        assertTrue(event.message!!.contains("Bob"))
    }

    @Test
    fun `handleAction END_TURN should broadcast only turn ended event`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.phase = GamePhase.BUYING

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("TURN_ENDED", event.event)
        assertEquals("Bob", event.gameState!!.currentPlayer!!.name)
    }

    @Test
    fun `handleAction END_TURN should clear turn state before advancing`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.phase = GamePhase.BUYING
        gameState.lastDiceRoll = DiceRoll(3, 4)
        gameState.currentActionCard = ChanceCard(
            id = 1,
            description = "Advance to Go",
            action = CardAction.MOVE_TO,
            targetFieldId = 0
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("TURN_ENDED", event.event)
        assertEquals(GamePhase.ROLLING, gameState.phase)
        assertNull(gameState.lastDiceRoll)
        assertNull(gameState.currentActionCard)
        assertEquals("Bob", gameState.currentPlayer!!.name)
    }

    @Test
    fun `handleAction END_TURN should reject when no current player exists`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameState.phase = GamePhase.BUYING

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "END_TURN"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("not your turn"))
    }

    @Test
    fun `drawChanceCard should move card from deck to currentActionCard`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7

        val initialDeckSize = gameState.chanceCards.size
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ACTION_DRAWN", event.event)
        assertNotNull(gameState.currentActionCard)
        assertEquals(initialDeckSize - 1, gameState.chanceCards.size)
    }

    @Test
    fun `drawCommunityChestCard should move card from deck to currentActionCard`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 2

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "COMMUNITY_CHEST")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ACTION_DRAWN", event.event)
        assertNotNull(gameState.currentActionCard)
        assertTrue(gameState.currentActionCard is CommunityChestCard)
    }

    @Test
    fun `handleAction ROLL_DICE in jail with doublet gets out of jail`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.position = 10
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("DICE_ROLLED", event.event)
        assertEquals(false, player.inJail)
        assertEquals(0, player.jailTurns)
        assertEquals(GamePhase.BUYING, gameState.phase)
    }

    @Test
    fun `handleAction ROLL_DICE in jail without doublet increases jailTurns`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.jailTurns = 1
        player.position = 10
        gameState.advanceTurn()

        var attempts = 0
        while (true) {
            gameState.phase = GamePhase.ROLLING
            player.jailTurns = 1
            player.inJail = true
            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))
            val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
            if (!gameState.lastDiceRoll!!.isDouble) {
                assertEquals(2, player.jailTurns)
                assertEquals(true, player.inJail)
                assertEquals(GamePhase.TURN_END, gameState.phase)
                break
            }
            attempts++
            if (attempts > 50) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll a non-doublet in 50 attempts")
        }
    }

    @Test
    fun `handleAction ROLL_DICE in jail fails 3rd time pays fine and gets out`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]

        var attempts = 0
        while (true) {
            player.inJail = true
            player.jailTurns = 2
            player.money = 1000
            player.position = 10
            gameState.phase = GamePhase.ROLLING
            gameState.currentPlayerIndex = 0

            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))

            if (!gameState.lastDiceRoll!!.isDouble) {
                assertEquals(950, player.money)
                assertEquals(false, player.inJail)
                assertEquals(0, player.jailTurns)
                assertEquals(GamePhase.BUYING, gameState.phase)
                break
            }
            attempts++
            if (attempts > 50) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll a non-doublet in 50 attempts")
        }
    }

    @Test
    fun `handleAction ROLL_DICE 3 doublets goes to jail`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.consecutiveDoublets = 2
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )

        assertEquals(true, player.inJail)
        assertEquals(10, player.position)
        assertEquals(0, player.consecutiveDoublets)
        assertEquals(GamePhase.TURN_END, gameState.phase)
    }

    @Test
    fun `handleAction ROLL_DICE lands on go to jail field with doublet`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.position = 18 // 18 + 12 = 30
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )

        assertEquals(true, player.inJail)
        assertEquals(10, player.position)
        assertEquals(GamePhase.TURN_END, gameState.phase)
    }

    @Test
    fun `handleAction ROLL_DICE non-double lands on go to jail field`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]

        var attempts = 0
        while (true) {
            gameState.phase = GamePhase.ROLLING
            gameState.currentPlayerIndex = 0
            player.position = 23 // Needs 7
            player.inJail = false

            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))
            val roll = gameState.lastDiceRoll!!

            if (!roll.isDouble && player.position == 10 && player.inJail) {
                assertEquals(GamePhase.TURN_END, gameState.phase)
                break
            }
            attempts++
            if (attempts > 500) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll a 7 in 500 attempts")
        }
    }

    @Test
    fun `PAY_JAIL_FINE wrong player gets error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.advanceTurn()

        val wrongPlayerId = if (gameState.currentPlayer!!.id == "host-1") "player-2" else "host-1"
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = wrongPlayerId,
                action = "PAY_JAIL_FINE"
            )
        )
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertEquals("It is not your turn.", event.message)
    }

    @Test
    fun `PAY_JAIL_FINE not in jail gets error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "PAY_JAIL_FINE"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("not in jail"))
    }

    @Test
    fun `PAY_JAIL_FINE not enough money gets error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.money = 10
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "PAY_JAIL_FINE"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Not enough money"))
    }

    @Test
    fun `PAY_JAIL_FINE success`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.position = 10
        player.money = 100
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "PAY_JAIL_FINE"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("JAIL_FINE_PAID", event.event)
        assertEquals(50, player.money)
        assertEquals(false, player.inJail)
    }

    @Test
    fun `USE_JAIL_CARD wrong player gets error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.advanceTurn()

        val wrongPlayerId = if (gameState.currentPlayer!!.id == "host-1") "player-2" else "host-1"
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = wrongPlayerId,
                action = "USE_JAIL_CARD"
            )
        )
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertEquals("It is not your turn.", event.message)
    }

    @Test
    fun `USE_JAIL_CARD not in jail gets error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "USE_JAIL_CARD"
            )
        )
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("not in jail"))
    }

    @Test
    fun `USE_JAIL_CARD no card gets error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.getOutOfJailCards = 0
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "USE_JAIL_CARD"
            )
        )
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("do not have"))
    }

    @Test
    fun `USE_JAIL_CARD success`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.position = 10
        player.getOutOfJailCards = 1
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "USE_JAIL_CARD"
            )
        )
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("JAIL_CARD_USED", event.event)
        assertEquals(0, player.getOutOfJailCards)
        assertEquals(false, player.inJail)
    }

    @Test
    fun `END_TURN with doublet gets another turn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "END_TURN"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_ENDED", event.event)
        assertEquals(GamePhase.ROLLING, gameState.phase)
        assertEquals("host-1", gameState.currentPlayer!!.id)
        assertTrue(event.message!!.contains("gets another turn"))
    }

    @Test
    fun `handleAction DRAW_CARD should fail when game has no current player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("It is not your turn"))
    }

    @Test
    fun `buyProperty should successfully purchase an unowned property`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 1500)
        )

        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 1

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val messages = captureLastMessages(messagingTemplate, 1)
        val event = messages.single().second as GameEvent

        assertEquals("PROPERTY_BOUGHT", event.event)
        assertEquals(1440, gameState.currentPlayer!!.money)
        assertEquals("host-1", (gameState.fields[1] as PropertyField).ownerId)
        assertTrue(event.message!!.contains("Alice bought"))
    }

    @Test
    fun `buyProperty should fail when property is already owned`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 1500)
        )

        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 1

        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "other-player"

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("already owned"))
    }

    @Test
    fun `buyProperty should fail when player has insufficient funds`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 50)
        )

        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 1

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("don't have enough money"))
    }

    @Test
    fun `buyProperty should fail when it's not the player's turn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", money = 1500))

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-2",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("It is not your turn"))
    }

    @Test
    fun `buyProperty should fail for non-property field`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")

        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 1500)
        )

        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 0

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "0")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("cannot be bought"))
    }

    @Test
    fun `buyProperty should fail when phase is not BUYING`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        gameState.phase = GamePhase.ROLLING
        gameState.currentPlayer!!.position = 1

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("buying phase", ignoreCase = true))
    }

    @Test
    fun `buyProperty should fail when player is not standing on requested field`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 2

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("currently standing on", ignoreCase = true))
    }

    @Test
    fun `buyProperty should successfully purchase railroad field`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 5

        val railroad = gameState.fields[5] as RailroadField
        val price = railroad.price

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "5")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("PROPERTY_BOUGHT", event.event)
        assertEquals(1500 - price, gameState.currentPlayer!!.money)
        assertEquals("host-1", railroad.ownerId)
        assertTrue(gameState.currentPlayer!!.ownedPropertyIds.contains(5))
    }

    @Test
    fun `buyProperty should successfully purchase utility field`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 12

        val utility = gameState.fields[12] as UtilityField
        val price = utility.price

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_PROPERTY",
                payload = mutableMapOf("fieldId" to "12")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("PROPERTY_BOUGHT", event.event)
        assertEquals(1500 - price, gameState.currentPlayer!!.money)
        assertEquals("host-1", utility.ownerId)
        assertTrue(gameState.currentPlayer!!.ownedPropertyIds.contains(12))
    }

    @Test
    fun `executeAction should fail when player is not current player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))

        val card = ChanceCard(
            id = 99,
            description = "Collect money",
            action = CardAction.COLLECT_MONEY,
            amount = 100
        )

        gameState.currentActionCard = card

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "player-2",
                action = "EXECUTE_ACTION"
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("It is not your turn"))
        assertNotNull(gameState.currentActionCard)
    }

    @Test
    fun `endTurn should reset currentActionCard`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.phase = GamePhase.BUYING

        gameState.currentActionCard = ChanceCard(
            id = 1,
            description = "Collect money",
            action = CardAction.COLLECT_MONEY,
            amount = 100
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        captureLastMessages(messagingTemplate, 1)

        assertNull(gameState.currentActionCard)
        assertEquals(GamePhase.ROLLING, gameState.phase)
    }

    @Test
    fun `endTurn should advance to next player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))
        gameState.phase = GamePhase.BUYING

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        assertEquals("player-2", gameState.currentPlayer!!.id)

        captureLastMessages(messagingTemplate, 1)
    }

    @Test
    fun `drawCard should succeed when drawing community chest card`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 2 // Community Chest field

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "COMMUNITY_CHEST")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ACTION_DRAWN", event.event)
        assertTrue(gameState.currentActionCard is CommunityChestCard)
    }

    @Test
    fun `drawCard should succeed when drawing chance card`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7 // Chance field

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ACTION_DRAWN", event.event)
        assertNotNull(gameState.currentActionCard)
        assertTrue(gameState.currentActionCard is ChanceCard)
    }

    @Test
    fun `drawCard should emit ACTION_DRAWN and set currentActionCard when drawing community chest`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 2 // Community Chest field

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "COMMUNITY_CHEST")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ACTION_DRAWN", event.event)
        assertNotNull(gameState.currentActionCard)
        assertTrue(gameState.currentActionCard is CommunityChestCard)
    }

    @Test
    fun `drawCard should emit ACTION_DRAWN and set currentActionCard when drawing chance`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7 // Chance field

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ACTION_DRAWN", event.event)
        assertNotNull(gameState.currentActionCard)
        assertTrue(gameState.currentActionCard is ChanceCard)
    }

    @Test
    fun `executeAction MOVE_FORWARD should not grant money when not passing Go`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame()
        val player = Player(id = "player-1", name = "Alice", position = 10, money = 1000)
        gameController.joinGame(gameState.gameId, player)

        gameState.currentActionCard = ChanceCard(
            id = 44,
            description = "Move forward 3 spaces",
            action = CardAction.MOVE_FORWARD,
            moveSpaces = 3
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "player-1",
                action = "EXECUTE_ACTION"
            )
        )

        captureLastMessages(messagingTemplate, 1)

        assertEquals(13, gameState.players[0].position)
        assertEquals(1000, gameState.players[0].money)
    }

    @Test
    fun `handleAction actions should emit error when no current player exists`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.currentPlayerIndex = -1

        val actions = listOf(
            "ROLL_DICE", "PAY_JAIL_FINE", "USE_JAIL_CARD",
            "DRAW_CARD", "EXECUTE_ACTION", "BUY_PROPERTY"
        )

        for (actionType in actions) {
            Mockito.clearInvocations(messagingTemplate)
            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = actionType, payload = mutableMapOf("cardType" to "CHANCE", "fieldId" to "1")))
            val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
            assertEquals("ERROR", event.event)
            assertTrue(
                event.message!!.contains("not your turn"),
                "Expected 'not your turn' error for $actionType when no current player exists, got: ${event.message}"
            )
        }
    }

    @Test
    fun `handleAction unknown action should emit error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "UNKNOWN_ACTION"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Unknown action"))
    }

    @Test
    fun `handleAction ROLL_DICE normal non-doublet passes go`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()

        var attempts = 0
        while(true) {
            Mockito.clearInvocations(messagingTemplate)
            gameState.phase = GamePhase.ROLLING
            player.position = 38
            player.money = 1500
            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))
            val payCaptor = ArgumentCaptor.forClass(Any::class.java)
            Mockito.verify(messagingTemplate, Mockito.times(1))
                .convertAndSend(Mockito.any(String::class.java), payCaptor.capture())
            val event = payCaptor.value as GameEvent
            if (!gameState.lastDiceRoll!!.isDouble) {
                assertEquals(1700, player.money)
                assertTrue(event.message!!.contains("passed Go"))
                break
            }
            attempts++
            if (attempts > 50) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll non-doublet")
        }
    }

    @Test
    fun `handleAction DRAW_CARD errors`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()

        Mockito.clearInvocations(messagingTemplate)
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DRAW_CARD"))
        var event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("cardType must be specified"))

        Mockito.clearInvocations(messagingTemplate)
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DRAW_CARD", payload = mutableMapOf("cardType" to "JUNK")))
        event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Unknown card type"))
    }

    @Test
    fun `handleAction BUY_PROPERTY errors`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        val player = gameState.players[0]
        player.position = 1

        Mockito.clearInvocations(messagingTemplate)
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_PROPERTY"))
        var event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)

        Mockito.clearInvocations(messagingTemplate)
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_PROPERTY", payload = mutableMapOf("fieldId" to "-1")))
        event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)

        Mockito.clearInvocations(messagingTemplate)
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_PROPERTY", payload = mutableMapOf("fieldId" to "100")))
        event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)

        Mockito.clearInvocations(messagingTemplate)
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_PROPERTY", payload = mutableMapOf("fieldId" to "3")))
        event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("currently standing on"))

        Mockito.clearInvocations(messagingTemplate)
        player.position = 7
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_PROPERTY", payload = mutableMapOf("fieldId" to "7")))
        event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("cannot be bought"))

        Mockito.clearInvocations(messagingTemplate)
        player.position = 1
        val propField = gameState.fields[1] as PropertyField
        propField.ownerId = "host-2"
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_PROPERTY", payload = mutableMapOf("fieldId" to "1")))
        event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("already owned"))

        Mockito.clearInvocations(messagingTemplate)
        propField.ownerId = null
        player.money = 0
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_PROPERTY", payload = mutableMapOf("fieldId" to "1")))
        event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("enough money"))
    }

    @Test
    fun `handleAction USE_JAIL_CARD without cards`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()
        val player = gameState.players[0]
        player.inJail = true
        player.getOutOfJailCards = 0

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "USE_JAIL_CARD"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("do not have a Get Out of Jail Free card"))
    }

    @Test
    fun `handleAction EXECUTE_ACTION no card`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "EXECUTE_ACTION"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("No action card"))
    }

    @Test
    fun `handleAction END_TURN with doublet but in jail advances turn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()

        gameState.phase = GamePhase.BUYING

        val player = gameState.players[0]
        assertEquals("host-1", player.id)

        player.inJail = true
        gameState.lastDiceRoll = DiceRoll(2, 2)
        player.consecutiveDoublets = 1

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "END_TURN"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_ENDED", event.event)
        assertEquals("host-2", gameState.currentPlayer!!.id)
    }

    @Test
    fun `handleAction END_TURN with doublet but 0 consecutiveDoublets advances turn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()

        gameState.phase = GamePhase.BUYING

        val player = gameState.players[0]
        assertEquals("host-1", player.id)

        gameState.lastDiceRoll = DiceRoll(2, 2)
        player.consecutiveDoublets = 0

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "END_TURN"))
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_ENDED", event.event)
        assertEquals("host-2", gameState.currentPlayer!!.id)
    }




    @Test
    fun `buyHouse should fail without complete color set`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        val player = gameState.currentPlayer!!
        gameState.phase = GamePhase.BUYING

        val property = gameState.fields[1] as PropertyField
        property.ownerId = player.id

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("complete color set"))
    }

    @Test
    fun `buyHouse should fail when houses are not built evenly`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        val player = gameState.currentPlayer!!
        gameState.phase = GamePhase.BUYING

        val property = gameState.fields[1] as PropertyField
        val sameColorProperties = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        sameColorProperties.forEach {
            it.ownerId = player.id
            player.ownedPropertyIds.add(it.id)
        }

        property.houses = 1

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("evenly"))
    }


    @Test
    fun `buyHotel should fail when property has less than four houses`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        val player = gameState.currentPlayer!!
        gameState.phase = GamePhase.BUYING

        val property = gameState.fields[1] as PropertyField
        val sameColorProperties = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        sameColorProperties.forEach {
            it.ownerId = player.id
            player.ownedPropertyIds.add(it.id)
        }

        property.houses = 3

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOTEL",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("4 houses"))
    }

    @Test
    fun `buyHouse should succeed when player owns complete color set`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 1500)
        )

        val player = gameState.currentPlayer!!
        gameState.phase = GamePhase.BUYING

        val property = gameState.fields[1] as PropertyField
        val sameColorProperties = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        sameColorProperties.forEach {
            it.ownerId = player.id
            it.houses = 0
            player.ownedPropertyIds.add(it.id)
        }

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("HOUSE_BOUGHT", event.event)
        assertEquals(1, property.houses)
    }

    @Test
    fun `buyHotel should succeed when property has four houses`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 1500)
        )

        val player = gameState.currentPlayer!!
        gameState.phase = GamePhase.BUYING

        val property = gameState.fields[1] as PropertyField
        val sameColorProperties = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        sameColorProperties.forEach {
            it.ownerId = player.id
            it.houses = 4
            player.ownedPropertyIds.add(it.id)
        }

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOTEL",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("HOTEL_BOUGHT", event.event)
        assertEquals(0, property.houses)
        assertTrue(property.hasHotel)
    }
    @Test
    fun `sellHouse should succeed when property has house`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 1500)
        )

        val player = gameState.currentPlayer!!

        val property = gameState.fields[1] as PropertyField
        val sameColorProperties = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        sameColorProperties.forEach {
            it.ownerId = player.id
            it.houses = 1
            player.ownedPropertyIds.add(it.id)
        }

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "SELL_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("HOUSE_SOLD", event.event)
        assertEquals(0, property.houses)
    }

    @Test
    fun `sellHotel should succeed when property has hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-1", name = "Alice", money = 1500)
        )

        val player = gameState.currentPlayer!!

        val property = gameState.fields[1] as PropertyField
        property.ownerId = player.id
        property.hasHotel = true
        property.houses = 0
        player.ownedPropertyIds.add(property.id)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "SELL_HOTEL",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("HOTEL_SOLD", event.event)
        assertEquals(false, property.hasHotel)
        assertEquals(4, property.houses)
    }

    @Test
    fun `buyHouse should fail with invalid field id`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.phase = GamePhase.BUYING

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to "999")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Invalid fieldId"))
    }

    @Test
    fun `buyHouse should fail when property is not owned by player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.phase = GamePhase.BUYING

        val property = gameState.fields[1] as PropertyField
        property.ownerId = "other-player"

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("own properties"))
    }
    @Test
    fun `sellHotel should fail when property has no hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField
        property.ownerId = player.id
        property.hasHotel = false

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "SELL_HOTEL",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("no hotel"))
    }

    @Test
    fun `buyHouse should fail when player does not own complete color set`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.BUYING

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField
        property.ownerId = player.id

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("complete color set"))
    }

    @Test
    fun `buyHouse should fail when player has not enough money`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 0))
        gameState.phase = GamePhase.BUYING

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField

        gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.color == property.color }
            .forEach {
                it.ownerId = player.id
                it.houses = 0
            }

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Not enough money"))
    }

    @Test
    fun `buyHouse should fail when property already has hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.BUYING

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField

        gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.color == property.color }
            .forEach { it.ownerId = player.id }

        property.hasHotel = true

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("already has a hotel"))
    }

    @Test
    fun `buyHotel should fail when hotel already exists`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField

        gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.color == property.color }
            .forEach { it.ownerId = player.id }

        property.houses = 4
        property.hasHotel = true

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOTEL",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("already has a hotel"))
    }

    @Test
    fun `sellHouse should fail when property has no houses`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField
        property.ownerId = player.id
        property.houses = 0

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "SELL_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("no houses"))
    }

    @Test
    fun `sellHouse should fail when property has hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField
        property.ownerId = player.id
        property.hasHotel = true

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "SELL_HOUSE",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Sell the hotel"))
    }

    @Test
    fun `sellHouse should fail when houses are not sold evenly`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.phase = GamePhase.BUYING

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField
        val colorSet = gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        colorSet.forEach { it.ownerId = player.id }
        colorSet[0].houses = 1
        colorSet[1].houses = 2

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "SELL_HOUSE",
                payload = mutableMapOf("fieldId" to colorSet[0].id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("evenly"))
    }

    @Test
    fun `buyHotel should fail when player has not enough money`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 0))

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField

        gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.color == property.color }
            .forEach {
                it.ownerId = player.id
                it.houses = 4
            }

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = player.id,
                action = "BUY_HOTEL",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Not enough money"))
    }

    @Test
    fun `drawCard should fail when a card is already pending execution`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7 // Chance field

        // First draw succeeds
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )
        assertNotNull(gameState.currentActionCard)
        Mockito.clearInvocations(messagingTemplate)

        // Second draw must fail
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("already drawn a card"))
    }

    @Test
    fun `executeAction COLLECT_FROM_EACH should take money from each other player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame()
        val player1 = Player(id = "p1", name = "Alice", money = 500)
        val player2 = Player(id = "p2", name = "Bob", money = 300)
        val player3 = Player(id = "p3", name = "Charlie", money = 200)
        gameController.joinGame(gameState.gameId, player1)
        gameController.joinGame(gameState.gameId, player2)
        gameController.joinGame(gameState.gameId, player3)

        gameState.currentActionCard = CommunityChestCard(
            id = 99,
            description = "Collect 10 from each",
            action = CardAction.COLLECT_FROM_EACH,
            amount = 10
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "p1",
                action = "EXECUTE_ACTION"
            )
        )

        captureLastMessages(messagingTemplate, 1)

        assertEquals(520, gameState.players[0].money) // 500 + 10 + 10
        assertEquals(290, gameState.players[1].money) // 300 - 10
        assertEquals(190, gameState.players[2].money) // 200 - 10
    }

    @Test
    fun `executeAction PAY_EACH_PLAYER should treat amount as per-player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame()
        val player1 = Player(id = "p1", name = "Alice", money = 500)
        val player2 = Player(id = "p2", name = "Bob", money = 300)
        val player3 = Player(id = "p3", name = "Charlie", money = 200)
        gameController.joinGame(gameState.gameId, player1)
        gameController.joinGame(gameState.gameId, player2)
        gameController.joinGame(gameState.gameId, player3)

        gameState.currentActionCard = ChanceCard(
            id = 99,
            description = "Pay each player 50",
            action = CardAction.PAY_EACH_PLAYER,
            amount = 50
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "p1",
                action = "EXECUTE_ACTION"
            )
        )

        captureLastMessages(messagingTemplate, 1)

        assertEquals(400, gameState.players[0].money) // 500 - 50 - 50
        assertEquals(350, gameState.players[1].money) // 300 + 50
        assertEquals(250, gameState.players[2].money) // 200 + 50
    }


    @Test
    fun `deck reshuffle on empty should regenerate cards`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7 // Chance field

        val initialSize = gameState.chanceCards.size
        // Draw all cards to empty the deck
        for (i in 1..initialSize) {
            gameState.currentActionCard = null
            controller.handleAction(
                GameAction(
                    gameId = gameState.gameId,
                    playerId = "host-1",
                    action = "DRAW_CARD",
                    payload = mutableMapOf("cardType" to "CHANCE")
                )
            )
        }
        assertEquals(0, gameState.chanceCards.size)

        // Next draw should reshuffle — total cards = 16 (full deck minus 1 drawn)
        Mockito.clearInvocations(messagingTemplate)
        gameState.currentActionCard = null
        gameState.advanceTurn() // resets currentActionCard
        gameState.currentPlayer!!.position = 7

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )

        assertNotNull(gameState.currentActionCard)
        assertEquals(15, gameState.chanceCards.size) // 16 reshuffled - 1 drawn
    }

}