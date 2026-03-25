package at.aau.serg.websocketdemoserver.messaging.dtos

data class StompMessage(
    var from: String = "",
    var text: String = ""
)

