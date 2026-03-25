package at.aau.serg.websocketdemoserver

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WebSocketDemoServerApplication

fun main(args: Array<String>) {
    runApplication<WebSocketDemoServerApplication>(*args)
}

