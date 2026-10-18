package org.wakegated.medication.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wakegated.medication.data.model.DoseState

class DoseStateTest {

    @Test
    fun testResolvedStates() {
        assertTrue(DoseState.TAKEN.isResolved())
        assertTrue(DoseState.SKIPPED.isResolved())
        assertTrue(DoseState.RESCHEDULED.isResolved())

        assertFalse(DoseState.SCHEDULED.isResolved())
        assertFalse(DoseState.DUE.isResolved())
        assertFalse(DoseState.RINGING.isResolved())
    }

    @Test
    fun testPendingStates() {
        assertTrue(DoseState.SCHEDULED.isPending())
        assertTrue(DoseState.DUE.isPending())
        assertTrue(DoseState.RINGING.isPending())

        assertFalse(DoseState.TAKEN.isPending())
        assertFalse(DoseState.SKIPPED.isPending())
        assertFalse(DoseState.RESCHEDULED.isPending())
    }
}
