package at.aau.serg.websocketdemoserver.messaging.dtos

data class OutputMessage(
    val from: String,
    val text: String,
    val time: String
)

