package at.aau.monopoly.klagenfurt.model

import at.aau.monopoly.klagenfurt.model.card.ChanceCard
import at.aau.monopoly.klagenfurt.model.card.CommunityChestCard
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.model.field.Field

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
    val hostPlayerId: String = "" // the player who created the game (host)
) {
    /** The player whose turn it currently is. */
    val currentPlayer: Player?
        get() = players.getOrNull(currentPlayerIndex)

    /** Advance the turn to the next player (wraps around). */
    fun advanceTurn() {
        if (players.isNotEmpty()) {
            currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        }
        phase = GamePhase.ROLLING
    }

    /** End the current player's turn without advancing to the next player yet.
     *  Sets phase to TURN_END and clears the last dice roll. */
    fun endCurrentTurn() {
        phase = GamePhase.TURN_END
        lastDiceRoll = null
    }

    /** Returns true when only one player has money / properties remaining. */
    fun isGameOver(): Boolean = players.count { !it.isBankrupt() } <= 1
}
