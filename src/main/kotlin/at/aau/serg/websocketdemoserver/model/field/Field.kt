package at.aau.serg.websocketdemoserver.model.field

import at.aau.serg.websocketdemoserver.model.enums.FieldType

abstract class Field(
    open val id: Int,
    open val name: String,
    open val type: FieldType
)

