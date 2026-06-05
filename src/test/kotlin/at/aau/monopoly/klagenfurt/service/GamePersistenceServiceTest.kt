package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.model.BoardFactory
import at.aau.monopoly.klagenfurt.model.GameState
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class GamePersistenceServiceTest {

    private fun newService(dir: Path, enabled: Boolean = true) =
        GamePersistenceService(enabled = enabled, dir = dir.toString())

    private fun sampleGame(): GameState = GameState(
        gameId = UUID.randomUUID().toString(),
        fields = BoardFactory.createDefaultBoard(),
        chanceCards = BoardFactory.createChanceCards(),
        communityChestCards = BoardFactory.createCommunityChestCards(),
        hostPlayerId = "host-1"
    ).apply {
        players.add(Player(id = "host-1", name = "Alice", money = 1234, position = 7))
        players.add(Player(id = "p-2", name = "Bob", iconId = "car"))
        phase = GamePhase.ROLLING
        freeParkingMoney = 200
    }

    @Test
    fun `save then loadAll round-trips a game including polymorphic fields and cards`(@TempDir tmp: Path) {
        val service = newService(tmp)
        val game = sampleGame()

        service.save(game)
        val loaded = service.loadAll()

        val restored = loaded[game.gameId]
        assertNotNull(restored)
        restored!!
        assertEquals(game.gameId, restored.gameId)
        assertEquals(40, restored.fields.size)
        assertEquals(16, restored.chanceCards.size)
        assertEquals(16, restored.communityChestCards.size)
        assertEquals(GamePhase.ROLLING, restored.phase)
        assertEquals(200, restored.freeParkingMoney)
        assertEquals(2, restored.players.size)
        assertEquals(1234, restored.players[0].money)
        // Polymorphic Field subtype survives the round-trip
        assertTrue(restored.fields.any { it is PropertyField })
    }

    @Test
    fun `delete removes the persisted file`(@TempDir tmp: Path) {
        val service = newService(tmp)
        val game = sampleGame()
        service.save(game)
        assertTrue(Files.exists(tmp.resolve("${game.gameId}.json")))

        service.delete(game.gameId)

        assertFalse(Files.exists(tmp.resolve("${game.gameId}.json")))
        assertTrue(service.loadAll().isEmpty())
    }

    @Test
    fun `loadAll skips corrupt files`(@TempDir tmp: Path) {
        val service = newService(tmp)
        val good = sampleGame()
        service.save(good)
        Files.writeString(tmp.resolve("broken.json"), "{ not valid json")

        val loaded = service.loadAll()

        assertEquals(1, loaded.size)
        assertTrue(loaded.containsKey(good.gameId))
    }

    @Test
    fun `disabled service does not write or read`(@TempDir tmp: Path) {
        val service = newService(tmp, enabled = false)
        val game = sampleGame()

        service.save(game)

        assertFalse(Files.exists(tmp.resolve("${game.gameId}.json")))
        assertTrue(service.loadAll().isEmpty())
    }
}

