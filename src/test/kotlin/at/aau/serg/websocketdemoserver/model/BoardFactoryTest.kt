package at.aau.serg.websocketdemoserver.model

import at.aau.serg.websocketdemoserver.model.field.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoardFactoryTest {

    @Test
    fun `createDefaultBoard should create 40 fields`() {
        val board = BoardFactory.createDefaultBoard()

        assertEquals(40, board.size)
    }

    @Test
    fun `createDefaultBoard should have important fixed fields at correct positions`() {
        val board = BoardFactory.createDefaultBoard()

        assertTrue(board[0] is GoField)
        assertTrue(board[10] is JailField)
        assertTrue(board[20] is FreeParkingField)
        assertTrue(board[30] is GoToJailField)
    }

    @Test
    fun `createDefaultBoard should contain 2 utility fields`() {
        val board = BoardFactory.createDefaultBoard()

        val utilityCount = board.count { it is UtilityField }

        assertEquals(2, utilityCount)
    }

    @Test
    fun `createDefaultBoard should contain 4 railroad fields`() {
        val board = BoardFactory.createDefaultBoard()

        val railroadCount = board.count { it is RailroadField }

        assertEquals(4, railroadCount)
    }

    @Test
    fun `createChanceCards should create 16 cards`() {
        val cards = BoardFactory.createChanceCards()

        assertEquals(16, cards.size)
    }

    @Test
    fun `createCommunityChestCards should create 16 cards`() {
        val cards = BoardFactory.createCommunityChestCards()

        assertEquals(16, cards.size)
    }

    @Test
    fun `all fields should have unique ids`() {
        val board = BoardFactory.createDefaultBoard()

        val ids = board.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `field ids should cover range from 0 to 39`() {
        val board = BoardFactory.createDefaultBoard()

        val ids = board.map { it.id }.sorted()

        assertEquals((0..39).toList(), ids)
    }

    @Test
    fun `all fields should have non-empty names`() {
        val board = BoardFactory.createDefaultBoard()

        board.forEach {
            assertTrue(it.name.isNotBlank())
        }
    }

    @Test
    fun `utility fields should have default price of 150`() {
        val board = BoardFactory.createDefaultBoard()

        val utilities = board.filterIsInstance<UtilityField>()

        utilities.forEach {
            assertEquals(150, it.price)
        }
    }

    @Test
    fun `property fields should have 6 rent levels`() {
        val board = BoardFactory.createDefaultBoard()

        val properties = board.filterIsInstance<PropertyField>()

        properties.forEach {
            assertEquals(6, it.rent.size)
        }
    }

    @Test
    fun `chance cards should not always be in same order`() {
        val first = BoardFactory.createChanceCards()
        val second = BoardFactory.createChanceCards()


        assertNotEquals(first, second)
    }

    @Test
    fun `there should be exactly one Go field`() {
        val board = BoardFactory.createDefaultBoard()

        val count = board.count { it is GoField }

        assertEquals(1, count)
    }

    @Test
    fun `board order should be consistent`() {
        val board = BoardFactory.createDefaultBoard()

        assertEquals("Go", board[0].name)
        assertEquals("Boardwalk", board[39].name)
    }
}