package at.aau.serg.websocketdemoserver.websocket

import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.BlockingQueue

class WebSocketHandlerClientImpl(
    private val messagesQueue: BlockingQueue<String>
) : WebSocketHandler {

    override fun afterConnectionEstablished(session: WebSocketSession) {
    }

    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        messagesQueue.add(message.payload as String)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
    }

    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
    }

    override fun supportsPartialMessages(): Boolean = false
}

