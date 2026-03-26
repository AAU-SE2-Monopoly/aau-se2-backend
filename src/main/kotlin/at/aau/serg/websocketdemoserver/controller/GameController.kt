package at.aau.serg.websocketdemoserver.controller

import at.aau.serg.websocketdemoserver.model.BoardFactory
import at.aau.serg.websocketdemoserver.model.GameState
import at.aau.serg.websocketdemoserver.model.Player
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
     * @return the newly created [GameState].
     */
    fun createGame(): GameState {
        val gameId = UUID.randomUUID().toString()
        val gameState = GameState(
            gameId = gameId,
            fields = BoardFactory.createDefaultBoard(),
            chanceCards = BoardFactory.createChanceCards(),
            communityChestCards = BoardFactory.createCommunityChestCards()
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
     * Returns all active game IDs.
     */
    fun listGameIds(): Set<String> = games.keys.toSet()
}

