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
import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.FreeParkingField
import at.aau.monopoly.klagenfurt.model.field.OwnableField
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField
import at.aau.monopoly.klagenfurt.model.PaymentSource
import at.aau.monopoly.klagenfurt.model.PendingPayment
import at.aau.monopoly.klagenfurt.service.PaymentService
import at.aau.monopoly.klagenfurt.service.RentCalculator
import kotlin.math.ceil
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import at.aau.monopoly.klagenfurt.model.GameState
import java.util.concurrent.ConcurrentHashMap


@Controller
class WebSocketBrokerController(
    private val messagingTemplate: SimpMessagingTemplate,
    private val gameController: GameController
) {

    private val gameLocks = ConcurrentHashMap<String, Any>()

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
        val lock = gameLocks.computeIfAbsent(action.gameId) { Any() }
        synchronized(lock) {
            val gameState = gameController.getGameState(action.gameId)
                ?: run {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(gameId = action.gameId, event = "ERROR", message = "Game not found.")
                    )
                    return
                }

            when (action.action) {
                "ROLL_DICE" -> handleRollDice(action, gameState)

                "PAY_JAIL_FINE" -> {
                    if (!validateCurrentPlayerTurn(action, gameState)) return
                    val player = gameState.currentPlayer!!
                    if (!player.inJail) {
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "You are not in jail."))
                        return
                    }
                    if (player.money < 50) {
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "Not enough money to pay the fine."))
                        return
                    }
                    player.money -= 50
                    player.inJail = false
                    player.jailTurns = 0
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "JAIL_FINE_PAID",
                            gameState = gameState,
                            message = "${player.name} paid 50M to get out of jail."
                        )
                    )
                }

                "USE_JAIL_CARD" -> {
                    if (!validateCurrentPlayerTurn(action, gameState)) return
                    val player = gameState.currentPlayer!!
                    if (!player.inJail) {
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "You are not in jail."))
                        return
                    }
                    if (player.getOutOfJailCards <= 0) {
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "You do not have a Get Out of Jail Free card."))
                        return
                    }
                    player.getOutOfJailCards -= 1
                    player.inJail = false
                    player.jailTurns = 0
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "JAIL_CARD_USED",
                            gameState = gameState,
                            message = "${player.name} used a Get Out of Jail Free card."
                        )
                    )
                }

                "END_TURN" -> handleEndTurn(action, gameState)

                "DRAW_CARD" -> handleDrawCard(action, gameState)

                "EXECUTE_ACTION" -> handleExecuteAction(action, gameState)

                "BUY_PROPERTY" -> handleBuyProperty(action, gameState)

                "BUY_HOUSE" -> handleBuyHouse(action, gameState)

                "BUY_HOTEL" -> handleBuyHotel(action, gameState)

                "SELL_HOUSE" -> handleSellHouse(action, gameState)

                "SELL_HOTEL" -> handleSellHotel(action, gameState)

                "PAY_RENT" -> handlePayRent(action, gameState)

                "MORTGAGE_PROPERTY" -> handleMortgageProperty(action, gameState)

                "UNMORTGAGE_PROPERTY" -> handleUnmortgageProperty(action, gameState)

                "DECLARE_BANKRUPTCY" -> handleDeclareBankruptcy(action, gameState)

                else -> {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(gameId = action.gameId, event = "ERROR", message = "Unknown action: ${action.action}")
                    )
                }
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
        private const val INVALID_FIELD_ID_MESSAGE = "Invalid fieldId."
        private const val NOT_YOUR_TURN_MESSAGE = "It is not your turn."
    }

    /**
     * Draw a Chance card from the deck. If the deck is empty, shuffle all cards back.
     */
    private fun findNearestOfType(
        currentPos: Int,
        fields: List<Field>,
        predicate: (Field) -> Boolean
    ): Int {
        val size = fields.size
        for (i in 1..size) {
            val idx = (currentPos + i) % size
            if (predicate(fields[idx])) return idx
        }
        return -1
    }
    
    private fun drawChanceCard(gameState: GameState): Card {
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
    private fun drawCommunityChestCard(gameState: GameState): Card {
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
        gameState: GameState,
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
                    val target = when (card.targetFieldId) {
                        -1 -> findNearestOfType(oldPosition, gameState.fields) { it is RailroadField }
                        -2 -> findNearestOfType(oldPosition, gameState.fields) { it is UtilityField }
                        else -> card.targetFieldId!!
                    }
                    player.position = target
                    if (target < oldPosition) {
                        player.money += 200
                    }
                }
            }

            CardAction.MOVE_FORWARD -> {
                val newPosition = player.position + card.moveSpaces

                player.position = newPosition % 40

                if (newPosition >= 40) {
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

    private fun handleBuyHouse(
        action: GameAction,
        gameState: GameState
    ) {
        val property = getValidatedProperty(action, gameState) ?: return
        val player = gameState.currentPlayer!!

        if (gameState.phase != GamePhase.BUYING && gameState.phase != GamePhase.TURN_END) {
            sendGameError(action, gameState, "Houses can only be bought during your turn.")
            return
        }

        if (property.ownerId != player.id) {
            sendGameError(action, gameState, "You can only build on your own properties.")
            return
        }

        if (!ownsCompleteColorSet(gameState, player.id, property)) {
            sendGameError(action, gameState, "You need the complete color set to build houses.")
            return
        }

        if (property.hasHotel) {
            sendGameError(action, gameState, "This property already has a hotel.")
            return
        }

        if (property.houses >= 4) {
            sendGameError(action, gameState, "This property already has 4 houses.")
            return
        }

        if (!canBuildHouseEvenly(gameState, property)) {
            sendGameError(action, gameState, "Houses must be built evenly across the color set.")
            return
        }

        if (player.money < property.houseCost) {
            sendGameError(action, gameState, "Not enough money to buy a house.")
            return
        }

        player.money -= property.houseCost
        property.houses += 1

        sendGameEvent(
            action,
            gameState,
            "HOUSE_BOUGHT",
            "${player.name} bought a house on ${property.name}."
        )
    }

    private fun handleBuyHotel(
        action: GameAction,
        gameState: GameState
    ) {
        val property = getValidatedProperty(action, gameState) ?: return
        val player = gameState.currentPlayer!!

        if (property.ownerId != player.id) {
            sendGameError(action, gameState, "You can only build on your own properties.")
            return
        }

        if (!ownsCompleteColorSet(gameState, player.id, property)) {
            sendGameError(action, gameState, "You need the complete color set to build a hotel.")
            return
        }

        if (property.hasHotel) {
            sendGameError(action, gameState, "This property already has a hotel.")
            return
        }

        if (property.houses != 4) {
            sendGameError(action, gameState, "You need 4 houses on this property before buying a hotel.")
            return
        }

        if (player.money < property.hotelCost) {
            sendGameError(action, gameState, "Not enough money to buy a hotel.")
            return
        }

        player.money -= property.hotelCost
        property.houses = 0
        property.hasHotel = true

        sendGameEvent(
            action,
            gameState,
            "HOTEL_BOUGHT",
            "${player.name} bought a hotel on ${property.name}."
        )
    }

    private fun handleSellHouse(
        action: GameAction,
        gameState: GameState
    ) {
        val property = getValidatedProperty(action, gameState) ?: return
        val player = gameState.currentPlayer!!

        if (property.ownerId != player.id) {
            sendGameError(action, gameState, "You can only sell houses from your own properties.")
            return
        }

        if (property.hasHotel) {
            sendGameError(action, gameState, "Sell the hotel before selling houses.")
            return
        }

        if (property.houses <= 0) {
            sendGameError(action, gameState, "This property has no houses to sell.")
            return
        }

        if (!canSellHouseEvenly(gameState, property)) {
            sendGameError(action, gameState, "Houses must be sold evenly across the color set.")
            return
        }

        property.houses -= 1
        player.money += property.houseCost / 2

        sendGameEvent(
            action,
            gameState,
            "HOUSE_SOLD",
            "${player.name} sold a house on ${property.name}."
        )
    }

    private fun handleSellHotel(
        action: GameAction,
        gameState: GameState
    ) {
        val property = getValidatedProperty(action, gameState) ?: return
        val player = gameState.currentPlayer!!

        if (property.ownerId != player.id) {
            sendGameError(action, gameState, "You can only sell hotels from your own properties.")
            return
        }

        if (!property.hasHotel) {
            sendGameError(action, gameState, "This property has no hotel to sell.")
            return
        }

        property.hasHotel = false
        property.houses = 4
        player.money += property.hotelCost / 2

        sendGameEvent(
            action,
            gameState,
            "HOTEL_SOLD",
            "${player.name} sold a hotel on ${property.name}."
        )
    }

    private fun canSellHouseEvenly(
        gameState: GameState,
        property: PropertyField
    ): Boolean {
        val colorSet = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        val maxHouses = colorSet.maxOf { it.houses }

        return property.houses == maxHouses
    }

    private fun ownsCompleteColorSet(
        gameState: GameState,
        playerId: String,
        property: PropertyField
    ): Boolean {
        val colorSet = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        return colorSet.all { it.ownerId == playerId }
    }

    private fun canBuildHouseEvenly(
        gameState: GameState,
        property: PropertyField
    ): Boolean {
        val colorSet = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }

        val minHouses = colorSet.minOf { it.houses }

        return property.houses == minHouses
    }

    private fun sendGameError(
        action: GameAction,
        gameState: GameState,
        message: String
    ) {
        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(
                gameId = action.gameId,
                event = "ERROR",
                gameState = gameState,
                message = message
            )
        )
    }

    private fun sendGameEvent(
        action: GameAction,
        gameState: GameState,
        event: String,
        message: String
    ) {
        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(
                gameId = action.gameId,
                event = event,
                gameState = gameState,
                message = message
            )
        )
    }

    private fun handleEndTurn(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        if (gameState.phase != GamePhase.BUYING && gameState.phase != GamePhase.TURN_END) {
            sendGameError(action, gameState, "Cannot end turn in the current phase (${gameState.phase.name}).")
            return
        }

        val player = gameState.currentPlayer
        val isDoublet = gameState.lastDiceRoll?.isDouble == true

        if (player != null && isDoublet && !player.inJail && player.consecutiveDoublets > 0) {
            gameState.endCurrentTurn()
            gameState.phase = GamePhase.ROLLING
            sendGameEvent(
                action,
                gameState,
                "TURN_ENDED",
                "${player.name} rolled a doublet and gets another turn!"
            )
        } else {
            player?.let { it.consecutiveDoublets = 0 }

            gameState.endCurrentTurn()
            gameState.advanceTurn()

            sendGameEvent(
                action,
                gameState,
                "TURN_ENDED",
                "Next turn: ${gameState.currentPlayer?.name}."
            )
        }
    }

    private fun handleExecuteAction(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

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
        val player = gameState.players.find { it.id == action.playerId }
            ?: run {
                sendGameError(action, gameState, "Player not found in game.")
                return
            }
        executeCardAction(gameState, card, action.playerId)
        if (card.action == CardAction.MOVE_TO || card.action == CardAction.MOVE_FORWARD) {
            resolveLandingEffects(action, gameState, player)
        }
        gameState.currentActionCard = null
        sendGameEvent(
            action,
            gameState,
            "ACTION_EXECUTED",
            "Action executed: ${card.description}"
        )
    }

    private fun handleDrawCard(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        if (gameState.currentActionCard != null) {
            sendGameError(action, gameState, "You have already drawn a card this turn. Execute or end your turn first.")
            return
        }

        val cardType = action.payload["cardType"]

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

        val currentField =
            gameState.fields.getOrNull(gameState.currentPlayer?.position ?: -1)

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
            else -> return
        }

        gameState.currentActionCard = card

        sendGameEvent(
            action,
            gameState,
            "ACTION_DRAWN",
            "Card drawn: ${card.description}"
        )
    }

    private fun handleRollDice(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

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

        val roll = createDiceRoll(action)
        gameState.lastDiceRoll = roll

        val player = gameState.currentPlayer!!
        var eventMessage = "${player.name} rolled ${roll.die1} + ${roll.die2} = ${roll.total}."

        if (player.inJail) {
            eventMessage = handleJailRoll(gameState, player, roll, eventMessage)
        } else {
            eventMessage = handleNormalRoll(gameState, player, roll, eventMessage)
        }

        sendGameEvent(
            action,
            gameState,
            "DICE_ROLLED",
            eventMessage
        )

        resolveLandingEffects(action, gameState, player)
    }

    private fun createDiceRoll(action: GameAction): DiceRoll {
        val isCheating = action.payload["cheat"] == "true"

        return if (isCheating) {
            println("DiceDebug: CHEAT DETECTED for player ${action.playerId}! Rolling double six.")
            DiceRoll(6, 6)
        } else {
            DiceRoll(
                die1 = (1..6).random(),
                die2 = (1..6).random()
            )
        }
    }

    private fun handleJailRoll(
        gameState: GameState,
        player: Player,
        roll: DiceRoll,
        message: String
    ): String {
        var eventMessage = message

        if (roll.isDouble) {
            player.inJail = false
            player.jailTurns = 0
            eventMessage += " They rolled a doublet and got out of jail!"

            movePlayerAfterRoll(gameState, player, roll.total)

            player.consecutiveDoublets = 0
            gameState.phase = GamePhase.BUYING
        } else {
            player.jailTurns++

            if (player.jailTurns >= 3) {
                player.money -= 50
                player.inJail = false
                player.jailTurns = 0
                eventMessage += " Failed 3rd attempt. Paid 50M to get out!"

                movePlayerAfterRoll(gameState, player, roll.total)
                gameState.phase = GamePhase.BUYING
            } else {
                eventMessage += " Still in jail (turn ${player.jailTurns}/3)."
                gameState.phase = GamePhase.TURN_END
            }
        }

        return eventMessage
    }

    private fun handleNormalRoll(
        gameState: GameState,
        player: Player,
        roll: DiceRoll,
        message: String
    ): String {
        var eventMessage = message

        if (roll.isDouble) {
            player.consecutiveDoublets++

            if (player.consecutiveDoublets >= 3) {
                player.inJail = true
                player.position = 10
                player.jailTurns = 0
                player.consecutiveDoublets = 0
                eventMessage += " Rolled 3 doublets! Go to Jail!"
                gameState.phase = GamePhase.TURN_END
            } else {
                eventMessage += " Rolled a doublet! Gets another turn."
                eventMessage = movePlayerAndHandleGoToJail(gameState, player, roll.total, eventMessage)
            }
        } else {
            player.consecutiveDoublets = 0
            eventMessage = movePlayerAndHandleGoToJail(gameState, player, roll.total, eventMessage)
        }

        return eventMessage
    }

    private fun movePlayerAfterRoll(
        gameState: GameState,
        player: Player,
        rollTotal: Int
    ) {
        val oldPos = player.position
        val newPos = (oldPos + rollTotal) % gameState.fields.size
        player.position = newPos
    }

    private fun movePlayerAndHandleGoToJail(
        gameState: GameState,
        player: Player,
        rollTotal: Int,
        message: String
    ): String {
        var eventMessage = message

        val oldPos = player.position
        val newPos = (oldPos + rollTotal) % gameState.fields.size

        if (newPos < oldPos) {
            player.money += 200
            eventMessage += " and passed Go (+200€)."
        }

        player.position = newPos
        gameState.phase = GamePhase.BUYING

        if (newPos == 30) {
            player.inJail = true
            player.position = 10
            player.jailTurns = 0
            player.consecutiveDoublets = 0
            eventMessage += " Landed on Go To Jail!"
            gameState.phase = GamePhase.TURN_END
        }

        return eventMessage
    }

    private fun handleBuyProperty(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return



        if (gameState.phase != GamePhase.BUYING) {
            sendGameError(action, gameState, "Property can only be bought during the buying phase.")
            return
        }

        val fieldId = action.payload["fieldId"]?.toIntOrNull()

        if (fieldId == null || fieldId !in gameState.fields.indices) {
            sendGameError(action, gameState, INVALID_FIELD_ID_MESSAGE)
            return
        }

        val player = gameState.currentPlayer!!

        if (player.position != fieldId) {
            sendGameError(action, gameState, "You can only buy the field you are currently standing on.")
            return
        }

        val field = gameState.fields[fieldId]

        val price = when (field) {
            is PropertyField -> field.price
            is RailroadField -> field.price
            is UtilityField -> field.price
            else -> {
                sendGameError(action, gameState, "This field cannot be bought.")
                return
            }
        }

        val ownerId = when (field) {
            is PropertyField -> field.ownerId
            is RailroadField -> field.ownerId
            is UtilityField -> field.ownerId
            else -> null
        }

        if (ownerId != null) {
            sendGameError(action, gameState, "${field.name} is already owned by another player.")
            return
        }

        if (player.money < price) {
            sendGameError(
                action,
                gameState,
                "You don't have enough money to buy ${field.name}. Price: $$price, Your Money: $${player.money}"
            )
            return
        }

        player.money -= price

        when (field) {
            is PropertyField -> field.ownerId = player.id
            is RailroadField -> field.ownerId = player.id
            is UtilityField -> field.ownerId = player.id
        }

        player.ownedPropertyIds.add(fieldId)

        sendGameEvent(
            action,
            gameState,
            "PROPERTY_BOUGHT",
            "${player.name} bought ${field.name} for $$price."
        )
    }

    private fun resolveLandingEffects(
        action: GameAction,
        gameState: GameState,
        player: Player
    ) {
        val landedField = gameState.fields.getOrNull(player.position) ?: return

        if (landedField is OwnableField) {
            val ownerId = landedField.ownerId
            if (ownerId != null && ownerId != player.id && !landedField.isMortgaged) {
                val owner = gameState.players.find { it.id == ownerId }
                if (owner != null && !owner.isBankrupt()) {
                    val rent = when (landedField) {
                        is PropertyField -> RentCalculator.calculatePropertyRent(landedField, gameState.fields, ownerId)
                        is RailroadField -> RentCalculator.calculateRailroadRent(landedField, gameState.fields, ownerId)
                        is UtilityField -> {
                            val diceTotal = gameState.lastDiceRoll?.total ?: 0
                            RentCalculator.calculateUtilityRent(landedField, gameState.fields, ownerId, diceTotal)
                        }
                        else -> 0
                    }

                    if (rent > 0) {
                        gameState.phase = GamePhase.PAYING_RENT
                        gameState.pendingPayment = PendingPayment(
                            amount = rent,
                            source = PaymentSource.RENT,
                            sourceFieldId = landedField.id,
                            creditorPlayerId = ownerId
                        )
                        messagingTemplate.convertAndSend(
                            "/topic/game/${action.gameId}",
                            GameEvent(gameId = action.gameId, event = GameEvent.RENT_DUE, gameState = gameState)
                        )
                    }
                }
            }
        }

        if (landedField is FreeParkingField && gameState.freeParkingMoney > 0) {
            player.money += gameState.freeParkingMoney
            gameState.freeParkingMoney = 0
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(gameId = action.gameId, event = GameEvent.FREE_PARKING_COLLECTED, gameState = gameState)
            )
        }
    }

    private fun handlePayRent(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        if (gameState.phase != GamePhase.PAYING_RENT) {
            sendGameError(action, gameState, "Payment can only be made when payment is due.")
            return
        }

        val pending = gameState.pendingPayment ?: return
        if (pending.amount <= 0) {
            sendGameError(action, gameState, "No pending payment to pay.")
            return
        }

        val player = gameState.currentPlayer!!
        val fieldId = action.payload["fieldId"]?.toIntOrNull()

        // If the pending payment has a sourceFieldId, validate that it matches the payload
        pending.sourceFieldId?.let { expectedId ->
            if (fieldId == null || fieldId != expectedId) {
                sendGameError(action, gameState, "Invalid fieldId for payment. Expected: $expectedId, got: $fieldId")
                return
            }
        }

        // Process payment based on source
        when (pending.source) {
            PaymentSource.RENT -> {
                // Rent: money goes to the creditor player
                val creditorId = pending.creditorPlayerId
                if (creditorId == null) {
                    sendGameError(action, gameState, "Invalid pending payment: missing creditor for rent.")
                    return
                }
                val creditor = gameState.players.find { it.id == creditorId }
                if (creditor == null || creditor.isBankrupt()) {
                    sendGameError(action, gameState, "Creditor not found or bankrupt.")
                    return
                }
                if (player.money < pending.amount) {
                    sendPaymentFailed(action, gameState, pending.amount)
                    return
                }
                player.money -= pending.amount
                creditor.money += pending.amount
            }
            else -> {
                sendGameError(action, gameState, "Unsupported payment source: ${pending.source}")
                return
            }
        }

        // Clear pending payment
        gameState.pendingPayment = null
        gameState.phase = GamePhase.TURN_END

        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(gameId = action.gameId, event = GameEvent.RENT_PAID, gameState = gameState)
        )
    }

    private fun sendPaymentFailed(action: GameAction, gameState: GameState, amount: Int) {
        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(
                gameId = action.gameId,
                event = GameEvent.PAYMENT_FAILED,
                gameState = gameState,
                message = "Insufficient funds. Need \$$amount but have \$${gameState.currentPlayer?.money ?: 0}."
            )
        )
    }

    private fun handleMortgageProperty(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        val player = gameState.currentPlayer!!
        val fieldId = action.payload["fieldId"]?.toIntOrNull() ?: return
        val field = gameState.fields.find { it.id == fieldId }
        val hasBuildings = field is PropertyField && (field.houses > 0 || field.hasHotel)

        if (field is OwnableField && field.ownerId == player.id && !field.isMortgaged && !hasBuildings) {
            PaymentService.mortgageProperty(player, field)
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(gameId = action.gameId, event = GameEvent.PROPERTY_MORTGAGED, gameState = gameState)
            )
        } else if (hasBuildings) {
            sendGameError(action, gameState, "Sell all houses/hotels before mortgaging.")
        }
    }

    private fun handleUnmortgageProperty(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        val player = gameState.currentPlayer!!
        val fieldId = action.payload["fieldId"]?.toIntOrNull() ?: return
        val field = gameState.fields.find { it.id == fieldId }

        if (field is OwnableField && field.ownerId == player.id && field.isMortgaged) {
            val price = when (field) {
                is PropertyField -> field.price
                is RailroadField -> field.price
                is UtilityField -> field.price
                else -> 0
            }
            val unmortgageCost = ceil(price / 2.0 * 1.1).toInt()

            if (player.money >= unmortgageCost) {
                PaymentService.unmortgageProperty(player, field)
                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(gameId = action.gameId, event = GameEvent.PROPERTY_UNMORTGAGED, gameState = gameState)
                )
            } else {
                sendGameError(action, gameState, "Not enough money to unmortgage. Need ${unmortgageCost}M.")
            }
        }
    }

    private fun handleDeclareBankruptcy(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        if (gameState.phase != GamePhase.PAYING_RENT) {
            sendGameError(action, gameState, "Bankruptcy can only be declared when a payment is due.")
            return
        }

        val pending = gameState.pendingPayment
        if (pending == null || pending.amount <= 0) {
            sendGameError(action, gameState, "No pending payment to resolve.")
            return
        }

        val player = gameState.currentPlayer!!
        val creditorId = pending.creditorPlayerId
        val ownedFields = gameState.fields.filterIsInstance<OwnableField>()
            .filter { it.ownerId == player.id }
        val ownedFieldIds = ownedFields.map { (it as Field).id }
        val totalAssetValue = player.money + ownedFields.sumOf { field ->
            val price = when (field) {
                is PropertyField -> field.price
                is RailroadField -> field.price
                is UtilityField -> field.price
                else -> 0
            }
            price / 2
        }
        val totalDebt = pending.amount
        val propertiesCount = ownedFields.size

        if (creditorId != null) {
            val creditor = gameState.players.find { it.id == creditorId }
            val remainingCash = player.money
            player.money = 0
            val transferredIds = mutableListOf<Int>()
            if (creditor != null) {
                creditor.money += remainingCash
                creditor.getOutOfJailCards += player.getOutOfJailCards
                ownedFields.forEach { field ->
                    field.ownerId = creditorId
                    transferredIds.add((field as Field).id)
                }
                creditor.ownedPropertyIds.addAll(transferredIds)
            } else {
                // Creditor is bankrupt or removed — return properties to bank
                ownedFields.forEach { field ->
                    field.ownerId = null
                    transferredIds.add((field as Field).id)
                }
            }
            player.getOutOfJailCards = 0
            player.ownedPropertyIds.clear()
        } else {
            player.money = 0
            gameState.fields.filterIsInstance<OwnableField>()
                .filter { it.ownerId == player.id }
                .forEach { it.ownerId = null }
            player.ownedPropertyIds.clear()
            player.getOutOfJailCards = 0
        }

        gameState.phase = GamePhase.TURN_END
        gameState.pendingPayment = null
        gameState.bankruptcyTotalAssets = totalAssetValue
        gameState.bankruptcyTotalDebt = totalDebt
        gameState.bankruptcyPropertiesCount = propertiesCount
        gameState.bankruptcyOwnedFieldIds = ownedFieldIds

        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(gameId = action.gameId, event = GameEvent.BANKRUPTCY_DECLARED, gameState = gameState)
        )
    }

    private fun validateCurrentPlayerTurn(
        action: GameAction,
        gameState: GameState
    ): Boolean {
        return if (gameState.currentPlayer?.id != action.playerId) {
            sendGameError(action, gameState, NOT_YOUR_TURN_MESSAGE)
            false
        } else {
            true
        }
    }

    private fun getValidatedProperty(
        action: GameAction,
        gameState: GameState
    ): PropertyField? {
        if (!validateCurrentPlayerTurn(action, gameState)) return null

        val fieldId = action.payload["fieldId"]?.toIntOrNull()
        if (fieldId == null || fieldId !in gameState.fields.indices) {
            sendGameError(action, gameState, INVALID_FIELD_ID_MESSAGE)
            return null
        }

        val property = gameState.fields[fieldId] as? PropertyField
        if (property == null) {
            sendGameError(action, gameState, "Only properties can have buildings.")
            return null
        }

        return property
    }

}
