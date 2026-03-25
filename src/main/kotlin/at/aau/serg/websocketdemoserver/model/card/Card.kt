package at.aau.serg.websocketdemoserver.model.card

import at.aau.serg.websocketdemoserver.model.enums.CardAction

abstract class Card(
    open val id: Int,
    open val description: String,
    open val action: CardAction,
    /** Amount of money involved (for COLLECT_MONEY, PAY_MONEY, etc.) */
    open val amount: Int = 0,
    /** Target field index (for MOVE_TO) */
    open val targetFieldId: Int? = null,
    /** Number of spaces to move (for MOVE_FORWARD) */
    open val moveSpaces: Int = 0
)

