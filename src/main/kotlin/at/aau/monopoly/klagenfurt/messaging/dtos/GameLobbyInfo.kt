package at.aau.monopoly.klagenfurt.messaging.dtos

import at.aau.monopoly.klagenfurt.model.enums.GamePhase

/**
 * Lightweight DTO representing a single game entry in the lobby list.
 * Sent to clients so they can see available games without receiving full board state.
 */
data class GameLobbyInfo(
    val gameId: String = "",
    val hostPlayerName: String = "",
    val playerCount: Int = 0,
    val maxPlayers: Int = 6,
    val phase: GamePhase = GamePhase.WAITING
)

