package at.aau.monopoly.klagenfurt.messaging.dtos

/**
 * Outbound DTO – pushed to all subscribers of the /topic/lobby topic.
 * Contains the current list of open games and/or a status message.
 */
data class LobbyEvent(
    val event: String = "",
    val games: List<GameLobbyInfo> = emptyList(),
    val message: String? = null
)

