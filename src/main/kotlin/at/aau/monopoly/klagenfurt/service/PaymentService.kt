package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.field.Field
import at.aau.monopoly.klagenfurt.model.field.OwnableField
import at.aau.monopoly.klagenfurt.model.field.PropertyField
import at.aau.monopoly.klagenfurt.model.field.RailroadField
import at.aau.monopoly.klagenfurt.model.field.UtilityField
import kotlin.math.ceil

object PaymentService {
    fun mortgageProperty(player: Player, field: OwnableField): Player {
        // Give player half the price (field must be PropertyField or Railroad/Utility with price property)
        val price = when (field) {
            is PropertyField -> field.price
            is RailroadField -> field.price
            is UtilityField -> field.price
            else -> error("Unsupported OwnableField type: ${field::class.simpleName}")
        }
        field.isMortgaged = true
        player.money += price / 2
        return player
    }

    fun unmortgageProperty(player: Player, field: OwnableField): Player {
        val price = when (field) {
            is PropertyField -> field.price
            is RailroadField -> field.price
            is UtilityField -> field.price
            else -> error("Unsupported OwnableField type: ${field::class.simpleName}")
        }
        // 10% interest, rounded up
        val cost = ceil(price / 2.0 * 1.1).toInt()
        field.isMortgaged = false
        player.money -= cost
        return player
    }

    /**
     * Sell one house from [field].
     * Enforces even-building rule — no other property in the color group may
     * have fewer houses than this property would have after the sale. If the rule
     * would be violated, returns [player] unchanged.
     */
    fun sellHouse(player: Player, field: PropertyField, allFields: List<Field>): Player {
        if (field.houses <= 0) return player
        val newHouseCount = field.houses - 1
        // Even building rule: check all other properties in the same color group
        val siblings = allFields.filterIsInstance<PropertyField>()
            .filter { it.color == field.color && it.id != field.id && it.ownerId == player.id }
        // Must sell from the most-developed property: siblings must be in [newCount, current].
        if (siblings.any { it.houses < newHouseCount || it.houses > field.houses }) {
            return player // cannot sell — would violate even building rule
        }
        field.houses -= 1
        player.money += field.houseCost / 2
        return player
    }

    /**
     * Sell the hotel on [field], reverting to 4 houses.
     *  Enforces even-building rule — all other properties in the color group
     * must have at least 4 houses (or a hotel). If the rule would be violated,
     * returns [player] unchanged.
     */
    fun sellHotel(player: Player, field: PropertyField, allFields: List<Field>): Player {
        if (!field.hasHotel) return player
        // Even building rule: all siblings must have at least 4 houses (or hotel)
        val siblings = allFields.filterIsInstance<PropertyField>()
            .filter { it.color == field.color && it.id != field.id && it.ownerId == player.id }
        if (siblings.any { it.houses < 4 && !it.hasHotel }) {
            return player // cannot sell hotel — others don't have enough buildings
        }
        field.hasHotel = false
        field.houses = 4 // revert to 4 houses
        player.money += field.hotelCost / 2
        return player
    }

    fun canPayAfterAssets(player: Player, fields: List<Field>, amount: Int): Boolean {
        val maxCash = calculateMaxRaiseableCash(player, fields)
        return player.money + maxCash >= amount
    }

    fun calculateMaxRaiseableCash(player: Player, fields: List<Field>): Int {
        var total = 0
        for (field in fields) {
            if (field is OwnableField && field.ownerId == player.id) {
                // Mortgage value
                val price = when (field) {
                    is PropertyField -> field.price
                    is RailroadField -> field.price
                    is UtilityField -> field.price
                    else -> 0
                }
                if (!field.isMortgaged) {
                    total += price / 2
                }
                // House/Hotel sell value for properties
                if (field is PropertyField) {
                    total += field.houses * (field.houseCost / 2)
                    if (field.hasHotel) {
                        // A hotel sell-back returns half the hotel cost AND replaces it with 4 houses
                        // which can then also be sold back.
                        total += (field.hotelCost / 2) + (4 * (field.houseCost / 2))
                    }
                }
            }
        }
        return total
    }
}
