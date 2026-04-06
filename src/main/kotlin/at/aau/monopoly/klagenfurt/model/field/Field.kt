package at.aau.monopoly.klagenfurt.model.field

import at.aau.monopoly.klagenfurt.model.enums.FieldType
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = PropertyField::class,       name = "PROPERTY"),
    JsonSubTypes.Type(value = RailroadField::class,       name = "RAILROAD"),
    JsonSubTypes.Type(value = UtilityField::class,        name = "UTILITY"),
    JsonSubTypes.Type(value = TaxField::class,            name = "TAX"),
    JsonSubTypes.Type(value = GoField::class,             name = "GO"),
    JsonSubTypes.Type(value = JailField::class,           name = "JAIL"),
    JsonSubTypes.Type(value = FreeParkingField::class,    name = "FREE_PARKING"),
    JsonSubTypes.Type(value = GoToJailField::class,       name = "GO_TO_JAIL"),
    JsonSubTypes.Type(value = ChanceField::class,         name = "CHANCE"),
    JsonSubTypes.Type(value = CommunityChestField::class, name = "COMMUNITY_CHEST"),
)
abstract class Field(
    open val id: Int,
    open val name: String,
    open val type: FieldType
)
