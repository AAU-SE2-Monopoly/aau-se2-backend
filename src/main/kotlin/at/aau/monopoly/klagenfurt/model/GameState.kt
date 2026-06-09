package at.aau.monopoly.klagenfurt.model

import at.aau.monopoly.klagenfurt.model.card.Card
import at.aau.monopoly.klagenfurt.model.card.ChanceCard
import at.aau.monopoly.klagenfurt.model.card.CommunityChestCard
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.model.field.Field

enum class PaymentSource { RENT, CARD_PAY, CARD_PAY_EACH, CARD_REPAIR, TAX }

data class PendingPayment(
    val amount: Int,
    val source: PaymentSource,
    val sourceFieldId: Int? = null,
    val creditorPlayerId: String? = null,
    val debtorCanPayAfterAssets: Boolean = false
)

data class TradeOffer(
    val id: String,
    val fromPlayerId: String,
    val toPlayerId: String,
    val offerMoney: Int = 0,
    val requestMoney: Int = 0,
    val offerPropertyIds: List<Int> = emptyList(),
    val requestPropertyIds: List<Int> = emptyList(),
    val offerJailCards: Int = 0,
    val requestJailCards: Int = 0
)

data class GameState(
    val gameId: String,
    val fields: List<Field>,
    val players: MutableList<Player> = mutableListOf(),
    var currentPlayerIndex: Int = 0,
    var phase: GamePhase = GamePhase.WAITING,
    val chanceCards: MutableList<ChanceCard> = mutableListOf(),
    val communityChestCards: MutableList<CommunityChestCard> = mutableListOf(),
    var freeParkingMoney: Int = 0,
    var lastDiceRoll: DiceRoll? = null, // replaced Pair with serializable DiceRoll
    val hostPlayerId: String = "", // the player who created the game (host)
    var currentActionCard: Card? = null, // Current action card (Chance/Community Chest) waiting for execution
    var pendingPayment: PendingPayment? = null,
    var bankruptcyTotalAssets: Int = 0,
    var bankruptcyTotalDebt: Int = 0,
    var bankruptcyPropertiesCount: Int = 0,
    var bankruptcyOwnedFieldIds: List<Int> = emptyList(),
    var bankruptcyPlayerId: String = "",
    var pendingTradeOffer: TradeOffer? = null,
    var hasDrawnCardThisTurn: Boolean = false,
    /**
     * Epoch-millis timestamp marking when the current player's turn-timeout clock started.
     * Refreshed whenever the active player changes or performs an action.
     * A value of 0 means no timer is active (e.g. while WAITING).
     */
    var turnTimerStartedAtMillis: Long = 0
) {
    /** The player whose turn it currently is. */
    val currentPlayer: Player?
        get() = players.getOrNull(currentPlayerIndex)

    /** (Re)starts the turn-timeout clock for the current player. */
    fun resetTurnTimer(nowMillis: Long = System.currentTimeMillis()) {
        turnTimerStartedAtMillis = nowMillis
    }

    /** Advance the turn to the next player (wraps around). */
    fun advanceTurn() {
        if (players.isNotEmpty()) {
            var attempts = 0
            do {
                currentPlayerIndex = (currentPlayerIndex + 1) % players.size
                attempts++
            } while (attempts < players.size && (players[currentPlayerIndex].isBankrupt() || players[currentPlayerIndex].eliminated))

            if (players.all { it.isBankrupt() || it.eliminated }) {
                phase = GamePhase.FINISHED
                return
            }
        }
        phase = GamePhase.ROLLING
        currentActionCard = null
        pendingPayment = null
        hasDrawnCardThisTurn = false
        resetTurnTimer()
    }

    /** End the current player's turn without advancing to the next player yet.
     *  Sets phase to TURN_END and clears the last dice roll. */
    fun endCurrentTurn() {
        phase = GamePhase.TURN_END
        lastDiceRoll = null
        hasDrawnCardThisTurn = false
    }

    /** Returns true when only one player has money / properties remaining. */
    fun isGameOver(): Boolean = players.count { !it.isBankrupt() && !it.eliminated } <= 1
}
