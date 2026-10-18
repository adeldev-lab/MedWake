package org.wakegated.medication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator

class TimeChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MedTimeChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "System time/timezone changed (${intent?.action}) -> Re-evaluating medication state")
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val evaluator = MedicationStateEvaluator(context)
                evaluator.evaluateMedicationState(System.currentTimeMillis(), source = "time")
            } catch (e: Exception) {
                Log.e(TAG, "Error in TimeChangeReceiver evaluation", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
