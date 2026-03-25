package at.aau.serg.websocketdemoserver.model

data class Player(
    val id: String,
    var name: String,
    var position: Int = 0,
    var money: Int = 1500,
    var inJail: Boolean = false,
    var jailTurns: Int = 0,
    var getOutOfJailCards: Int = 0,
    val ownedPropertyIds: MutableList<Int> = mutableListOf()
) {
    /** Returns true if the player is bankrupt (no money and no properties). */
    fun isBankrupt(): Boolean = money <= 0 && ownedPropertyIds.isEmpty()
}

