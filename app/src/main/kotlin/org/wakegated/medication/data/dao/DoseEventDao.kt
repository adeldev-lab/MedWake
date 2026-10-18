package org.wakegated.medication.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.model.DoseState

@Dao
interface DoseEventDao {
    @Query("SELECT * FROM dose_events WHERE scheduledAt >= :startEpochMs AND scheduledAt < :endEpochMs ORDER BY scheduledAt ASC")
    fun getEventsForRangeFlow(startEpochMs: Long, endEpochMs: Long): Flow<List<DoseEventEntity>>

    @Query("SELECT * FROM dose_events WHERE scheduledAt >= :startEpochMs AND scheduledAt < :endEpochMs ORDER BY scheduledAt ASC")
    suspend fun getEventsForRange(startEpochMs: Long, endEpochMs: Long): List<DoseEventEntity>

    @Query("SELECT * FROM dose_events ORDER BY scheduledAt ASC")
    suspend fun getAllList(): List<DoseEventEntity>

    @Query("SELECT * FROM dose_events WHERE state IN ('SCHEDULED', 'DUE', 'RINGING') ORDER BY scheduledAt ASC")
    suspend fun getPendingEvents(): List<DoseEventEntity>

    @Query("SELECT * FROM dose_events WHERE state = 'DUE' ORDER BY scheduledAt ASC")
    suspend fun getDueEvents(): List<DoseEventEntity>

    @Query("SELECT * FROM dose_events WHERE state = 'RINGING' ORDER BY scheduledAt ASC")
    suspend fun getRingingEvents(): List<DoseEventEntity>

    @Query("SELECT * FROM dose_events WHERE doseEventId = :id")
    suspend fun getById(id: Long): DoseEventEntity?

    @Query("SELECT * FROM dose_events WHERE medicationId = :medicationId AND scheduledAt = :scheduledAt LIMIT 1")
    suspend fun getByMedicationAndSchedule(medicationId: Long, scheduledAt: Long): DoseEventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(event: DoseEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DoseEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<DoseEventEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReturningId(event: DoseEventEntity): Long

    @Update
    suspend fun update(event: DoseEventEntity)

    @Query("UPDATE dose_events SET state = :newState WHERE doseEventId = :id")
    suspend fun updateState(id: Long, newState: DoseState)

    @Query("UPDATE dose_events SET state = :newState, actionTimestamp = :actionTimestamp WHERE doseEventId = :id")
    suspend fun resolveEvent(id: Long, newState: DoseState, actionTimestamp: Long)

    @Query("UPDATE dose_events SET plannedTriggerAt = :plannedTriggerAt WHERE doseEventId = :id")
    suspend fun updatePlannedTrigger(id: Long, plannedTriggerAt: Long?)

    @Query("UPDATE dose_events SET stockHandled = 1 WHERE doseEventId = :id")
    suspend fun markStockHandled(id: Long)

    @Query("DELETE FROM dose_events WHERE doseEventId = :id")
    suspend fun deleteDoseEvent(id: Long)

    @Query("DELETE FROM dose_events WHERE medicationId = :medicationId AND state IN ('SCHEDULED','DUE')")
    suspend fun deletePendingForMedication(medicationId: Long)

    @Query("DELETE FROM dose_events")
    suspend fun deleteAll()

    @Query("SELECT * FROM dose_events ORDER BY scheduledAt DESC LIMIT :limit")
    fun getRecentHistoryFlow(limit: Int = 100): Flow<List<DoseEventEntity>>
}
