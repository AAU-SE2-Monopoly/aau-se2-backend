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
            else -> 0
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
            else -> 0
        }
        // 10% interest, rounded up
        val cost = ceil(price / 2.0 * 1.1).toInt()
        field.isMortgaged = false
        player.money -= cost
        return player
    }

    fun sellHouse(player: Player, field: PropertyField): Player {
        if (field.houses <= 0) return player
        field.houses -= 1
        player.money += field.houseCost / 2
        return player
    }

    fun sellHotel(player: Player, field: PropertyField): Player {
        if (!field.hasHotel) return player
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
                    is at.aau.monopoly.klagenfurt.model.field.RailroadField -> field.price
                    is at.aau.monopoly.klagenfurt.model.field.UtilityField -> field.price
                    else -> 0
                }
                if (!field.isMortgaged) {
                    total += price / 2
                }
                // House/Hotel sell value for properties
                if (field is PropertyField) {
                    total += field.houses * (field.houseCost / 2)
                    if (field.hasHotel) {
                        total += field.hotelCost / 2
                    }
                }
            }
        }
        return total
    }
}
