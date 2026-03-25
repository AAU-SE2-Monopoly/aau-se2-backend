package at.aau.serg.websocketdemoserver

import at.aau.serg.websocketdemoserver.messaging.dtos.StompMessage
import at.aau.serg.websocketdemoserver.websocket.StompFrameHandlerClientImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.converter.MessageConverter
import org.springframework.messaging.converter.StringMessageConverter
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

@ExtendWith(SpringExtension::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketBrokerIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val websocketUri = "ws://localhost:%d/websocket-example-broker"
    private val websocketTopic = "/topic/hello-response"
    private val websocketTopicObject = "/topic/rcv-object"

    @Test
    fun testWebSocketMessageBroker() {
        val messages: BlockingQueue<String> = LinkedBlockingDeque()
        val session = initStompSession(websocketTopic, StringMessageConverter(), messages, String::class.java)

        // send a message to the server
        val message = "Test message"
        session.send("/app/hello", message)

        val expectedResponse = "echo from broker: $message"
        assertThat(messages.poll(1, TimeUnit.SECONDS)).isEqualTo(expectedResponse)
    }

    @Test
    fun testWebSocketMessageBrokerHandleObject() {
        val messages: BlockingQueue<StompMessage> = LinkedBlockingDeque()
        val session = initStompSession(websocketTopicObject, JacksonJsonMessageConverter(), messages, StompMessage::class.java)

        // send a message object to the server
        val message = StompMessage("client", "Test Object Message")
        session.send("/app/object", message)

        assertThat(messages.poll(1, TimeUnit.SECONDS)).isEqualTo(message)
    }

    /**
     * @return The Stomp session for the WebSocket connection (Stomp - WebSocket is comparable to HTTP - TCP).
     */
    fun <T> initStompSession(
        destination: String,
        messageConverter: MessageConverter,
        queue: BlockingQueue<T>,
        expectedType: Class<T>
    ): StompSession {
        val stompClient = WebSocketStompClient(StandardWebSocketClient())
        stompClient.messageConverter = messageConverter

        // connect client to the websocket server
        val session = stompClient.connectAsync(
            String.format(websocketUri, port),
            object : StompSessionHandlerAdapter() {}
        )
            // wait 1 sec for the client to be connected
            .get(1, TimeUnit.SECONDS)

        // subscribes to the topic defined in WebSocketBrokerController
        // and adds received messages to WebSocketBrokerIntegrationTest#messages
        session.subscribe(destination, StompFrameHandlerClientImpl(queue, expectedType))

        return session
    }
}

