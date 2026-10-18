package org.wakegated.medication.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.wakegated.medication.data.dao.DoseEventDao
import org.wakegated.medication.data.dao.MedicationDao
import org.wakegated.medication.data.dao.ScheduleDao
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.data.entity.ScheduleEntity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MedicationEntity::class,
        ScheduleEntity::class,
        DoseEventEntity::class
    ],
        version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun doseEventDao(): DoseEventDao

       companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Validate tables exist — query() (not execSQL: SELECT is rejected by SQLiteDatabase.execSQL)
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('medications','schedules','dose_events')").use { c ->
                    require(c.count == 3) { "MedWake schema mismatch: expected 3 tables" }
                }
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medwake_database.db"
                                ).addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
