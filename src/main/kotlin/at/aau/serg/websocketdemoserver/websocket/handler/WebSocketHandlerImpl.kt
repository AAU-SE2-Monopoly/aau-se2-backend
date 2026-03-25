package at.aau.serg.websocketdemoserver.websocket.handler

import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession

class WebSocketHandlerImpl : WebSocketHandler {

    override fun afterConnectionEstablished(session: WebSocketSession) {
    }

    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        // TODO handle the messages here
        session.sendMessage(TextMessage("echo from handler: ${message.payload}"))
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
    }

    override fun supportsPartialMessages(): Boolean = false
}

