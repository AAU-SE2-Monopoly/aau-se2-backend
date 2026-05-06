package at.aau.monopoly.klagenfurt.messaging.dtos

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Inbound DTO – sent by a client to perform an action in the game.
 */
data class GameAction(
    @JsonProperty("gameId") var gameId: String = "",
    @JsonProperty("playerId") var playerId: String = "",
    @JsonProperty("action") var action: String = "",

    // HIER IST DER FIX: var statt val und mutableMapOf statt emptyMap
    @JsonProperty("payload") var payload: MutableMap<String, String> = mutableMapOf()
)