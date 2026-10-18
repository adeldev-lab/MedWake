package org.wakegated.medication.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["medicationId"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("medicationId")]
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val scheduleId: Long = 0,
    val medicationId: Long,
    val timeInMinutes: Int, // 0 to 1439 (minutes from midnight)
    val daysOfWeek: Int = 0b1111111, // Bitmask: Mon(1), Tue(2), Wed(4), Thu(8), Fri(16), Sat(32), Sun(64)
    val cycleDaysOn: Int = 0,
    val cycleDaysOff: Int = 0,
    val startDate: Long? = null,
    val endDate: Long? = null
)
