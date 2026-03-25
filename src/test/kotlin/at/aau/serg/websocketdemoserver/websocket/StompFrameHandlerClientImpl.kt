package at.aau.serg.websocketdemoserver.websocket

import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import java.lang.reflect.Type
import java.util.concurrent.BlockingQueue

class StompFrameHandlerClientImpl<T>(
    private val messagesQueue: BlockingQueue<T>,
    private val payloadType: Class<T>
) : StompFrameHandler {

    override fun getPayloadType(headers: StompHeaders): Type = payloadType

    @Suppress("UNCHECKED_CAST")
    override fun handleFrame(headers: StompHeaders, payload: Any?) {
        // add the new message to the queue of received messages
        messagesQueue.add(payload as T)
    }
}

