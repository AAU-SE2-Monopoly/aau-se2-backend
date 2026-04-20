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
                payload = emptyMap()
            )
        )

        val messages = captureMessages(messagingTemplate, 2)
        val gameEvent = messages.first { it.first == "/topic/game/${gameState.gameId}" }.second as GameEvent
        val lobbyEvent = messages.first { it.first == "/topic/lobby" }.second as LobbyEvent

        assertEquals("PLAYER_JOINED", gameEvent.event)
        assertEquals("player-2", gameEvent.gameState!!.players[1].name)
        assertEquals("lindwurm", gameEvent.gameState!!.players[1].iconId)
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

        controller.handleAction(GameAction(gameId = gameState.gameId, action = "ROLL_DICE"))

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

        controller.handleAction(GameAction(gameId = gameState.gameId, action = "END_TURN"))

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
    fun `listGames should broadcast waiting games to lobby`() {
        val (controller, gameController, messagingTemplate) = createController()
        val waitingGame = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(waitingGame.gameId, Player(id = "host-1", name = "Alice"))
        val startedGame = gameController.createGame(hostPlayerId = "host-2")
        gameController.joinGame(startedGame.gameId, Player(id = "host-2", name = "Bob"))
        gameController.getGameState(startedGame.gameId)!!.advanceTurn()

        controller.listGames(GameAction())

        val lobbyEvent = captureMessages(messagingTemplate, 1).single().second as LobbyEvent

        assertEquals("LOBBY_UPDATE", lobbyEvent.event)
        assertEquals(1, lobbyEvent.games.size)
        assertEquals(waitingGame.gameId, lobbyEvent.games.single().gameId)
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
}
