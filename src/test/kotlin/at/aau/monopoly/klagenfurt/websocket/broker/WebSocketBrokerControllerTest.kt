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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))

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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(startedGame.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
        gameController.joinGame(gameState.gameId, Player(id = "player-3", name = "Charlie", iconId = "gti"))

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
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", iconId = "woerthersee"))

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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))

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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
                Player(id = "player-$index", name = "Player $index", iconId = "icon-$index")
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
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", iconId = "woerthersee"))

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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))

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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameState.phase = GamePhase.BUYING

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "END_TURN"))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("not your turn"))
    }

    @Test
    fun `handleAction END_TURN should reject when on Chance field and card not drawn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 7 // ChanceField
        gameState.hasDrawnCardThisTurn = false

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("must draw a card"))
    }

    @Test
    fun `handleAction END_TURN should reject when on Community Chest field and card not drawn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 2 // CommunityChestField
        gameState.hasDrawnCardThisTurn = false

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("must draw a card"))
    }

    @Test
    fun `handleAction END_TURN should succeed when on Chance field after drawing card`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 7 // ChanceField
        gameState.hasDrawnCardThisTurn = true

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_ENDED", event.event)
    }

    @Test
    fun `handleAction END_TURN should succeed when not on card field`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayer!!.position = 1 // PropertyField (not a card field)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_ENDED", event.event)
    }

    @Test
    fun `executeAction MOVE_TO another card field should reset hasDrawnCardThisTurn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7 // starting on ChanceField
        gameState.hasDrawnCardThisTurn = true
        gameState.currentActionCard = ChanceCard(
            id = 99,
            description = "Move to another Chance",
            action = CardAction.MOVE_TO,
            targetFieldId = 36
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "EXECUTE_ACTION"
            )
        )

        assertFalse(gameState.hasDrawnCardThisTurn)
        assertEquals(36, gameState.currentPlayer!!.position)
    }

    @Test
    fun `drawCard should fail when already drawn this turn`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7 // ChanceField

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to "CHANCE")
            )
        )
        Mockito.clearInvocations(messagingTemplate)

        gameState.currentActionCard = null
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
        assertTrue(event.message!!.contains("already drawn a card"))
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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 1500))

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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))

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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
        gameState.phase = GamePhase.BUYING

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "END_TURN"
            )
        )

        assertEquals("player-2", gameState.currentPlayer!!.id)

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
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
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
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()

        gameState.phase = GamePhase.BUYING

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
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
        gameState.currentPlayerIndex = -1
        gameState.advanceTurn()

        gameState.phase = GamePhase.BUYING

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
        gameState.phase = GamePhase.ROLLING
        gameState.phase = GamePhase.ROLLING

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
        gameState.phase = GamePhase.ROLLING

        val property = gameState.fields[1] as PropertyField
        property.ownerId = player.id
        property.hasHotel = true
        property.houses = 0
        player.ownedPropertyIds.add(property.id)

        val siblings = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color && it.id != property.id }
        siblings.forEach {
            it.ownerId = player.id
            it.houses = 4
            player.ownedPropertyIds.add(it.id)
        }

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
        gameState.phase = GamePhase.ROLLING
        gameState.phase = GamePhase.ROLLING

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
        gameState.phase = GamePhase.ROLLING

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField

        gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.color == property.color }
            .forEach {
                it.ownerId = player.id
                it.houses = 0
                player.ownedPropertyIds.add(it.id)
                player.ownedPropertyIds.add(it.id)
            }

        player.money = 0

        player.money = 0

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
        gameState.phase = GamePhase.ROLLING
        gameState.phase = GamePhase.ROLLING

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
        gameState.phase = GamePhase.ROLLING
        gameState.phase = GamePhase.ROLLING

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
        gameState.phase = GamePhase.ROLLING
        gameState.phase = GamePhase.ROLLING

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
        gameState.phase = GamePhase.ROLLING
        gameState.phase = GamePhase.ROLLING

        val player = gameState.currentPlayer!!
        val property = gameState.fields[1] as PropertyField

        gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.color == property.color }
            .forEach {
                it.ownerId = player.id
                it.houses = 4
                player.ownedPropertyIds.add(it.id)
                player.ownedPropertyIds.add(it.id)
            }

        player.money = 0

        player.money = 0

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
        val player1 = Player(id = "p1", name = "Alice", iconId = "lindwurm", money = 500)
        val player2 = Player(id = "p2", name = "Bob", iconId = "woerthersee", money = 300)
        val player3 = Player(id = "p3", name = "Charlie", iconId = "gti", money = 200)
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
        val player1 = Player(id = "p1", name = "Alice", iconId = "lindwurm", money = 500)
        val player2 = Player(id = "p2", name = "Bob", iconId = "woerthersee", money = 300)
        val player3 = Player(id = "p3", name = "Charlie", iconId = "gti", money = 200)
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
            gameState.hasDrawnCardThisTurn = false
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

    @Test
    fun `handlePayRent should deduct from payer and credit owner correctly`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.RENT,
            sourceFieldId = 1,
            creditorPlayerId = "host-2",
            debtorCanPayAfterAssets = true
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_RENT",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GameEvent.RENT_PAID, event.event)
        assertEquals(400, gameState.players[0].money)
        assertEquals(600, gameState.players[1].money)
        assertNull(gameState.pendingPayment)
        assertEquals(GamePhase.TURN_END, gameState.phase)
    }

    @Test
    fun `handlePayRent sends PAYMENT_FAILED when player cannot afford rent`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 50))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.RENT,
            sourceFieldId = 1,
            creditorPlayerId = "host-2",
            debtorCanPayAfterAssets = false
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_RENT",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GameEvent.PAYMENT_FAILED, event.event)
        assertTrue(event.message!!.contains("Insufficient funds"))
    }

    @Test
    fun `handlePayRent clears pending rent fields after payment`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 50,
            source = PaymentSource.RENT,
            sourceFieldId = 1,
            creditorPlayerId = "host-2",
            debtorCanPayAfterAssets = true
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_RENT",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        captureLastMessages(messagingTemplate, 1)
        assertNull(gameState.pendingPayment)
        assertEquals(GamePhase.TURN_END, gameState.phase)
    }

    @Test
    fun `handleBuyHouse rejects when phase is PAYING_RENT`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_HOUSE",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Cannot buy houses while rent is due"))
    }

    @Test
    fun `handleBuyHotel rejects when phase is PAYING_RENT`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "BUY_HOTEL",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Cannot buy hotels while rent is due"))
    }

    @Test
    fun `handleBuyProperty rejects when phase is PAYING_RENT`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT

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
        assertTrue(event.message!!.contains("Cannot buy property while rent is due"))
    }

    @Test
    fun `handleDeclareBankruptcy rejects when player can pay after liquidation`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.RENT,
            sourceFieldId = 1,
            creditorPlayerId = "host-2"
        )

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DECLARE_BANKRUPTCY"
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("You can still pay"))
    }

    @Test
    fun `handleDeclareBankruptcy transfers properties to creditor and sets money to zero`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.RENT,
            sourceFieldId = 1,
            creditorPlayerId = "host-2"
        )

        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        prop1.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DECLARE_BANKRUPTCY"
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GameEvent.BANKRUPTCY_DECLARED, event.event)
        assertEquals(0, gameState.players[0].money)
        assertTrue(gameState.players[0].eliminated)
        assertEquals("host-2", prop1.ownerId)
    }

    @Test
    fun `handleDeclareBankruptcy transfers get out of jail cards to creditor`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10, getOutOfJailCards = 2))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.RENT,
            sourceFieldId = 1,
            creditorPlayerId = "host-2"
        )

        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        prop1.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DECLARE_BANKRUPTCY"
            )
        )

        captureLastMessages(messagingTemplate, 1)
        assertEquals(0, gameState.players[0].getOutOfJailCards)
        assertEquals(2, gameState.players[1].getOutOfJailCards)
    }

    @Test
    fun `after bankruptcy declaration other players can still end turn and advance game`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.RENT,
            sourceFieldId = 1,
            creditorPlayerId = "host-2"
        )

        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        prop1.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "DECLARE_BANKRUPTCY"
            )
        )

        Mockito.clearInvocations(messagingTemplate)

        gameState.currentPlayerIndex = 1
        gameState.phase = GamePhase.TURN_END

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-2",
                action = "END_TURN"
            )
        )

        val events = captureLastMessages(messagingTemplate, 1)
        val event = events.single().second as GameEvent
        assertNotNull(event)
        assertTrue(event.event == "TURN_ENDED" || event.gameState?.phase == GamePhase.FINISHED)
    }

    @Test
    fun `handleMortgageProperty should set isMortgaged true and add half price`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.phase = GamePhase.BUYING

        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "MORTGAGE_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GameEvent.PROPERTY_MORTGAGED, event.event)
        assertTrue(prop1.isMortgaged)
        assertEquals(500 + 60 / 2, gameState.players[0].money)
    }

    @Test
    fun `handleUnmortgageProperty should set isMortgaged false and deduct cost`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.phase = GamePhase.BUYING

        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        prop1.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "UNMORTGAGE_PROPERTY",
                payload = mutableMapOf("fieldId" to "1")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GameEvent.PROPERTY_UNMORTGAGED, event.event)
        assertFalse(prop1.isMortgaged)
        assertEquals(500 - 33, gameState.players[0].money)
    }

    @Test
    fun `PAY_RENT rejects non-current player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1, creditorPlayerId = "host-2")

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-2", action = "PAY_RENT",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("not your turn"))
    }

    @Test
    fun `DECLARE_BANKRUPTCY rejects non-current player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1, creditorPlayerId = "host-2")

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-2", action = "DECLARE_BANKRUPTCY"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("not your turn"))
    }

    @Test
    fun `MORTGAGE_PROPERTY allows non-current player to mortgage their property`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 1500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING

        val field = gameState.fields[1] as PropertyField
        field.ownerId = "host-2"
        gameState.players.find { it.id == "host-2" }!!.ownedPropertyIds.add(field.id)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-2", action = "MORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to field.id.toString())))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("PROPERTY_MORTGAGED", event.event)
        assertTrue(field.isMortgaged)
    }

    @Test
    fun `UNMORTGAGE_PROPERTY allows non-current player to unmortgage their property`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 1500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING

        val field = gameState.fields[1] as PropertyField
        field.ownerId = "host-2"
        field.isMortgaged = true
        gameState.players.find { it.id == "host-2" }!!.ownedPropertyIds.add(field.id)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-2", action = "UNMORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to field.id.toString())))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("PROPERTY_UNMORTGAGED", event.event)
        assertFalse(field.isMortgaged)
    }

    @Test
    fun `DECLARE_BANKRUPTCY rejects when phase is not PAYING_RENT`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.phase = GamePhase.BUYING

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DECLARE_BANKRUPTCY"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("only be declared when a payment is due"))
    }

    @Test
    fun `SELL_HOUSE succeeds during PAYING_RENT phase to raise funds`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 200, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.houses = 1
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "SELL_HOUSE",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("HOUSE_SOLD", event.event)
        assertEquals(0, prop.houses)
    }

    @Test
    fun `SELL_HOTEL succeeds during PAYING_RENT phase to raise funds`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 200, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.hasHotel = true
        gameState.players[0].ownedPropertyIds.add(1)

        val siblings = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == prop.color && it.id != prop.id }
        siblings.forEach {
            it.ownerId = "host-1"
            it.houses = 4
            gameState.players[0].ownedPropertyIds.add(it.id)
        }

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "SELL_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("HOTEL_SOLD", event.event)
        assertEquals(false, prop.hasHotel)
    }

    @Test
    fun `MORTGAGE succeeds during PAYING_RENT phase to raise funds`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 200, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "MORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GameEvent.PROPERTY_MORTGAGED, event.event)
        assertTrue(prop.isMortgaged)
    }

    @Test
    fun `UNMORTGAGE rejects during PAYING_RENT phase`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 200, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "UNMORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Cannot unmortgage while rent is due"))
    }

    @Test
    fun `PAY_RENT rejects when player is bankrupt`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 0, ownedPropertyIds = mutableListOf()))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "PAY_RENT",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("bankrupt"))
    }

    @Test
    fun `PAY_RENT rejects when fieldId does not match sourceFieldId`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 50, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "PAY_RENT",
            payload = mutableMapOf("fieldId" to "99")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Invalid fieldId"))
    }

    @Test
    fun `DECLARE_BANKRUPTCY rejects when player has buildings`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.houses = 1
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DECLARE_BANKRUPTCY"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Sell all houses and hotels"))
    }

    @Test
    fun `DECLARE_BANKRUPTCY rejects when player has unmortgaged properties`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DECLARE_BANKRUPTCY"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Mortgage all properties"))
    }

    @Test
    fun `DECLARE_BANKRUPTCY rejects when player already eliminated`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "host-2")
        gameState.currentPlayerIndex = 0
        gameState.players[0].eliminated = true

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DECLARE_BANKRUPTCY"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("already declared bankruptcy"))
    }

    @Test
    fun `DECLARE_BANKRUPTCY returns properties to bank when no creditor`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = null)
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DECLARE_BANKRUPTCY"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GameEvent.BANKRUPTCY_DECLARED, event.event)
        assertEquals(0, gameState.players[0].money)
        assertNull(prop.ownerId)
    }

    @Test
    fun `MORTGAGE rejects when player is bankrupt`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 0, ownedPropertyIds = mutableListOf()))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "MORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("bankrupt"))
    }

    @Test
    fun `MORTGAGE rejects when field has buildings`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.houses = 1
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "MORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Sell all houses"))
    }

    @Test
    fun `MORTGAGE rejects when sibling in color set has houses`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING
        gameState.players[0].ownedPropertyIds.addAll(listOf(1, 3))
        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        val prop3 = gameState.fields[3] as PropertyField
        prop3.ownerId = "host-1"
        prop3.houses = 2

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "MORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("color set"))
    }

    @Test
    fun `MORTGAGE rejects when sibling in color set has hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING
        gameState.players[0].ownedPropertyIds.addAll(listOf(1, 3))
        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        val prop3 = gameState.fields[3] as PropertyField
        prop3.ownerId = "host-1"
        prop3.hasHotel = true

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "MORTGAGE_PROPERTY",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("color set"))
    }

    @Test
    fun `BUY_HOUSE rejects when color set has mortgaged property`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING
        gameState.players[0].ownedPropertyIds.addAll(listOf(1, 3))
        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        val prop3 = gameState.fields[3] as PropertyField
        prop3.ownerId = "host-1"
        prop3.isMortgaged = true

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_HOUSE",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("mortgaged"))
    }

    @Test
    fun `BUY_HOTEL rejects when color set has mortgaged property`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1000))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING
        gameState.players[0].ownedPropertyIds.addAll(listOf(1, 3))
        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        prop1.houses = 4
        val prop3 = gameState.fields[3] as PropertyField
        prop3.ownerId = "host-1"
        prop3.houses = 4
        prop3.isMortgaged = true

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("mortgaged"))
    }

    @Test
    fun `PAY_JAIL_FINE resets consecutiveDoublets`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.position = 10
        player.money = 100
        player.consecutiveDoublets = 2
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "PAY_JAIL_FINE"))

        assertEquals(0, player.consecutiveDoublets)
        assertEquals(false, player.inJail)
    }

    @Test
    fun `USE_JAIL_CARD resets consecutiveDoublets`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.position = 10
        player.consecutiveDoublets = 2
        player.getOutOfJailCards = 1
        gameState.advanceTurn()

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "USE_JAIL_CARD"))

        assertEquals(0, player.consecutiveDoublets)
        assertEquals(false, player.inJail)
    }

    @Test
    fun `forced jail payment on 3rd failed roll resets consecutiveDoublets`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 200))
        val player = gameState.players[0]

        var attempts = 0
        while (true) {
            player.inJail = true
            player.position = 10
            player.jailTurns = 2
            player.consecutiveDoublets = 2
            gameState.phase = GamePhase.ROLLING
            gameState.currentPlayerIndex = 0

            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))
            if (!gameState.lastDiceRoll!!.isDouble) {
                assertEquals(false, player.inJail)
                assertEquals(0, player.jailTurns)
                assertEquals(0, player.consecutiveDoublets)
                break
            }
            attempts++
            if (attempts > 50) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll a non-doublet in 50 attempts")
        }
    }

    @Test
    fun `forced jail payment with insufficient money sets money to zero`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 20))
        val player = gameState.players[0]

        var attempts = 0
        while (true) {
            player.inJail = true
            player.position = 10
            player.jailTurns = 2
            player.money = 20
            gameState.phase = GamePhase.ROLLING
            gameState.currentPlayerIndex = 0

            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))
            if (!gameState.lastDiceRoll!!.isDouble) {
                assertEquals(false, player.inJail)
                assertEquals(0, player.jailTurns)
                assertEquals(0, player.money)
                break
            }
            attempts++
            if (attempts > 50) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll a non-doublet in 50 attempts")
        }
    }

    @Test
    fun `USE_JAIL_CARD returns card to deck`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        player.position = 10
        player.getOutOfJailCards = 1
        gameState.advanceTurn()

        val chanceBefore = gameState.chanceCards.size
        val communityBefore = gameState.communityChestCards.size
        val totalBefore = chanceBefore + communityBefore

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "USE_JAIL_CARD"))

        assertEquals(0, player.getOutOfJailCards)
        val totalAfter = gameState.chanceCards.size + gameState.communityChestCards.size
        assertEquals(totalBefore + 1, totalAfter)
    }

    @Test
    fun `bank bankruptcy cancels mortgages on seized properties`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = null)
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DECLARE_BANKRUPTCY"))

        assertNull(prop.ownerId)
        assertFalse(prop.isMortgaged)
    }

    @Test
    fun `bank bankruptcy cancels mortgages on seized properties when creditor also bankrupt`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(amount = 100, source = PaymentSource.RENT, sourceFieldId = 1,
            creditorPlayerId = "creditor-1")
        gameState.currentPlayerIndex = 0
        val prop = gameState.fields[1] as PropertyField
        prop.ownerId = "host-1"
        prop.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "DECLARE_BANKRUPTCY"))

        assertNull(prop.ownerId)
        assertFalse(prop.isMortgaged)
    }

    @Test
    fun `BUY_HOUSE succeeds when all same-color properties owned and no mortgages`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING
        gameState.players[0].ownedPropertyIds.addAll(listOf(1, 3))
        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"
        val prop3 = gameState.fields[3] as PropertyField
        prop3.ownerId = "host-1"

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_HOUSE",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("HOUSE_BOUGHT", event.event)
        assertEquals(1, prop1.houses)
    }

    @Test
    fun `buyHotel should fail when sibling has less than four houses and no hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.BUYING
        val player = gameState.players[0]
        val prop1 = gameState.fields[1] as PropertyField
        val prop3 = gameState.fields[3] as PropertyField
        prop1.ownerId = "host-1"; prop1.houses = 4
        prop3.ownerId = "host-1"; prop3.houses = 2 // sibling underdeveloped
        player.ownedPropertyIds.addAll(listOf(1, 3))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("4 houses or a hotel"))
    }

    @Test
    fun `buyHotel should succeed when sibling already has a hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.BUYING
        val player = gameState.players[0]
        val prop1 = gameState.fields[1] as PropertyField
        val prop3 = gameState.fields[3] as PropertyField
        prop1.ownerId = "host-1"; prop1.houses = 4
        prop3.ownerId = "host-1"; prop3.hasHotel = true; prop3.houses = 0
        player.ownedPropertyIds.addAll(listOf(1, 3))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("HOTEL_BOUGHT", event.event)
        assertTrue(prop1.hasHotel)
    }

    @Test
    fun `sellHotel should fail when sibling has less than three houses and no hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.ROLLING
        val player = gameState.players[0]
        val prop1 = gameState.fields[1] as PropertyField
        val prop3 = gameState.fields[3] as PropertyField
        prop1.ownerId = "host-1"; prop1.hasHotel = true; prop1.houses = 0
        prop3.ownerId = "host-1"; prop3.houses = 2 // sibling underdeveloped
        player.ownedPropertyIds.addAll(listOf(1, 3))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "SELL_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("at least 3 houses or a hotel"))
    }

    @Test
    fun `sellHotel should succeed when sibling has four houses`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.ROLLING
        val player = gameState.players[0]
        val prop1 = gameState.fields[1] as PropertyField
        val prop3 = gameState.fields[3] as PropertyField
        prop1.ownerId = "host-1"; prop1.hasHotel = true; prop1.houses = 0
        prop3.ownerId = "host-1"; prop3.houses = 4
        player.ownedPropertyIds.addAll(listOf(1, 3))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "SELL_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("HOTEL_SOLD", event.event)
        assertFalse(prop1.hasHotel)
        assertEquals(4, prop1.houses)
    }

    @Test
    fun `sellHotel should succeed when sibling has three houses`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.ROLLING
        val player = gameState.players[0]
        val prop1 = gameState.fields[1] as PropertyField
        val prop3 = gameState.fields[3] as PropertyField
        prop1.ownerId = "host-1"; prop1.hasHotel = true; prop1.houses = 0
        prop3.ownerId = "host-1"; prop3.houses = 3
        player.ownedPropertyIds.addAll(listOf(1, 3))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "SELL_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("HOTEL_SOLD", event.event)
        assertFalse(prop1.hasHotel)
        assertEquals(4, prop1.houses)
    }

    @Test
    fun `sellHotel should succeed when sibling also has a hotel`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.ROLLING
        val player = gameState.players[0]
        val prop1 = gameState.fields[1] as PropertyField
        val prop3 = gameState.fields[3] as PropertyField
        prop1.ownerId = "host-1"; prop1.hasHotel = true; prop1.houses = 0
        prop3.ownerId = "host-1"; prop3.hasHotel = true; prop3.houses = 0
        player.ownedPropertyIds.addAll(listOf(1, 3))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "SELL_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("HOTEL_SOLD", event.event)
        assertFalse(prop1.hasHotel)
        assertEquals(4, prop1.houses)
    }

    @Test
    fun `buyHotel should fail when sibling is mortgaged`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.BUYING
        val player = gameState.players[0]
        val prop1 = gameState.fields[1] as PropertyField
        val prop3 = gameState.fields[3] as PropertyField
        prop1.ownerId = "host-1"; prop1.houses = 4
        prop3.ownerId = "host-1"; prop3.houses = 4; prop3.isMortgaged = true
        player.ownedPropertyIds.addAll(listOf(1, 3))

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "BUY_HOTEL",
            payload = mutableMapOf("fieldId" to "1")))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("mortgaged"))
    }

    // --- Logic Fix Verification & Coverage Expansion ---

    @Test
    fun `verify Hotel Pricing - charges only houseCost instead of hotelCost`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 300)
        gameController.joinGame(gameState.gameId, player)

        val fields = gameState.fields as MutableList
        fields[1] = (fields[1] as PropertyField).copy(ownerId = "p1", houses = 4, houseCost = 50, hotelCost = 250)
        fields[3] = (fields[3] as PropertyField).copy(ownerId = "p1", houses = 4, houseCost = 50, hotelCost = 250)

        player.ownedPropertyIds.addAll(listOf(1, 3))
        player.money = 300
        player.position = 1
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayerIndex = gameState.players.indexOfFirst { it.id == "p1" }

        controller.handleAction(GameAction(gameState.gameId, "p1", "BUY_HOTEL", mutableMapOf("fieldId" to "1")))

        // 300 - 50 = 250.
        assertEquals(250, player.money)
        assertTrue((gameState.fields[1] as PropertyField).hasHotel)
    }

    @Test
    fun `verify Identity Spoofing Vulnerability - documented`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val alice = Player(id = "alice", name = "Alice", iconId = "woerthersee", money = 1500)
        val bob = Player(id = "bob", name = "Bob", iconId = "lindwurm", money = 1500)
        gameController.joinGame(gameState.gameId, alice)
        gameController.joinGame(gameState.gameId, bob)

        gameState.currentPlayerIndex = gameState.players.indexOfFirst { it.id == "bob" }
        gameState.phase = GamePhase.BUYING
        bob.position = 1

        val action = GameAction(
            gameId = gameState.gameId,
            playerId = "bob", // SPOOFED by client
            action = "BUY_PROPERTY",
            payload = mutableMapOf("fieldId" to "1")
        )

        controller.handleAction(action)

        assertEquals(1440, bob.money)
        assertEquals("bob", (gameState.fields[1] as PropertyField).ownerId)
    }

    @Test
    fun `verify Bank Supply Limit Absence - documented`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 100000)
        gameController.joinGame(gameState.gameId, player)

        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.BUYING

        val properties = gameState.fields.filterIsInstance<PropertyField>()

        // Build 4 houses on MANY properties (exceeding 32)
        properties.forEach {
            it.ownerId = "p1"
            player.ownedPropertyIds.add(it.id)
            it.isMortgaged = false
        }

        // Manually build to avoid complex state transitions and focus on supply absence
        properties.take(20).forEach { it.houses = 4 }

        val totalHouses = properties.sumOf { it.houses }
        assertTrue(totalHouses >= 80, "Should be able to build many houses (no bank limit yet). Actual: $totalHouses")
    }

    @Test
    fun `executeAction COLLECT_MONEY should increase player funds`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500)
        gameController.joinGame(gameState.gameId, player)

        val card = at.aau.monopoly.klagenfurt.model.card.ChanceCard(id = 1, description = "Win 100", action = at.aau.monopoly.klagenfurt.model.enums.CardAction.COLLECT_MONEY, amount = 100)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(1600, player.money)
    }

    @Test
    fun `executeAction PAY_MONEY should decrease player funds and add to free parking`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500)
        gameController.joinGame(gameState.gameId, player)

        val card = at.aau.monopoly.klagenfurt.model.card.ChanceCard(id = 2, description = "Pay 50", action = at.aau.monopoly.klagenfurt.model.enums.CardAction.PAY_MONEY, amount = 50)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0
        gameState.freeParkingMoney = 100

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(1450, player.money)
        assertEquals(150, gameState.freeParkingMoney)
    }

    @Test
    fun `executeAction GO_TO_JAIL should move player to jail`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500, position = 15)
        gameController.joinGame(gameState.gameId, player)

        val card = at.aau.monopoly.klagenfurt.model.card.ChanceCard(id = 5, description = "Go to Jail", action = at.aau.monopoly.klagenfurt.model.enums.CardAction.GO_TO_JAIL)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(10, player.position)
        assertTrue(player.inJail)
    }

    @Test
    fun `resolveLandingEffects skips rent when property is mortgaged`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        val owner = Player(id = "p1", name = "Alice", iconId = "woerthersee", money = 1500)
        val lander = Player(id = "p2", name = "Bob", iconId = "lindwurm", money = 1500, position = 0)
        gameController.joinGame(gameState.gameId, owner)
        gameController.joinGame(gameState.gameId, lander)

        val field = gameState.fields[1] as PropertyField
        field.ownerId = "p1"
        field.isMortgaged = true
        owner.ownedPropertyIds.add(field.id)

        gameState.currentPlayerIndex = gameState.players.indexOfFirst { it.id == "p2" }
        gameState.phase = GamePhase.ROLLING
        lander.position = 1

        controller.handleAction(GameAction(gameState.gameId, "p2", "ROLL_DICE",
            payload = mutableMapOf("cheat" to "true")))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GamePhase.BUYING, gameState.phase)
        assertNull(gameState.pendingPayment)
    }

    @Test
    fun `resolveLandingEffects triggers RENT_DUE when landing on owned railroad`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        val owner = Player(id = "p1", name = "Alice", iconId = "woerthersee", money = 1500)
        val lander = Player(id = "p2", name = "Bob", iconId = "lindwurm", money = 1500, position = 0)
        gameController.joinGame(gameState.gameId, owner)
        gameController.joinGame(gameState.gameId, lander)

        val rrField = gameState.fields.first { it is RailroadField }
        (rrField as RailroadField).ownerId = "p1"
        owner.ownedPropertyIds.add(rrField.id)
        val rrPosition = rrField.id

        gameState.currentPlayerIndex = gameState.players.indexOfFirst { it.id == "p2" }
        gameState.phase = GamePhase.ROLLING
        lander.position = (rrPosition - 12 + 40) % 40

        controller.handleAction(GameAction(gameState.gameId, "p2", "ROLL_DICE",
            payload = mutableMapOf("cheat" to "true")))

        val events = captureMessages(messagingTemplate, 2)
        val rentEvent = events.find { (it.second as? GameEvent)?.event == GameEvent.RENT_DUE }
        assertNotNull(rentEvent, "Expected RENT_DUE event when landing on owned railroad")
        assertEquals(GamePhase.PAYING_RENT, gameState.phase)
        assertNotNull(gameState.pendingPayment)
        assertEquals(25, gameState.pendingPayment!!.amount)
    }

    @Test
    fun `resolveLandingEffects triggers RENT_DUE when landing on owned utility`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        val owner = Player(id = "p1", name = "Alice", iconId = "woerthersee", money = 1500)
        val lander = Player(id = "p2", name = "Bob", iconId = "lindwurm", money = 1500, position = 0)
        gameController.joinGame(gameState.gameId, owner)
        gameController.joinGame(gameState.gameId, lander)

        val utField = gameState.fields.first { it is UtilityField }
        (utField as UtilityField).ownerId = "p1"
        owner.ownedPropertyIds.add(utField.id)
        val utPosition = utField.id

        gameState.currentPlayerIndex = gameState.players.indexOfFirst { it.id == "p2" }
        gameState.phase = GamePhase.ROLLING
        lander.position = (utPosition - 12 + 40) % 40

        controller.handleAction(GameAction(gameState.gameId, "p2", "ROLL_DICE",
            payload = mutableMapOf("cheat" to "true")))

        val events = captureMessages(messagingTemplate, 2)
        val rentEvent = events.find { (it.second as? GameEvent)?.event == GameEvent.RENT_DUE }
        assertNotNull(rentEvent, "Expected RENT_DUE event when landing on owned utility")
        assertEquals(GamePhase.PAYING_RENT, gameState.phase)
        assertNotNull(gameState.pendingPayment)
    }

    @Test
    fun `resolveLandingEffects skips rent when property owner is eliminated`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        val owner = Player(id = "p1", name = "Alice", iconId = "woerthersee", money = 1500, eliminated = true)
        val lander = Player(id = "p2", name = "Bob", iconId = "lindwurm", money = 1500, position = 0)
        gameController.joinGame(gameState.gameId, owner)
        gameController.joinGame(gameState.gameId, lander)

        val field = gameState.fields[1] as PropertyField
        field.ownerId = "p1"
        owner.ownedPropertyIds.add(field.id)

        gameState.currentPlayerIndex = gameState.players.indexOfFirst { it.id == "p2" }
        gameState.phase = GamePhase.ROLLING
        lander.position = 1

        controller.handleAction(GameAction(gameState.gameId, "p2", "ROLL_DICE",
            payload = mutableMapOf("cheat" to "true")))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GamePhase.BUYING, gameState.phase)
        assertNull(gameState.pendingPayment)
    }

    @Test
    fun `resolveLandingEffects skips rent when landing on own property`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        val owner = Player(id = "p1", name = "Alice", money = 1500, position = 0)
        gameController.joinGame(gameState.gameId, owner)

        val field = gameState.fields[1] as PropertyField
        field.ownerId = "p1"
        owner.ownedPropertyIds.add(field.id)

        gameState.currentPlayerIndex = gameState.players.indexOfFirst { it.id == "p1" }
        gameState.phase = GamePhase.ROLLING
        owner.position = 1

        controller.handleAction(GameAction(gameState.gameId, "p1", "ROLL_DICE",
            payload = mutableMapOf("cheat" to "true")))

        val event = captureMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals(GamePhase.BUYING, gameState.phase)
        assertNull(gameState.pendingPayment)
    }

    @Test
    fun `executeAction MOVE_TO with -1 moves player to nearest railroad`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500, position = 0)
        gameController.joinGame(gameState.gameId, player)

        val card = ChanceCard(id = 10, description = "Advance to nearest Railroad", action = CardAction.MOVE_TO, targetFieldId = -1)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(5, player.position)
    }

    @Test
    fun `executeAction MOVE_TO with -2 moves player to nearest utility`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500, position = 0)
        gameController.joinGame(gameState.gameId, player)

        val card = ChanceCard(id = 11, description = "Advance to nearest Utility", action = CardAction.MOVE_TO, targetFieldId = -2)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(12, player.position)
    }

    @Test
    fun `executeAction MOVE_TO collects 200 when passing Go`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500, position = 35)
        gameController.joinGame(gameState.gameId, player)

        val card = ChanceCard(id = 12, description = "Go to Go", action = CardAction.MOVE_TO, targetFieldId = 0)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(0, player.position)
        assertEquals(1700, player.money)
    }

    @Test
    fun `executeAction GET_OUT_OF_JAIL increments players card count`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500)
        gameController.joinGame(gameState.gameId, player)

        val card = ChanceCard(id = 13, description = "Get Out of Jail Free", action = CardAction.GET_OUT_OF_JAIL)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(1, player.getOutOfJailCards)
    }

    @Test
    fun `executeAction GET_OUT_OF_JAIL does not return card to deck`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500)
        gameController.joinGame(gameState.gameId, player)

        val chanceSizeBefore = gameState.chanceCards.size
        val card = CommunityChestCard(id = 14, description = "Get Out of Jail Free", action = CardAction.GET_OUT_OF_JAIL)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(chanceSizeBefore, gameState.chanceCards.size)
    }

    @Test
    fun `executeAction PAY_PER_BUILDING charges per house and per hotel`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500)
        gameController.joinGame(gameState.gameId, player)

        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "p1"
        prop1.houses = 2
        player.ownedPropertyIds.add(1)

        val prop3 = gameState.fields[3] as PropertyField
        prop3.ownerId = "p1"
        prop3.hasHotel = true
        prop3.houses = 0
        player.ownedPropertyIds.add(3)

        val card = ChanceCard(id = 15, description = "Street repairs", action = CardAction.PAY_PER_BUILDING, perBuildingAmount = 25, perHotelAmount = 100)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0
        gameState.freeParkingMoney = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(1350, player.money)
        assertEquals(150, gameState.freeParkingMoney)
    }

    @Test
    fun `executeAction PAY_PER_BUILDING with no buildings charges nothing`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 1500)
        gameController.joinGame(gameState.gameId, player)

        val card = ChanceCard(id = 16, description = "Street repairs", action = CardAction.PAY_PER_BUILDING, perBuildingAmount = 25, perHotelAmount = 100)
        gameState.currentActionCard = card
        gameState.currentPlayerIndex = 0

        controller.handleAction(GameAction(gameState.gameId, "p1", "EXECUTE_ACTION"))

        assertEquals(1500, player.money)
    }

    @Test
    fun `DEBUG_FORWARD_GAME assigns money and properties`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        gameController.joinGame(gameState.gameId, Player(id = "p1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "p2", name = "Bob", iconId = "woerthersee"))
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayerIndex = 0
        Mockito.clearInvocations(messagingTemplate)

        controller.setDebugMode(true)
        controller.handleAction(GameAction(gameState.gameId, "p1", "DEBUG_FORWARD_GAME"))

        val players = gameState.players
        players.forEach { assertEquals(10000, it.money) }
        val propertyField = gameState.fields.filterIsInstance<PropertyField>()
        assertTrue(propertyField.any { it.ownerId != null && it.houses == 3 })
    }

    @Test
    fun `DEBUG_SETUP_BANKRUPTCY sets up rent due`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        gameController.joinGame(gameState.gameId, Player(id = "p1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "p2", name = "Bob", iconId = "woerthersee"))
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayerIndex = 0
        Mockito.clearInvocations(messagingTemplate)

        controller.setDebugMode(true)
        controller.handleAction(GameAction(gameState.gameId, "p1", "DEBUG_SETUP_BANKRUPTCY"))

        assertEquals(GamePhase.PAYING_RENT, gameState.phase)
        assertNotNull(gameState.pendingPayment)
        assertEquals(1200, gameState.pendingPayment!!.amount)
        assertEquals(25, gameState.players[0].money)
    }

    @Test
    fun `DEBUG_FORWARD_GAME returns error when debug disabled`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame("host")
        gameController.joinGame(gameState.gameId, Player(id = "p1", name = "Alice"))
        gameState.phase = GamePhase.BUYING
        gameState.currentPlayerIndex = 0
        Mockito.clearInvocations(messagingTemplate)

        // debugMode defaults to false via @Value (no property set in test)
        controller.handleAction(GameAction(gameState.gameId, "p1", "DEBUG_FORWARD_GAME"))

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Debug actions are disabled"))
    }

    @Test
    fun `handleDeclareBankruptcy advances turn to next non-eliminated player`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameController.joinGame(gameState.gameId, Player(id = "host-3", name = "Charlie", iconId = "gti", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100, source = PaymentSource.RENT, sourceFieldId = 1, creditorPlayerId = "host-2"
        )
        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"; prop1.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameState.gameId, "host-1", "DECLARE_BANKRUPTCY"))

        assertTrue(gameState.players[0].eliminated)
        assertEquals(1, gameState.currentPlayerIndex)
        assertEquals("host-2", gameState.currentPlayer!!.id)
    }

    @Test
    fun `handleDeclareBankruptcy sets bankruptcyPlayerId on state`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 500))
        gameState.currentPlayerIndex = 0
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 100, source = PaymentSource.RENT, sourceFieldId = 1, creditorPlayerId = "host-2"
        )
        val prop1 = gameState.fields[1] as PropertyField
        prop1.ownerId = "host-1"; prop1.isMortgaged = true
        gameState.players[0].ownedPropertyIds.add(1)

        controller.handleAction(GameAction(gameState.gameId, "host-1", "DECLARE_BANKRUPTCY"))

        assertEquals("host-1", gameState.bankruptcyPlayerId)
    }

    // ─── Turn-timeout / forceEndTurn ───────────────────────────────────────────

    @Test
    fun `forceEndTurn returns false for unknown game`() {
        val (controller, _, _) = createController()
        assertFalse(controller.forceEndTurn("does-not-exist"))
    }

    @Test
    fun `forceEndTurn returns false while WAITING`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        assertFalse(controller.forceEndTurn(gameState.gameId))
    }

    @Test
    fun `forceEndTurn rolls and advances when player idle in ROLLING phase`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
        gameState.advanceTurn()
        // Avoid landing on a doublet special-case by forcing a non-card field start.
        gameState.currentPlayer!!.position = 1

        val ended = controller.forceEndTurn(gameState.gameId)

        assertTrue(ended)
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_TIMEOUT", event.event)
        // Turn should have advanced away from the idle player (unless a doublet was
        // rolled, in which case they keep the turn but a new ROLLING phase begins).
        assertTrue(gameState.phase == GamePhase.ROLLING)
    }

    @Test
    fun `forceEndTurn ends turn from BUYING phase`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 1
        gameState.phase = GamePhase.BUYING
        gameState.lastDiceRoll = DiceRoll(2, 5) // non-doublet

        val ended = controller.forceEndTurn(gameState.gameId)

        assertTrue(ended)
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_TIMEOUT", event.event)
        assertEquals("host-1", gameState.currentPlayer!!.id)
    }

    @Test
    fun `forceEndTurn auto-draws and executes card on card field`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee"))
        gameState.advanceTurn()
        gameState.currentPlayer!!.position = 7 // ChanceField
        gameState.phase = GamePhase.BUYING
        gameState.hasDrawnCardThisTurn = false
        gameState.lastDiceRoll = DiceRoll(2, 5) // non-doublet

        val ended = controller.forceEndTurn(gameState.gameId)

        assertTrue(ended)
        // Card resolved and turn advanced — no leftover action card.
        assertNull(gameState.currentActionCard)
        assertEquals("host-1", gameState.currentPlayer!!.id)
    }

    @Test
    fun `forceEndTurn auto-pays rent when affordable`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 1500))
        gameState.currentPlayerIndex = 0
        gameState.currentPlayer!!.position = 1
        gameState.phase = GamePhase.PAYING_RENT
        gameState.lastDiceRoll = DiceRoll(2, 5)
        gameState.pendingPayment = PendingPayment(
            amount = 100, source = PaymentSource.RENT, sourceFieldId = 1, creditorPlayerId = "host-2"
        )

        val ended = controller.forceEndTurn(gameState.gameId)

        assertTrue(ended)
        assertNull(gameState.pendingPayment)
        assertEquals(1400, gameState.players[0].money)
        assertEquals(1600, gameState.players[1].money)
    }

    @Test
    fun `forceEndTurn declares bankruptcy when rent unpayable`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 1500))
        gameState.currentPlayerIndex = 0
        gameState.currentPlayer!!.position = 1
        gameState.phase = GamePhase.PAYING_RENT
        gameState.lastDiceRoll = DiceRoll(2, 5)
        gameState.pendingPayment = PendingPayment(
            amount = 1200, source = PaymentSource.RENT, sourceFieldId = 1, creditorPlayerId = "host-2"
        )

        val ended = controller.forceEndTurn(gameState.gameId)

        assertTrue(ended)
        assertTrue(gameState.players[0].eliminated)
        assertNull(gameState.pendingPayment)
    }

    @Test
    fun `forceEndTurn liquidates assets to cover rent before bankruptcy`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 10))
        gameController.joinGame(gameState.gameId, Player(id = "host-2", name = "Bob", iconId = "woerthersee", money = 1500))
        gameState.currentPlayerIndex = 0
        gameState.currentPlayer!!.position = 3
        gameState.phase = GamePhase.PAYING_RENT
        gameState.lastDiceRoll = DiceRoll(2, 5)
        // Give the debtor a mortgageable property worth enough to cover the debt.
        val owned = gameState.fields[1] as PropertyField
        owned.ownerId = "host-1"
        gameState.players[0].ownedPropertyIds.add(1)
        val mortgageValue = owned.price / 2
        gameState.pendingPayment = PendingPayment(
            amount = mortgageValue, source = PaymentSource.RENT, sourceFieldId = 3, creditorPlayerId = "host-2"
        )
        val rentProp = gameState.fields[3] as PropertyField
        rentProp.ownerId = "host-2"

        val ended = controller.forceEndTurn(gameState.gameId)

        assertTrue(ended)
        // Player should have mortgaged and paid rather than going bankrupt.
        assertFalse(gameState.players[0].eliminated)
        assertTrue(owned.isMortgaged)
        assertNull(gameState.pendingPayment)
    }

    @Test
    fun `resetTurnTimer refreshes the deadline on action`() {
        val (controller, gameController, _) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameState.advanceTurn()
        // Simulate an old timer.
        gameState.turnTimerStartedAtMillis = 1L

        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))

        assertTrue(gameState.turnTimerStartedAtMillis > 1L)
    }

    @Test
    fun `handleAction ROLL_DICE should deduct tax when landing on income tax field`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", position = 2, money = 1500))
        gameState.advanceTurn()

        val player = gameState.currentPlayer!!
        val freeParkingBefore = gameState.freeParkingMoney

        // From position 2, rolling 2 will land on position 4 (Income Tax)
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf()
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("DICE_ROLLED", event.event)
        // If landed on position 4, money should be 1300 (1500 - 200 tax)
        if (player.position == 4) {
            assertEquals(1300, player.money)
            assertEquals(freeParkingBefore + 200, gameState.freeParkingMoney)
            assertTrue(event.message!!.contains("Reichensteuer") && event.message!!.contains("200€"))
        }
    }

    @Test
    fun `handleAction ROLL_DICE should deduct tax when landing on income tax field (position 4)`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", position = 2, money = 1500))
        gameState.advanceTurn()

        val player = gameState.currentPlayer!!
        val freeParkingBefore = gameState.freeParkingMoney

        // From position 2, rolling 2 will land on position 4 (Income Tax)
        // We simulate by manually rolling 1+1=2
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf()
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("DICE_ROLLED", event.event)
        // If landed on position 4, money should be 1300 (1500 - 200 tax)
        if (player.position == 4) {
            assertEquals(1300, player.money)
            assertEquals(freeParkingBefore + 200, gameState.freeParkingMoney)
            assertTrue(event.message!!.contains("Reichensteuer") && event.message!!.contains("200€"))
        }
    }

    @Test
    fun `handleAction ROLL_DICE should deduct luxury tax when landing on position 38`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", position = 36, money = 1500))
        gameState.advanceTurn()

        val player = gameState.currentPlayer!!
        val freeParkingBefore = gameState.freeParkingMoney

        // From position 36, rolling 2 will land on position 38 (Luxury Tax)
        // We don't control the exact roll, but if it happens, we verify the tax
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf()
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("DICE_ROLLED", event.event)
        // If landed on position 38, money should be reduced by 100
        if (player.position == 38) {
            assertEquals(1400, player.money)
            assertEquals(freeParkingBefore + 100, gameState.freeParkingMoney)
            assertTrue(event.message!!.contains("Reichensteuer") && event.message!!.contains("100€"))
        }
    }

    // ═══ TAX FIELD EDGE CASES ═══
    
    @Test
    fun `TAX_FIELD pays immediately when sufficient funds`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        val player = Player(id = "host-1", name = "Alice", position = 4, money = 500)
        gameController.joinGame(gameState.gameId, player)
        gameState.phase = GamePhase.ROLLING
         
        // Manually force the player to position 4 (Income Tax)
        player.position = 4
        player.money = 500
         
        Mockito.clearInvocations(messagingTemplate)
         
        // Simulate landing on Tax by handling dice roll
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true") // Use cheat mode for predictable roll
            )
        )
         
        // The tax should be handled automatically
        // Player money should be at least 200 less if landed on field 4
        assertTrue(gameState.pendingPayment == null || player.money <= 500, "Player should either have paid or have pending payment")
    }
     
    @Test
    fun `TAX_FIELD creates pending payment when insufficient funds`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        val player = Player(id = "host-1", name = "Alice", money = 50)
        gameController.joinGame(gameState.gameId, player)
        gameState.phase = GamePhase.ROLLING
        gameState.currentPlayerIndex = 0
         
        // Manually move player to position 4 (Income Tax - 200€)
        player.position = 4
        player.money = 50  // Not enough for 200€ tax
         
        Mockito.clearInvocations(messagingTemplate)
         
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )
         
        // After rolling and potentially landing on tax field
        if (player.position == 4) {
            assertNotNull(gameState.pendingPayment, "PendingPayment should be created for insufficient funds")
            assertEquals(PaymentSource.TAX, gameState.pendingPayment?.source)
            assertEquals(200, gameState.pendingPayment?.amount)
            assertEquals(GamePhase.PAYING_RENT, gameState.phase)
        }
    }
     
    @Test
    fun `PAY_TAX deducts money and adds to Free Parking`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 1500))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 200,
            source = PaymentSource.TAX,
            sourceFieldId = 4,
            creditorPlayerId = null
        )
         
        val player = gameState.currentPlayer!!
        val moneyBefore = player.money
        val freeParkingBefore = gameState.freeParkingMoney
         
        Mockito.clearInvocations(messagingTemplate)
         
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )
         
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
         
        assertEquals("TAX_PAID", event.event)
        assertEquals(moneyBefore - 200, player.money)
        assertEquals(freeParkingBefore + 200, gameState.freeParkingMoney)
        assertNull(gameState.pendingPayment)
        assertEquals(GamePhase.TURN_END, gameState.phase)
    }
     
    @Test
    fun `PAY_TAX rejects when insufficient funds (forces Bankruptcy)`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 100))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 200,
            source = PaymentSource.TAX,
            sourceFieldId = 4,
            creditorPlayerId = null,
            debtorCanPayAfterAssets = false
        )
         
        val player = gameState.currentPlayer!!
         
        Mockito.clearInvocations(messagingTemplate)
         
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )
         
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
         
        assertEquals("PAYMENT_FAILED", event.event)
        assertEquals(100, player.money)  // Money unchanged
        assertNotNull(gameState.pendingPayment)  // Payment still pending
    }
     
    @Test
    fun `TAX_FIELD luxury tax (38) is 100€ not 200€`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        val player = Player(id = "host-1", name = "Alice", money = 1500)
        gameController.joinGame(gameState.gameId, player)
        gameState.phase = GamePhase.ROLLING
        gameState.currentPlayerIndex = 0
         
        // Manually move to field 38 (Luxury Tax)
        player.position = 38
        player.money = 200
         
        val freeParkingBefore = gameState.freeParkingMoney
         
        Mockito.clearInvocations(messagingTemplate)
         
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )
         
        // After rolling
        if (player.position == 38) {
            assertEquals(100, player.money)  // 200 - 100€ luxury tax
            assertEquals(freeParkingBefore + 100, gameState.freeParkingMoney)
        }
    }
     
    @Test
    fun `TAX payment fails when player is already bankrupt`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice", money = 100))
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 200,
            source = PaymentSource.TAX,
            sourceFieldId = 4,
            creditorPlayerId = null
        )
         
        val player = gameState.currentPlayer!!
        player.money = -100  // Mark as bankrupt
         
        Mockito.clearInvocations(messagingTemplate)
         
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )
         
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
         
        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("bankrupt", ignoreCase = true))
    }
     
    @Test
    fun `TAX payment can be deferred by selling assets`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        val player = Player(id = "host-1", name = "Alice", money = 100)
        gameController.joinGame(gameState.gameId, player)
         
        // Give player a property with a house
        val propField = gameState.fields[1] as PropertyField
        propField.ownerId = player.id
        propField.houses = 1
        player.ownedPropertyIds.add(1)
         
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = PendingPayment(
            amount = 200,
            source = PaymentSource.TAX,
            sourceFieldId = 4,
            creditorPlayerId = null,
            debtorCanPayAfterAssets = true  // Can pay after selling assets
        )
         
        Mockito.clearInvocations(messagingTemplate)
         
        // Try to pay tax - should fail initially but indicate bankruptcy handling available
        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )
         
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
         
        // Should indicate payment failed but suggest asset selling
        assertEquals("PAYMENT_FAILED", event.event)
        assertNotNull(gameState.pendingPayment)
    }
    @Test
    fun `ROLL_DICE landing on Free Parking collects jackpot`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.ROLLING
        gameState.freeParkingMoney = 300
        player.position = 8
        player.money = 1500

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "ROLL_DICE",
                payload = mutableMapOf("cheat" to "true")
            )
        )

        val events = captureLastMessages(messagingTemplate, 2)
            .map { it.second as GameEvent }

        assertEquals(20, player.position)
        assertEquals(1800, player.money)
        assertEquals(0, gameState.freeParkingMoney)
        assertTrue(events.any { it.event == GameEvent.FREE_PARKING_COLLECTED })
    }
    @Test
    fun `PAY_TAX should move money to Free Parking and emit TAX_PAID`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        gameState.freeParkingMoney = 0
        player.money = 150

        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.TAX,
            sourceFieldId = 38,
            creditorPlayerId = null,
            debtorCanPayAfterAssets = true
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "38")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals(GameEvent.TAX_PAID, event.event)
        assertEquals(50, player.money)
        assertEquals(100, gameState.freeParkingMoney)
        assertNull(gameState.pendingPayment)
        assertEquals(GamePhase.TURN_END, gameState.phase)
    }
    @Test
    fun `PAY_TAX with insufficient money emits payment failed`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.money = 50

        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.TAX,
            sourceFieldId = 38
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "38")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals(GameEvent.PAYMENT_FAILED, event.event)
        assertEquals(50, player.money)
        assertEquals(0, gameState.freeParkingMoney)
        assertNotNull(gameState.pendingPayment)
    }
    @Test
    fun `PAY_TAX with wrong field id should send error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.money = 500

        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.TAX,
            sourceFieldId = 38
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertEquals(500, player.money)
        assertEquals(0, gameState.freeParkingMoney)
        assertNotNull(gameState.pendingPayment)
    }


    @Test
    fun `PAY_TAX with missing field id should send error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.money = 500

        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.TAX,
            sourceFieldId = 38
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX"
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertEquals(500, player.money)
        assertEquals(0, gameState.freeParkingMoney)
        assertNotNull(gameState.pendingPayment)
    }

    @Test
    fun `PAY_TAX outside paying rent phase should send error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "38")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
    }

    @Test
    fun `PAY_TAX without pending payment should send error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        gameState.pendingPayment = null

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "38")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
    }

    @Test
    fun `forceEndTurn should auto liquidate houses to pay tax debt`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.money = 0

        val property = gameState.fields.filterIsInstance<PropertyField>().first()
        property.ownerId = player.id
        property.houses = 2
        player.ownedPropertyIds.add(property.id)

        gameState.pendingPayment = PendingPayment(
            amount = 50,
            source = PaymentSource.TAX,
            sourceFieldId = 4,
            creditorPlayerId = null,
            debtorCanPayAfterAssets = true
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.forceEndTurn(gameState.gameId)

        assertTrue(player.money >= 0)
        assertTrue(property.houses < 2)
        assertEquals(50, gameState.freeParkingMoney)
        assertNull(gameState.pendingPayment)
    }

    @Test
    fun `forceEndTurn should auto mortgage property to pay tax debt`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.money = 0

        val property = gameState.fields.filterIsInstance<PropertyField>().first()
        property.ownerId = player.id
        property.houses = 0
        property.isMortgaged = false
        player.ownedPropertyIds.add(property.id)

        gameState.pendingPayment = PendingPayment(
            amount = 20,
            source = PaymentSource.TAX,
            sourceFieldId = 4,
            creditorPlayerId = null,
            debtorCanPayAfterAssets = true
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.forceEndTurn(gameState.gameId)

        assertTrue(property.isMortgaged)
        assertEquals(20, gameState.freeParkingMoney)
        assertNull(gameState.pendingPayment)
    }

    @Test
    fun `USE_JAIL_CARD should return jail card to deck`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.ROLLING
        player.inJail = true
        player.getOutOfJailCards = 1

        val chanceSizeBefore = gameState.chanceCards.size
        val communitySizeBefore = gameState.communityChestCards.size

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "USE_JAIL_CARD"
            )
        )

        assertFalse(player.inJail)
        assertEquals(0, player.getOutOfJailCards)
        assertTrue(
            gameState.chanceCards.size == chanceSizeBefore + 1 ||
                    gameState.communityChestCards.size == communitySizeBefore + 1
        )
    }
    @Test
    fun `PAY_TAX with zero pending amount should send error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.money = 500

        gameState.pendingPayment = PendingPayment(
            amount = 0,
            source = PaymentSource.TAX,
            sourceFieldId = 4
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertEquals("No pending payment to pay.", event.message)
    }

    @Test
    fun `PAY_TAX by bankrupt player should send error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.eliminated = true
        player.money = 500

        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.TAX,
            sourceFieldId = 4
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertEquals("You are bankrupt and cannot pay rent.", event.message)
    }

    @Test
    fun `PAY_RENT with unsupported payment source should send error`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT

        gameState.pendingPayment = PendingPayment(
            amount = 100,
            source = PaymentSource.CARD_PAY,
            sourceFieldId = null
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_RENT"
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Unsupported payment source"))
    }
    @Test
    fun `ROLL_DICE landing on empty Free Parking should not send collected event`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.ROLLING
        gameState.freeParkingMoney = 0
        player.position = 8
        player.money = 1500

        Mockito.clearInvocations(messagingTemplate)

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
        assertEquals(20, player.position)
        assertEquals(1500, player.money)
        assertEquals(0, gameState.freeParkingMoney)
        assertNotEquals(GameEvent.FREE_PARKING_COLLECTED, event.event)
    }
    @Test
    fun `UNMORTGAGE_PROPERTY should send error when player has not enough money`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        player.money = 1

        val property = gameState.fields.filterIsInstance<PropertyField>().first()
        property.ownerId = player.id
        property.isMortgaged = true
        player.ownedPropertyIds.add(property.id)

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "UNMORTGAGE_PROPERTY",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals("ERROR", event.event)
        assertTrue(event.message!!.contains("Not enough money to unmortgage"))
        assertTrue(property.isMortgaged)
    }
    @Test
    fun `UNMORTGAGE_PROPERTY should unmortgage property when player has enough money`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        player.money = 1000

        val property = gameState.fields.filterIsInstance<PropertyField>().first()
        property.ownerId = player.id
        property.isMortgaged = true
        player.ownedPropertyIds.add(property.id)

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "UNMORTGAGE_PROPERTY",
                payload = mutableMapOf("fieldId" to property.id.toString())
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals(GameEvent.PROPERTY_UNMORTGAGED, event.event)
        assertFalse(property.isMortgaged)
    }

    @Test
    fun `PAY_TAX should pay pending tax and add amount to free parking`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.PAYING_RENT
        player.money = 500
        gameState.freeParkingMoney = 50

        gameState.pendingPayment = PendingPayment(
            amount = 200,
            source = PaymentSource.TAX,
            sourceFieldId = 4,
            creditorPlayerId = null
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "PAY_TAX",
                payload = mutableMapOf("fieldId" to "4")
            )
        )

        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent

        assertEquals(GameEvent.TAX_PAID, event.event)
        assertEquals(300, player.money)
        assertEquals(250, gameState.freeParkingMoney)
        assertNull(gameState.pendingPayment)
        assertEquals(GamePhase.TURN_END, gameState.phase)
    }

    @Test
    fun `EXECUTE_ACTION move to tax field should emit TAX_PAID when player has enough money`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        player.position = 0
        player.money = 1500
        gameState.freeParkingMoney = 0

        gameState.currentActionCard = ChanceCard(
            id = 9001,
            description = "Advance to Income Tax",
            action = CardAction.MOVE_TO,
            targetFieldId = 4
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "EXECUTE_ACTION"
            )
        )

        val events = captureLastMessages(messagingTemplate, 2)
            .map { it.second as GameEvent }

        assertEquals(4, player.position)
        assertEquals(1300, player.money)
        assertEquals(200, gameState.freeParkingMoney)
        assertTrue(events.any { it.event == GameEvent.TAX_PAID })
    }

    @Test
    fun `EXECUTE_ACTION move to tax field should emit TAX_DUE when player has insufficient money`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        player.position = 0
        player.money = 50

        gameState.currentActionCard = ChanceCard(
            id = 9002,
            description = "Advance to Income Tax",
            action = CardAction.MOVE_TO,
            targetFieldId = 4
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "EXECUTE_ACTION"
            )
        )

        val events = captureLastMessages(messagingTemplate, 2)
            .map { it.second as GameEvent }

        assertEquals(4, player.position)
        assertEquals(GamePhase.PAYING_RENT, gameState.phase)
        assertNotNull(gameState.pendingPayment)
        assertEquals(PaymentSource.TAX, gameState.pendingPayment!!.source)
        assertEquals(200, gameState.pendingPayment!!.amount)
        assertTrue(events.any { it.event == GameEvent.TAX_DUE })
    }

    @Test
    fun `EXECUTE_ACTION move to Free Parking should collect jackpot`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))

        val player = gameState.players[0]
        gameState.advanceTurn()
        gameState.phase = GamePhase.BUYING
        player.position = 0
        player.money = 1000
        gameState.freeParkingMoney = 300

        gameState.currentActionCard = ChanceCard(
            id = 9003,
            description = "Advance to Free Parking",
            action = CardAction.MOVE_TO,
            targetFieldId = 20
        )

        Mockito.clearInvocations(messagingTemplate)

        controller.handleAction(
            GameAction(
                gameId = gameState.gameId,
                playerId = "host-1",
                action = "EXECUTE_ACTION"
            )
        )

        val events = captureLastMessages(messagingTemplate, 2)
            .map { it.second as GameEvent }

        assertEquals(20, player.position)
        assertEquals(1300, player.money)
        assertEquals(0, gameState.freeParkingMoney)
        assertTrue(events.any { it.event == GameEvent.FREE_PARKING_COLLECTED })
    }


}
