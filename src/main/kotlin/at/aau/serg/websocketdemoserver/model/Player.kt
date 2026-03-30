package at.aau.serg.websocketdemoserver.model

import at.aau.serg.websocketdemoserver.model.enums.PlayerToken
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

data class Player(
    val id: String,
    var name: String,
    val playerToken: PlayerToken? = null,
    @JsonSetter(nulls = Nulls.SKIP) var position: Int = 0,
    @JsonSetter(nulls = Nulls.SKIP) var money: Int = 1500,
    @JsonSetter(nulls = Nulls.SKIP) var inJail: Boolean = false,
    @JsonSetter(nulls = Nulls.SKIP) var jailTurns: Int = 0,
    @JsonSetter(nulls = Nulls.SKIP) var getOutOfJailCards: Int = 0,
    @JsonSetter(nulls = Nulls.SKIP) val ownedPropertyIds: MutableList<Int> = mutableListOf()
) {
    /** Returns true if the player is bankrupt (no money and no properties). */
    fun isBankrupt(): Boolean = money <= 0 && ownedPropertyIds.isEmpty()
}

