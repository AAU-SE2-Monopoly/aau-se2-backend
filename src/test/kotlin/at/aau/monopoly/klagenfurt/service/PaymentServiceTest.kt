package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.enums.PropertyColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaymentServiceTest {

    private fun propertyField(
        id: Int,
        price: Int = 60,
        color: PropertyColor = PropertyColor.BROWN,
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
            rent = listOf(2, 10, 30, 90, 160, 250),
            houseCost = houseCost,
            hotelCost = hotelCost,
            ownerId = ownerId,
            houses = houses,
            hasHotel = hasHotel
        )
    }

    @Test
    fun `mortgageProperty sets isMortgaged true and adds price half`() {
        val player = Player(id = "p1", name = "Alice", money = 500)
        val field = propertyField(id = 1, price = 100, ownerId = "p1")

        PaymentService.mortgageProperty(player, field)

        assertTrue(field.isMortgaged)
        assertEquals(550, player.money)
    }

    @Test
    fun `unmortgageProperty sets isMortgaged false and deducts ceil price half times 1_1`() {
        val player = Player(id = "p1", name = "Alice", money = 200)
        val field = propertyField(id = 1, price = 100, ownerId = "p1")
        field.isMortgaged = true

        PaymentService.unmortgageProperty(player, field)

        assertFalse(field.isMortgaged)
        assertEquals(200 - 56, player.money)
    }

    @Test
    fun `sellHouse decrements count and adds houseCost half`() {
        val player = Player(id = "p1", name = "Alice")
        val field = propertyField(id = 1, price = 60, ownerId = "p1", houses = 1, houseCost = 50)
        val fields = listOf(field)

        val result = PaymentService.sellHouse(player, field, fields)

        assertEquals(0, field.houses)
        assertEquals(1525, player.money)
        assertEquals(player, result)
    }

    @Test
    fun `sellHouse refuses when even building rule violated`() {
        val player = Player(id = "p1", name = "Alice")
        val field1 = propertyField(id = 1, color = PropertyColor.BROWN, ownerId = "p1", houses = 1)
        val field2 = propertyField(id = 2, color = PropertyColor.BROWN, ownerId = "p1", houses = 3)
        val fields = listOf(field1, field2)

        val beforeMoney = player.money
        PaymentService.sellHouse(player, field2, fields)

        assertEquals(3, field2.houses)
        assertEquals(beforeMoney, player.money)
    }

    @Test
    fun `sellHouse returns player unchanged when no houses present`() {
        val player = Player(id = "p1", name = "Alice", money = 300)
        val field = propertyField(id = 1, color = PropertyColor.BROWN, ownerId = "p1", houses = 0)
        val fields = listOf(field)

        val result = PaymentService.sellHouse(player, field, fields)

        assertEquals(0, field.houses)
        assertEquals(300, player.money)
        assertEquals(player, result)
    }

    @Test
    fun `sellHotel sets hasHotel false and reverts to 4 houses`() {
        val player = Player(id = "p1", name = "Alice")
        val field = propertyField(id = 1, color = PropertyColor.BROWN, ownerId = "p1", hasHotel = true, hotelCost = 100)
        val fields = listOf(field)

        PaymentService.sellHotel(player, field, fields)

        assertFalse(field.hasHotel)
        assertEquals(4, field.houses)
        assertEquals(1550, player.money)
    }

    @Test
    fun `sellHotel refuses when sibling has fewer than 4 houses`() {
        val player = Player(id = "p1", name = "Alice")
        val field1 = propertyField(id = 1, color = PropertyColor.BROWN, ownerId = "p1", hasHotel = true)
        val field2 = propertyField(id = 2, color = PropertyColor.BROWN, ownerId = "p1", houses = 3)
        val fields = listOf(field1, field2)

        val beforeMoney = player.money
        PaymentService.sellHotel(player, field1, fields)

        assertTrue(field1.hasHotel)
        assertEquals(0, field1.houses)
        assertEquals(beforeMoney, player.money)
    }

    @Test
    fun `canPayAfterAssets true when cash alone covers amount`() {
        val player = Player(id = "p1", name = "Alice", money = 500)
        val fields = emptyList<at.aau.monopoly.klagenfurt.model.field.Field>()

        assertTrue(PaymentService.canPayAfterAssets(player, fields, 300))
    }

    @Test
    fun `canPayAfterAssets true when liquidating properties covers amount`() {
        val player = Player(id = "p1", name = "Alice", money = 100)
        val field = propertyField(id = 1, price = 400, ownerId = "p1")
        val fields = listOf<at.aau.monopoly.klagenfurt.model.field.Field>(field)

        assertTrue(PaymentService.canPayAfterAssets(player, fields, 300))
    }

    @Test
    fun `canPayAfterAssets false when total assets insufficient`() {
        val player = Player(id = "p1", name = "Alice", money = 50)
        val field = propertyField(id = 1, price = 60, ownerId = "p1")
        val fields = listOf<at.aau.monopoly.klagenfurt.model.field.Field>(field)

        assertFalse(PaymentService.canPayAfterAssets(player, fields, 200))
    }

    @Test
    fun `canPayAfterAssets counts house sell back values correctly`() {
        val player = Player(id = "p1", name = "Alice", money = 50)
        val field = propertyField(id = 1, price = 100, ownerId = "p1", houses = 2, houseCost = 50)
        val fields = listOf<at.aau.monopoly.klagenfurt.model.field.Field>(field)

        assertTrue(PaymentService.canPayAfterAssets(player, fields, 150))
    }

    @Test
    fun `canPayAfterAssets excludes already mortgaged properties`() {
        val player = Player(id = "p1", name = "Alice", money = 10)
        val field = propertyField(id = 1, price = 200, ownerId = "p1")
        field.isMortgaged = true
        val fields = listOf<at.aau.monopoly.klagenfurt.model.field.Field>(field)

        assertFalse(PaymentService.canPayAfterAssets(player, fields, 110))
    }
}
