package at.aau.monopoly.klagenfurt.websocket.broker

import at.aau.monopoly.klagenfurt.controller.GameController
import at.aau.monopoly.klagenfurt.messaging.dtos.GameAction
import at.aau.monopoly.klagenfurt.messaging.dtos.GameEvent
import at.aau.monopoly.klagenfurt.messaging.dtos.LobbyEvent
import at.aau.monopoly.klagenfurt.model.DiceRoll
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.card.Card
import at.aau.monopoly.klagenfurt.model.enums.CardAction
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.model.field.ChanceField
import at.aau.monopoly.klagenfurt.model.field.CommunityChestField
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Controller
class WebSocketBrokerController(
    private val messagingTemplate: SimpMessagingTemplate,
    private val gameController: GameController
) {

    private fun normalizeIconId(iconId: String?): String =
        iconId?.takeIf { it.isNotBlank() } ?: "lindwurm"

    /** CREATE – any client sends a player name; server creates a game and responds. */
    @MessageMapping("/game/create")
    fun createGame(player: Player) {
        val hostPlayer = player.copy(iconId = normalizeIconId(player.iconId))
        val gameState = gameController.createGame(hostPlayerId = hostPlayer.id)
        gameController.joinGame(gameState.gameId, hostPlayer)
        val event = GameEvent(
            gameId = gameState.gameId,
            event = "GAME_CREATED",
            gameState = gameState,
            message = "Game created. Share the gameId to let others join."
        )
        // Send to the real game topic (for any already-subscribed clients)
        messagingTemplate.convertAndSend("/topic/game/${gameState.gameId}", event)
        // Also send to the player's temporary topic so the creator receives the gameId
        // even though they couldn't subscribe to the real topic before it was known.
        messagingTemplate.convertAndSend("/topic/game/${hostPlayer.id}", event)
        // Broadcast updated lobby list so all clients in the lobby see the new game
        broadcastLobby()
    }

    /** JOIN – client sends a GameAction with gameId + player details in payload. */
    @MessageMapping("/game/join")
    fun joinGame(action: GameAction) {
        val gameState = gameController.getGameState(action.gameId)
            ?: run {
                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(gameId = action.gameId, event = "ERROR", message = "Game not found.")
                )
                return
            }
        val player = Player(
            id = action.playerId,
            name = action.payload["name"] ?: action.playerId,
            iconId = normalizeIconId(action.payload["iconId"])
        )
        try {
            gameController.joinGame(action.gameId, player)
        } catch (e: IllegalArgumentException) {
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = "ERROR",
                    gameState = gameState,
                    message = e.message ?: "Cannot join game."
                )
            )
            return
        }
        // Resolve the stored name from gameState (rejoin preserves existing identity)
        val joinedPlayerName = gameState.players.first { it.id == action.playerId }.name
        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(
                gameId = action.gameId,
                event = "PLAYER_JOINED",
                gameState = gameState,
                message = "$joinedPlayerName joined the game."
            )
        )
        // Broadcast updated lobby list (player count changed)
        broadcastLobby()
    }

    /** START – host sends a GameAction with gameId; game phase moves to ROLLING. */
    @MessageMapping("/game/start")
    fun startGame(action: GameAction) {
        val gameState = gameController.getGameState(action.gameId)
            ?: run {
                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(gameId = action.gameId, event = "ERROR", message = "Game not found.")
                )
                return
            }
        gameState.advanceTurn()
        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(
                gameId = action.gameId,
                event = "GAME_STARTED",
                gameState = gameState,
                message = "Game started. ${gameState.currentPlayer?.name}'s turn."
            )
        )
        // Game is no longer in WAITING phase – update lobby
        broadcastLobby()
    }

    /**
     * ACTION – client sends a GameAction.
     * Currently handles: ROLL_DICE, END_TURN.
     * Additional actions (BUY_PROPERTY, etc.) will be added with game logic.
     */
    @MessageMapping("/game/action")
    fun handleAction(action: GameAction) {
        println("DiceDebug backend action=${action.action} gameId=${action.gameId} playerId=${action.playerId}")
        println("DiceDebug backend action='${action.action}' length=${action.action.length}")
        val gameState = gameController.getGameState(action.gameId)
            ?: run {
                println("DiceDebug backend ERROR game not found for gameId=${action.gameId}")
                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(gameId = action.gameId, event = "ERROR", message = "Game not found.")
                )
                return
            }

        when (action.action) {
            "ROLL_DICE" -> {
                println("DiceDebug backend ROLL_DICE currentPlayer=${gameState.currentPlayer?.id} phase=${gameState.phase}")
                if (gameState.currentPlayer?.id != action.playerId) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "It is not your turn."
                        )
                    )
                    return
                }

                if (gameState.phase != GamePhase.ROLLING) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "Dice can only be rolled during the rolling phase."
                        )
                    )
                    return
                }


                val isCheating = action.payload["cheat"] == "true"
                val die1: Int
                val die2: Int

                if (isCheating) {
                    die1 = 6
                    die2 = 6
                    println("DiceDebug: CHEAT DETECTED for player ${action.playerId}! Rolling double six.")
                } else {
                    die1 = (1..6).random()
                    die2 = (1..6).random()
                }

                val roll = DiceRoll(die1, die2)
                val player = gameState.currentPlayer!!
                val oldPosition = player.position
                val newPosition = (oldPosition + roll.total) % gameState.fields.size

                // Pass-Go / land-on-Go bonus
                if (newPosition < oldPosition && newPosition != 0) {
                    player.money += 200
                }

                player.position = newPosition
                gameState.lastDiceRoll = roll
                gameState.phase = GamePhase.BUYING

                val passGoMsg = if (newPosition < oldPosition) " and passed Go (+200€)" else ""

                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(
                        gameId = action.gameId,
                        event = "DICE_ROLLED",
                        gameState = gameState,
                        message = "${player.name} rolled ${roll.die1} + ${roll.die2} = ${roll.total}$passGoMsg."
                    )
                )
            }

            "END_TURN" -> {
                gameState.endCurrentTurn()
                gameState.advanceTurn()
                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(
                        gameId = action.gameId,
                        event = "TURN_ENDED",
                        gameState = gameState,
                        message = "Next turn: ${gameState.currentPlayer?.name}."
                    )
                )
            }

            "DRAW_CARD" -> {
                //validate if the player has already drawn a card this turn (only one card per turn allowed)
                if (gameState.hasDrawnCardThisTurn) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "You can only draw one card per turn."
                        )
                    )
                    return
                }

                // Validate that it is the current player's turn
                if (gameState.currentPlayer?.id != action.playerId) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "It is not your turn."
                        )
                    )
                    return
                }

                val cardType = action.payload["cardType"] as? String
                if (cardType == null) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "cardType must be specified in payload."
                        )
                    )
                    return
                }

                // Validate that the player is on the correct field type
                val currentField = gameState.fields.getOrNull(gameState.currentPlayer?.position ?: -1)
                val isValidFieldType = when (cardType) {
                    "CHANCE" -> currentField is ChanceField
                    "COMMUNITY_CHEST" -> currentField is CommunityChestField
                    else -> {
                        messagingTemplate.convertAndSend(
                            "/topic/game/${action.gameId}",
                            GameEvent(
                                gameId = action.gameId,
                                event = "ERROR",
                                message = "Unknown card type: $cardType"
                            )
                        )
                        return
                    }
                }

                if (!isValidFieldType) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "You must be on a $cardType field to draw this card."
                        )
                    )
                    return
                }

                val card = when (cardType) {
                    "CHANCE" -> drawChanceCard(gameState)
                    "COMMUNITY_CHEST" -> drawCommunityChestCard(gameState)
                    else -> return  // Should not reach here due to earlier validation
                }

                gameState.currentActionCard = card
                gameState.hasDrawnCardThisTurn = true
                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(
                        gameId = action.gameId,
                        event = "ACTION_DRAWN",
                        gameState = gameState,
                        message = "Card drawn: ${card.description}"
                    )
                )
            }

            "EXECUTE_ACTION" -> {
                if (gameState.currentActionCard == null) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "No action card to execute."
                        )
                    )
                    return
                }

                val card = gameState.currentActionCard!!
                executeCardAction(gameState, card, action.playerId)
                gameState.currentActionCard = null

                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(
                        gameId = action.gameId,
                        event = "ACTION_EXECUTED",
                        gameState = gameState,
                        message = "Action executed: ${card.description}"
                    )
                )
            }

            else -> {
                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(gameId = action.gameId, event = "ERROR", message = "Unknown action: ${action.action}")
                )
}
        }
    }

    /** STATE – client requests a snapshot of the current game state. */
    @MessageMapping("/game/state")
    fun getGameState(action: GameAction) {
        val gameState = gameController.getGameState(action.gameId)
        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(
                gameId = action.gameId,
                event = if (gameState != null) "STATE_SNAPSHOT" else "ERROR",
                gameState = gameState,
                message = if (gameState == null) "Game not found." else null
            )
        )
    }

    /** LIST – client requests the list of all active games. */
    @MessageMapping("/game/list")
    @Suppress("UNUSED_PARAMETER")
    fun listGames(action: GameAction) {
        val allGames = gameController.listAllGames()
        messagingTemplate.convertAndSend(
            "/topic/lobby",
            LobbyEvent(
                event = "LOBBY_UPDATE",
                games = allGames
            )
        )
    }

    /** CLOSE – host closes (removes) a game. Only the host is allowed. */
    @MessageMapping("/game/close")
    fun closeGame(action: GameAction) {
        try {
            val closedGameState = gameController.closeGame(action.gameId, action.playerId)
            // Notify all subscribers of the game topic that the game was closed
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = "GAME_CLOSED",
                    gameState = closedGameState,
                    message = "The host has closed this game."
                )
            )
            // Update the lobby list
            broadcastLobby()
        } catch (e: IllegalArgumentException) {
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = "ERROR",
                    message = e.message ?: "Cannot close game."
                )
            )
        }
    }

    /** Broadcasts the current open-game list to all lobby subscribers. */
    private fun broadcastLobby() {
        val openGames = gameController.listAllGames()
        messagingTemplate.convertAndSend(
            "/topic/lobby",
            LobbyEvent(
                event = "LOBBY_UPDATE",
                games = openGames
            )
        )
    }

    /**
     * Handles WebSocket disconnects by logging the event.
     * Players are deliberately NOT removed from their game's player list on disconnect —
     * their slot and playerId persist, enabling reconnection detection via the existing
     * rejoin logic in [GameController.joinGame]. Only an explicit leave/close endpoint
     * should remove a player from the game.
     */
    @EventListener
    fun onSessionDisconnect(event: SessionDisconnectEvent) {
        logger.info(
            "Session disconnected: sessionId={}, userId={}",
            event.sessionId,
            event.user?.name ?: "unknown"
        )
        // No player removal — keep slot and playerIds for reconnection.
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebSocketBrokerController::class.java)
    }

    /**
     * Draw a Chance card from the deck. If the deck is empty, shuffle all cards back.
     */
    private fun drawChanceCard(gameState: at.aau.monopoly.klagenfurt.model.GameState): Card {
        if (gameState.chanceCards.isEmpty()) {
            gameState.chanceCards.addAll(
                at.aau.monopoly.klagenfurt.model.BoardFactory.createChanceCards()
            )
        }
        return gameState.chanceCards.removeAt(0)
    }

    /**
     * Draw a Community Chest card from the deck. If the deck is empty, shuffle all cards back.
     */
    private fun drawCommunityChestCard(gameState: at.aau.monopoly.klagenfurt.model.GameState): Card {
        if (gameState.communityChestCards.isEmpty()) {
            gameState.communityChestCards.addAll(
                at.aau.monopoly.klagenfurt.model.BoardFactory.createCommunityChestCards()
            )
        }
        return gameState.communityChestCards.removeAt(0)
    }

    /**
     * Execute a card action: transfer money, move player, etc.
     */
    private fun executeCardAction(
        gameState: at.aau.monopoly.klagenfurt.model.GameState,
        card: Card,
        playerId: String
    ) {
        val player = gameState.players.find { it.id == playerId } ?: return

        when (card.action) {
            CardAction.COLLECT_MONEY -> {
                player.money += card.amount
            }

            CardAction.PAY_MONEY -> {
                player.money -= card.amount
                gameState.freeParkingMoney += card.amount  // Money goes to Free Parking
            }

            CardAction.MOVE_TO -> {
                if (card.targetFieldId != null) {
                    val oldPosition = player.position
                    player.position = card.targetFieldId!!
                    // If moved past or to Go (position 0), collect $200
                    if (card.targetFieldId!! < oldPosition) {
                        player.money += 200
                    }
                }
            }

            CardAction.MOVE_FORWARD -> {
                player.position = (player.position + card.moveSpaces) % 40
                if (card.moveSpaces > 0) {
                    // If advanced past Go, collect $200
                    player.money += 200
                }
            }

            CardAction.GO_TO_JAIL -> {
                player.position = 10  // Jail field
                player.inJail = true
                player.jailTurns = 0
            }

            CardAction.GET_OUT_OF_JAIL -> {
                player.getOutOfJailCards += 1
            }

            CardAction.PAY_EACH_PLAYER -> {
                // Pay to each other player
                gameState.players.forEach { otherPlayer ->
                    if (otherPlayer.id != playerId) {
                        player.money -= card.amount
                        otherPlayer.money += card.amount
                    }
                }
            }

            CardAction.COLLECT_FROM_EACH -> {
                // Collect from each other player
                gameState.players.forEach { otherPlayer ->
                    if (otherPlayer.id != playerId) {
                        otherPlayer.money -= card.amount
                        player.money += card.amount
                    }
                }
            }
        }
    }
}
