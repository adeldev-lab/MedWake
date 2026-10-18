package org.wakegated.medication

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator
import org.wakegated.medication.domain.manager.RingDecisionLog
import org.wakegated.medication.domain.manager.UsageStatsUnlockOracle

class MedWakeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i("MedWakeApp", "MedWake application initialized")

        // Register dynamic USER_PRESENT receiver for unlock oracle logging + fast-path evaluation
        registerUserPresentReceiver()
        // Register dynamic SCREEN_OFF receiver for the unlock-oracle fallback (records only)
        registerScreenOffReceiver()

        // Central lifecycle rule: evaluate persistent state on application start
        appScope.launch {
            try {
                val evaluator = MedicationStateEvaluator(this@MedWakeApp)
                evaluator.evaluateMedicationState(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e("MedWakeApp", "Initial medication state evaluation failed", e)
            }
        }
    }

    private fun registerUserPresentReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    val now = System.currentTimeMillis()
                    // Log to oracle (in-memory + prefs)
                    val oracle = UsageStatsUnlockOracle(context)
                    oracle.recordUserPresent(now)
                    // Log to ring-decision log
                    RingDecisionLog.logUserPresent(context)
                    Log.i("MedWakeApp", "USER_PRESENT received at $now")
                    // Fast path: evaluate immediately so a DUE dose rings promptly on unlock
                    appScope.launch {
                        try {
                            val evaluator = MedicationStateEvaluator(context)
                            evaluator.evaluateMedicationState(now, source = "userPresent")
                        } catch (e: Exception) {
                            Log.e("MedWakeApp", "USER_PRESENT evaluation failed", e)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun registerScreenOffReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    val now = System.currentTimeMillis()
                    UsageStatsUnlockOracle(context).recordScreenOff(now)
                    Log.i("MedWakeApp", "SCREEN_OFF received at $now")
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }
}
