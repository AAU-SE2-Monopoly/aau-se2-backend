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
import at.aau.monopoly.klagenfurt.model.field.FreeParkingField
import at.aau.monopoly.klagenfurt.model.field.OwnableField
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.TaxField
import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.UtilityField
import at.aau.monopoly.klagenfurt.service.PaymentService
import at.aau.monopoly.klagenfurt.service.RentCalculator
import kotlin.math.ceil
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.web.socket.messaging.SessionDisconnectEvent
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
            "ROLL_DICE" -> {
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
                gameState.lastDiceRoll = roll
                val player = gameState.currentPlayer!!
                var eventMessage = "${player.name} rolled ${roll.die1} + ${roll.die2} = ${roll.total}."

                if (player.inJail) {
                    if (roll.isDouble) {
                        player.inJail = false
                        player.jailTurns = 0
                        eventMessage += " They rolled a doublet and got out of jail!"

                        val oldPos = player.position
                        val newPos = (oldPos + roll.total) % gameState.fields.size
                        player.position = newPos

                        player.consecutiveDoublets = 0
                        gameState.phase = GamePhase.BUYING
                    } else {
                        player.jailTurns++
                        if (player.jailTurns >= 3) {
                            player.money -= 50
                            player.inJail = false
                            player.jailTurns = 0
                            eventMessage += " Failed 3rd attempt. Paid 50M to get out!"

                            val oldPos = player.position
                            val newPos = (oldPos + roll.total) % gameState.fields.size
                            player.position = newPos
                            gameState.phase = GamePhase.BUYING
                        } else {
                            eventMessage += " Still in jail (turn ${player.jailTurns}/3)."
                            gameState.phase = GamePhase.TURN_END
                        }
                    }
                } else {
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
                            val oldPos = player.position
                            val newPos = (oldPos + roll.total) % gameState.fields.size
                            if (newPos < oldPos) {
                                player.money += 200
                                eventMessage += " and passed Go (+200€)."
                            }
                            player.position = newPos
                            gameState.phase = GamePhase.BUYING

                            if (newPos == 30) { // Go to Jail Field
                                player.inJail = true
                                player.position = 10
                                player.jailTurns = 0
                                player.consecutiveDoublets = 0
                                eventMessage += " Landed on Go To Jail!"
                                gameState.phase = GamePhase.TURN_END
                            }
                        }
                    } else {
                        player.consecutiveDoublets = 0
                        val oldPos = player.position
                        val newPos = (oldPos + roll.total) % gameState.fields.size
                        if (newPos < oldPos) {
                            player.money += 200
                            eventMessage += " and passed Go (+200€)."
                        }
                        player.position = newPos
                        gameState.phase = GamePhase.BUYING

                        if (newPos == 30) { // Go to Jail Field
                            player.inJail = true
                            player.position = 10
                            player.jailTurns = 0
                            eventMessage += " Landed on Go To Jail!"
                            gameState.phase = GamePhase.TURN_END
                        }
                    }
                }

                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(
                        gameId = action.gameId,
                        event = "DICE_ROLLED",
                        gameState = gameState,
                        message = eventMessage
                    )
                )

                // interactive payment flow — always send RENT_DUE/TAX_DUE events, never auto-deduct
                val landedField = gameState.fields.getOrNull(player.position)
                if (landedField != null) {
                    when (landedField) {
                        is TaxField -> {
                            val taxAmount = landedField.amount
                            gameState.phase = GamePhase.PAYING_RENT
                            gameState.pendingTaxAmount = taxAmount
                            gameState.pendingTaxFieldId = landedField.id
                            val taxEvent = GameEvent(gameId = action.gameId, event = GameEvent.TAX_DUE, gameState = gameState)
                            messagingTemplate.convertAndSend("/topic/game/${action.gameId}", taxEvent)
                        }
                        is OwnableField -> {
                            val ownerId = landedField.ownerId
                            if (ownerId != null && ownerId != player.id && !landedField.isMortgaged) {
                                val owner = gameState.players.find { it.id == ownerId }
                                if (owner != null && !owner.isBankrupt()) {
                                    val rent = when (landedField) {
                                        is PropertyField -> RentCalculator.calculatePropertyRent(landedField, gameState.fields, ownerId)
                                        is RailroadField -> RentCalculator.calculateRailroadRent(landedField, gameState.fields, ownerId)
                                        is UtilityField -> {
                                            val diceTotal = (gameState.lastDiceRoll?.total ?: 0)
                                            RentCalculator.calculateUtilityRent(landedField, gameState.fields, ownerId, diceTotal)
                                        }
                                        else -> 0
                                    }
                                    if (rent > 0) {
                                        gameState.phase = GamePhase.PAYING_RENT
                                        gameState.pendingRentAmount = rent
                                        gameState.pendingRentOwnerId = ownerId
                                        gameState.pendingRentFieldId = landedField.id
                                        val dueEvent = GameEvent(gameId = action.gameId, event = GameEvent.RENT_DUE, gameState = gameState)
                                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", dueEvent)
                                    }
                                }
                            }
                        }
                    }
                    // Check for Free Parking using type check instead of hardcoded position 20
                    if (landedField is FreeParkingField && gameState.freeParkingMoney > 0) {
                        player.money += gameState.freeParkingMoney
                        gameState.freeParkingMoney = 0
                        val fpEvent = GameEvent(gameId = action.gameId, event = GameEvent.FREE_PARKING_COLLECTED, gameState = gameState)
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", fpEvent)
                    }
                }
            }

            "PAY_JAIL_FINE" -> {
                if (gameState.currentPlayer?.id != action.playerId) {
                    messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "It is not your turn."))
                    return
                }
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
                if (gameState.currentPlayer?.id != action.playerId) {
                    messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "It is not your turn."))
                    return
                }
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
            //end turn safety
            "END_TURN" -> {
                if (gameState.phase != GamePhase.BUYING && gameState.phase != GamePhase.TURN_END) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "Cannot end turn in the current phase (${gameState.phase.name})."
                        )
                    )
                    return
                }

                val player = gameState.currentPlayer
                val isDoublet = gameState.lastDiceRoll?.isDouble == true

                if (player != null && isDoublet && !player.inJail && player.consecutiveDoublets > 0) {
                    gameState.endCurrentTurn()
                    gameState.phase = GamePhase.ROLLING
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "TURN_ENDED",
                            gameState = gameState,
                            message = "${player.name} rolled a doublet and gets another turn!"
                        )
                    )
                } else {
                    if (player != null) player.consecutiveDoublets = 0 // Reset just in case
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

            "BUY_PROPERTY" -> {
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

                if (gameState.phase != GamePhase.BUYING) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "Property can only be bought during the buying phase."
                        )
                    )
                    return
                }

                val fieldIdStr = action.payload["fieldId"]
                if (fieldIdStr.isNullOrBlank()) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "fieldId must be specified in payload."
                        )
                    )
                    return
                }

                val fieldId = fieldIdStr.toIntOrNull()
                if (fieldId == null || fieldId < 0 || fieldId >= gameState.fields.size) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "Invalid fieldId."
                        )
                    )
                    return
                }

                val player = gameState.currentPlayer!!

                if (player.position != fieldId) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "You can only buy the field you are currently standing on."
                        )
                    )
                    return
                }

                val field = gameState.fields[fieldId]

                val price = when (field) {
                    is PropertyField -> field.price
                    is RailroadField -> field.price
                    is UtilityField -> field.price
                    else -> {
                        messagingTemplate.convertAndSend(
                            "/topic/game/${action.gameId}",
                            GameEvent(
                                gameId = action.gameId,
                                event = "ERROR",
                                gameState = gameState,
                                message = "This field cannot be bought."
                            )
                        )
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
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "${field.name} is already owned by another player."
                        )
                    )
                    return
                }

                if (player.money < price) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "You don't have enough money to buy ${field.name}. Price: $$price, Your Money: $${player.money}"
                        )
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

                messagingTemplate.convertAndSend(
                    "/topic/game/${action.gameId}",
                    GameEvent(
                        gameId = action.gameId,
                        event = "PROPERTY_BOUGHT",
                        gameState = gameState,
                        message = "${player.name} bought ${field.name} for $$price."
                    )
                )
            }

            "PAY_RENT" -> {
                val player = gameState.currentPlayer ?: return
                val fieldId = action.payload["fieldId"]?.toIntOrNull()
                // validate fieldId matches pending rent or tax field
                val expectedFieldId = gameState.pendingRentFieldId ?: gameState.pendingTaxFieldId
                if (fieldId == null || fieldId != expectedFieldId) {
                    messagingTemplate.convertAndSend(
                        "/topic/game/${action.gameId}",
                        GameEvent(
                            gameId = action.gameId,
                            event = "ERROR",
                            gameState = gameState,
                            message = "Invalid fieldId for rent payment. Expected: $expectedFieldId, got: $fieldId"
                        )
                    )
                    return
                }
                val field = gameState.fields.find { it.id == fieldId }
                if (field is OwnableField) {
                    val ownerId = field.ownerId ?: return  // owner required for rent calculation
                    val rent = when (field) {
                        is PropertyField -> RentCalculator.calculatePropertyRent(field, gameState.fields, ownerId)
                        is RailroadField -> RentCalculator.calculateRailroadRent(field, gameState.fields, ownerId)
                        is UtilityField -> {
                            val diceTotal = gameState.lastDiceRoll?.total ?: 0
                            RentCalculator.calculateUtilityRent(field, gameState.fields, ownerId, diceTotal)
                        }
                        else -> 0
                    }
                    val owner = gameState.players.find { it.id == ownerId }
                    if (owner != null && rent > 0) {
                        if (player.money < rent) {
                            messagingTemplate.convertAndSend(
                                "/topic/game/${action.gameId}",
                                GameEvent(
                                    gameId = action.gameId,
                                    event = GameEvent.PAYMENT_FAILED,
                                    gameState = gameState,
                                    message = "Insufficient funds. Need $${rent}M but have $${player.money}M."
                                )
                            )
                            return
                        }
                        player.money -= rent
                        owner.money += rent
                    }
                    gameState.phase = GamePhase.TURN_END
                    gameState.pendingRentAmount = 0
                    gameState.pendingRentOwnerId = null
                    gameState.pendingRentFieldId = null
                    val event = GameEvent(gameId = action.gameId, event = GameEvent.RENT_PAID, gameState = gameState)
                    messagingTemplate.convertAndSend("/topic/game/${action.gameId}", event)
                } else if (field is TaxField) {
                    val taxAmount = gameState.pendingTaxAmount
                    if (taxAmount > 0) {
                        if (player.money < taxAmount) {
                            messagingTemplate.convertAndSend(
                                "/topic/game/${action.gameId}",
                                GameEvent(
                                    gameId = action.gameId,
                                    event = GameEvent.PAYMENT_FAILED,
                                    gameState = gameState,
                                    message = "Insufficient funds. Need $${taxAmount}M but have $${player.money}M."
                                )
                            )
                            return
                        }
                        player.money -= taxAmount
                        // Tax money goes to the Free Parking pool (standard Monopoly house rule)
                        gameState.freeParkingMoney += taxAmount
                    }
                    gameState.phase = GamePhase.TURN_END
                    gameState.pendingTaxAmount = 0
                    gameState.pendingTaxFieldId = null
                    val event = GameEvent(gameId = action.gameId, event = GameEvent.TAX_PAID, gameState = gameState)
                    messagingTemplate.convertAndSend("/topic/game/${action.gameId}", event)
                }
            }

            //  houses/hotel check — cannot mortgage property with buildings
            "MORTGAGE_PROPERTY" -> {
                val player = gameState.currentPlayer ?: return
                val fieldId = action.payload["fieldId"]?.toIntOrNull()
                if (fieldId != null) {
                    val field = gameState.fields.find { it.id == fieldId }
                    // block mortgage if PropertyField has houses or hotel
                    val hasBuildings = field is PropertyField && (field.houses > 0 || field.hasHotel)
                    if (field is OwnableField && field.ownerId == player.id && !field.isMortgaged && !hasBuildings) {
                        PaymentService.mortgageProperty(player, field)
                        val event = GameEvent(gameId = action.gameId, event = GameEvent.PROPERTY_MORTGAGED, gameState = gameState)
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", event)
                    } else if (hasBuildings) {
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "Sell all houses/hotels before mortgaging."))
                    }
                }
            }

            //  affordability check — player must have enough money to unmortgage
            "UNMORTGAGE_PROPERTY" -> {
                val player = gameState.currentPlayer ?: return
                val fieldId = action.payload["fieldId"]?.toIntOrNull()
                if (fieldId != null) {
                    val field = gameState.fields.find { it.id == fieldId }
                    if (field is OwnableField && field.ownerId == player.id && field.isMortgaged) {
                        // calculate unmortgage cost (price/2 + 10% interest)
                        val price = when (field) {
                            is PropertyField -> field.price
                            is RailroadField -> field.price
                            is UtilityField -> field.price
                            else -> 0
                        }
                        val unmortgageCost = ceil(price / 2.0 * 1.1).toInt()
                        if (player.money >= unmortgageCost) {
                            PaymentService.unmortgageProperty(player, field)
                            val event = GameEvent(gameId = action.gameId, event = GameEvent.PROPERTY_UNMORTGAGED, gameState = gameState)
                            messagingTemplate.convertAndSend("/topic/game/${action.gameId}", event)
                        } else {
                            messagingTemplate.convertAndSend("/topic/game/${action.gameId}", GameEvent(gameId = action.gameId, event = "ERROR", message = "Not enough money to unmortgage. Need ${unmortgageCost}M."))
                        }
                    }
                }
            }

            "SELL_HOUSE" -> {
                val player = gameState.currentPlayer ?: return
                val fieldId = action.payload["fieldId"]?.toIntOrNull()
                if (fieldId != null) {
                    val field = gameState.fields.find { it.id == fieldId }
                    if (field is PropertyField && field.ownerId == player.id && (field.houses > 0 || field.hasHotel)) {
                        if (field.hasHotel) {
                            PaymentService.sellHotel(player, field, gameState.fields)
                        } else {
                            PaymentService.sellHouse(player, field, gameState.fields)
                        }
                        val event = GameEvent(gameId = action.gameId, event = GameEvent.HOUSE_SOLD, gameState = gameState)
                        messagingTemplate.convertAndSend("/topic/game/${action.gameId}", event)
                    }
                }
            }

            "DECLARE_BANKRUPTCY" -> {
                val player = gameState.currentPlayer ?: return
                val creditorId = gameState.pendingRentOwnerId

                // Compute bankruptcy summary BEFORE processing
                val ownedFields = gameState.fields.filterIsInstance<OwnableField>()
                    .filter { it.ownerId == player.id }
                val ownedFieldIds = ownedFields.map { (it as Field).id }
                val totalAssetValue = player.money + ownedFields.sumOf { f ->
                    val price = when (f) {
                        is PropertyField -> f.price
                        is RailroadField -> f.price
                        is UtilityField -> f.price
                        else -> 0
                    }
                    price / 2  // mortgage value
                }
                val totalDebt = gameState.pendingRentAmount + gameState.pendingTaxAmount
                val propertiesCount = ownedFields.size

                //bankruptcy (tax debt) vs player-creditor bankruptcy
                if (gameState.pendingTaxAmount > 0) {
                    // Cancel all mortgages on debtor's properties
                    gameState.fields.filterIsInstance<OwnableField>()
                        .filter { it.ownerId == player.id }
                        .forEach { it.isMortgaged = false }
                    // Return jail cards to bottom of decks (just discard them)
                    player.getOutOfJailCards = 0
                    player.money = 0
                    // Clear ownership of all fields
                    gameState.fields.filterIsInstance<OwnableField>()
                        .filter { it.ownerId == player.id }
                        .forEach { it.ownerId = null }
                    player.ownedPropertyIds.clear()
                    gameState.pendingTaxAmount = 0
                } else if (creditorId != null) {
                    //player-creditor bankruptcy
                    val creditor = gameState.players.find { it.id == creditorId }
                    player.money = 0
                    // Transfer owned properties to the creditor
                    val transferredIds = mutableListOf<Int>()
                    gameState.fields
                        .filter { it is OwnableField && it.ownerId == player.id }
                        .forEach {
                            (it as OwnableField).ownerId = creditorId
                            transferredIds.add(it.id)
                        }
                    // transfer jail cards to creditor
                    if (creditor != null) {
                        creditor.getOutOfJailCards += player.getOutOfJailCards
                        creditor.ownedPropertyIds.addAll(transferredIds)
                    }
                    player.getOutOfJailCards = 0
                    player.ownedPropertyIds.clear()
                } else {
                    // fallback: no creditor, just zero out
                    player.money = 0
                    gameState.fields.filterIsInstance<OwnableField>()
                        .filter { it.ownerId == player.id }
                        .forEach { it.ownerId = null }
                    player.ownedPropertyIds.clear()
                    player.getOutOfJailCards = 0
                }
                gameState.phase = GamePhase.TURN_END
                gameState.pendingRentAmount = 0
                gameState.pendingRentOwnerId = null
                gameState.pendingRentFieldId = null
                gameState.pendingTaxAmount = 0
                // include bankruptcy summary data in the event
                gameState.bankruptcyTotalAssets = totalAssetValue
                gameState.bankruptcyTotalDebt = totalDebt
                gameState.bankruptcyPropertiesCount = propertiesCount
                gameState.bankruptcyOwnedFieldIds = ownedFieldIds
                val event = GameEvent(gameId = action.gameId, event = GameEvent.BANKRUPTCY_DECLARED, gameState = gameState)
                messagingTemplate.convertAndSend("/topic/game/${action.gameId}", event)
            }

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
}
