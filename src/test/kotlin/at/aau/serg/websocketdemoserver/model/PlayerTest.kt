package at.aau.serg.websocketdemoserver.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlayerTest {

    @Test
    fun `player should be initialized with default values`() {
        val player = Player(id = "1", name = "Max")

        assertEquals("1", player.id)
        assertEquals("Max", player.name)
        assertEquals(0, player.position)
        assertEquals(1500, player.money)
        assertFalse(player.inJail)
        assertEquals(0, player.jailTurns)
        assertEquals(0, player.getOutOfJailCards)
        assertTrue(player.ownedPropertyIds.isEmpty())
    }

    @Test
    fun `player should allow modifying properties`() {
        val player = Player(id = "1", name = "Max")

        player.position = 10
        player.money = 1200
        player.inJail = true
        player.jailTurns = 2
        player.getOutOfJailCards = 1
        player.ownedPropertyIds.add(5)

        assertEquals(10, player.position)
        assertEquals(1200, player.money)
        assertTrue(player.inJail)
        assertEquals(2, player.jailTurns)
        assertEquals(1, player.getOutOfJailCards)
        assertTrue(player.ownedPropertyIds.contains(5))
    }

    @Test
    fun `isBankrupt should return true when no money and no properties`() {
        val player = Player(id = "1", name = "Max")

        player.money = 0
        player.ownedPropertyIds.clear()

        assertTrue(player.isBankrupt())
    }

    @Test
    fun `isBankrupt should return false when player has money`() {
        val player = Player(id = "1", name = "Max")

        player.money = 100

        assertFalse(player.isBankrupt())
    }

    @Test
    fun `isBankrupt should return false when player owns properties`() {
        val player = Player(id = "1", name = "Max")

        player.money = 0
        player.ownedPropertyIds.add(1)

        assertFalse(player.isBankrupt())
    }

    @Test
    fun `player should be bankrupt when money is negative and no properties`() {
        val player = Player(id = "1", name = "Max")

        player.money = -50
        player.ownedPropertyIds.clear()

        assertTrue(player.isBankrupt())
    }
}