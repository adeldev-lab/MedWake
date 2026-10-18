package org.wakegated.medication.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true)
    val medicationId: Long = 0,
    val name: String,
    val dosage: String = "",
    val unit: String = "",
    val notes: String = "",
    val wakeGated: Boolean = true,
    val wakeThresholdMinutes: Int = 420, // 07:00 AM (7 * 60)
    val soundUri: String = "",
    val soundTitle: String = "Default Alarm",
    val vibrate: Boolean = true,
    val stockQuantity: Double? = null,
    val color: Int = 0xFF006C50.toInt(),
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
