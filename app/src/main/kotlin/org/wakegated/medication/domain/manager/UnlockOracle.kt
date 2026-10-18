package org.wakegated.medication.domain.manager

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator

/**
 * Interface for querying the latest user-unlock timestamp.
 */
interface UnlockOracle {
    /** Latest unlock ts >= [since], else null. */
    fun lastUnlockAt(since: Long): Long?
}

/**
 * UsageStats-backed oracle plus in-process USER_PRESENT fallback.
 *
 * The keyguard API (`isKeyguardLocked`) is unreliable on real devices:
 * - at device wake (exact alarm) the keyguard window is not laid out yet → reads FALSE;
 * - a showWhenLocked screen (phone's own alarm app) occludes the keyguard → reads FALSE.
 * So "user unlocked" must be corroborated by real unlock events, not keyguard reads.
 * The system writes these events to UsageStats even when the app process is dead.
 */
class UsageStatsUnlockOracle(private val context: Context) : UnlockOracle {

    companion object {
        private const val TAG = "UnlockOracle"

        @Volatile
        var lastUserPresentTs: Long = 0L
            internal set

        @Volatile
        var lastScreenOffTs: Long = 0L
            internal set

        /**
         * Check whether usage-access permission is granted.
         */
        fun usageAccessGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
                mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                Log.w(TAG, "usageAccessGranted check failed", e)
                false
            }
        }

        /**
         * Pure barrier comparison: a real unlock (KEYGUARD_HIDDEN / USER_PRESENT) newer than
         * the last keyguard-show / screen-off barrier means the user is genuinely unlocked.
         * The relock shape (unlock, then keyguard shown / screen off afterwards) → false,
         * which is exactly the phone-alarm-wake case (screen on, no unlock). Pure — unit-tested.
         */
        fun unlockedAfterBarrier(
            lastKeyguardHidden: Long,
            lastKeyguardShown: Long,
            lastScreenNonInteractive: Long,
            lastUserPresent: Long
        ): Boolean {
            val barrier = maxOf(lastKeyguardShown, lastScreenNonInteractive)
            val lastRealUnlock = maxOf(lastKeyguardHidden, lastUserPresent)
            return lastRealUnlock > 0L && lastRealUnlock > barrier
        }
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persist the in-process USER_PRESENT timestamp so it survives process restarts.
     */
    fun recordUserPresent(ts: Long) {
        lastUserPresentTs = ts
        prefs().edit()
            .putLong(MedicationStateEvaluator.KEY_LAST_USER_PRESENT_TS, ts)
            .apply()
    }

    /**
     * Persist the last SCREEN_OFF timestamp (used by the no-usage-access fallback).
     */
    fun recordScreenOff(ts: Long) {
        lastScreenOffTs = ts
        prefs().edit()
            .putLong(MedicationStateEvaluator.KEY_LAST_SCREEN_OFF_TS, ts)
            .apply()
    }

    /**
     * Load persisted USER_PRESENT / SCREEN_OFF timestamps into the in-memory volatiles.
     */
    fun loadPersistedState() {
        val prefs = prefs()
        val persistedUserPresent = prefs.getLong(MedicationStateEvaluator.KEY_LAST_USER_PRESENT_TS, 0L)
        if (persistedUserPresent > lastUserPresentTs) {
            lastUserPresentTs = persistedUserPresent
        }
        val persistedScreenOff = prefs.getLong(MedicationStateEvaluator.KEY_LAST_SCREEN_OFF_TS, 0L)
        if (persistedScreenOff > lastScreenOffTs) {
            lastScreenOffTs = persistedScreenOff
        }
    }

    override fun lastUnlockAt(since: Long): Long? {
        val usageResult = queryUsageStats(since)
        val prefsResult = prefsUserPresent(since)
        val best = maxOf(usageResult ?: 0L, prefsResult ?: 0L).takeIf { it >= since }
        return best
    }

    /**
     * Longest continuous unlocked episode (KEYGUARD_HIDDEN until the next KEYGUARD_SHOWN /
     * SCREEN_NON_INTERACTIVE) with start >= [since], in ms. An episode still running at query
     * time counts up to now. Used by the quick-open catch-up: ring at the next poll if the
     * phone was REALLY open for at least the configured window — duration, not last-unlock recency.
     * Requires usage access; null when unavailable.
     */
    fun longestUnlockDurationMs(since: Long): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(since, now)
            var episodeStart = 0L
            var maxDuration = 0L
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.KEYGUARD_HIDDEN -> if (episodeStart == 0L) episodeStart = event.timeStamp
                    UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (episodeStart != 0L) {
                            val d = event.timeStamp - episodeStart
                            if (d > maxDuration) maxDuration = d
                            episodeStart = 0L
                        }
                    }
                }
            }
            if (episodeStart != 0L) {
                val d = now - episodeStart
                if (d > maxDuration) maxDuration = d
            }
            maxDuration.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "longestUnlockDurationMs failed", e)
            null
        }
    }

    /**
     * True iff the last real unlock (KEYGUARD_HIDDEN / USER_PRESENT) happened AFTER the last
     * time the device went to sleep / showed the keyguard — i.e. the user is genuinely unlocked
     * right now, not just "screen on" (phone alarm wake, showWhenLocked occlusion, transient
     * keyguard read at device wake).
     *
     * Primary source: UsageStats (survives process death, immune to occlusion).
     * Fallback (no usage access): in-process/prefs USER_PRESENT vs SCREEN_OFF — only reliable
     * while the process is alive, so the evaluator also applies a stability re-check.
     */
    fun isUnlockedAfterLastScreenOff(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && usageAccessGranted(context)) {
            val usageResult = usageUnlockedSinceLastOff()
            if (usageResult != null) return usageResult
        }
        // Fallback: last unlock must be newer than last screen-off
        val lastUp = lastUserPresentTs
        val lastOff = lastScreenOffTs
        return lastUp > 0L && lastUp > lastOff
    }

    /**
     * null when the query cannot be answered (no events / failure). Otherwise whether the
     * latest KEYGUARD_HIDDEN (or USER_PRESENT) is newer than the latest KEYGUARD_SHOWN /
     * SCREEN_NON_INTERACTIVE barrier.
     */
    private fun usageUnlockedSinceLastOff(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 24L * 60 * 60 * 1000, now)
            var lastHidden = 0L
            var lastShown = 0L
            var lastNonInteractive = 0L
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.KEYGUARD_HIDDEN -> if (event.timeStamp > lastHidden) lastHidden = event.timeStamp
                    UsageEvents.Event.KEYGUARD_SHOWN -> if (event.timeStamp > lastShown) lastShown = event.timeStamp
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> if (event.timeStamp > lastNonInteractive) lastNonInteractive = event.timeStamp
                }
            }
            val barrier = maxOf(lastShown, lastNonInteractive)
            val lastRealUnlock = maxOf(lastHidden, lastUserPresentTs)
            if (lastRealUnlock == 0L && barrier == 0L) return null // no events at all → unknown
            unlockedAfterBarrier(lastHidden, lastShown, lastNonInteractive, lastUserPresentTs)
        } catch (e: SecurityException) {
            Log.w(TAG, "UsageStats query denied (SecurityException)", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "UsageStats query failed", e)
            null
        }
    }

    private fun queryUsageStats(since: Long): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val events = usm.queryEvents(since, System.currentTimeMillis())
            var maxTs = 0L
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    if (event.timeStamp > maxTs) maxTs = event.timeStamp
                }
            }
            maxTs.takeIf { it > 0 }
        } catch (e: SecurityException) {
            Log.w(TAG, "UsageStats query denied (SecurityException)", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "UsageStats query failed", e)
            null
        }
    }

    private fun prefsUserPresent(since: Long): Long? {
        val ts = lastUserPresentTs
        return ts.takeIf { it >= since }
    }
}
