package org.wakegated.medication.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.wakegated.medication.domain.manager.UsageStatsUnlockOracle

class UnlockOracleBarrierTest {

    @Test
    fun fresh_unlock_after_barrier_is_unlocked() {
        assertEquals(
            true,
            UsageStatsUnlockOracle.unlockedAfterBarrier(
                lastKeyguardHidden = 10_000L,
                lastKeyguardShown = 5_000L,
                lastScreenNonInteractive = 1_000L,
                lastUserPresent = 0L
            )
        )
    }

    @Test
    fun unlock_then_relock_is_not_unlocked() {
        // B2 shape: unlock at 10s, keyguard shown again at 20s (phone-alarm wake) → locked
        assertEquals(
            false,
            UsageStatsUnlockOracle.unlockedAfterBarrier(
                lastKeyguardHidden = 10_000L,
                lastKeyguardShown = 20_000L,
                lastScreenNonInteractive = 0L,
                lastUserPresent = 0L
            )
        )
    }

    @Test
    fun unlock_then_screen_off_is_not_unlocked() {
        assertEquals(
            false,
            UsageStatsUnlockOracle.unlockedAfterBarrier(
                lastKeyguardHidden = 10_000L,
                lastKeyguardShown = 0L,
                lastScreenNonInteractive = 20_000L,
                lastUserPresent = 0L
            )
        )
    }

    @Test
    fun no_events_at_all_is_not_unlocked() {
        assertEquals(
            false,
            UsageStatsUnlockOracle.unlockedAfterBarrier(0L, 0L, 0L, 0L)
        )
    }

    @Test
    fun user_present_newer_than_barrier_is_unlocked() {
        // prefs fallback corroboration: USER_PRESENT after the last screen-off
        assertEquals(
            true,
            UsageStatsUnlockOracle.unlockedAfterBarrier(
                lastKeyguardHidden = 0L,
                lastKeyguardShown = 0L,
                lastScreenNonInteractive = 5_000L,
                lastUserPresent = 10_000L
            )
        )
    }

    @Test
    fun barrier_without_any_unlock_is_not_unlocked() {
        assertEquals(
            false,
            UsageStatsUnlockOracle.unlockedAfterBarrier(
                lastKeyguardHidden = 0L,
                lastKeyguardShown = 10_000L,
                lastScreenNonInteractive = 0L,
                lastUserPresent = 0L
            )
        )
    }
}
