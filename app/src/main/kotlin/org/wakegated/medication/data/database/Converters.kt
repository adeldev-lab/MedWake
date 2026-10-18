package org.wakegated.medication.data.database

import androidx.room.TypeConverter
import org.wakegated.medication.data.model.DoseState

class Converters {
    @TypeConverter
    fun fromDoseState(state: DoseState): String = state.name

    @TypeConverter
    fun toDoseState(value: String): DoseState = try {
        DoseState.valueOf(value)
    } catch (e: Exception) {
        DoseState.SCHEDULED
    }
}
