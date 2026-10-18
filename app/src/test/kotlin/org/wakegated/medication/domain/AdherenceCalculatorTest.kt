package org.wakegated.medication.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.model.DoseState
import org.wakegated.medication.domain.model.AdherenceCalculator
import org.wakegated.medication.domain.model.DayAdherenceLevel
import java.util.Calendar

class AdherenceCalculatorTest {

    @Test
    fun testMonthAdherenceSummaryAndStreaks() {
        val year = 2026
        val month = Calendar.AUGUST // Month 7 (0-based)

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val events = mutableListOf<DoseEventEntity>()

        // Day 1 to 5: TAKEN (5-day streak)
        for (day in 1..5) {
            cal.set(Calendar.DAY_OF_MONTH, day)
            events.add(
                DoseEventEntity(
                    doseEventId = day.toLong(),
                    medicationId = 1,
                    scheduledAt = cal.timeInMillis,
                    dueAt = cal.timeInMillis,
                    state = DoseState.TAKEN
                )
            )
        }

        // Day 6: SKIPPED (breaks streak)
        cal.set(Calendar.DAY_OF_MONTH, 6)
        events.add(
            DoseEventEntity(
                doseEventId = 6,
                medicationId = 1,
                scheduledAt = cal.timeInMillis,
                dueAt = cal.timeInMillis,
                state = DoseState.SKIPPED
            )
        )

        // Day 7 to 9: TAKEN (3-day streak)
        for (day in 7..9) {
            cal.set(Calendar.DAY_OF_MONTH, day)
            events.add(
                DoseEventEntity(
                    doseEventId = day.toLong(),
                    medicationId = 1,
                    scheduledAt = cal.timeInMillis,
                    dueAt = cal.timeInMillis,
                    state = DoseState.TAKEN
                )
            )
        }

        val summary = AdherenceCalculator.computeMonthSummary(year, month, events)

        assertEquals(8, summary.totalTaken)
        assertEquals(1, summary.totalSkipped)
        assertEquals(88, summary.adherencePercentage) // 8 / 9 = 88.8% -> 88%
        assertEquals(5, summary.bestStreakDays) // Best streak was days 1..5
        assertEquals(DayAdherenceLevel.PERFECT, summary.dayStatusMap[1])
        assertEquals(DayAdherenceLevel.SKIPPED, summary.dayStatusMap[6])
        assertEquals(DayAdherenceLevel.EMPTY, summary.dayStatusMap[10])
    }
}
