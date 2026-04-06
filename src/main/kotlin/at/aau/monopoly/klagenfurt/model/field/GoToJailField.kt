package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType


data class GoToJailField(
    override val id: Int = 30,
    override val name: String = "Go To Jail",
    override val type: FieldType = FieldType.GO_TO_JAIL
) : Field(id, name, type)

