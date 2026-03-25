package at.aau.serg.websocketdemoserver.websocket.broker

import at.aau.serg.websocketdemoserver.messaging.dtos.StompMessage
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class WebSocketBrokerController {

    @MessageMapping("/hello")
    @SendTo("/topic/hello-response")
    fun handleHello(text: String): String {
        // TODO handle the messages here
        return "echo from broker: $text"
    }

    @MessageMapping("/object")
    @SendTo("/topic/rcv-object")
    fun handleObject(msg: StompMessage): StompMessage {
        return msg
    }
}

