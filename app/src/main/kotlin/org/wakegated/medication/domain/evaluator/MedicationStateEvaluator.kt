package org.wakegated.medication.domain.evaluator

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.wakegated.medication.BuildConfig
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.data.model.DoseState
import org.wakegated.medication.data.repository.MedicationRepository
import org.wakegated.medication.domain.manager.AlarmScheduler
import org.wakegated.medication.domain.manager.MedicationNotificationHelper
import org.wakegated.medication.domain.manager.UnlockDetector
import org.wakegated.medication.domain.manager.UsageStatsUnlockOracle
import org.wakegated.medication.domain.manager.RingDecisionLog
import org.wakegated.medication.domain.model.ScheduleCalculator
import org.wakegated.medication.service.MedicationAlarmService
import org.wakegated.medication.ui.alarm.MedicationAlarmActivity
import java.util.Calendar
import kotlin.random.Random

class MedicationStateEvaluator(private val context: Context) {

    private val repository = MedicationRepository(context)
    private val unlockDetector = UnlockDetector(context)
    private val alarmScheduler = AlarmScheduler(context)
    private val mutex = Mutex()
    private val unlockOracle: UsageStatsUnlockOracle = UsageStatsUnlockOracle(context).also { it.loadPersistedState() }

    companion object {
        private const val TAG = "MedWakeEvaluator"
        const val PREFS_NAME = "medwake_prefs"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_UNLOCK_DELAY_MODE = "unlock_delay_mode" // "immediate", "fixed", "random"
        const val KEY_UNLOCK_DELAY_SECONDS = "unlock_delay_seconds" // 0 to 120
        const val KEY_SNOOZE_MINUTES = "snooze_minutes"
        const val CHASER_INTERVAL_MS = 90_000L // unlock re-check cadence while DUE + keyguard locked

        // Batch 6 — opt-in ring gate settings
        const val KEY_QUICK_OPEN_ENABLED = "quick_open_enabled"
        const val KEY_QUICK_OPEN_WINDOW_SECONDS = "quick_open_window_seconds"
        const val KEY_SAFETY_NET_ENABLED = "safety_net_enabled"
        const val KEY_SAFETY_NET_MINUTES = "safety_net_minutes"
        const val KEY_LAST_USER_PRESENT_TS = "last_user_present_ts"
        const val KEY_LAST_SCREEN_OFF_TS = "last_screen_off_ts"
        // Debug-only: force a poisoned "unlocked" keyguard read (fault-injection for tests)
        const val KEY_DEBUG_FORCE_POISON = "debug_force_poison"
    }

    /**
     * Central deterministic evaluation function callable from all lifecycle entry points:
     * App start, unlock, boot, alarm callback, service restart, time/timezone change.
     */
    suspend fun evaluateMedicationState(
        now: Long = System.currentTimeMillis(),
        launchFullScreenActivity: Boolean = true,
        source: String = "eval",
        fromOverLockscreenContext: Boolean = false
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (interactive, isDeviceLocked, isKeyguardLocked) = unlockDetector.debugState()

            // 1. Expand recurring schedules for active medications across [now - 1 day, now + 1 day]
            expandRecurringSchedules(now)

            // 2. Query all unresolved events (SCHEDULED, DUE, RINGING)
            val pendingEvents = repository.getPendingEvents()
            val activeMeds = repository.getActiveMedications().associateBy { it.medicationId }

            var highestPriorityRingingMed: Pair<MedicationEntity, DoseEventEntity>? = null
            var earliestFutureTrigger: Long? = null
            var dueEvents = mutableListOf<DoseEventEntity>()
            val transitions = mutableListOf<String>()

            // Read opt-in settings from prefs
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val quickOpenEnabled = prefs.getBoolean(KEY_QUICK_OPEN_ENABLED, false)
            val quickOpenWindowMs = prefs.getInt(KEY_QUICK_OPEN_WINDOW_SECONDS, 15).coerceIn(5, 120) * 1000L
            val safetyNetEnabled = prefs.getBoolean(KEY_SAFETY_NET_ENABLED, false)
            val safetyNetMinutes = prefs.getInt(KEY_SAFETY_NET_MINUTES, 30).coerceIn(10, 300)
            val safetyNetMs = safetyNetMinutes * 60_000L

            for (event in pendingEvents) {
                val med = activeMeds[event.medicationId] ?: continue

                // Check if dose has reached its scheduled time
                if (now >= event.scheduledAt) {
                    // Once ringing, a dose stays ringing until resolved (Take/Skip/Snooze) —
                    // the gate never demotes RINGING.
                    if (event.state == DoseState.RINGING) {
                        if (highestPriorityRingingMed == null) {
                            highestPriorityRingingMed = Pair(med, event)
                        }
                        // Keep the ringing notification fresh (re-fires FSI, per B35)
                        try {
                            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                                .cancel(MedicationNotificationHelper.ALARM_NOTIFICATION_ID)
                        } catch (_: Exception) {}
                        try {
                            MedicationNotificationHelper(context).postAlarmNotification(med, event, ringing = true)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to post alarm notification for ${med.name}", e)
                        }
                        continue
                    }
                    val trustedLiveReads = !fromOverLockscreenContext
                    // Debug-only fault injection: force a poisoned "unlocked" read so tests can
                    // prove the corroboration layer end-to-end (expect decision=due). Set by
                    // writing medwake_prefs debug_force_poison=true via run-as while stopped.
                    val debugPoison = BuildConfig.DEBUG && prefs.getBoolean(KEY_DEBUG_FORCE_POISON, false)
                    val liveUnlocked = if (debugPoison) {
                        true
                    } else {
                        trustedLiveReads && interactive && !isDeviceLocked && !isKeyguardLocked
                    }
                    val corroboratedUnlock = unlockOracle.isUnlockedAfterLastScreenOff()
                    val usageAccess = UsageStatsUnlockOracle.usageAccessGranted(context)
                    val liveUnlockedEffective = RingGate.effectiveLiveUnlocked(liveUnlocked, corroboratedUnlock, usageAccess)
                    val lastUnlockSinceDue = unlockOracle.lastUnlockAt(since = event.scheduledAt)
                    val longestUnlockSinceDue = unlockOracle.longestUnlockDurationMs(since = event.scheduledAt)
                    val ringable = RingGate.decide(
                        wakeGated = med.wakeGated,
                        liveUnlocked = liveUnlockedEffective,
                        longestUnlockSinceDueMs = longestUnlockSinceDue,
                        now = now,
                        scheduledAt = event.scheduledAt,
                        quickOpenEnabled = quickOpenEnabled,
                        quickOpenWindowMs = quickOpenWindowMs,
                        safetyNetEnabled = safetyNetEnabled,
                        safetyNetMs = safetyNetMs
                    )

                    // Log the decision
                    val decision = if (ringable) "ring" else "due"
                    RingDecisionLog.log(
                        context, source, interactive, isKeyguardLocked,
                        fromOverLockscreenContext, lastUnlockSinceDue, corroboratedUnlock, decision, event.doseEventId
                    )

                    if (ringable) {
                        // User is awake / medication is not wake-gated / quick-open / safety-net
                        val plannedTrigger = event.plannedTriggerAt ?: run {
                            // v1.0.1 flow restored: liveUnlocked rings apply the unlock-delay;
                            // quickOpen / safetyNet / non-wake-gated ring immediately (no delay).
                            val delayMs = if (liveUnlockedEffective) computeUnlockDelayMillis() else 0L
                            val targetTrigger = now + delayMs
                            repository.updatePlannedTrigger(event.doseEventId, targetTrigger)
                            targetTrigger
                        }

                        if (now >= plannedTrigger) {
                            // Ready to RING!
                            if (event.state != DoseState.RINGING) {
                                repository.updateDoseEventState(event.doseEventId, DoseState.RINGING)
                                transitions.add("${event.state}->RINGING")
                            }
                            if (highestPriorityRingingMed == null) {
                                highestPriorityRingingMed = Pair(med, event)
                            }
                            // Ensure a visible notification exists for every ringing dose
                            // Cancel first so posting a fresh notification re-fires the FSI
                            try {
                                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                                    .cancel(MedicationNotificationHelper.ALARM_NOTIFICATION_ID)
                            } catch (_: Exception) {}
                            try {
                                MedicationNotificationHelper(context).postAlarmNotification(med, event, ringing = true)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to post alarm notification for ${med.name}", e)
                            }
                        } else {
                            // Waiting for planned 0-120s delay to elapse
                            if (earliestFutureTrigger == null || plannedTrigger < earliestFutureTrigger) {
                                earliestFutureTrigger = plannedTrigger
                            }
                        }
                    } else {
                        // Wake-gated & user is asleep / device is locked & no safety-net -> Mark DUE silently
                        if (event.state != DoseState.DUE) {
                            repository.updateDoseEventState(event.doseEventId, DoseState.DUE)
                            if (event.state != DoseState.RINGING) {
                                transitions.add("${event.state}->DUE")
                            }
                        }
                        // Post silent due-channel FSI notification
                        try {
                            MedicationNotificationHelper(context).postAlarmNotification(med, event, ringing = false)
                        } catch (_: Exception) {}
                        dueEvents.add(event)
                    }
                } else {
                    // Future scheduled event
                    if (earliestFutureTrigger == null || event.scheduledAt < earliestFutureTrigger) {
                        earliestFutureTrigger = event.scheduledAt
                    }
                }
            }

            // 3. Handle Alarm Presentation & Audio Service
            if (highestPriorityRingingMed != null && launchFullScreenActivity) {
                triggerAlarmPresentation(highestPriorityRingingMed.first, highestPriorityRingingMed.second)
            }

            // 4. Schedule Next Exact Alarm Clock Wakeup (escalation + chaser aware)
            val nextDueEscalation = if (safetyNetEnabled)
                dueEvents.minOfOrNull { it.scheduledAt + safetyNetMs } else null
            var next = listOfNotNull(earliestFutureTrigger, nextDueEscalation).minOrNull()
            val canRingNow = interactive && !isDeviceLocked && !isKeyguardLocked
            if (dueEvents.isNotEmpty() && !canRingNow) {
                val chaser = now + CHASER_INTERVAL_MS
                next = listOfNotNull(next, chaser).minOrNull()
            }
            // Never arm a wakeup in the past (would spin)
            if (next != null && next <= now) next = now + 5_000
            if (next != null) {
                alarmScheduler.scheduleExactWakeup(next)
            }

        }
    }

    private suspend fun expandRecurringSchedules(now: Long) {
        val activeMeds = repository.getActiveMedications()
        val calendar = Calendar.getInstance()

        // Check yesterday, today, and tomorrow
        for (dayOffset in -1..1) {
            calendar.timeInMillis = now
            calendar.add(Calendar.DAY_OF_YEAR, dayOffset)

            for (med in activeMeds) {
                val schedules = repository.getSchedulesForMedication(med.medicationId)
                for (schedule in schedules) {
                    if (ScheduleCalculator.isScheduleActiveOnDay(schedule.daysOfWeek, calendar)) {
                        val occurrenceMillis = ScheduleCalculator.calculateOccurrenceMillis(
                            calendar,
                            schedule.timeInMinutes
                        )

                        val stale = occurrenceMillis < now - 60_000L
                        val newEvent = DoseEventEntity(
                            medicationId = med.medicationId,
                            scheduledAt = occurrenceMillis,
                            dueAt = occurrenceMillis,
                            state = if (stale) DoseState.MISSED else DoseState.SCHEDULED
                        )

                        // UNIQUE(medicationId, scheduledAt) ensures atomic deduplication
                        repository.insertDoseEventIfNotExists(newEvent)
                    }
                }
            }
        }
    }

    private fun computeUnlockDelayMillis(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_UNLOCK_DELAY_MODE, "random") ?: "random"
        val maxSeconds = prefs.getInt(KEY_UNLOCK_DELAY_SECONDS, 7).coerceIn(0, 120)

        val delaySeconds = when (mode) {
            "immediate" -> 0
            "fixed" -> maxSeconds
            "random" -> if (maxSeconds > 0) Random.nextInt(0, maxSeconds + 1) else 0
            else -> 3
        }

        return delaySeconds * 1000L
    }

    private fun triggerAlarmPresentation(med: MedicationEntity, event: DoseEventEntity) {
        Log.i(TAG, "Triggering alarm presentation for medication '${med.name}' (Dose ID ${event.doseEventId})")

        // 1. Start MedicationAlarmService (Audio & Vibration)
        val serviceIntent = Intent(context, MedicationAlarmService::class.java).apply {
            action = MedicationAlarmService.ACTION_START_ALARM
            putExtra(MedicationAlarmService.EXTRA_DOSE_EVENT_ID, event.doseEventId)
            putExtra(MedicationAlarmService.EXTRA_MEDICATION_ID, med.medicationId)
        }
        val fgsSuccess = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "FGS start denied, falling back to FSI notification", e)
            MedicationNotificationHelper(context).postAlarmNotification(med, event, ringing = true)
            false
        }


        // 2. Launch Full-Screen MedicationAlarmActivity
        val activityIntent = Intent(context, MedicationAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_DOSE_EVENT_ID", event.doseEventId)
            putExtra("EXTRA_MEDICATION_ID", med.medicationId)
        }
        context.startActivity(activityIntent)
    }
}
