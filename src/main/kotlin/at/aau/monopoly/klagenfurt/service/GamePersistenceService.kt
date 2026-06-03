package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.model.GameState
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists each active game to its own JSON file (`{dir}/{gameId}.json`) so that
 * games survive a server restart or crash.
 *
 * Writes are synchronous: every state change is flushed to disk immediately.
 * To avoid half-written files on crash, each save writes to a temporary file
 * and then atomically renames it over the target file.
 *
 * Can be disabled entirely via `app.persistence.enabled=false` (e.g. in tests).
 */
@Service
class GamePersistenceService(
    @param:Value("\${app.persistence.enabled:true}") private val enabled: Boolean,
    @param:Value("\${app.persistence.dir:data}") private val dir: String
) {

    private val log = LoggerFactory.getLogger(GamePersistenceService::class.java)

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        // Computed helpers like Player.isBankrupt() are serialized but have no
        // matching constructor parameter, so ignore unknown properties on read.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val baseDir: Path = Path.of(dir)

    private fun fileFor(gameId: String): Path = baseDir.resolve("$gameId.json")

    /** Persists a single game to `{dir}/{gameId}.json` (atomic write). */
    fun save(gameState: GameState) {
        if (!enabled) return
        try {
            Files.createDirectories(baseDir)
            val target = fileFor(gameState.gameId)
            val tmp = Files.createTempFile(baseDir, gameState.gameId, ".tmp")
            mapper.writeValue(tmp.toFile(), gameState)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log.error("Failed to persist game '${gameState.gameId}'", e)
        }
    }

    /** Deletes the persisted file for the given game, if it exists. */
    fun delete(gameId: String) {
        if (!enabled) return
        try {
            Files.deleteIfExists(fileFor(gameId))
        } catch (e: Exception) {
            log.error("Failed to delete persisted game '$gameId'", e)
        }
    }

    /**
     * Loads all persisted games from disk. Corrupt or unreadable files are
     * logged and skipped so that a single bad file cannot block startup.
     */
    fun loadAll(): Map<String, GameState> {
        if (!enabled) return emptyMap()
        if (!Files.isDirectory(baseDir)) return emptyMap()

        val result = LinkedHashMap<String, GameState>()
        try {
            Files.newDirectoryStream(baseDir, "*.json").use { stream ->
                for (path in stream) {
                    try {
                        val gameState = mapper.readValue(path.toFile(), GameState::class.java)
                        result[gameState.gameId] = gameState
                    } catch (e: Exception) {
                        log.error("Skipping corrupt persisted game file '$path'", e)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to list persisted game directory '$baseDir'", e)
        }
        log.info("Loaded ${result.size} persisted game(s) from '$baseDir'.")
        return result
    }
}



