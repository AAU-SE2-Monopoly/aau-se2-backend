package at.aau.serg.websocketdemoserver.model

import at.aau.serg.websocketdemoserver.model.card.ChanceCard
import at.aau.serg.websocketdemoserver.model.card.CommunityChestCard
import at.aau.serg.websocketdemoserver.model.enums.GamePhase
import at.aau.serg.websocketdemoserver.model.field.Field

data class GameState(
    val gameId: String,
    val fields: List<Field>,
    val players: MutableList<Player> = mutableListOf(),
    var currentPlayerIndex: Int = 0,
    var phase: GamePhase = GamePhase.WAITING,
    val chanceCards: MutableList<ChanceCard> = mutableListOf(),
    val communityChestCards: MutableList<CommunityChestCard> = mutableListOf(),
    var freeParkingMoney: Int = 0,
    var lastDiceRoll: Pair<Int, Int>? = null
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

    /** Returns true when only one player has money / properties remaining. */
    fun isGameOver(): Boolean = players.count { !it.isBankrupt() } <= 1
}

