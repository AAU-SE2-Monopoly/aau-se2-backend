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
    val maxPlayersPerGame: Int = 6

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
     * @throws IllegalArgumentException if the game does not exist or is already full.
     * @return the updated [GameState].
     */
    fun joinGame(gameId: String, player: Player): GameState {
        val gameState = games[gameId]
            ?: throw IllegalArgumentException("Game with id '$gameId' not found.")
        require(gameState.players.size < maxPlayersPerGame) {
            "Game '$gameId' is already full ($maxPlayersPerGame players)."
        }
        require(gameState.players.none { it.id == player.id }) {
            "Player '${player.id}' is already in game '$gameId'."
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
     * @return the removed [GameState], or null if the game does not exist.
     * @throws IllegalArgumentException if the player is not the host.
     */
    fun closeGame(gameId: String, playerId: String): GameState {
        val gameState = games[gameId]
            ?: throw IllegalArgumentException("Game with id '$gameId' not found.")
        require(gameState.hostPlayerId == playerId) {
            "Only the host can close the game."
        }
        games.remove(gameId)
        return gameState
    }

    /**
     * Returns all active game IDs.
     */
    fun listGameIds(): Set<String> = games.keys.toSet()

    /**
     * Returns a list of [GameLobbyInfo] for all games that are still in the WAITING phase
     * (i.e. open for joining).
     */
    fun listOpenGames(): List<GameLobbyInfo> =
        games.values
            .filter { it.phase == GamePhase.WAITING }
            .map { game ->
                val hostName = game.players.firstOrNull { it.id == game.hostPlayerId }?.name ?: "Unknown"
                GameLobbyInfo(
                    gameId = game.gameId,
                    hostPlayerName = hostName,
                    playerCount = game.players.size,
                    maxPlayers = maxPlayersPerGame,
                    phase = game.phase
                )
            }
}

