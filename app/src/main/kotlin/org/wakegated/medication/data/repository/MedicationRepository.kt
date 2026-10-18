package org.wakegated.medication.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.wakegated.medication.data.database.AppDatabase
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.data.entity.ScheduleEntity
import org.wakegated.medication.data.model.DoseState

class MedicationRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val medicationDao = database.medicationDao()
    private val scheduleDao = database.scheduleDao()
    private val doseEventDao = database.doseEventDao()

    fun getAllMedicationsFlow(): Flow<List<MedicationEntity>> = medicationDao.getAllFlow()
    fun getActiveMedicationsFlow(): Flow<List<MedicationEntity>> = medicationDao.getActiveFlow()
    suspend fun getActiveMedications(): List<MedicationEntity> = medicationDao.getActiveList()
    suspend fun getAllMedications(): List<MedicationEntity> = medicationDao.getAllList()
    suspend fun getMedicationById(id: Long): MedicationEntity? = medicationDao.getById(id)

    suspend fun insertMedicationWithSchedules(
        medication: MedicationEntity,
        schedules: List<ScheduleEntity>
    ): Long {
        val medId = medicationDao.insert(medication)
        val preparedSchedules = schedules.map { it.copy(medicationId = medId) }
        scheduleDao.insertAll(preparedSchedules)
        return medId
    }

    suspend fun insertMedications(medications: List<MedicationEntity>) = medicationDao.insertAll(medications)
    suspend fun insertDoseEvents(events: List<DoseEventEntity>) = doseEventDao.insertAll(events)
    suspend fun insertScheduleReturningId(schedule: ScheduleEntity): Long = scheduleDao.insertReturningId(schedule)

    suspend fun insertDoseEventReturningId(event: DoseEventEntity): Long = doseEventDao.insertReturningId(event)

    suspend fun updateMedication(medication: MedicationEntity) = medicationDao.update(medication)
    suspend fun deleteMedication(medication: MedicationEntity) = medicationDao.delete(medication)

    fun getSchedulesForMedicationFlow(medicationId: Long): Flow<List<ScheduleEntity>> =
        scheduleDao.getSchedulesForMedicationFlow(medicationId)

    suspend fun getSchedulesForMedication(medicationId: Long): List<ScheduleEntity> =
        scheduleDao.getSchedulesForMedication(medicationId)

    suspend fun getAllSchedules(): List<ScheduleEntity> = scheduleDao.getAllSchedules()

    suspend fun upsertSchedule(schedule: ScheduleEntity) = scheduleDao.insert(schedule)

    fun getEventsForRangeFlow(startEpochMs: Long, endEpochMs: Long): Flow<List<DoseEventEntity>> =
        doseEventDao.getEventsForRangeFlow(startEpochMs, endEpochMs)

    suspend fun getEventsForRange(startEpochMs: Long, endEpochMs: Long): List<DoseEventEntity> =
        doseEventDao.getEventsForRange(startEpochMs, endEpochMs)

    suspend fun getPendingEvents(): List<DoseEventEntity> = doseEventDao.getPendingEvents()

    suspend fun getDueEvents(): List<DoseEventEntity> = doseEventDao.getDueEvents()
    suspend fun getRingingEvents(): List<DoseEventEntity> = doseEventDao.getRingingEvents()
    suspend fun getDoseEventById(id: Long): DoseEventEntity? = doseEventDao.getById(id)

    suspend fun getAllDoseEvents(): List<DoseEventEntity> = doseEventDao.getAllList()

    suspend fun insertDoseEventIfNotExists(event: DoseEventEntity): Long =
        doseEventDao.insertIfNotExists(event)

    suspend fun updateDoseEvent(event: DoseEventEntity) = doseEventDao.update(event)
    suspend fun updateDoseEventState(id: Long, state: DoseState) =
        doseEventDao.updateState(id, state)

    suspend fun resolveDoseEvent(id: Long, state: DoseState, actionTimestamp: Long) {
        val event = doseEventDao.getById(id)
        doseEventDao.resolveEvent(id, state, actionTimestamp)

        // Handle stock decrement if state is TAKEN
        if (state == DoseState.TAKEN && event != null && !event.stockHandled) {
            val med = medicationDao.getById(event.medicationId)
            if (med != null && med.stockQuantity != null && med.stockQuantity > 0.0) {
                val updatedStock = (med.stockQuantity - 1.0).coerceAtLeast(0.0)
                medicationDao.update(med.copy(stockQuantity = updatedStock))
                doseEventDao.markStockHandled(id)
            }
        }
    }

    suspend fun updatePlannedTrigger(id: Long, plannedTriggerAt: Long?) =
        doseEventDao.updatePlannedTrigger(id, plannedTriggerAt)

    suspend fun deleteDoseEvent(id: Long) = doseEventDao.deleteDoseEvent(id)

    suspend fun deletePendingEventsForMedication(medicationId: Long) =
        doseEventDao.deletePendingForMedication(medicationId)

    fun getRecentHistoryFlow(limit: Int = 100): Flow<List<DoseEventEntity>> =
        doseEventDao.getRecentHistoryFlow(limit)

    suspend fun clearAll() {
        doseEventDao.deleteAll()
        scheduleDao.deleteAll()
        medicationDao.deleteAll()
    }
}
