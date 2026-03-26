package at.aau.serg.websocketdemoserver.model.field

import at.aau.serg.websocketdemoserver.model.enums.FieldType

data class RailroadField(
    override val id: Int,
    override val name: String,
    override val type: FieldType = FieldType.RAILROAD,
    val price: Int = 200,
    var ownerId: String? = null,
    var isMortgaged: Boolean = false
) : Field(id, name, type)

