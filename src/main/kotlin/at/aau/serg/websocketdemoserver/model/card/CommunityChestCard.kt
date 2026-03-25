package at.aau.serg.websocketdemoserver.model.card

import at.aau.serg.websocketdemoserver.model.enums.CardAction

data class CommunityChestCard(
    override val id: Int,
    override val description: String,
    override val action: CardAction,
    override val amount: Int = 0,
    override val targetFieldId: Int? = null,
    override val moveSpaces: Int = 0
) : Card(id, description, action, amount, targetFieldId, moveSpaces)

