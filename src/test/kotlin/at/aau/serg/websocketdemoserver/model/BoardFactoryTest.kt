package at.aau.serg.websocketdemoserver.model

import at.aau.serg.websocketdemoserver.model.field.*
import org.junit.jupiter.api.Assertions.assertEquals
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
}