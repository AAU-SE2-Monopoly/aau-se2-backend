package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType

data class GoField(
    override val id: Int = 0,
    override val name: String = "Go",
    override val type: FieldType = FieldType.GO,
    val salary: Int = 200
) : Field(id, name, type)

