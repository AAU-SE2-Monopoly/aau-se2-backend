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
    fun `startGame should remove game from open lobby list`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        gameController.joinGame(gameState.gameId, Player(id = "player-2", name = "Bob"))

        controller.startGame(GameAction(gameId = gameState.gameId))

        val messages = captureMessages(messagingTemplate, 2)
        val lobbyEvent = messages.first { it.first == "/topic/lobby" }.second as LobbyEvent

        assertEquals("LOBBY_UPDATE", lobbyEvent.event)
        assertTrue(lobbyEvent.games.none { it.gameId == gameState.gameId })
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

    @Test
    fun `handleAction ROLL_DICE in jail with doublet gets out of jail`() {
        val (controller, gameController, messagingTemplate) = createController()
        val gameState = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(gameState.gameId, Player(id = "host-1", name = "Alice"))
        val player = gameState.players[0]
        player.inJail = true
        gameState.advanceTurn()
        
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE", payload = mutableMapOf("cheat" to "true")))
        
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
        gameState.advanceTurn()
        
        var attempts = 0
        while (true) {
            gameState.phase = GamePhase.ROLLING
            player.jailTurns = 1
            controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE"))
            val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
            if (!gameState.lastDiceRoll!!.isDouble) {
                assertEquals(2, player.jailTurns)
                assertEquals(true, player.inJail)
                assertEquals(GamePhase.TURN_END, gameState.phase)
                break
            }
            attempts++
            if(attempts > 50) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll a non-doublet in 50 attempts")
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
            if(attempts > 50) org.junit.jupiter.api.Assertions.fail<Unit>("Could not roll a non-doublet in 50 attempts")
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
        
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE", payload = mutableMapOf("cheat" to "true")))
        
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
        
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE", payload = mutableMapOf("cheat" to "true")))
        
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
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = wrongPlayerId, action = "PAY_JAIL_FINE"))
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
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = wrongPlayerId, action = "USE_JAIL_CARD"))
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
        
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "USE_JAIL_CARD"))
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
        
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "USE_JAIL_CARD"))
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
        player.getOutOfJailCards = 1
        gameState.advanceTurn()
        
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "USE_JAIL_CARD"))
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
        val player = gameState.players[0]
        gameState.advanceTurn()
        
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "ROLL_DICE", payload = mutableMapOf("cheat" to "true")))
        controller.handleAction(GameAction(gameId = gameState.gameId, playerId = "host-1", action = "END_TURN"))
        
        val event = captureLastMessages(messagingTemplate, 1).single().second as GameEvent
        assertEquals("TURN_ENDED", event.event)
        assertEquals(GamePhase.ROLLING, gameState.phase)
        assertEquals("host-1", gameState.currentPlayer!!.id)
        assertTrue(event.message!!.contains("gets another turn"))
    }
}
