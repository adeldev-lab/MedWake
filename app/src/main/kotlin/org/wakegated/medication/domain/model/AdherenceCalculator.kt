package org.wakegated.medication.domain.model

import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.model.DoseState
import java.util.Calendar

enum class DayAdherenceLevel {
    EMPTY,       // No scheduled doses
    PERFECT,     // 100% taken (all doses taken)
    PARTIAL,     // Partial (some taken, none skipped)
    SKIPPED      // Has skipped or missed doses
}

data class MonthAdherenceSummary(
    val year: Int,
    val month: Int, // 0-based (0 = Jan, 7 = Aug, etc.)
    val totalTaken: Int,
    val totalSkipped: Int,
    val adherencePercentage: Int,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val dayStatusMap: Map<Int, DayAdherenceLevel> // Day of month (1..31) -> Status
)

object AdherenceCalculator {

    fun computeMonthSummary(
        year: Int,
        month: Int,
        allEvents: List<DoseEventEntity>
    ): MonthAdherenceSummary {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val monthStartMs = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, daysInMonth)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val monthEndMs = cal.timeInMillis

        // Group events in this month by day of month (1..daysInMonth)
        val monthEvents = allEvents.filter { it.scheduledAt in monthStartMs..monthEndMs }
        val dayGrouped = monthEvents.groupBy { event ->
            val eventCal = Calendar.getInstance().apply { timeInMillis = event.scheduledAt }
            eventCal.get(Calendar.DAY_OF_MONTH)
        }

        val statusMap = mutableMapOf<Int, DayAdherenceLevel>()
        var monthTaken = 0
        var monthSkipped = 0

        val todayCal = Calendar.getInstance()
        val isCurrentMonth = (todayCal.get(Calendar.YEAR) == year && todayCal.get(Calendar.MONTH) == month)
        val currentDayOfMonth = if (isCurrentMonth) todayCal.get(Calendar.DAY_OF_MONTH) else if (year < todayCal.get(Calendar.YEAR) || (year == todayCal.get(Calendar.YEAR) && month < todayCal.get(Calendar.MONTH))) daysInMonth else 0

        for (day in 1..daysInMonth) {
            val events = dayGrouped[day] ?: emptyList()
            val taken = events.count { it.state == DoseState.TAKEN }
            val skipped = events.count { it.state == DoseState.SKIPPED || it.state == DoseState.MISSED }
            val total = events.size

            monthTaken += taken
            monthSkipped += skipped

            val status = when {
                total == 0 -> DayAdherenceLevel.EMPTY
                skipped > 0 -> DayAdherenceLevel.SKIPPED
                taken == total -> DayAdherenceLevel.PERFECT
                taken > 0 -> DayAdherenceLevel.PARTIAL
                else -> DayAdherenceLevel.EMPTY
            }
            statusMap[day] = status
        }

        val totalResolved = monthTaken + monthSkipped
        val percentage = if (totalResolved > 0) {
            (monthTaken.toDouble() / totalResolved * 100).toInt()
        } else {
            100
        }

        // Compute Streaks (consecutive days of PERFECT or EMPTY adherence)
        var currentStreak = 0
        var bestStreak = 0
        var tempStreak = 0

        // For streak, iterate through all past days up to today
        val checkLimit = if (isCurrentMonth) currentDayOfMonth else daysInMonth
        for (day in 1..checkLimit) {
            val status = statusMap[day] ?: DayAdherenceLevel.EMPTY
            if (status == DayAdherenceLevel.PERFECT) {
                tempStreak++
                if (tempStreak > bestStreak) bestStreak = tempStreak
            } else if (status == DayAdherenceLevel.SKIPPED) {
                tempStreak = 0
            }
        }
        currentStreak = tempStreak

        return MonthAdherenceSummary(
            year = year,
            month = month,
            totalTaken = monthTaken,
            totalSkipped = monthSkipped,
            adherencePercentage = percentage,
            currentStreakDays = currentStreak,
            bestStreakDays = bestStreak,
            dayStatusMap = statusMap
        )
    }
}
