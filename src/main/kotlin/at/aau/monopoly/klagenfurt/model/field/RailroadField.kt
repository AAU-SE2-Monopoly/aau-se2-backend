package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType


data class RailroadField(
    override val id: Int,
    override val name: String,
    override val type: FieldType = FieldType.RAILROAD,
    val price: Int = 200,
    var ownerId: String? = null,
    var isMortgaged: Boolean = false
) : Field(id, name, type)

