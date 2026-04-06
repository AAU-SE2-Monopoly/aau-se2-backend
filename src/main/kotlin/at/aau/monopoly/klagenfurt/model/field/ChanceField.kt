package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType

data class ChanceField(
    override val id: Int,
    override val name: String = "Chance",
    override val type: FieldType = FieldType.CHANCE
) : Field(id, name, type)

