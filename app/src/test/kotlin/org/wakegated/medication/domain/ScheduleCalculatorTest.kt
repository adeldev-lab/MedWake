package org.wakegated.medication.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wakegated.medication.domain.model.ScheduleCalculator
import java.util.Calendar

class ScheduleCalculatorTest {

    @Test
    fun testEverydayScheduleActive() {
        val everydayBitmask = ScheduleCalculator.PRESET_EVERYDAY
        val calendar = Calendar.getInstance()

        for (day in Calendar.SUNDAY..Calendar.SATURDAY) {
            calendar.set(Calendar.DAY_OF_WEEK, day)
            assertTrue(
                "Everyday schedule should be active on day $day",
                ScheduleCalculator.isScheduleActiveOnDay(everydayBitmask, calendar)
            )
        }
    }

    @Test
    fun testAlternatingMwfSunVsTtsSchedule() {
        // MWF + Sun = 1 + 4 + 16 + 64 = 85
        val mwfSunBitmask = ScheduleCalculator.PRESET_MWF_SUN
        // TTS = 2 + 8 + 32 = 42
        val ttsBitmask = ScheduleCalculator.PRESET_TTS

        val calendar = Calendar.getInstance()

        // Test Monday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        assertTrue(ScheduleCalculator.isScheduleActiveOnDay(mwfSunBitmask, calendar))
        assertFalse(ScheduleCalculator.isScheduleActiveOnDay(ttsBitmask, calendar))

        // Test Tuesday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY)
        assertFalse(ScheduleCalculator.isScheduleActiveOnDay(mwfSunBitmask, calendar))
        assertTrue(ScheduleCalculator.isScheduleActiveOnDay(ttsBitmask, calendar))

        // Test Wednesday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
        assertTrue(ScheduleCalculator.isScheduleActiveOnDay(mwfSunBitmask, calendar))
        assertFalse(ScheduleCalculator.isScheduleActiveOnDay(ttsBitmask, calendar))

        // Test Thursday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY)
        assertFalse(ScheduleCalculator.isScheduleActiveOnDay(mwfSunBitmask, calendar))
        assertTrue(ScheduleCalculator.isScheduleActiveOnDay(ttsBitmask, calendar))

        // Test Friday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)
        assertTrue(ScheduleCalculator.isScheduleActiveOnDay(mwfSunBitmask, calendar))
        assertFalse(ScheduleCalculator.isScheduleActiveOnDay(ttsBitmask, calendar))

        // Test Saturday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
        assertFalse(ScheduleCalculator.isScheduleActiveOnDay(mwfSunBitmask, calendar))
        assertTrue(ScheduleCalculator.isScheduleActiveOnDay(ttsBitmask, calendar))

        // Test Sunday
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        assertTrue(ScheduleCalculator.isScheduleActiveOnDay(mwfSunBitmask, calendar))
        assertFalse(ScheduleCalculator.isScheduleActiveOnDay(ttsBitmask, calendar))
    }

    @Test
    fun testFormatDaysSummary() {
        assertEquals("Every day", ScheduleCalculator.formatDaysSummary(ScheduleCalculator.PRESET_EVERYDAY))
        assertEquals("Mon, Wed, Fri, Sun", ScheduleCalculator.formatDaysSummary(ScheduleCalculator.PRESET_MWF_SUN))
        assertEquals("Tue, Thu, Sat", ScheduleCalculator.formatDaysSummary(ScheduleCalculator.PRESET_TTS))
        assertEquals("Weekdays (Mon-Fri)", ScheduleCalculator.formatDaysSummary(ScheduleCalculator.PRESET_WEEKDAYS))
        assertEquals("Weekends (Sat-Sun)", ScheduleCalculator.formatDaysSummary(ScheduleCalculator.PRESET_WEEKENDS))
        assertEquals("Mon, Tue", ScheduleCalculator.formatDaysSummary(3))
    }
}
