package at.aau.serg.websocketdemoserver.model.field

import at.aau.serg.websocketdemoserver.model.enums.FieldType

data class JailField(
    override val id: Int = 10,
    override val name: String = "Jail / Just Visiting",
    override val type: FieldType = FieldType.JAIL
) : Field(id, name, type)

