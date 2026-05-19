package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField

object RentCalculator {
    fun calculatePropertyRent(field: PropertyField): Int {
        return if (field.hasHotel) {
            // Hotel rent is at index 5
            field.rent[5]
        } else {
            // Use number of houses, capped at 4
            val index = field.houses.coerceAtMost(4)
            field.rent[index]
        }
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
