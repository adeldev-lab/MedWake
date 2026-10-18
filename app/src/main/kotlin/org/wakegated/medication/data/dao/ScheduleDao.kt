package org.wakegated.medication.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.wakegated.medication.data.entity.ScheduleEntity

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId")
    fun getSchedulesForMedicationFlow(medicationId: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId")
    suspend fun getSchedulesForMedication(medicationId: Long): List<ScheduleEntity>

    @Query("SELECT * FROM schedules")
    suspend fun getAllSchedules(): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: ScheduleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(schedules: List<ScheduleEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReturningId(schedule: ScheduleEntity): Long

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Delete
    suspend fun delete(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE medicationId = :medicationId")
    suspend fun deleteForMedication(medicationId: Long)

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()
}
