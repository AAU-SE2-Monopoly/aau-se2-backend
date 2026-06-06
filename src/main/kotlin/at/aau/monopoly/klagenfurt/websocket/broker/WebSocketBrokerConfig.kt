package at.aau.monopoly.klagenfurt.websocket.broker

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketBrokerConfig : WebSocketMessageBrokerConfigurer {

    @Bean
    fun servletServerContainerFactoryBean(): org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean {
        val container = org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean()
        container.setMaxTextMessageBufferSize(1024 * 1024) // 1MB
        container.setMaxSessionIdleTimeout(20000L)
        return container
    }

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins("*")
    }

    override fun configureWebSocketTransport(registry: org.springframework.web.socket.config.annotation.WebSocketTransportRegistration) {
        registry.setMessageSizeLimit(1024 * 1024) // 1MB
        registry.setSendBufferSizeLimit(1024 * 1024) // 1MB
        registry.setSendTimeLimit(20000)
    }
}
