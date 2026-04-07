package at.aau.monopoly.klagenfurt.model

/** Represents the result of a single dice roll (two six-sided dice). */
data class DiceRoll(
    val die1: Int,
    val die2: Int
) {
    val total: Int get() = die1 + die2
    val isDouble: Boolean get() = die1 == die2
}

