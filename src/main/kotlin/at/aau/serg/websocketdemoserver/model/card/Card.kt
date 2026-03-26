package at.aau.serg.websocketdemoserver.model.card

import at.aau.serg.websocketdemoserver.model.enums.CardAction
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "cardType")
@JsonSubTypes(
    JsonSubTypes.Type(value = ChanceCard::class,         name = "CHANCE"),
    JsonSubTypes.Type(value = CommunityChestCard::class, name = "COMMUNITY_CHEST"),
)
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
