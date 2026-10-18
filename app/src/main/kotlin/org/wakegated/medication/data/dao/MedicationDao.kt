package org.wakegated.medication.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.wakegated.medication.data.entity.MedicationEntity

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications ORDER BY name ASC")
    fun getAllFlow(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications ORDER BY name ASC")
    suspend fun getAllList(): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE isActive = 1 ORDER BY name ASC")
    fun getActiveFlow(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE isActive = 1")
    suspend fun getActiveList(): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE medicationId = :id")
    suspend fun getById(id: Long): MedicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medication: MedicationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medications: List<MedicationEntity>)

    @Update
    suspend fun update(medication: MedicationEntity)

    @Delete
    suspend fun delete(medication: MedicationEntity)

    @Query("DELETE FROM medications")
    suspend fun deleteAll()
}
