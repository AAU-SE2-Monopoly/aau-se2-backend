package at.aau.monopoly.klagenfurt.websocket.broker

import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField
import at.aau.monopoly.klagenfurt.model.BoardFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.messaging.simp.SimpMessagingTemplate
import kotlin.jvm.functions.Function1

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
            Function1::class.java
        )
        method.isAccessible = true
        val predicate: Function1<Field, Boolean> = Function1 { field -> field is RailroadField }
        val result = method.invoke(controller, 35, fields, predicate) as Int
        // In standard Monopoly board, the next railroad after position 35 is at index 5 (wrapping around)
        assertEquals(5, result)
    }

    @Test
    fun `findNearestOfType returns nearest utility with wrap`() {
        val fields: List<Field> = BoardFactory.createDefaultBoard()
        val method = WebSocketBrokerController::class.java.getDeclaredMethod(
            "findNearestOfType",
            Int::class.java,
            List::class.java,
            Function1::class.java
        )
        method.isAccessible = true
        val predicate: Function1<Field, Boolean> = Function1 { field -> field is UtilityField }
        val result = method.invoke(controller, 35, fields, predicate) as Int
        // In standard Monopoly board, the next utility after position 35 is at index 12 (wrapping around)
        assertEquals(12, result)
    }
}
