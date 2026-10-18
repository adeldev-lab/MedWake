package org.wakegated.medication.domain.model

import java.util.Calendar

object ScheduleCalculator {

    const val PRESET_EVERYDAY = 127      // 0b1111111 (Mon-Sun)
    const val PRESET_MWF_SUN = 85        // 0b1010101 (Mon, Wed, Fri, Sun)
    const val PRESET_TTS = 42            // 0b0101010 (Tue, Thu, Sat)
    const val PRESET_WEEKDAYS = 31       // 0b0011111 (Mon-Fri)
    const val PRESET_WEEKENDS = 96       // 0b1100000 (Sat-Sun)

    /**
     * Checks whether a schedule matches a given calendar day.
     * daysOfWeek is a bitmask where:
     * Mon = 1 (1<<0)
     * Tue = 2 (1<<1)
     * Wed = 4 (1<<2)
     * Thu = 8 (1<<3)
     * Fri = 16 (1<<4)
     * Sat = 32 (1<<5)
     * Sun = 64 (1<<6)
     */
    fun isScheduleActiveOnDay(daysOfWeekBitmask: Int, calendar: Calendar): Boolean {
        val calDay = calendar.get(Calendar.DAY_OF_WEEK)
        val bit = when (calDay) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 4
            Calendar.THURSDAY -> 8
            Calendar.FRIDAY -> 16
            Calendar.SATURDAY -> 32
            Calendar.SUNDAY -> 64
            else -> 1
        }
        return (daysOfWeekBitmask and bit) != 0
    }

    fun formatDaysSummary(bitmask: Int): String {
        return when (bitmask) {
            PRESET_EVERYDAY -> "Every day"
            PRESET_MWF_SUN -> "Mon, Wed, Fri, Sun"
            PRESET_TTS -> "Tue, Thu, Sat"
            PRESET_WEEKDAYS -> "Weekdays (Mon-Fri)"
            PRESET_WEEKENDS -> "Weekends (Sat-Sun)"
            0 -> "No active days"
            else -> {
                val days = mutableListOf<String>()
                if ((bitmask and 1) != 0) days.add("Mon")
                if ((bitmask and 2) != 0) days.add("Tue")
                if ((bitmask and 4) != 0) days.add("Wed")
                if ((bitmask and 8) != 0) days.add("Thu")
                if ((bitmask and 16) != 0) days.add("Fri")
                if ((bitmask and 32) != 0) days.add("Sat")
                if ((bitmask and 64) != 0) days.add("Sun")
                days.joinToString(", ")
            }
        }
    }

    /**
     * Calculates the exact epoch millis for a given day and minute of day (0..1439).
     */
    fun calculateOccurrenceMillis(calendar: Calendar, timeInMinutes: Int): Long {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, timeInMinutes / 60)
        cal.set(Calendar.MINUTE, timeInMinutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
