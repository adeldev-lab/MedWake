package org.wakegated.medication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.model.DoseState
import org.wakegated.medication.data.repository.MedicationRepository
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator
import org.wakegated.medication.service.MedicationAlarmService

class ExternalActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MedExternalAction"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val doseEventId = intent.getLongExtra("EXTRA_DOSE_EVENT_ID", -1L)
        val minutes = intent.getIntExtra("EXTRA_MINUTES", 10)
        Log.i(TAG, "External action received: $action (Dose ID: $doseEventId)")

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = MedicationRepository(context)
                val evaluator = MedicationStateEvaluator(context)
                val prefs = context.getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
                val snoozeMinutes = intent.getIntExtra("EXTRA_MINUTES", prefs.getInt(MedicationStateEvaluator.KEY_SNOOZE_MINUTES, 10))

                when (action) {
                    "org.wakegated.medication.ACTION_MARK_TAKEN" -> {
                        if (doseEventId != -1L) {
                            repository.resolveDoseEvent(doseEventId, DoseState.TAKEN, System.currentTimeMillis())
                            stopAlarmService(context)
                            evaluator.evaluateMedicationState(System.currentTimeMillis(), source = "action")
                        }
                    }
                    "org.wakegated.medication.ACTION_MARK_SKIPPED" -> {
                        if (doseEventId != -1L) {
                            repository.resolveDoseEvent(doseEventId, DoseState.SKIPPED, System.currentTimeMillis())
                            stopAlarmService(context)
                            evaluator.evaluateMedicationState(System.currentTimeMillis(), source = "action")
                        }
                    }
                    "org.wakegated.medication.ACTION_SNOOZE" -> {
                        if (doseEventId != -1L) {
                            val event = repository.getDoseEventById(doseEventId)
                            if (event != null) {
                                val now = System.currentTimeMillis()
                                repository.resolveDoseEvent(doseEventId, DoseState.RESCHEDULED, now)
                                val snoozeEpoch = now + (snoozeMinutes * 60_000L)
                                val snoozedEvent = DoseEventEntity(
                                    medicationId = event.medicationId,
                                    scheduledAt = snoozeEpoch,
                                    dueAt = snoozeEpoch,
                                    state = DoseState.SCHEDULED,
                                    rescheduledFromId = doseEventId
                                )
                                var insertResult = repository.insertDoseEventIfNotExists(snoozedEvent)
                                if (insertResult == -1L) {
                                    var attempt = 1
                                    while (insertResult == -1L && attempt <= 5) {
                                        val retryEvent = snoozedEvent.copy(
                                            scheduledAt = snoozeEpoch + attempt * 60_000L,
                                            dueAt = snoozeEpoch + attempt * 60_000L
                                        )
                                        Log.w(TAG, "Snooze collision at scheduledAt=$snoozeEpoch, retry #attempt=$attempt")
                                        insertResult = repository.insertDoseEventIfNotExists(retryEvent)
                                        attempt++
                                    }
                                }
                                stopAlarmService(context)
                                evaluator.evaluateMedicationState(System.currentTimeMillis(), source = "action")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing external action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun stopAlarmService(context: Context) {
        val stopIntent = Intent(context, MedicationAlarmService::class.java).apply {
            action = MedicationAlarmService.ACTION_STOP_ALARM
        }
        context.startService(stopIntent)
    }
}
