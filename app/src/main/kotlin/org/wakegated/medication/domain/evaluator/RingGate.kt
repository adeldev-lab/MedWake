package org.wakegated.medication.domain.evaluator

/**
 * Pure ring-decision gate. No Android imports — unit-testable.
 */
object RingGate {
    /**
     * @param wakeGated whether the medication has wake-gating enabled
     * @param liveUnlocked trusted live read: interactive && !keyguardLocked, receiver context only
     * @param longestUnlockSinceDueMs longest continuous unlocked episode (ms) starting after
     *        scheduledAt, or null when unknown
     * @param now current epoch ms
     * @param scheduledAt the dose's scheduledAt epoch ms
     * @param quickOpenEnabled opt-in quick-open catch-up setting
     * @param quickOpenWindowMs minimum unlock duration in ms (default 15s)
     * @param safetyNetEnabled opt-in safety-net setting
     * @param safetyNetMs safety-net threshold in ms (default 30min)
     */
    fun decide(
        wakeGated: Boolean,
        liveUnlocked: Boolean,
        longestUnlockSinceDueMs: Long?,
        now: Long,
        scheduledAt: Long,
        quickOpenEnabled: Boolean,
        quickOpenWindowMs: Long,
        safetyNetEnabled: Boolean,
        safetyNetMs: Long
    ): Boolean {
        if (!wakeGated) return true
        if (liveUnlocked) return true
        // Quick-open catch-up: the phone was REALLY unlocked (not just screen-on) for at least
        // the configured duration after the dose became due — ring at the next poll even if it
        // is locked again now. Duration, not last-unlock recency (owner's requirement).
        val quickOpen = quickOpenEnabled && longestUnlockSinceDueMs != null &&
            longestUnlockSinceDueMs >= quickOpenWindowMs && !liveUnlocked
        if (quickOpen) return true
        return safetyNetEnabled && now - scheduledAt >= safetyNetMs
    }

    /**
     * v1.2.0: liveUnlocked is trusted only when corroborated by a real unlock event,
     * or when no corroboration data exists at all (no usage access AND no observed
     * USER_PRESENT/SCREEN_OFF) — that falls back to the v1.0.1 keyguard-read behavior.
     * With usage access granted, an un-corroborated "unlocked" read (screen-on wake,
     * showWhenLocked occlusion) must NOT ring.
     */
    fun effectiveLiveUnlocked(liveUnlocked: Boolean, corroborated: Boolean, usageAccessGranted: Boolean): Boolean =
        liveUnlocked && (corroborated || !usageAccessGranted)
}
