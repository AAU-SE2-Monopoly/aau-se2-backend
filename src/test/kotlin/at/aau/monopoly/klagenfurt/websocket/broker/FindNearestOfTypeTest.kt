package at.aau.monopoly.klagenfurt.websocket.broker

import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField
import at.aau.monopoly.klagenfurt.model.BoardFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.messaging.simp.SimpMessagingTemplate

class FindNearestOfTypeTest {
    private val controller = WebSocketBrokerController(
        Mockito.mock(SimpMessagingTemplate::class.java),
        Mockito.mock(at.aau.monopoly.klagenfurt.controller.GameController::class.java)
    )

    @Test
    fun `findNearestOfType returns nearest railroad with wrap`() {
        val fields: List<Field> = BoardFactory.createDefaultBoard()
        val method = WebSocketBrokerController::class.java.getDeclaredMethod(
            "findNearestOfType",
            Int::class.java,
            List::class.java,
            kotlin.jvm.functions.Function1::class.java
        )
        method.isAccessible = true
        val predicate = { field: Field -> field is RailroadField }
        val result = method.invoke(controller, 35, fields, predicate) as Int
        assertEquals(5, result)
    }

    @Test
    fun `findNearestOfType returns nearest utility with wrap`() {
        val fields: List<Field> = BoardFactory.createDefaultBoard()
        val method = WebSocketBrokerController::class.java.getDeclaredMethod(
            "findNearestOfType",
            Int::class.java,
            List::class.java,
            kotlin.jvm.functions.Function1::class.java
        )
        method.isAccessible = true
        val predicate = { field: Field -> field is UtilityField }
        val result = method.invoke(controller, 35, fields, predicate) as Int
        assertEquals(12, result)
    }
}
