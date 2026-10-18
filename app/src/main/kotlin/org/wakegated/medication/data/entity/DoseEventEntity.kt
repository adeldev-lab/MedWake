package org.wakegated.medication.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.wakegated.medication.data.model.DoseState

@Entity(
    tableName = "dose_events",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["medicationId"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["medicationId", "scheduledAt"], unique = true),
        Index(value = ["scheduledAt"]),
        Index(value = ["state"])
    ]
)
data class DoseEventEntity(
    @PrimaryKey(autoGenerate = true)
    val doseEventId: Long = 0,
    val medicationId: Long,
    val scheduledAt: Long, // Epoch ms of intended dose time
    val dueAt: Long, // Epoch ms when it transitions to DUE
    val plannedTriggerAt: Long? = null, // Epoch ms for delayed unlock trigger (0-15s)
    val actionTimestamp: Long? = null, // Epoch ms when Taken/Skipped was confirmed
    val state: DoseState = DoseState.SCHEDULED,
    val rescheduledFromId: Long? = null,
    val stockHandled: Boolean = false,
    val notes: String = ""
)
