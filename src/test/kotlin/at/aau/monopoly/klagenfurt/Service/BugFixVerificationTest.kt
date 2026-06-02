package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.controller.GameController
import at.aau.monopoly.klagenfurt.messaging.dtos.GameAction
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.enums.PropertyColor
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.websocket.broker.WebSocketBrokerController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.messaging.simp.SimpMessagingTemplate

class BugFixVerificationTest {

    private fun propertyField(
        id: Int,
        price: Int = 100,
        color: PropertyColor = PropertyColor.BROWN,
        ownerId: String? = null,
        houses: Int = 0,
        hasHotel: Boolean = false,
        houseCost: Int = 50,
        hotelCost: Int = 250
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
    fun `verify Hotel Pricing Bug Fix - charges only houseCost instead of hotelCost`() {
        val messagingTemplate = mock(SimpMessagingTemplate::class.java)
        val gameController = GameController()
        val controller = WebSocketBrokerController(messagingTemplate, gameController)

        val gameState = gameController.createGame("host")
        val player = Player(id = "p1", name = "Alice", money = 300)
        gameController.joinGame(gameState.gameId, player)

        // Setup property with 4 houses
        val prop = propertyField(id = 1, ownerId = "p1", houses = 4, houseCost = 50, hotelCost = 250)

        // Re-inject fields into the created game state
        val gameStateFields = gameState.fields as MutableList<at.aau.monopoly.klagenfurt.model.field.Field>
        gameStateFields[1] = prop

        val playerInList = gameState.players.find { it.id == "p1" }!!
        playerInList.money = 300
        playerInList.ownedPropertyIds.add(1)

        gameState.currentPlayerIndex = gameState.players.indexOf(playerInList)
        gameState.phase = at.aau.monopoly.klagenfurt.model.enums.GamePhase.BUYING
        playerInList.position = 1

        val action = GameAction(
            gameId = gameState.gameId,
            playerId = "p1",
            action = "BUY_HOTEL",
            payload = mutableMapOf("fieldId" to "1")
        )

        // MOCK the check for complete color set if needed, but here it should work if we add siblings
        // Brown set is field 1 and 3
        val prop3 = propertyField(id = 3, ownerId = "p1", houses = 4, houseCost = 50, hotelCost = 250)
        gameStateFields[3] = prop3

        println("BEFORE: Money=${playerInList.money}, HasHotel=${prop.hasHotel}, Phase=${gameState.phase}, CurrentPlayer=${gameState.currentPlayer?.id}, Pos=${playerInList.position}")
        controller.handleAction(action)
        println("AFTER: Money=${playerInList.money}, HasHotel=${prop.hasHotel}, Phase=${gameState.phase}")

        // Alice had 300. House cost is 50. Hotel cost was 250.
        // If bug is fixed, she should have 300 - 50 = 250.
        // If bug is NOT fixed, she would have 300 - 250 = 50.
        assertEquals(250, playerInList.money, "Player should only be charged the houseCost for hotel upgrade. Actual: ${playerInList.money}")
        assertTrue(prop.hasHotel, "Property should now have a hotel")
        assertEquals(0, prop.houses, "Houses should be reset to 0 when hotel is built")
    }

    @Test
    fun `verify Bankruptcy Asset Calculation Fix - includes houses returned from hotel`() {
        val player = Player(id = "p1", name = "Alice", money = 0)
        val prop = propertyField(id = 1, price = 100, ownerId = "p1", hasHotel = true, houseCost = 100, hotelCost = 500)
        val fields = listOf(prop)

        // Asset calculation should be:
        // Mortgage value: 100 / 2 = 50
        // Hotel sell value: 500 / 2 = 250
        // 4 houses returned sell value: 4 * (100 / 2) = 200
        // Total: 50 + 250 + 200 = 500

        val maxCash = PaymentService.calculateMaxRaiseableCash(player, fields)

        // If bug is fixed: 500
        // If bug is NOT fixed: 50 + 250 = 300 (it missed the 4 houses)
        assertEquals(500, maxCash, "Asset calculation should include the value of houses returned from selling a hotel")
    }
}
