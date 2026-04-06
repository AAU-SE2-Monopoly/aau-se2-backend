package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType
import at.aau.monopoly.klagenfurt.model.enums.PropertyColor


data class PropertyField(
    override val id: Int,
    override val name: String,
    override val type: FieldType = FieldType.PROPERTY,
    val color: PropertyColor,
    val price: Int,
    /** rent[0] = no houses, rent[1] = 1 house, …, rent[5] = hotel */
    val rent: List<Int>,
    val houseCost: Int,
    val hotelCost: Int,
    var ownerId: String? = null,
    var houses: Int = 0,
    var hasHotel: Boolean = false,
    var isMortgaged: Boolean = false
) : Field(id, name, type)

