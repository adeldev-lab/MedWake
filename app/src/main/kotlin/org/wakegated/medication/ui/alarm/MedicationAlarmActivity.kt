package org.wakegated.medication.ui.alarm

import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.wakegated.medication.R
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.data.model.DoseState
import org.wakegated.medication.data.repository.MedicationRepository
import org.wakegated.medication.databinding.ActivityMedicationAlarmBinding
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator
import org.wakegated.medication.domain.manager.MedicationNotificationHelper
import org.wakegated.medication.service.MedicationAlarmService
import java.text.SimpleDateFormat
import java.util.*

class MedicationAlarmActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MedAlarmActivity"
        const val KEY_ALARM_THEME = "alarm_theme"
        const val KEY_ALARM_MASCOT = "alarm_mascot"
        const val KEY_CUSTOM_BACKGROUND_URI = "custom_background_uri"
        const val THEME_ELECTRIC_BLUE = "electric_blue"
        const val THEME_EMERALD = "emerald"
        const val THEME_MIDNIGHT = "midnight"
        const val THEME_SUNSET = "sunset"
        const val THEME_ROYAL = "royal"
        const val MASCOT_CUSTOM = "custom"
        const val MASCOT_ROBOT = "robot"
        const val MASCOT_MINIMAL = "minimal"
    }

    private lateinit var binding: ActivityMedicationAlarmBinding
    private lateinit var repository: MedicationRepository
    private lateinit var evaluator: MedicationStateEvaluator

    private var doseEventId: Long = -1L
    private var medicationId: Long = -1L
    private var isDetailsExpanded = false

    // FIX H: track ringing state and held dose data for re-presentation
    private var ringing: Boolean = true
    private var muted = false
    private var currentMed: MedicationEntity? = null
    private var currentEvent: DoseEventEntity? = null

    // v1.2.0: USER_PRESENT unlock listener (v1.0.1 restored; SCREEN_ON receiver NOT restored)
    private var userPresentReceiver: BroadcastReceiver? = null
    private var userPresentRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        showOverLockscreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMedicationAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = MedicationRepository(this)
        evaluator = MedicationStateEvaluator(this)

        doseEventId = intent.getLongExtra("EXTRA_DOSE_EVENT_ID", -1L)
        medicationId = intent.getLongExtra("EXTRA_MEDICATION_ID", -1L)

        // FIX H: BACK key minimizes instead of destroying the alarm
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ringing) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        applyThemeAndPersonalization()
        loadDoseData()
        setupListeners()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            doseEventId = intent.getLongExtra("EXTRA_DOSE_EVENT_ID", -1L)
            medicationId = intent.getLongExtra("EXTRA_MEDICATION_ID", -1L)
            loadDoseData()
        }
    }

    /**
     * Batch 6: Room-state-only resume. No keyguard reads, no receiver registration, no evaluator call.
     * If the dose is RINGING → start the alarm service; otherwise finish.
     */
    override fun onStart() {
        super.onStart()
        // v1.2.0: v1.0.1 flow restored — re-evaluate, then act on persisted Room state;
        // audio starts only when RINGING AND the keyguard is really clear (both reads).
        lifecycleScope.launch {
            evaluator.evaluateMedicationState(
                System.currentTimeMillis(),
                launchFullScreenActivity = false,
                source = "fsi",
                fromOverLockscreenContext = true
            )
            if (doseEventId != -1L) {
                val event = repository.getDoseEventById(doseEventId)
                if (event?.state == DoseState.RINGING) {
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (!keyguardManager.isDeviceLocked && !keyguardManager.isKeyguardLocked) {
                        startMedicationAlarmService()
                    } else {
                        registerUnlockListeners()
                    }
                }
            }
        }
    }

    private fun startMedicationAlarmService() {
        try {
            val serviceIntent = Intent(this, MedicationAlarmService::class.java).apply {
                action = MedicationAlarmService.ACTION_START_ALARM
                putExtra(MedicationAlarmService.EXTRA_DOSE_EVENT_ID, doseEventId)
                putExtra(MedicationAlarmService.EXTRA_MEDICATION_ID, medicationId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start MedicationAlarmService from onStart", e)
        }
    }

    // v1.2.0: v1.0.1 unlock listener restored (USER_PRESENT only — the SCREEN_ON receiver
    // was the false-ring path and is intentionally NOT restored).
    private fun registerUnlockListeners() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(this, null)

        if (!userPresentRegistered) {
            userPresentReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_USER_PRESENT) {
                        lifecycleScope.launch {
                            evaluator.evaluateMedicationState(
                                System.currentTimeMillis(),
                                launchFullScreenActivity = false,
                                source = "fsi"
                            )
                        }
                        startMedicationAlarmService()
                        unregisterAllUnlockListeners()
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(userPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(userPresentReceiver, filter)
            }
            userPresentRegistered = true
        }
    }

    private fun unregisterAllUnlockListeners() {
        if (userPresentRegistered && userPresentReceiver != null) {
            try {
                unregisterReceiver(userPresentReceiver)
            } catch (_: Exception) {}
            userPresentReceiver = null
            userPresentRegistered = false
        }
    }

    private fun toggleAlarmSound() {
        muted = !muted
        try {
            val intent = Intent(this, MedicationAlarmService::class.java).apply {
                action = MedicationAlarmService.ACTION_TOGGLE_MUTE
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle alarm sound", e)
        }
        binding.ivSoundIndicator.setImageResource(
            if (muted) android.R.drawable.ic_lock_silent_mode
            else android.R.drawable.ic_lock_silent_mode_off
        )
        binding.ivSoundIndicator.contentDescription = if (muted) "Sound Muted" else "Sound Active"
    }

    private fun applyThemeAndPersonalization() {
        val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
        val theme = prefs.getString(KEY_ALARM_THEME, THEME_ELECTRIC_BLUE) ?: THEME_ELECTRIC_BLUE
        val mascot = prefs.getString(KEY_ALARM_MASCOT, MASCOT_ROBOT) ?: MASCOT_ROBOT
        val customBgUriStr = prefs.getString(KEY_CUSTOM_BACKGROUND_URI, null)

        // Apply Theme Background & Button Color
        val (bgColor, btnTextColor) = when (theme) {
            THEME_EMERALD -> Pair("#006C4C", "#006C4C")
            THEME_MIDNIGHT -> Pair("#0F172A", "#0F172A")
            THEME_SUNSET -> Pair("#E11D48", "#E11D48")
            THEME_ROYAL -> Pair("#7C3AED", "#7C3AED")
            else -> Pair("#155EEF", "#155EEF") // Electric Blue default
        }

        val alarmColor = Color.parseColor(bgColor)
        binding.layoutAlarmRoot.setBackgroundColor(alarmColor)
        binding.btnTakeNow.setTextColor(Color.parseColor(btnTextColor))
        // Keep the system bars on the alarm's own palette instead of the host theme.
        window.statusBarColor = alarmColor
        window.navigationBarColor = alarmColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Mascot & Wallpaper Rendering Mode
        when (mascot) {
            MASCOT_ROBOT -> {
                binding.ivCustomBackground.visibility = View.GONE
                binding.layoutArtworkPlaceholder.visibility = View.GONE
                binding.ivAlarmMascot.visibility = View.VISIBLE
            }
            MASCOT_MINIMAL -> {
                binding.ivCustomBackground.visibility = View.GONE
                binding.layoutArtworkPlaceholder.visibility = View.GONE
                binding.ivAlarmMascot.visibility = View.GONE
            }
            else -> { // MASCOT_CUSTOM
                binding.ivAlarmMascot.visibility = View.GONE
                if (!customBgUriStr.isNullOrBlank()) {
                    try {
                        val uri = Uri.parse(customBgUriStr)
                        binding.ivCustomBackground.setImageURI(uri)
                        binding.ivCustomBackground.visibility = View.VISIBLE
                        binding.layoutArtworkPlaceholder.visibility = View.GONE
                    } catch (e: Exception) {
                        binding.ivCustomBackground.visibility = View.GONE
                        binding.layoutArtworkPlaceholder.visibility = View.VISIBLE
                    }
                } else {
                    binding.ivCustomBackground.visibility = View.GONE
                    binding.layoutArtworkPlaceholder.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadDoseData() {
        lifecycleScope.launch {
            if (doseEventId != -1L && medicationId != -1L) {
                val med = repository.getMedicationById(medicationId)
                val event = repository.getDoseEventById(doseEventId)

                if (med != null && event != null) {
                    currentMed = med
                    currentEvent = event
                    bindData(med, event)
                }
            }
        }
    }

    private fun bindData(med: MedicationEntity, event: DoseEventEntity) {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        binding.tvMedicationName.text = med.name
        binding.tvDosage.text = "${med.dosage} ${med.unit}".trim()
        binding.tvScheduledTime.text = "Scheduled for ${sdf.format(Date(event.scheduledAt))}"
        binding.btnViewMedications.text = "${med.name} (${med.dosage} ${med.unit}) >"

        val overdueMinutes = ((System.currentTimeMillis() - event.scheduledAt) / 60_000L).coerceAtLeast(0)
        if (overdueMinutes > 0) {
            val h = overdueMinutes / 60
            val m = overdueMinutes % 60
            binding.tvLateDuration.text = if (h > 0) "Overdue by ${h}h ${m}m" else "Overdue by ${m}m"
            binding.tvLateDuration.visibility = View.VISIBLE
        } else {
            binding.tvLateDuration.visibility = View.GONE
        }

        if (med.notes.isNotBlank()) {
            binding.tvNotes.text = med.notes
            binding.tvNotes.visibility = View.VISIBLE
        } else {
            binding.tvNotes.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnViewMedications.setOnClickListener {
            isDetailsExpanded = !isDetailsExpanded
            binding.cardExpandedDetails.visibility = if (isDetailsExpanded) View.VISIBLE else View.GONE
            val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
            val mascot = prefs.getString(KEY_ALARM_MASCOT, MASCOT_ROBOT) ?: MASCOT_ROBOT
            val customBgUriStr = prefs.getString(KEY_CUSTOM_BACKGROUND_URI, null)

            if (isDetailsExpanded) {
                binding.layoutArtworkPlaceholder.visibility = View.GONE
                binding.ivAlarmMascot.visibility = View.GONE
            } else {
                if (mascot == MASCOT_ROBOT) {
                    binding.ivAlarmMascot.visibility = View.VISIBLE
                } else if (mascot == MASCOT_CUSTOM && customBgUriStr.isNullOrBlank()) {
                    binding.layoutArtworkPlaceholder.visibility = View.VISIBLE
                }
            }
        }

        binding.ivSoundIndicator.setOnClickListener {
            toggleAlarmSound()
        }

        binding.btnTakeNow.setOnClickListener {
            handleAction(DoseState.TAKEN)
        }

        binding.btnSkip.setOnClickListener {
            handleAction(DoseState.SKIPPED)
        }

        binding.btnSkipContainer.setOnClickListener {
            handleAction(DoseState.SKIPPED)
        }

        binding.btnReschedule.setOnClickListener {
            showRescheduleDialog()
        }

        binding.btnRescheduleContainer.setOnClickListener {
            showRescheduleDialog()
        }
    }

    private fun handleAction(state: DoseState) {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            if (doseEventId != -1L) {
                repository.resolveDoseEvent(doseEventId, state, now)
            }
            ringing = false
            stopAlarmService()
            evaluator.evaluateMedicationState(now, launchFullScreenActivity = false, source = "fsi")
            finishAndRemoveTask()
        }
    }

    private fun showRescheduleDialog() {
        // Use the snooze length configured in Settings for the first (default) option.
        val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
        val snoozeMins = prefs.getInt(MedicationStateEvaluator.KEY_SNOOZE_MINUTES, 10).coerceIn(1, 120)
        val snoozeLabel = if (snoozeMins == 1) "In 1 minute" else "In $snoozeMins minutes"
        val options = arrayOf(
            snoozeLabel,
            "In 15 minutes",
            "In 30 minutes",
            "In 1 hour",
            "Pick exact time"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Snooze Medication")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> snoozeByMinutes(snoozeMins)
                    1 -> snoozeByMinutes(15)
                    2 -> snoozeByMinutes(30)
                    3 -> snoozeByMinutes(60)
                    4 -> pickExactSnoozeTime()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun snoozeByMinutes(minutes: Int) {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val snoozeEpoch = now + (minutes * 60_000L)

            if (doseEventId != -1L && medicationId != -1L) {
                val snoozedEvent = DoseEventEntity(
                    medicationId = medicationId,
                    scheduledAt = snoozeEpoch,
                    dueAt = snoozeEpoch,
                    state = DoseState.SCHEDULED,
                    rescheduledFromId = doseEventId
                )
                repository.resolveDoseEvent(doseEventId, DoseState.RESCHEDULED, now)
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
            }

            ringing = false
            stopAlarmService()
            evaluator.evaluateMedicationState(now, launchFullScreenActivity = false, source = "fsi")
            Toast.makeText(this@MedicationAlarmActivity, "Snoozed for $minutes minutes", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }
    }

    private fun pickExactSnoozeTime() {
        val cal = Calendar.getInstance()
        TimePickerDialog(this, { _, hourOfDay, minute ->
            val targetCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hourOfDay)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val diffMs = targetCal.timeInMillis - System.currentTimeMillis()
            val diffMins = (diffMs / 60_000L).toInt()
            if (diffMins > 0) {
                snoozeByMinutes(diffMins)
            } else {
                Toast.makeText(this, "Please select a future time", Toast.LENGTH_SHORT).show()
            }
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }

    private fun stopAlarmService() {
        val stopIntent = Intent(this, MedicationAlarmService::class.java).apply {
            action = MedicationAlarmService.ACTION_STOP_ALARM
        }
        startService(stopIntent)
    }

    // FIX H: re-post notification when alarm goes to background
    override fun onPause() {
        super.onPause()
        if (ringing) {
            val med = currentMed
            val event = currentEvent
            if (med != null && event != null) {
                // Cancel first so posting a fresh notification re-fires the FSI
                try {
                    (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .cancel(MedicationNotificationHelper.ALARM_NOTIFICATION_ID)
                } catch (_: Exception) {}
                try {
                    MedicationNotificationHelper(this).postAlarmNotification(med, event, ringing = true)
                } catch (_: Exception) {}
            }
        }
    }

    // FIX H: re-present alarm activity after leaving foreground
    override fun onStop() {
        super.onStop()
        if (ringing) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (ringing && !isFinishing) {
                    try {
                        startActivity(intent.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "re-present blocked; notification remains", e)
                    }
                }
            }, 1500L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterAllUnlockListeners()
    }

    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // Batch 6: Removed requestDismissKeyguard — let user unlock naturally
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}
