package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField

object RentCalculator {

    /**
     * Calculate rent for a property field.
     * If the owner has a monopoly (owns all properties of this color group) and the
     * property is unimproved (0 houses, no hotel), rent is doubled per official rules.
     */
    fun calculatePropertyRent(field: PropertyField, allFields: List<Field>, ownerId: String): Int {
        return if (field.hasHotel) {
            // Hotel rent is at index 5
            field.rent[5]
        } else {
            val index = field.houses.coerceAtMost(4)
            val baseRent = field.rent[index]
            //  double rent on unimproved properties in a monopoly
            if (field.houses == 0 && !field.hasHotel && isMonopoly(field, allFields, ownerId)) {
                baseRent * 2
            } else {
                baseRent
            }
        }
    }

    /**
     * Returns true if [ownerId] owns every PropertyField in [field]'s color group.
     */
    private fun isMonopoly(field: PropertyField, allFields: List<Field>, ownerId: String): Boolean {
        val colorGroupProperties = allFields.filterIsInstance<PropertyField>()
            .filter { it.color == field.color }
        return colorGroupProperties.isNotEmpty() && colorGroupProperties.all { it.ownerId == ownerId }
    }

    fun calculateRailroadRent(field: RailroadField, allFields: List<Field>, ownerId: String): Int {
        val ownedCount = allFields.filterIsInstance<RailroadField>()
            .count { it.ownerId == ownerId }
        if (ownedCount == 0) return 0
        // Rent doubles with each additional railroad, starting at 25
        val rent = 25 * (1 shl (ownedCount - 1))
        return if (rent > 200) 200 else rent
    }

    fun calculateUtilityRent(field: UtilityField, allFields: List<Field>, ownerId: String, diceTotal: Int): Int {
        val ownedCount = allFields.filterIsInstance<UtilityField>()
            .count { it.ownerId == ownerId }
        return when (ownedCount) {
            0 -> 0
            1 -> 4 * diceTotal
            else -> 10 * diceTotal
        }
    }
}
