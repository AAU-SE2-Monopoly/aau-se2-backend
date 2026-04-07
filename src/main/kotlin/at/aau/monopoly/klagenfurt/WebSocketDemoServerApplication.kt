package at.aau.monopoly.klagenfurt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WebSocketDemoServerApplication

fun main(args: Array<String>) {
    runApplication<at.aau.monopoly.klagenfurt.WebSocketDemoServerApplication>(*args)
}

