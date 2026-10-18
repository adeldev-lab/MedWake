package org.wakegated.medication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MedAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MedWake:AlarmReceiverWakeLock"
        )
        wakeLock.acquire(10_000)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val evaluator = MedicationStateEvaluator(context)
                evaluator.evaluateMedicationState(System.currentTimeMillis(), source = "alarm")
            } catch (e: Exception) {
                Log.e(TAG, "Error in AlarmReceiver evaluation", e)
            } finally {
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                pendingResult.finish()
            }
        }
    }
}
