package org.wakegated.medication.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.wakegated.medication.domain.evaluator.RingGate

class RingGateTest {

    private val now = 1_700_000_000_000L
    private val scheduledAt = now - 60_000L // 1 minute ago
    private val quickOpenWindowMs = 15_000L // 15 seconds minimum unlock duration
    private val safetyNetMs = 30 * 60_000L // 30 minutes

    @Test
    fun non_wake_gated_always_rings() {
        val result = RingGate.decide(
            wakeGated = false,
            liveUnlocked = false,
            longestUnlockSinceDueMs = null,
            now = now,
            scheduledAt = scheduledAt,
            quickOpenEnabled = false,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(true, result)
    }

    @Test
    fun wake_gated_plus_liveUnlocked_rings() {
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = true,
            longestUnlockSinceDueMs = null,
            now = now,
            scheduledAt = scheduledAt,
            quickOpenEnabled = false,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(true, result)
    }

    @Test
    fun locked_no_unlock_since_due_both_options_off_no_ring() {
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = null,
            now = now,
            scheduledAt = scheduledAt,
            quickOpenEnabled = false,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(false, result)
    }

    @Test
    fun quickOpen_long_unlock_after_due_rings() {
        // Phone really unlocked for 20s after due (window 15s) → ring at next poll, locked or not
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = 20_000L,
            now = now,
            scheduledAt = scheduledAt,
            quickOpenEnabled = true,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(true, result)
    }

    @Test
    fun quickOpen_short_unlock_below_window_no_ring() {
        // Only 5s unlocked, window 15s → not a meaningful open
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = 5_000L,
            now = now,
            scheduledAt = scheduledAt,
            quickOpenEnabled = true,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(false, result)
    }

    @Test
    fun quickOpen_no_unlock_data_no_ring() {
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = null,
            now = now,
            scheduledAt = scheduledAt,
            quickOpenEnabled = true,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(false, result)
    }

    @Test
    fun quickOpen_option_off_no_ring() {
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = 20_000L,
            now = now,
            scheduledAt = scheduledAt,
            quickOpenEnabled = false,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(false, result)
    }

    @Test
    fun safetyNet_past_threshold_rings() {
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = null,
            now = scheduledAt + safetyNetMs + 1_000L, // past 30min threshold
            scheduledAt = scheduledAt,
            quickOpenEnabled = false,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = true,
            safetyNetMs = safetyNetMs
        )
        assertEquals(true, result)
    }

    @Test
    fun safetyNet_before_threshold_no_ring() {
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = null,
            now = scheduledAt + safetyNetMs - 1_000L, // 1s before threshold
            scheduledAt = scheduledAt,
            quickOpenEnabled = false,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = true,
            safetyNetMs = safetyNetMs
        )
        assertEquals(false, result)
    }

    @Test
    fun safetyNet_option_off_no_ring() {
        val result = RingGate.decide(
            wakeGated = true,
            liveUnlocked = false,
            longestUnlockSinceDueMs = null,
            now = scheduledAt + safetyNetMs + 1_000L,
            scheduledAt = scheduledAt,
            quickOpenEnabled = false,
            quickOpenWindowMs = quickOpenWindowMs,
            safetyNetEnabled = false,
            safetyNetMs = safetyNetMs
        )
        assertEquals(false, result)
    }

    @Test
    fun effectiveLiveUnlocked_corroborated_real_unlock_rings() {
        assertEquals(true, RingGate.effectiveLiveUnlocked(liveUnlocked = true, corroborated = true, usageAccessGranted = true))
    }

    @Test
    fun effectiveLiveUnlocked_uncorroborated_with_usage_access_silent() {
        assertEquals(false, RingGate.effectiveLiveUnlocked(liveUnlocked = true, corroborated = false, usageAccessGranted = true))
    }

    @Test
    fun effectiveLiveUnlocked_uncorroborated_no_usage_access_falls_back_to_v101() {
        assertEquals(true, RingGate.effectiveLiveUnlocked(liveUnlocked = true, corroborated = false, usageAccessGranted = false))
    }

    @Test
    fun effectiveLiveUnlocked_locked_never_rings_even_corroborated() {
        assertEquals(false, RingGate.effectiveLiveUnlocked(liveUnlocked = false, corroborated = true, usageAccessGranted = true))
    }
}
