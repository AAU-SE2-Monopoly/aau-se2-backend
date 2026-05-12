package at.aau.monopoly.klagenfurt.controller

import at.aau.monopoly.klagenfurt.messaging.dtos.GameLobbyInfo
import at.aau.monopoly.klagenfurt.model.BoardFactory
import at.aau.monopoly.klagenfurt.model.GameState
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class GameController {

    private val games: ConcurrentHashMap<String, GameState> = ConcurrentHashMap()

    /** Maximum number of players allowed per game. */
    val maxPlayersPerGame: Int = 5

    /**
     * Creates a new game with a fresh board and empty player list.
     * The [hostPlayerId] is recorded as the host of the game.
     * @return the newly created [GameState].
     */
    fun createGame(hostPlayerId: String = ""): GameState {
        val gameId = UUID.randomUUID().toString()
        val gameState = GameState(
            gameId = gameId,
            fields = BoardFactory.createDefaultBoard(),
            chanceCards = BoardFactory.createChanceCards(),
            communityChestCards = BoardFactory.createCommunityChestCards(),
            hostPlayerId = hostPlayerId
        )
        games[gameId] = gameState
        return gameState
    }

    /**
     * Adds a [player] to an existing game identified by [gameId].
     *
     * *Rejoin logic*: if the player already exists in the game (same [player.id]),
     * the join is silently accepted — the existing identity and icon are preserved.
     * This handles WiFi drops during WAITING as well as app restarts during InProgress.
     *
     * *Fresh join*: only allowed when the game is in [GamePhase.WAITING].
     *
     * @throws IllegalArgumentException if the game does not exist, is not in WAITING
     *         for a fresh join, or is already full.
     * @return the updated [GameState].
     */
    fun joinGame(gameId: String, player: Player): GameState {
        val gameState = games[gameId]
            ?: throw IllegalArgumentException("Game with id '$gameId' not found.")

        // 1. Rejoin? If player is already registered, silently accept.
        //    Existing identity (name, icon) wins over any incoming values.
        if (gameState.players.any { it.id == player.id }) {
            return gameState
        }

        // 2. Fresh join — must be WAITING phase.
        require(gameState.phase == GamePhase.WAITING) {
            "Cannot join: you are not a participant in this game."
        }

        // 3. Fresh join — must have room.
        require(gameState.players.size < maxPlayersPerGame) {
            "Game '$gameId' is already full ($maxPlayersPerGame players)."
        }

        gameState.players.add(player)
        return gameState
    }

    /**
     * Returns the [GameState] for the given [gameId], or null if not found.
     */
    fun getGameState(gameId: String): GameState? = games[gameId]

    /**
     * Removes the game with the given [gameId].
     * @return true if the game was removed, false if it did not exist.
     */
    fun removeGame(gameId: String): Boolean = games.remove(gameId) != null

    /**
     * Closes (removes) the game if the requesting [playerId] is the host.
     * Games that have already started (phase > WAITING) cannot be closed —
     * this prevents accidental destruction of an in-progress game.
     * @return the removed [GameState].
     * @throws IllegalArgumentException if the game does not exist, the player
     *         is not the host, or the game has already started.
     */
    fun closeGame(gameId: String, playerId: String): GameState {
        val gameState = games[gameId]
            ?: throw IllegalArgumentException("Game with id '$gameId' not found.")
        require(gameState.hostPlayerId == playerId) {
            "Only the host can close the game."
        }
        require(gameState.phase == GamePhase.WAITING) {
            "Cannot close a game that has already started."
    }
        games.remove(gameId)
        return gameState
    }

    /**
     * Returns all active game IDs.
     */
    fun listGameIds(): Set<String> = games.keys.toSet()

    /**
     * Returns a list of [GameLobbyInfo] for all active games, regardless of phase.
     * Games in WAITING phase are open for joining; others are already started.
     * The frontend can use the [GameLobbyInfo.phase] field to show a "STARTED" indicator.
     */
    fun listAllGames(): List<GameLobbyInfo> =
        games.values
            .map { game ->
                val hostName = game.players.firstOrNull { it.id == game.hostPlayerId }?.name ?: "Unknown"
                GameLobbyInfo(
                    gameId = game.gameId,
                    hostPlayerName = hostName,
                    playerCount = game.players.size,
                    maxPlayers = maxPlayersPerGame,
                    phase = game.phase,
                    playerIds = game.players.map { it.id }
                )
            }
}
