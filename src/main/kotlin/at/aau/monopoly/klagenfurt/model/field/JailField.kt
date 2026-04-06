package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType

data class JailField(
    override val id: Int = 10,
    override val name: String = "Jail / Just Visiting",
    override val type: FieldType = FieldType.JAIL
) : Field(id, name, type)

