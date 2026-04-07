package at.aau.monopoly.klagenfurt.messaging.dtos

/**
 * Inbound DTO – sent by a client to perform an action in the game.
 *
 * Examples of [action] values: "ROLL_DICE", "BUY_PROPERTY", "END_TURN",
 * "PAY_RENT", "BUILD_HOUSE", "MORTGAGE", "DECLARE_BANKRUPTCY".
 */
data class GameAction(
    val gameId: String = "",
    val playerId: String = "",
    val action: String = "",
    /** Optional extra data relevant to the action (e.g. propertyId for BUY_PROPERTY). */
    val payload: Map<String, String> = emptyMap()
)

