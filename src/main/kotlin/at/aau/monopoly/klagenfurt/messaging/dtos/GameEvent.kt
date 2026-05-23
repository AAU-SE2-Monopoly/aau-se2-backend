package at.aau.monopoly.klagenfurt.messaging.dtos

import at.aau.monopoly.klagenfurt.model.GameState

/**
 * Outbound DTO – pushed from the server to all subscribers of a game topic.
 *
 * Examples of [event] values: "GAME_CREATED", "PLAYER_JOINED", "GAME_STARTED",
 * "DICE_ROLLED", "PLAYER_MOVED", "PROPERTY_BOUGHT", "RENT_PAID",
 * "TURN_ENDED", "GAME_OVER".
 */
data class GameEvent(
    val gameId: String = "",
    val event: String = "",
    val gameState: GameState? = null,
    val message: String? = null
)
 {
    companion object {
        const val RENT_DUE = "RENT_DUE"
        const val TAX_DUE = "TAX_DUE"
        const val RENT_PAID = "RENT_PAID"
        const val TAX_PAID = "TAX_PAID"
        const val PROPERTY_MORTGAGED = "PROPERTY_MORTGAGED"
        const val PROPERTY_UNMORTGAGED = "PROPERTY_UNMORTGAGED"
        const val PAYMENT_FAILED = "PAYMENT_FAILED"
        const val HOUSE_SOLD = "HOUSE_SOLD"
        const val BANKRUPTCY_DECLARED = "BANKRUPTCY_DECLARED"
        const val FREE_PARKING_COLLECTED = "FREE_PARKING_COLLECTED"
    }
}

