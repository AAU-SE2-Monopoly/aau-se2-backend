package at.aau.monopoly.klagenfurt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class WebSocketDemoServerApplication

fun main(args: Array<String>) {
    runApplication<at.aau.monopoly.klagenfurt.WebSocketDemoServerApplication>(*args)
}

