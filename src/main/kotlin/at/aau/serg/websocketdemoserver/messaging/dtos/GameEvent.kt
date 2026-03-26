package at.aau.serg.websocketdemoserver.messaging.dtos

import at.aau.serg.websocketdemoserver.model.GameState

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

