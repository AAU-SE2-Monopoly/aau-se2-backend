package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.model.BoardFactory
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField
import at.aau.monopoly.klagenfurt.model.enums.PropertyColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RentCalculatorTest {

    private fun propertyField(
        id: Int,
        price: Int = 60,
        rent: List<Int>,
        color: PropertyColor,
        ownerId: String? = null,
        houses: Int = 0,
        hasHotel: Boolean = false,
        houseCost: Int = 50,
        hotelCost: Int = 50
    ): PropertyField {
        return PropertyField(
            id = id,
            name = "Field $id",
            color = color,
            price = price,
            rent = rent,
            houseCost = houseCost,
            hotelCost = hotelCost,
            ownerId = ownerId,
            houses = houses,
            hasHotel = hasHotel
        )
    }

    private fun railroadField(id: Int, ownerId: String? = null): RailroadField {
        return RailroadField(id = id, name = "Railroad $id", ownerId = ownerId)
    }

    private fun utilityField(id: Int, ownerId: String? = null): UtilityField {
        return UtilityField(id = id, name = "Utility $id", ownerId = ownerId)
    }

    @Test
    fun `calculatePropertyRent returns base rent with no houses and no monopoly`() {
        val fields = listOf(
            propertyField(id = 1, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN),
            propertyField(id = 2, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN, ownerId = "other")
        )
        val prop = fields[0]
        val rent = RentCalculator.calculatePropertyRent(prop, fields, ownerId = "p1")
        assertEquals(2, rent)
    }

    @Test
    fun `calculatePropertyRent doubles rent on monopoly with no houses`() {
        val fields = listOf(
            propertyField(id = 1, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN, ownerId = "p1"),
            propertyField(id = 2, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN, ownerId = "p1")
        )
        val prop = fields[0]
        val rent = RentCalculator.calculatePropertyRent(prop, fields, ownerId = "p1")
        assertEquals(4, rent)
    }

    @Test
    fun `calculatePropertyRent does not double when houses are present`() {
        val fields = listOf(
            propertyField(id = 1, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN, ownerId = "p1", houses = 1),
            propertyField(id = 2, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN, ownerId = "p1")
        )
        val prop = fields[0]
        val rent = RentCalculator.calculatePropertyRent(prop, fields, ownerId = "p1")
        assertEquals(10, rent)
    }

    @Test
    fun `calculatePropertyRent returns hotel rent at index 5`() {
        val fields = listOf(
            propertyField(id = 1, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN, ownerId = "p1", hasHotel = true),
            propertyField(id = 2, rent = listOf(2, 10, 30, 90, 160, 250), color = PropertyColor.BROWN, ownerId = "p1")
        )
        val prop = fields[0]
        val rent = RentCalculator.calculatePropertyRent(prop, fields, ownerId = "p1")
        assertEquals(250, rent)
    }

    @Test
    fun `calculateRailroadRent returns 25 for one railroad`() {
        val fields = listOf(
            railroadField(id = 1, ownerId = "p1")
        )
        val rent = RentCalculator.calculateRailroadRent(fields[0], fields, ownerId = "p1")
        assertEquals(25, rent)
    }

    @Test
    fun `calculateRailroadRent returns 50 for two railroads`() {
        val fields = listOf(
            railroadField(id = 1, ownerId = "p1"),
            railroadField(id = 2, ownerId = "p1")
        )
        val rent = RentCalculator.calculateRailroadRent(fields[0], fields, ownerId = "p1")
        assertEquals(50, rent)
    }

    @Test
    fun `calculateRailroadRent returns 100 for three railroads`() {
        val fields = listOf(
            railroadField(id = 1, ownerId = "p1"),
            railroadField(id = 2, ownerId = "p1"),
            railroadField(id = 3, ownerId = "p1")
        )
        val rent = RentCalculator.calculateRailroadRent(fields[0], fields, ownerId = "p1")
        assertEquals(100, rent)
    }

    @Test
    fun `calculateRailroadRent returns 200 for four railroads`() {
        val fields = listOf(
            railroadField(id = 1, ownerId = "p1"),
            railroadField(id = 2, ownerId = "p1"),
            railroadField(id = 3, ownerId = "p1"),
            railroadField(id = 4, ownerId = "p1")
        )
        val rent = RentCalculator.calculateRailroadRent(fields[0], fields, ownerId = "p1")
        assertEquals(200, rent)
    }

    @Test
    fun `calculateRailroadRent caps at 200`() {
        val fields = listOf(
            railroadField(id = 1, ownerId = "p1"),
            railroadField(id = 2, ownerId = "p1"),
            railroadField(id = 3, ownerId = "p1"),
            railroadField(id = 4, ownerId = "p1"),
            railroadField(id = 5, ownerId = "p1")
        )
        val rent = RentCalculator.calculateRailroadRent(fields[0], fields, ownerId = "p1")
        assertEquals(200, rent)
    }

    @Test
    fun `calculateUtilityRent returns 4x dice for one utility`() {
        val fields = listOf(
            utilityField(id = 1, ownerId = "p1")
        )
        val rent = RentCalculator.calculateUtilityRent(fields[0], fields, ownerId = "p1", diceTotal = 7)
        assertEquals(28, rent)
    }

    @Test
    fun `calculateUtilityRent returns 10x dice for two utilities`() {
        val fields = listOf(
            utilityField(id = 1, ownerId = "p1"),
            utilityField(id = 2, ownerId = "p1")
        )
        val rent = RentCalculator.calculateUtilityRent(fields[0], fields, ownerId = "p1", diceTotal = 7)
        assertEquals(70, rent)
    }

    @Test
    fun `calculateUtilityRent returns 0 when no utilities owned`() {
        val fields = listOf(
            utilityField(id = 1, ownerId = "p2"),
            utilityField(id = 2, ownerId = "p2")
        )
        val rent = RentCalculator.calculateUtilityRent(fields[0], fields, ownerId = "p1", diceTotal = 7)
        assertEquals(0, rent)
    }
}
