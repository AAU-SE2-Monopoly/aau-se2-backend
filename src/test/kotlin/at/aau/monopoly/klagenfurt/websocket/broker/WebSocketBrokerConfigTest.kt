package at.aau.monopoly.klagenfurt.websocket.broker

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.config.SimpleBrokerRegistration
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration

class WebSocketBrokerConfigTest {

    @Test
    fun `configureMessageBroker should configure topic broker and app prefix`() {
        val config = WebSocketBrokerConfig()
        val registry = Mockito.mock(MessageBrokerRegistry::class.java)
        val brokerRegistration = Mockito.mock(SimpleBrokerRegistration::class.java)
        Mockito.`when`(registry.enableSimpleBroker("/topic")).thenReturn(brokerRegistration)

        config.configureMessageBroker(registry)

        Mockito.verify(registry).enableSimpleBroker("/topic")
        Mockito.verify(registry).setApplicationDestinationPrefixes("/app")
    }

    @Test
    fun `registerStompEndpoints should register websocket endpoint`() {
        val config = WebSocketBrokerConfig()
        val registry = Mockito.mock(StompEndpointRegistry::class.java)
        val endpointRegistration = Mockito.mock(StompWebSocketEndpointRegistration::class.java)
        Mockito.`when`(registry.addEndpoint("/ws")).thenReturn(endpointRegistration)
        Mockito.`when`(endpointRegistration.setAllowedOrigins("*")).thenReturn(endpointRegistration)

        config.registerStompEndpoints(registry)

        Mockito.verify(registry).addEndpoint("/ws")
        Mockito.verify(endpointRegistration).setAllowedOrigins("*")
    }
}
