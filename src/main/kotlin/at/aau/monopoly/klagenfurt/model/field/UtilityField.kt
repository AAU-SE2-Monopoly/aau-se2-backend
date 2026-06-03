package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType

data class UtilityField(
    override val id: Int,
    override val name: String,
    override val type: FieldType = FieldType.UTILITY,
    val price: Int = 150,
    override var ownerId: String? = null,
    override var isMortgaged: Boolean = false
) : Field(id, name, type), OwnableField

