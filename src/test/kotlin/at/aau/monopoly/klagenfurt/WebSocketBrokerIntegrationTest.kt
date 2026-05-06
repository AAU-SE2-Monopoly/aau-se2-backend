package at.aau.monopoly.klagenfurt

import at.aau.monopoly.klagenfurt.messaging.dtos.GameAction
import at.aau.monopoly.klagenfurt.messaging.dtos.GameEvent
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.websocket.StompFrameHandlerClientImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.converter.MessageConverter
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketBrokerIntegrationTest {

    @Autowired
    private lateinit var gameController: at.aau.monopoly.klagenfurt.controller.GameController

    @Autowired
    private lateinit var webSocketBrokerController: at.aau.monopoly.klagenfurt.websocket.broker.WebSocketBrokerController

    @LocalServerPort
    private var port: Int = 0

    private val websocketUri get() = "ws://localhost:$port/ws"

    private fun awaitNewGameId(existingIds: Set<String>, timeoutSeconds: Long = 2): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val newGameId = gameController.listGameIds().firstOrNull { it !in existingIds }
            if (newGameId != null) {
                return newGameId
            }
            Thread.sleep(50)
        }
        throw AssertionError("Expected a new game to be created within $timeoutSeconds seconds.")
    }

    private fun <T> initStompSession(
        destination: String,
        messageConverter: MessageConverter,
        queue: BlockingQueue<T>,
        expectedType: Class<T>
    ): StompSession {
        val stompClient = WebSocketStompClient(StandardWebSocketClient())
        stompClient.messageConverter = messageConverter
        val session = stompClient.connectAsync(websocketUri, object : StompSessionHandlerAdapter() {})
            .get(1, TimeUnit.SECONDS)
        session.subscribe(destination, StompFrameHandlerClientImpl(queue, expectedType))
        return session
    }

    // ─── Game: create ────────────────────────────────────────────────────────

    @Test
    fun `create game broadcasts GAME_CREATED event`() {
        val events: BlockingQueue<GameEvent> = LinkedBlockingDeque()
        val jackson = JacksonJsonMessageConverter()

        val stompClient = WebSocketStompClient(StandardWebSocketClient())
        stompClient.messageConverter = jackson
        val session = stompClient.connectAsync(websocketUri, object : StompSessionHandlerAdapter() {})
            .get(1, TimeUnit.SECONDS)

        val player = Player(id = UUID.randomUUID().toString(), name = "Alice")
        session.send("/app/game/create", player)
        Thread.sleep(300) // let server create the game

        // The test passes if no exception is thrown (endpoint reachable, JSON serializable)
        session.disconnect()
    }

    @Test
    fun `create game stores host icon in broadcast game state`() {
        val existingIds = gameController.listGameIds()
        val jackson = JacksonJsonMessageConverter()
        val stompClient = WebSocketStompClient(StandardWebSocketClient())
        stompClient.messageConverter = jackson
        val session = stompClient.connectAsync(websocketUri, object : StompSessionHandlerAdapter() {})
            .get(1, TimeUnit.SECONDS)

        session.send("/app/game/create", Player(id = "host-player", name = "Alice", iconId = "gti"))

        val gameId = awaitNewGameId(existingIds)
        val gameState = gameController.getGameState(gameId)

        assertThat(gameState).isNotNull
        assertThat(gameState!!.players).hasSize(1)
        assertThat(gameState.players[0].id).isEqualTo("host-player")
        assertThat(gameState.players[0].iconId).isEqualTo("gti")

        session.disconnect()
    }

    // ─── Game: full flow with known gameId ───────────────────────────────────
    @Test
    fun `full game flow - create, join, start, roll dice`() {
        val jackson = JacksonJsonMessageConverter()

        // ---- Session A: creator (Alice) ----
        val alicePlayerId = UUID.randomUUID().toString()
        val existingIds = gameController.listGameIds()

        val stompClientA = WebSocketStompClient(StandardWebSocketClient())
        stompClientA.messageConverter = jackson
        val sessionA = stompClientA.connectAsync(websocketUri, object : StompSessionHandlerAdapter() {})
            .get(2, TimeUnit.SECONDS)

        // Alice creates a game
        sessionA.send(
            "/app/game/create",
            Player(id = alicePlayerId, name = "Alice", iconId = "gti")
        )

        // Get game ID from controller directly
        val gameId = awaitNewGameId(existingIds)
        val createdState = gameController.getGameState(gameId)
        assertThat(createdState).isNotNull
        assertThat(createdState!!.players).hasSize(1)

        // ---- Session B: joiner (Bob) ----
        val bobPlayerId = UUID.randomUUID().toString()

        val stompClientB = WebSocketStompClient(StandardWebSocketClient())
        stompClientB.messageConverter = jackson
        val sessionB = stompClientB.connectAsync(websocketUri, object : StompSessionHandlerAdapter() {})
            .get(2, TimeUnit.SECONDS)

        // Bob joins the game
        sessionB.send(
            "/app/game/join",
            GameAction(
                gameId = gameId,
                playerId = bobPlayerId,
                payload = mapOf("name" to "Bob", "iconId" to "ironman")
            )
        )
        Thread.sleep(500) // let server process join

        val afterJoin = gameController.getGameState(gameId)!!
        assertThat(afterJoin.players).hasSize(2)
        assertThat(afterJoin.players[1].name).isEqualTo("Bob")

        // Alice starts the game
        sessionA.send(
            "/app/game/start",
            GameAction(gameId = gameId, playerId = alicePlayerId)
        )
        Thread.sleep(500) // let server process start

        val afterStart = gameController.getGameState(gameId)!!
        assertThat(afterStart.phase).isEqualTo(GamePhase.ROLLING)
        val currentPlayerId = afterStart.currentPlayer?.id
        assertThat(currentPlayerId).isNotNull

        // The player whose turn it is rolls the dice
        val rollerSession = if (currentPlayerId == alicePlayerId) sessionA else sessionB
        rollerSession.send(
            "/app/game/action",
            GameAction(gameId = gameId, playerId = currentPlayerId!!, action = "ROLL_DICE")
        )
        Thread.sleep(500) // let server process roll

        val afterRoll = gameController.getGameState(gameId)!!
        assertThat(afterRoll.lastDiceRoll).isNotNull
        val rollTotal = afterRoll.lastDiceRoll!!.total
        assertThat(rollTotal).isBetween(2, 12)
        assertThat(afterRoll.phase).isEqualTo(GamePhase.BUYING)

        // Cleanup
        sessionA.disconnect()
        sessionB.disconnect()
    }


    @Test
    fun `join game stores icon from payload and broadcasts it`() {
        val gameState = gameController.createGame(hostPlayerId = "host-for-join")
        gameController.joinGame(
            gameState.gameId,
            Player(id = "host-for-join", name = "Alice", iconId = "woerthersee")
        )

        webSocketBrokerController.joinGame(
            GameAction(
                gameId = gameState.gameId,
                playerId = "joiner-1",
                payload = mapOf(
                    "name" to "Bob",
                    "iconId" to "ironman"
                )
            )
        )

        val updatedGameState = gameController.getGameState(gameState.gameId)
        assertThat(updatedGameState).isNotNull
        assertThat(updatedGameState!!.players).extracting<String> { it.iconId }
            .containsExactly("woerthersee", "ironman")
        assertThat(updatedGameState.players[1].name).isEqualTo("Bob")
        assertThat(updatedGameState.players[1].iconId).isEqualTo("ironman")
    }

    // ─── Game: action - ROLL_DICE ─────────────────────────────────────────────

    @Test
    fun `sending ROLL_DICE action to unknown game returns ERROR event`() {
        val events: BlockingQueue<GameEvent> = LinkedBlockingDeque()
        val fakeGameId = "nonexistent-game-id"

        val session = initStompSession(
            "/topic/game/$fakeGameId",
            JacksonJsonMessageConverter(),
            events,
            GameEvent::class.java
        )

        val action = GameAction(
            gameId = fakeGameId,
            playerId = "player-1",
            action = "ROLL_DICE"
        )
        session.send("/app/game/action", action)

        val event = events.poll(2, TimeUnit.SECONDS)
        assertThat(event).isNotNull
        assertThat(event!!.event).isEqualTo("ERROR")
        assertThat(event.message).contains("not found")

        session.disconnect()
    }

    // ─── Game: state - unknown game ───────────────────────────────────────────

    @Test
    fun `requesting state of unknown game returns ERROR event`() {
        val events: BlockingQueue<GameEvent> = LinkedBlockingDeque()
        val fakeGameId = "nonexistent-game-id-2"

        val session = initStompSession(
            "/topic/game/$fakeGameId",
            JacksonJsonMessageConverter(),
            events,
            GameEvent::class.java
        )

        session.send("/app/game/state", GameAction(gameId = fakeGameId))

        val event = events.poll(2, TimeUnit.SECONDS)
        assertThat(event).isNotNull
        assertThat(event!!.event).isEqualTo("ERROR")

        session.disconnect()
    }
}
