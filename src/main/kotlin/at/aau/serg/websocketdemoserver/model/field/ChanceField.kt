package at.aau.serg.websocketdemoserver.model.field

import at.aau.serg.websocketdemoserver.model.enums.FieldType

data class ChanceField(
    override val id: Int,
    override val name: String = "Chance",
    override val type: FieldType = FieldType.CHANCE
) : Field(id, name, type)

