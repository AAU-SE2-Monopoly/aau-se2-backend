package at.aau.serg.websocketdemoserver.model.field

import at.aau.serg.websocketdemoserver.model.enums.FieldType

data class FreeParkingField(
    override val id: Int = 20,
    override val name: String = "Free Parking",
    override val type: FieldType = FieldType.FREE_PARKING
) : Field(id, name, type)

