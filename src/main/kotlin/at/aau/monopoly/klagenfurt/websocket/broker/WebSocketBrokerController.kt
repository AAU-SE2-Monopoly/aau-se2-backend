package at.aau.monopoly.klagenfurt.websocket.broker

import at.aau.monopoly.klagenfurt.controller.GameController
import at.aau.monopoly.klagenfurt.messaging.dtos.GameAction
import at.aau.monopoly.klagenfurt.messaging.dtos.GameEvent
import at.aau.monopoly.klagenfurt.messaging.dtos.LobbyEvent
import at.aau.monopoly.klagenfurt.model.DiceRoll
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.card.Card
import at.aau.monopoly.klagenfurt.model.card.ChanceCard
import at.aau.monopoly.klagenfurt.model.card.CommunityChestCard
import at.aau.monopoly.klagenfurt.model.enums.CardAction
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.model.field.ChanceField
import at.aau.monopoly.klagenfurt.model.field.CommunityChestField
import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.FreeParkingField
import at.aau.monopoly.klagenfurt.model.field.OwnableField
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.TaxField
import at.aau.monopoly.klagenfurt.model.field.UtilityField
import at.aau.monopoly.klagenfurt.model.PaymentSource
import at.aau.monopoly.klagenfurt.model.PendingPayment
import at.aau.monopoly.klagenfurt.model.TradeOffer
import at.aau.monopoly.klagenfurt.service.PaymentService
import at.aau.monopoly.klagenfurt.service.RentCalculator
import kotlin.math.ceil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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

    // ═══ DEBUG BEGIN ═══
    @Value("\${app.debug:false}") private var debugMode: Boolean = false
    /** For tests only. */
    internal fun setDebugMode(enabled: Boolean) { debugMode = enabled }
    // ═══ DEBUG END ═══

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
        gameController.persist(action.gameId)
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

                "PAY_JAIL_FINE" -> handlePayJailFine(action, gameState)

                "REPORT_CHEATER" -> handleReportCheater(action, gameState)

                "USE_JAIL_CARD" -> handleUseJailCard(action, gameState)

                "END_TURN" -> handleEndTurn(action, gameState)

                "DRAW_CARD" -> handleDrawCard(action, gameState)

                "EXECUTE_ACTION" -> handleExecuteAction(action, gameState)

                "BUY_PROPERTY" -> handleBuyProperty(action, gameState)

                "BUY_HOUSE" -> handleBuyHouse(action, gameState)

                "BUY_HOTEL" -> handleBuyHotel(action, gameState)

                "SELL_HOUSE" -> handleSellHouse(action, gameState)

                "SELL_HOTEL" -> handleSellHotel(action, gameState)

                "PAY_RENT" -> handlePayRent(action, gameState)

                "PAY_TAX" -> handlePayRent(action, gameState)

                "MORTGAGE_PROPERTY" -> handleMortgageProperty(action, gameState)

                "UNMORTGAGE_PROPERTY" -> handleUnmortgageProperty(action, gameState)

                "DECLARE_BANKRUPTCY" -> handleDeclareBankruptcy(action, gameState)

                "PROPOSE_TRADE" -> handleProposeTrade(action, gameState)

                "ACCEPT_TRADE" -> handleAcceptTrade(action, gameState)

                "REJECT_TRADE" -> handleRejectTrade(action, gameState)

                // ═══ DEBUG BEGIN ═══
                "DEBUG_FORWARD_GAME" -> {
                    if (!debugMode) {
                        sendGameError(action, gameState, "Debug actions are disabled.")
                    } else {
                        handleDebugForwardGame(action, gameState)
                    }
                }
                "DEBUG_SETUP_BANKRUPTCY" -> {
                    if (!debugMode) {
                        sendGameError(action, gameState, "Debug actions are disabled.")
                    } else {
                        handleDebugSetupBankruptcy(action, gameState)
                    }
                }
                // ═══ DEBUG END ═══

                else -> {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(gameId = action.gameId, event = "ERROR", message = "Unknown action: ${action.action}")
                    )
                }
            }
            // A real player just acted — refresh their turn-timeout clock so an
            // engaged player is never forcibly skipped.
            gameState.resetTurnTimer()
            // Persist the (possibly) mutated game state after every action.
            gameController.persist(action.gameId)
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
        private const val UNKNOWN_PLAYER_MESSAGE = "A player"
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

    private fun returnJailCardToDeck(gameState: GameState) {
        val goojfCardId = 999
        if (gameState.chanceCards.size <= gameState.communityChestCards.size) {
            gameState.chanceCards.add(ChanceCard(id = goojfCardId, description = "Get Out of Jail Free", action = CardAction.GET_OUT_OF_JAIL))
        } else {
            gameState.communityChestCards.add(CommunityChestCard(id = goojfCardId, description = "Get Out of Jail Free", action = CardAction.GET_OUT_OF_JAIL))
        }
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
            CardAction.COLLECT_MONEY -> player.money += card.amount
            CardAction.PAY_MONEY -> {
                player.money -= card.amount
                gameState.freeParkingMoney += card.amount
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
                    if (target < oldPosition) player.money += 200
                }
            }
            CardAction.MOVE_FORWARD -> {
                val newPosition = player.position + card.moveSpaces
                player.position = ((newPosition % 40) + 40) % 40
                if (newPosition >= 40) player.money += 200
            }
            CardAction.GO_TO_JAIL -> {
                player.position = 10
                player.inJail = true
                player.jailTurns = 0
            }
            CardAction.GET_OUT_OF_JAIL -> player.getOutOfJailCards += 1
            CardAction.PAY_PER_BUILDING -> {
                var houses = 0
                var hotels = 0
                gameState.fields.filterIsInstance<PropertyField>().forEach { f ->
                    if (f.ownerId == playerId) {
                        houses += f.houses
                        if (f.hasHotel) hotels++
                    }
                }
                val total = card.perBuildingAmount * houses + card.perHotelAmount * hotels
                if (total > 0) {
                    player.money -= total
                    gameState.freeParkingMoney += total
                }
            }
            CardAction.PAY_EACH_PLAYER -> gameState.players.forEach { other ->
                if (other.id != playerId) {
                    player.money -= card.amount
                    other.money += card.amount
                }
            }
            CardAction.COLLECT_FROM_EACH -> gameState.players.forEach { other ->
                if (other.id != playerId) {
                    other.money -= card.amount
                    player.money += card.amount
                }
            }
        }

        if (card.action != CardAction.GET_OUT_OF_JAIL) {
            when (card) {
                is ChanceCard -> gameState.chanceCards.add(card)
                is CommunityChestCard -> gameState.communityChestCards.add(card)
            }
        }
    }

    private fun handleBuyHouse(
        action: GameAction,
        gameState: GameState
    ) {
        if (gameState.phase == GamePhase.PAYING_RENT) {
            sendGameError(action, gameState, "Cannot buy houses while rent is due.")
            return
        }

        val property = getValidatedProperty(action, gameState) ?: return
        val player = gameState.players.find { it.id == action.playerId } ?: return

        if (property.ownerId != player.id) {
            sendGameError(action, gameState, "You can only build on your own properties.")
            return
        }

        if (!ownsCompleteColorSet(gameState, player.id, property)) {
            sendGameError(action, gameState, "You need the complete color set to build houses.")
            return
        }

        if (!isColorSetMortgageFree(gameState, property)) {
            sendGameError(action, gameState, "Cannot build while any property in the color set is mortgaged.")
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
        if (gameState.phase == GamePhase.PAYING_RENT) {
            sendGameError(action, gameState, "Cannot buy hotels while rent is due.")
            return
        }

        val property = getValidatedProperty(action, gameState) ?: return
        val player = gameState.players.find { it.id == action.playerId } ?: return

        if (property.ownerId != player.id) {
            sendGameError(action, gameState, "You can only build on your own properties.")
            return
        }

        if (!ownsCompleteColorSet(gameState, player.id, property)) {
            sendGameError(action, gameState, "You need the complete color set to build a hotel.")
            return
        }

        if (!isColorSetMortgageFree(gameState, property)) {
            sendGameError(action, gameState, "Cannot build while any property in the color set is mortgaged.")
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

        if (!canBuyHotelEvenly(gameState, property)) {
            sendGameError(action, gameState, "All properties in the color set must have 4 houses or a hotel before buying a hotel.")
            return
        }

        if (player.money < property.houseCost) {
            sendGameError(action, gameState, "Not enough money to buy a hotel.")
            return
        }

        player.money -= property.houseCost
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
        val player = gameState.players.find { it.id == action.playerId } ?: return

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

        recomputeCanPayAfterAssets(gameState)

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
        val player = gameState.players.find { it.id == action.playerId } ?: return

        if (property.ownerId != player.id) {
            sendGameError(action, gameState, "You can only sell hotels from your own properties.")
            return
        }

        if (!property.hasHotel) {
            sendGameError(action, gameState, "This property has no hotel to sell.")
            return
        }

        if (!canSellHotelEvenly(gameState, property)) {
            sendGameError(action, gameState, "Cannot sell hotel — all properties in the color set must have at least 3 houses or a hotel.")
            return
        }

        property.hasHotel = false
        property.houses = 4
        player.money += property.hotelCost / 2

        recomputeCanPayAfterAssets(gameState)

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

    private fun canBuyHotelEvenly(
        gameState: GameState,
        property: PropertyField
    ): Boolean {
        val colorSet = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color && it.id != property.id }

        return colorSet.all { it.houses == 4 || it.hasHotel }
    }

    private fun canSellHotelEvenly(
        gameState: GameState,
        property: PropertyField
    ): Boolean {
        val colorSet = gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color && it.id != property.id }

        return colorSet.all { it.houses >= 3 || it.hasHotel }
    }

    private fun isColorSetMortgageFree(
        gameState: GameState,
        property: PropertyField
    ): Boolean {
        return gameState.fields
            .filterIsInstance<PropertyField>()
            .filter { it.color == property.color }
            .none { it.isMortgaged }
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

    private fun handleProposeTrade(
        action: GameAction,
        gameState: GameState
    ) {
        if (gameState.phase == GamePhase.WAITING || gameState.phase == GamePhase.FINISHED) {
            sendGameError(action, gameState, "Trades are only available during an active game.")
            return
        }
        if (gameState.phase == GamePhase.PAYING_RENT) {
            sendGameError(action, gameState, "Cannot propose a trade while a payment is due.")
            return
        }

        val currentPlayer = gameState.currentPlayer
        val existingOffer = gameState.pendingTradeOffer
        if (existingOffer == null && currentPlayer?.id != action.playerId) {
            sendGameError(action, gameState, "Trades can only be started on your turn.")
            return
        }
        if (existingOffer != null && currentPlayer?.id != existingOffer.fromPlayerId) {
            sendGameError(action, gameState, "This trade is no longer active for the current turn.")
            return
        }

        val fromPlayerId = existingOffer?.fromPlayerId ?: action.playerId
        val fromPlayer = gameState.players.find { it.id == fromPlayerId }
        val toPlayerId = action.payload["toPlayerId"]?.takeIf { it.isNotBlank() }
        val toPlayer = gameState.players.find { it.id == toPlayerId }
        if (fromPlayer == null || toPlayer == null) {
            sendGameError(action, gameState, "Both trade players must be in the game.")
            return
        }
        if (existingOffer != null &&
            action.playerId != existingOffer.fromPlayerId &&
            action.playerId != existingOffer.toPlayerId
        ) {
            sendGameError(action, gameState, "Only involved players can update this trade.")
            return
        }
        if (existingOffer != null &&
            (existingOffer.fromPlayerId != fromPlayer.id || existingOffer.toPlayerId != toPlayer.id)
        ) {
            sendGameError(action, gameState, "Only one trade can be active at a time.")
            return
        }
        if (fromPlayer.id == toPlayer.id) {
            sendGameError(action, gameState, "You cannot trade with yourself.")
            return
        }
        if (fromPlayer.isBankrupt() || toPlayer.isBankrupt() || fromPlayer.eliminated || toPlayer.eliminated) {
            sendGameError(action, gameState, "Bankrupt or eliminated players cannot trade.")
            return
        }

        val submittedOfferMoney = action.payload["offerMoney"].toNonNegativeInt("offerMoney", action, gameState) ?: return
        val submittedRequestMoney = action.payload["requestMoney"].toNonNegativeInt("requestMoney", action, gameState) ?: return
        val submittedOfferPropertyIds = parseFieldIds(action.payload["offerPropertyIds"])
        val submittedRequestPropertyIds = parseFieldIds(action.payload["requestPropertyIds"])
        val submittedOfferJailCards = action.payload["offerJailCards"].toNonNegativeInt("offerJailCards", action, gameState) ?: return
        val submittedRequestJailCards = action.payload["requestJailCards"].toNonNegativeInt("requestJailCards", action, gameState) ?: return

        val offer = TradeOffer(
            id = existingOffer?.id ?: java.util.UUID.randomUUID().toString(),
            fromPlayerId = fromPlayer.id,
            toPlayerId = toPlayer.id,
            offerMoney = if (existingOffer == null || action.playerId == fromPlayer.id) {
                submittedOfferMoney
            } else {
                existingOffer.offerMoney
            },
            requestMoney = if (existingOffer == null || action.playerId == toPlayer.id) {
                submittedRequestMoney
            } else {
                existingOffer.requestMoney
            },
            offerPropertyIds = if (existingOffer == null || action.playerId == fromPlayer.id) {
                submittedOfferPropertyIds
            } else {
                existingOffer.offerPropertyIds
            },
            requestPropertyIds = if (existingOffer == null || action.playerId == toPlayer.id) {
                submittedRequestPropertyIds
            } else {
                existingOffer.requestPropertyIds
            },
            offerJailCards = if (existingOffer == null || action.playerId == fromPlayer.id) {
                submittedOfferJailCards
            } else {
                existingOffer.offerJailCards
            },
            requestJailCards = if (existingOffer == null || action.playerId == toPlayer.id) {
                submittedRequestJailCards
            } else {
                existingOffer.requestJailCards
            }
        )

        if (!validateTradeOffer(offer, gameState, action)) return

        gameState.pendingTradeOffer = offer
        sendGameEvent(
            action,
            gameState,
            if (existingOffer == null) GameEvent.TRADE_PROPOSED else GameEvent.TRADE_UPDATED,
            if (existingOffer == null) {
                "${fromPlayer.name} proposed a trade with ${toPlayer.name}."
            } else {
                "${gameState.players.find { it.id == action.playerId }?.name ?: UNKNOWN_PLAYER_MESSAGE} updated the trade."
            }
        )
    }

    private fun handleAcceptTrade(
        action: GameAction,
        gameState: GameState
    ) {
        val offer = gameState.pendingTradeOffer ?: run {
            sendGameError(action, gameState, "There is no pending trade to accept.")
            return
        }
        if (action.playerId != offer.toPlayerId && action.playerId != offer.fromPlayerId) {
            sendGameError(action, gameState, "Only involved players can accept this trade.")
            return
        }
        val requestedOfferId = action.payload["tradeId"]
        if (!requestedOfferId.isNullOrBlank() && requestedOfferId != offer.id) {
            sendGameError(action, gameState, "This trade offer is no longer active.")
            return
        }
        if (!validateTradeOffer(offer, gameState, action)) return
        if (!offer.hasTradeContents()) {
            sendGameError(action, gameState, "Add money, properties, or jail cards before accepting the trade.")
            return
        }

        if (action.playerId in offer.acceptedByPlayerIds) {
            val updatedOffer = offer.copy(
                acceptedByPlayerIds = offer.acceptedByPlayerIds.filterNot { it == action.playerId }
            )
            gameState.pendingTradeOffer = updatedOffer
            val reconsideringPlayer = gameState.players.find { it.id == action.playerId }
            sendGameEvent(
                action,
                gameState,
                GameEvent.TRADE_UPDATED,
                "${reconsideringPlayer?.name ?: UNKNOWN_PLAYER_MESSAGE} is reconsidering the trade."
            )
            return
        }

        val acceptedIds = (offer.acceptedByPlayerIds + action.playerId).distinct()
        if (!acceptedIds.contains(offer.fromPlayerId) || !acceptedIds.contains(offer.toPlayerId)) {
            gameState.pendingTradeOffer = offer.copy(acceptedByPlayerIds = acceptedIds)
            val acceptingPlayer = gameState.players.find { it.id == action.playerId }
            sendGameEvent(
                action,
                gameState,
                GameEvent.TRADE_ACCEPTED,
                "${acceptingPlayer?.name ?: UNKNOWN_PLAYER_MESSAGE} accepted the current trade offer."
            )
            return
        }

        val fromPlayer = gameState.players.first { it.id == offer.fromPlayerId }
        val toPlayer = gameState.players.first { it.id == offer.toPlayerId }

        fromPlayer.money -= offer.offerMoney
        toPlayer.money += offer.offerMoney
        toPlayer.money -= offer.requestMoney
        fromPlayer.money += offer.requestMoney

        fromPlayer.getOutOfJailCards -= offer.offerJailCards
        toPlayer.getOutOfJailCards += offer.offerJailCards
        toPlayer.getOutOfJailCards -= offer.requestJailCards
        fromPlayer.getOutOfJailCards += offer.requestJailCards

        transferProperties(gameState, offer.offerPropertyIds, fromPlayer, toPlayer)
        transferProperties(gameState, offer.requestPropertyIds, toPlayer, fromPlayer)

        gameState.pendingTradeOffer = null
        recomputeCanPayAfterAssets(gameState)

        sendGameEvent(
            action,
            gameState,
            GameEvent.TRADE_COMPLETED,
            "${fromPlayer.name} and ${toPlayer.name} completed a trade."
        )
    }

    private fun handleRejectTrade(
        action: GameAction,
        gameState: GameState
    ) {
        val offer = gameState.pendingTradeOffer ?: run {
            sendGameError(action, gameState, "There is no pending trade to reject.")
            return
        }
        if (action.playerId != offer.toPlayerId && action.playerId != offer.fromPlayerId) {
            sendGameError(action, gameState, "Only involved players can close this trade.")
            return
        }
        val player = gameState.players.find { it.id == action.playerId }
        gameState.pendingTradeOffer = null
        sendGameEvent(
            action,
            gameState,
            GameEvent.TRADE_REJECTED,
            "${player?.name ?: UNKNOWN_PLAYER_MESSAGE} declined the trade."
        )
    }

    private fun String?.toNonNegativeInt(
        fieldName: String,
        action: GameAction,
        gameState: GameState
    ): Int? {
        val value = this?.takeIf { it.isNotBlank() } ?: return 0
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed < 0) {
            sendGameError(action, gameState, "$fieldName must be a non-negative number.")
            return null
        }
        return parsed
    }

    private fun parseFieldIds(rawValue: String?): List<Int> {
        return rawValue
            ?.split(",", ";", "|")
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank)?.toIntOrNull() }
            ?.distinct()
            ?: emptyList()
    }

    private fun validateTradeOffer(
        offer: TradeOffer,
        gameState: GameState,
        action: GameAction
    ): Boolean {
        val fromPlayer = gameState.players.find { it.id == offer.fromPlayerId }
        val toPlayer = gameState.players.find { it.id == offer.toPlayerId }
        if (fromPlayer == null || toPlayer == null) {
            sendGameError(action, gameState, "Both trade players must still be in the game.")
            return false
        }
        if (fromPlayer.money < offer.offerMoney || toPlayer.money < offer.requestMoney) {
            sendGameError(action, gameState, "One player no longer has enough money for this trade.")
            return false
        }
        if (fromPlayer.getOutOfJailCards < offer.offerJailCards || toPlayer.getOutOfJailCards < offer.requestJailCards) {
            sendGameError(action, gameState, "One player no longer has enough Get Out of Jail Free cards.")
            return false
        }
        if (!validateTradeProperties(offer.offerPropertyIds, fromPlayer.id, gameState, action)) return false
        if (!validateTradeProperties(offer.requestPropertyIds, toPlayer.id, gameState, action)) return false

        return true
    }

    private fun TradeOffer.hasTradeContents(): Boolean {
        return offerMoney > 0 ||
            requestMoney > 0 ||
            offerPropertyIds.isNotEmpty() ||
            requestPropertyIds.isNotEmpty() ||
            offerJailCards > 0 ||
            requestJailCards > 0
    }

    private fun validateTradeProperties(
        fieldIds: List<Int>,
        ownerId: String,
        gameState: GameState,
        action: GameAction
    ): Boolean {
        fieldIds.forEach { fieldId ->
            val field = gameState.fields.getOrNull(fieldId)
            if (field !is OwnableField) {
                sendGameError(action, gameState, "Only ownable fields can be traded.")
                return false
            }
            if (field.ownerId != ownerId) {
                sendGameError(action, gameState, "${(field as Field).name} is not owned by the expected player.")
                return false
            }
            if (field is PropertyField && (field.houses > 0 || field.hasHotel)) {
                sendGameError(action, gameState, "Sell all buildings on ${field.name} before trading it.")
                return false
            }
        }
        return true
    }

    private fun transferProperties(
        gameState: GameState,
        fieldIds: List<Int>,
        fromPlayer: Player,
        toPlayer: Player
    ) {
        fieldIds.forEach { fieldId ->
            val field = gameState.fields.getOrNull(fieldId) as? OwnableField ?: return@forEach
            field.ownerId = toPlayer.id
            fromPlayer.ownedPropertyIds.remove(fieldId)
            toPlayer.ownedPropertyIds.remove(fieldId)
            toPlayer.ownedPropertyIds.add(fieldId)
        }
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

        val player = gameState.currentPlayer ?: run {
            sendGameError(action, gameState, "No current player.")
            return
        }

        val currentField = gameState.fields.getOrNull(player.position)
        if (!gameState.hasDrawnCardThisTurn && (currentField is ChanceField || currentField is CommunityChestField)) {
            sendGameError(action, gameState, "You must draw a card before ending your turn.")
            return
        }
        val isDoublet = gameState.lastDiceRoll?.isDouble == true

        if (isDoublet && !player.isBankrupt() && !player.inJail && player.consecutiveDoublets > 0) {
            gameState.endCurrentTurn()
            gameState.phase = GamePhase.ROLLING
            sendGameEvent(
                action,
                gameState,
                "TURN_ENDED",
                "${player.name} rolled a doublet and gets another turn!"
            )
        } else {
            player.consecutiveDoublets = 0

            gameState.endCurrentTurn()
            gameState.advanceTurn()
            checkAndHandleGameOver(gameState)

            sendGameEvent(
                action,
                gameState,
                "TURN_ENDED",
                "Next turn: ${gameState.currentPlayer?.name}."
            )
        }
    }

    /**
     * Forces the current player's turn to a clean end, executing any intermediate
     * actions that would normally be required of the player. Used by the turn-timeout
     * sweep so a game does not get stuck if a player's device dies mid-turn.
     *
     * The game lock is acquired here so this is safe to call from a scheduled thread.
     *
     * @return true if a turn was actually forced to end, false if nothing was due.
     */
    fun forceEndTurn(gameId: String): Boolean {
        val lock = gameLocks.computeIfAbsent(gameId) { Any() }
        synchronized(lock) {
            val gameState = gameController.getGameState(gameId) ?: return false
            val player = gameState.currentPlayer ?: return false
            if (gameState.phase == GamePhase.WAITING || gameState.phase == GamePhase.FINISHED) {
                return false
            }

            val timeoutAction = GameAction(
                gameId = gameId,
                playerId = player.id,
                action = "END_TURN"
            )

            // 1. The player may still need to roll the dice to even start resolving.
            if (gameState.phase == GamePhase.ROLLING) {
                handleRollDice(timeoutAction.copy(action = "ROLL_DICE"), gameState)
            }

            // 2. If the player landed on a Chance / Community Chest field they must
            //    draw and execute the card before the turn can end.
            autoResolveCard(gameState, player.id)

            // 3. Settle any pending payment (rent / card payment).
            autoResolvePendingPayment(gameId, gameState, player)

            // 4. Finally end the turn (advances to the next player, or grants another
            //    roll on a doublet).
            if (gameState.phase == GamePhase.BUYING || gameState.phase == GamePhase.TURN_END) {
                handleEndTurn(timeoutAction, gameState)
            }

            gameState.resetTurnTimer()
            sendGameEvent(
                timeoutAction,
                gameState,
                "TURN_TIMEOUT",
                "${player.name}'s turn was auto-completed after inactivity."
            )
            gameController.persist(gameId)
            return@synchronized true
        }
        return true
    }

    /** Draws (if needed) and executes a card for a timed-out player on a card field. */
    private fun autoResolveCard(gameState: GameState, playerId: String) {
        val player = gameState.players.find { it.id == playerId } ?: return
        val currentField = gameState.fields.getOrNull(player.position)
        val onCardField = currentField is ChanceField || currentField is CommunityChestField

        if (onCardField && !gameState.hasDrawnCardThisTurn) {
            val cardType = if (currentField is ChanceField) "CHANCE" else "COMMUNITY_CHEST"
            val drawAction = GameAction(
                gameId = gameState.gameId,
                playerId = playerId,
                action = "DRAW_CARD",
                payload = mutableMapOf("cardType" to cardType)
            )
            handleDrawCard(drawAction, gameState)
        }

        if (gameState.currentActionCard != null) {
            val executeAction = GameAction(
                gameId = gameState.gameId,
                playerId = playerId,
                action = "EXECUTE_ACTION"
            )
            handleExecuteAction(executeAction, gameState)
        }
    }

    /**
     * Auto-resolves a pending payment for a timed-out player: pays if affordable,
     * otherwise liquidates assets / declares bankruptcy so the game can continue.
     */
    private fun autoResolvePendingPayment(
        gameId: String,
        gameState: GameState,
        player: Player
    ) {
        val pending = gameState.pendingPayment ?: return
        if (gameState.phase != GamePhase.PAYING_RENT || pending.amount <= 0) return

        if (player.money < pending.amount) {
            autoLiquidateForDebt(gameState, player, pending.amount)
        }

        if (player.money >= pending.amount) {
            val payAction = GameAction(
                gameId = gameId,
                playerId = player.id,
                action = "PAY_RENT",
                payload = pending.sourceFieldId?.let {
                    mutableMapOf("fieldId" to it.toString())
                } ?: mutableMapOf()
            )
            handlePayRent(payAction, gameState)
        } else {
            val bankruptcyAction = GameAction(
                gameId = gameId,
                playerId = player.id,
                action = "DECLARE_BANKRUPTCY"
            )
            handleDeclareBankruptcy(bankruptcyAction, gameState)
        }
    }

    /**
     * Sells buildings and mortgages properties for a timed-out player until they can
     * cover [amountDue] or run out of assets. Mirrors the manual sell/mortgage flow.
     */
    private fun autoLiquidateForDebt(
        gameState: GameState,
        player: Player,
        amountDue: Int
    ) {
        val owned = gameState.fields.filterIsInstance<PropertyField>()
            .filter { it.ownerId == player.id }

        owned.filter { it.hasHotel }.forEach {
            if (player.money < amountDue) {
                it.hasHotel = false
                it.houses = 4
                player.money += it.hotelCost / 2
            }
        }
        owned.sortedByDescending { it.houses }.forEach { prop ->
            while (player.money < amountDue && prop.houses > 0) {
                prop.houses -= 1
                player.money += prop.houseCost / 2
            }
        }
        gameState.fields.filterIsInstance<OwnableField>()
            .filter { it.ownerId == player.id && !it.isMortgaged }
            .forEach { field ->
                if (player.money < amountDue) {
                    val hasBuildings = field is PropertyField && (field.houses > 0 || field.hasHotel)
                    if (!hasBuildings) {
                        PaymentService.mortgageProperty(player, field)
                    }
                }
            }
        recomputeCanPayAfterAssets(gameState)
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
            val currentField = gameState.fields.getOrNull(player.position)
            if (currentField is ChanceField || currentField is CommunityChestField) {
                gameState.hasDrawnCardThisTurn = false
            }
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

        if (gameState.hasDrawnCardThisTurn) {
            sendGameError(action, gameState, "You have already drawn a card this turn.")
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
        gameState.hasDrawnCardThisTurn = true

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
        player.hasCheated = action.payload["cheat"] == "true"
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

        if (!player.inJail) {
            resolveLandingEffects(action, gameState, player)
        }
    }

    private fun createDiceRoll(action: GameAction): DiceRoll {
        val isCheating = action.payload["cheat"] == "true"

        return if (isCheating) {
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

            if (player.position == 30) {
                player.inJail = true
                player.jailTurns = 0
                player.consecutiveDoublets = 0
                eventMessage += " Landed on Go To Jail!"
                gameState.phase = GamePhase.TURN_END
            } else {
                player.consecutiveDoublets = 0
                gameState.phase = GamePhase.BUYING
            }
        } else {
            player.jailTurns++

            if (player.jailTurns >= 3) {
                if (player.money >= 50) {
                    player.money -= 50
                } else {
                    player.money = 0
                }
                player.consecutiveDoublets = 0
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

        if (gameState.phase == GamePhase.PAYING_RENT) {
            sendGameError(action, gameState, "Cannot buy property while rent is due.")
            return
        }

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

    private fun handlePayJailFine(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return
        val player = gameState.currentPlayer!!
        if (!player.inJail) {
            sendGameError(action, gameState, "You are not in jail.")
            return
        }
        if (player.money < 50) {
            sendGameError(action, gameState, "Not enough money to pay the fine.")
            return
        }
        player.money -= 50
        player.inJail = false
        player.jailTurns = 0
        player.consecutiveDoublets = 0
        sendGameEvent(
            action,
            gameState,
            "JAIL_FINE_PAID",
            "${player.name} paid 50M to get out of jail."
        )
    }

    private fun handleUseJailCard(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return
        val player = gameState.currentPlayer!!
        if (!player.inJail) {
            sendGameError(action, gameState, "You are not in jail.")
            return
        }
        if (player.getOutOfJailCards <= 0) {
            sendGameError(action, gameState, "You do not have a Get Out of Jail Free card.")
            return
        }
        player.getOutOfJailCards -= 1
        player.inJail = false
        player.jailTurns = 0
        player.consecutiveDoublets = 0
        returnJailCardToDeck(gameState)
        sendGameEvent(
            action,
            gameState,
            "JAIL_CARD_USED",
            "${player.name} used a Get Out of Jail Free card."
        )
    }

    private fun resolveLandingEffects(
        action: GameAction,
        gameState: GameState,
        player: Player
    ) {
        val landedField = gameState.fields.getOrNull(player.position) ?: return

        handleTaxFieldLanding(action, gameState, player, landedField)
        if (gameState.phase == GamePhase.PAYING_RENT) return

        handleOwnableFieldLanding(action, gameState, player, landedField)
        handleFreeParkingLanding(action, gameState, player, landedField)
    }

    private fun handleTaxFieldLanding(
        action: GameAction,
        gameState: GameState,
        player: Player,
        landedField: Field
    ) {
        if (landedField !is TaxField) return

        val taxAmount = landedField.amount

        if (player.money >= taxAmount) {
            player.money -= taxAmount
            gameState.freeParkingMoney += taxAmount

            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = GameEvent.TAX_PAID,
                    gameState = gameState,
                    message = "${player.name} paid ${taxAmount}€ tax."
                )
            )
        } else {
            gameState.phase = GamePhase.PAYING_RENT
            gameState.pendingPayment = PendingPayment(
                amount = taxAmount,
                source = PaymentSource.TAX,
                sourceFieldId = landedField.id,
                creditorPlayerId = null,
                debtorCanPayAfterAssets = PaymentService.canPayAfterAssets(
                    player,
                    gameState.fields,
                    taxAmount
                )
            )

            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = GameEvent.TAX_DUE,
                    gameState = gameState,
                    message = "${player.name} owes ${taxAmount}€ tax."
                )
            )
        }
    }

    private fun handleOwnableFieldLanding(
        action: GameAction,
        gameState: GameState,
        player: Player,
        landedField: Field
    ) {
        if (landedField !is OwnableField) return
        val ownerId = landedField.ownerId
        if (ownerId == null || ownerId == player.id || landedField.isMortgaged) return
        val owner = gameState.players.find { it.id == ownerId }
        if (owner == null || owner.eliminated) return
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
                creditorPlayerId = ownerId,
                debtorCanPayAfterAssets = PaymentService.canPayAfterAssets(player, gameState.fields, rent)
            )
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = GameEvent.RENT_DUE,
                    gameState = gameState,
                    message = "${player.name} owes ${rent}M rent to ${owner.name} (${landedField.name})."
                )
            )
        }
    }

    private fun handleFreeParkingLanding(
        action: GameAction,
        gameState: GameState,
        player: Player,
        landedField: Field
    ) {
        if (landedField is FreeParkingField && gameState.freeParkingMoney > 0) {
            val collectedAmount = gameState.freeParkingMoney
            player.money += collectedAmount
            gameState.freeParkingMoney = 0
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = GameEvent.FREE_PARKING_COLLECTED,
                    gameState = gameState,
                    message = "${player.name} collected ${collectedAmount}M from Free Parking!"
                )
            )
        }
    }

    // ═══ DEBUG BEGIN ═══

    private fun handleDebugForwardGame(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        if (gameState.phase == GamePhase.WAITING) {
            sendGameError(action, gameState, "Start the game before using debug forward.")
            return
        }

        val players = gameState.players
        if (players.isEmpty()) {
            sendGameError(action, gameState, "No players available for debug forward.")
            return
        }

        players.forEach { player ->
            player.money = 10_000
            player.inJail = false
            player.jailTurns = 0
            player.consecutiveDoublets = 0
            player.ownedPropertyIds.clear()
        }

        val ownableFields = gameState.fields.filterIsInstance<OwnableField>()

        ownableFields.forEach { field ->
            field.ownerId = null
            field.isMortgaged = false
            if (field is PropertyField) {
                field.hasHotel = false
                field.houses = 0
            }
        }

        val propertyFields = ownableFields.filterIsInstance<PropertyField>()
        val colorGroups = propertyFields.groupBy { it.color }
        colorGroups.entries.forEachIndexed { index, (_, group) ->
            val owner = players[index % players.size]
            group.forEach { prop ->
                prop.ownerId = owner.id
                prop.houses = 3
                owner.ownedPropertyIds.add(prop.id)
            }
        }

        val railroads = gameState.fields.filterIsInstance<RailroadField>()
        val utilities = gameState.fields.filterIsInstance<UtilityField>()
        (railroads + utilities).forEachIndexed { index, field ->
            val owner = players[index % players.size]
            field.ownerId = owner.id
            owner.ownedPropertyIds.add(field.id)
        }

        gameState.pendingPayment = null
        gameState.currentActionCard = null
        gameState.phase = GamePhase.BUYING

        sendGameEvent(
            action,
            gameState,
            "STATE_UPDATED",
            "DEBUG: game forwarded to endgame-like setup."
        )
    }

    private fun handleDebugSetupBankruptcy(
        action: GameAction,
        gameState: GameState
    ) {
        if (!validateCurrentPlayerTurn(action, gameState)) return

        if (gameState.phase == GamePhase.WAITING) {
            sendGameError(action, gameState, "Start the game before using debug bankruptcy setup.")
            return
        }

        val debtor = gameState.currentPlayer ?: run {
            sendGameError(action, gameState, "No current player available for debug bankruptcy setup.")
            return
        }

        val creditor = gameState.players.firstOrNull { it.id != debtor.id }

        debtor.money = 25
        gameState.pendingPayment = PendingPayment(
            amount = 1200,
            source = PaymentSource.RENT,
            sourceFieldId = debtor.position,
            creditorPlayerId = creditor?.id
        )
        gameState.phase = GamePhase.PAYING_RENT
        gameState.currentActionCard = null

        sendGameEvent(
            action,
            gameState,
            "RENT_DUE",
            "DEBUG: bankruptcy setup active."
        )
    }

    // ═══ DEBUG END ═══

    private fun recomputeCanPayAfterAssets(gameState: GameState) {
        val pending = gameState.pendingPayment ?: return
        val player = gameState.currentPlayer ?: return
        gameState.pendingPayment = pending.copy(
            debtorCanPayAfterAssets = PaymentService.canPayAfterAssets(player, gameState.fields, pending.amount)
        )
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

        val pending = gameState.pendingPayment
        if (pending == null) {
            sendGameError(action, gameState, "No pending payment found.")
            return
        }
        if (pending.amount <= 0) {
            sendGameError(action, gameState, "No pending payment to pay.")
            return
        }

        val player = gameState.currentPlayer!!
        if (player.isBankrupt()) {
            sendGameError(action, gameState, "You are bankrupt and cannot pay rent.")
            return
        }
        val fieldId = action.payload["fieldId"]?.toIntOrNull()

        // If the pending payment has a sourceFieldId, validate that it matches the payload
        pending.sourceFieldId?.let { expectedId ->
            if (fieldId == null || fieldId != expectedId) {
                sendGameError(action, gameState, "Invalid fieldId for payment. Expected: $expectedId, got: $fieldId")
                return
            }
        }

        // Process payment based on source
         val paymentResult = when (pending.source) {
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
                 GameEvent.RENT_PAID to "${player.name} paid ${pending.amount}M to ${creditor.name}."
             }
             PaymentSource.TAX -> {
                 // Tax: money goes to Free Parking pot
                 if (player.money < pending.amount) {
                     sendPaymentFailed(action, gameState, pending.amount)
                     return
                 }
                 player.money -= pending.amount
                 gameState.freeParkingMoney += pending.amount
                 GameEvent.TAX_PAID to "${player.name} paid ${pending.amount}M tax."
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
             GameEvent(
                 gameId = action.gameId,
                 event = paymentResult.first,
                 gameState = gameState,
                 message = paymentResult.second
             )
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
        if (!validatePlayerCanManageProperty(action, gameState)) return

        val player = gameState.players.find { it.id == action.playerId } ?: return
        val fieldId = action.payload["fieldId"]?.toIntOrNull() ?: return
        val field = gameState.fields.find { it.id == fieldId }
        val hasBuildings = field is PropertyField && (field.houses > 0 || field.hasHotel)
        val colorGroup = (field as? PropertyField)?.color
        val siblingHasBuildings = colorGroup != null && gameState.fields
            .filterIsInstance<PropertyField>()
            .any { it.color == colorGroup && it.id != field.id && it.ownerId == player.id && (it.houses > 0 || it.hasHotel) }

        if (field is OwnableField && field.ownerId == player.id && !field.isMortgaged && !hasBuildings && !siblingHasBuildings) {
            PaymentService.mortgageProperty(player, field)
            recomputeCanPayAfterAssets(gameState)
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(
                    gameId = action.gameId,
                    event = GameEvent.PROPERTY_MORTGAGED,
                    gameState = gameState,
                    message = "${player.name} mortgaged ${field.name}."
                )
            )
        } else if (hasBuildings) {
            sendGameError(action, gameState, "Sell all houses/hotels before mortgaging.")
        } else if (siblingHasBuildings) {
            sendGameError(action, gameState, "Sell all houses/hotels in the color set before mortgaging.")
        }
    }

    private fun handleUnmortgageProperty(
        action: GameAction,
        gameState: GameState
    ) {
        if (gameState.phase == GamePhase.PAYING_RENT) {
            sendGameError(action, gameState, "Cannot unmortgage while rent is due.")
            return
        }

        if (!validatePlayerCanManageProperty(action, gameState)) return

        val player = gameState.players.find { it.id == action.playerId } ?: return
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
                    GameEvent(
                        gameId = action.gameId,
                        event = GameEvent.PROPERTY_UNMORTGAGED,
                        gameState = gameState,
                        message = "${player.name} unmortgaged ${field.name}."
                    )
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

        if (player.eliminated) {
            sendGameError(action, gameState, "You have already declared bankruptcy.")
            return
        }

        val hasBuildings = gameState.fields
            .filterIsInstance<PropertyField>()
            .any { it.ownerId == player.id && (it.houses > 0 || it.hasHotel) }
        if (hasBuildings) {
            sendGameError(action, gameState, "Sell all houses and hotels before declaring bankruptcy.")
            return
        }

        val hasUnmortgaged = gameState.fields
            .filterIsInstance<OwnableField>()
            .any { it.ownerId == player.id && !it.isMortgaged }
        if (hasUnmortgaged) {
            sendGameError(action, gameState, "Mortgage all properties before declaring bankruptcy.")
            return
        }

        if (PaymentService.canPayAfterAssets(player, gameState.fields, pending.amount)) {
            sendGameError(action, gameState,
                "You can still pay by mortgaging properties or selling buildings. Declare bankruptcy only if your total assets are insufficient.")
            return
        }

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
                creditor.ownedPropertyIds.removeAll(transferredIds.toSet())
                creditor.ownedPropertyIds.addAll(transferredIds)
            } else {
                ownedFields.forEach { field ->
                    field.ownerId = null
                    field.isMortgaged = false
                    transferredIds.add((field as Field).id)
                }
            }
            player.getOutOfJailCards = 0
            player.ownedPropertyIds.clear()
        } else {
            player.money = 0
            gameState.fields.filterIsInstance<OwnableField>()
                .filter { it.ownerId == player.id }
                .forEach {
                    it.ownerId = null
                    it.isMortgaged = false
                }
            player.ownedPropertyIds.clear()
            player.getOutOfJailCards = 0
        }

        player.eliminated = true
        gameState.endCurrentTurn()
        gameState.advanceTurn()
        gameState.pendingPayment = null
        gameState.bankruptcyTotalAssets = totalAssetValue
        gameState.bankruptcyTotalDebt = totalDebt
        gameState.bankruptcyPropertiesCount = propertiesCount
        gameState.bankruptcyOwnedFieldIds = ownedFieldIds
        gameState.bankruptcyPlayerId = player.id


        messagingTemplate.convertAndSend(
            "/topic/game/${action.gameId}",
            GameEvent(
                gameId = action.gameId,
                event = GameEvent.BANKRUPTCY_DECLARED,
                gameState = gameState,
                message = "${player.name} went bankrupt (debt: ${totalDebt}M, assets: ${totalAssetValue}M)."
            )
        )
        checkAndHandleGameOver(gameState)
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

    /**
     * Validates that [action.playerId] can manage properties (buy/sell houses, mortgage).
     * Replaces validateCurrentPlayerTurn for property management actions — any active
     * player may manage their properties at any time after the game has started.
     */
    private fun validatePlayerCanManageProperty(
        action: GameAction,
        gameState: GameState
    ): Boolean {
        if (gameState.phase == GamePhase.WAITING || gameState.phase == GamePhase.FINISHED) {
            sendGameError(action, gameState, "Property management is only available during the game.")
            return false
        }
        val player = gameState.players.find { it.id == action.playerId }
        if (player == null) {
            sendGameError(action, gameState, "Player not found in game.")
            return false
        }
        if (player.isBankrupt()) {
            sendGameError(action, gameState, "You are bankrupt and cannot manage properties.")
            return false
        }
        return true
    }

    private fun getValidatedProperty(
        action: GameAction,
        gameState: GameState
    ): PropertyField? {
        if (!validatePlayerCanManageProperty(action, gameState)) return null

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

    private fun handleReportCheater(
        action: GameAction,
        gameState: GameState
    ) {
        val reporterId = action.playerId
        val reportedPlayerId = action.payload["reportedPlayerId"]

        if (reportedPlayerId == null) {
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(gameId = action.gameId, event = "ERROR", message = "reportedPlayerId is missing.")
            )
            return
        }

        if (reporterId == reportedPlayerId) {
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(gameId = action.gameId, event = "ERROR", message = "You cannot report yourself.")
            )
            return
        }

        val reporter = gameState.players.find { it.id == reporterId }
        val reported = gameState.players.find { it.id == reportedPlayerId }

        if (reporter == null || reported == null) {
            messagingTemplate.convertAndSend(
                "/topic/game/${action.gameId}",
                GameEvent(gameId = action.gameId, event = "ERROR", message = "Reporter or reported player not found.")
            )
            return
        }

        if (reported.hasCheated) {
            reported.money -= 500
            reporter.money += 500
            reported.hasCheated = false // reset flag so they aren't repeatedly fined

            sendGameEvent(
                action,
                gameState,
                "CHEATER_REPORTED",
                "${reporter.name} successfully reported ${reported.name} for cheating! ${reported.name} paid a 500M fine to ${reporter.name}."
            )
        } else {
            reporter.money -= 500
            reported.money += 500

            sendGameEvent(
                action,
                gameState,
                "CHEATER_REPORT_FAILED",
                "${reporter.name} falsely accused ${reported.name} of cheating, and pays them a 500M fine!"
            )
        }
    }

    private fun checkAndHandleGameOver(gameState: GameState) {
        if (!gameState.isGameOver()) return

        gameState.phase = GamePhase.FINISHED
        gameState.pendingPayment = null
        gameState.currentActionCard = null
        gameState.lastDiceRoll = null

        messagingTemplate.convertAndSend(
            "/topic/game/${gameState.gameId}",
            GameEvent(
                gameId = gameState.gameId,
                event = GameEvent.GAME_OVER,
                gameState = gameState,
                message = "Game over"
            )
        )
    }
}
