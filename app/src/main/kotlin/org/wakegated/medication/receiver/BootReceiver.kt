package org.wakegated.medication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MedBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // Only accept system-protected boot broadcasts.
        // BOOT_COMPLETED and LOCKED_BOOT_COMPLETED are protected broadcasts
        // — Android enforces that only the system (uid 1000) can send them.
        // Explicit broadcasts from other apps with these actions are blocked
        // by the framework, so this action check is the primary guard.
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Log.i(TAG, "Device booted ($action) -> Reconstructing medication alarms")
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val evaluator = MedicationStateEvaluator(context)
                evaluator.evaluateMedicationState(System.currentTimeMillis(), source = "boot")
            } catch (e: Exception) {
                Log.e(TAG, "Error in BootReceiver evaluation", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
