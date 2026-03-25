package at.aau.serg.websocketdemoserver.model.field

import at.aau.serg.websocketdemoserver.model.enums.FieldType

data class TaxField(
    override val id: Int,
    override val name: String,
    override val type: FieldType = FieldType.TAX,
    val amount: Int
) : Field(id, name, type)

