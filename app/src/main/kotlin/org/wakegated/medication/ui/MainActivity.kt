package org.wakegated.medication.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import org.wakegated.medication.domain.manager.RingDecisionLog
import org.wakegated.medication.domain.manager.UsageStatsUnlockOracle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.wakegated.medication.R
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.data.entity.ScheduleEntity
import org.wakegated.medication.data.model.DoseState
import org.wakegated.medication.data.repository.MedicationRepository
import org.wakegated.medication.databinding.ActivityMainBinding
import org.wakegated.medication.databinding.DialogAddMedicationBinding
import org.wakegated.medication.databinding.DialogEditDoseStatusBinding
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator
import org.wakegated.medication.domain.manager.AlarmScheduler
import org.wakegated.medication.domain.model.AdherenceCalculator
import org.wakegated.medication.domain.model.ScheduleCalculator
import org.wakegated.medication.service.MedicationAlarmService
import org.wakegated.medication.ui.alarm.MedicationAlarmActivity
import org.wakegated.medication.ui.dashboard.DoseEventAdapter
import org.wakegated.medication.ui.medications.MedicationAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: MedicationRepository
    private lateinit var evaluator: MedicationStateEvaluator
    private lateinit var alarmScheduler: AlarmScheduler

    private lateinit var todayAdapter: DoseEventAdapter
    private lateinit var historyAdapter: DoseEventAdapter
    private lateinit var medsAdapter: MedicationAdapter

    private var previewMediaPlayer: MediaPlayer? = null
    private var fadePreviewJob: Job? = null
    private var isFadePreviewPlaying = false
    private var pendingSoundUriCallback: ((Uri?, String) -> Unit)? = null

    // Calendar Filter State
    private var filterDateStartMs: Long? = null
    private var filterDateEndMs: Long? = null
    private var selectedCalendarYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedCalendarMonth: Int = Calendar.getInstance().get(Calendar.MONTH)
    private var cachedHistoryList: List<Pair<DoseEventEntity, MedicationEntity>> = emptyList()

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            val title = if (uri != null) {
                RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Selected Sound"
            } else {
                "Silent"
            }
            pendingSoundUriCallback?.invoke(uri, title)
        }
    }

    private val wallpaperPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(MedicationAlarmActivity.KEY_CUSTOM_BACKGROUND_URI, uri.toString())
                .putString(MedicationAlarmActivity.KEY_ALARM_MASCOT, MedicationAlarmActivity.MASCOT_CUSTOM)
                .apply()
            binding.chipMascotCustom.isChecked = true
            Toast.makeText(this, "Custom wallpaper imported!", Toast.LENGTH_SHORT).show()
        }
    }


    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    exportBackup(uri)
                    Toast.makeText(this@MainActivity, "Backup exported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    restoreBackup(uri)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Invalid backup file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // FIX 1: Notification permission launcher for Android 13+
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
        ) { _ -> /* granted or denied — onboarding dialog handles UX */ }

    private fun themeOverlayRes(theme: String): Int = when (theme) {
        MedicationAlarmActivity.THEME_EMERALD -> R.style.ThemeOverlay_MedWake_Emerald
        MedicationAlarmActivity.THEME_MIDNIGHT -> R.style.ThemeOverlay_MedWake_Midnight
        MedicationAlarmActivity.THEME_SUNSET -> R.style.ThemeOverlay_MedWake_Sunset
        MedicationAlarmActivity.THEME_ROYAL -> R.style.ThemeOverlay_MedWake_Royal
        else -> R.style.ThemeOverlay_MedWake_ElectricBlue
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply alarm theme overlay before inflating the UI
        val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
        val alarmTheme = prefs.getString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_ELECTRIC_BLUE)
            ?: MedicationAlarmActivity.THEME_ELECTRIC_BLUE
        theme.applyStyle(themeOverlayRes(alarmTheme), true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = MedicationRepository(this)
        evaluator = MedicationStateEvaluator(this)
        alarmScheduler = AlarmScheduler(this)

        setupRecyclerViews()
        setupTabs()
        setupFab()
        setupSettingsTab()
        setupCalendarView()

        observeData()

        lifecycleScope.launch {
            evaluator.evaluateMedicationState(System.currentTimeMillis(), launchFullScreenActivity = false, source = "app")
        }

        // FIX 1: First-launch onboarding — request notification permission + show grant-dialog once
        showOnboardingIfNeeded()
    }

    override fun onResume() {
            super.onResume()
            refreshSettingsStatus()
            lifecycleScope.launch {
                evaluator.evaluateMedicationState(System.currentTimeMillis(), launchFullScreenActivity = true, source = "app")
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        stopSoundPreview()
        stopFadePreview()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FIX 1 — First-launch permission onboarding
    // ────────────────────────────────────────────────────────────────────────────

    private fun showOnboardingIfNeeded() {
        val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MedicationStateEvaluator.KEY_ONBOARDING_DONE, false)) return

        // (a) Request POST_NOTIFICATIONS on API 33+ if not yet granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check which critical grants are missing
        val missingGrants = mutableListOf<String>()
        // Notifications
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        if (!notifGranted) missingGrants.add("Notifications")

        // Full-screen intent
        val fsiGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.canUseFullScreenIntent()
        } else true
        if (!fsiGranted) missingGrants.add("Full-screen alerts")

        // Battery optimization
        val batteryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        if (!batteryGranted) missingGrants.add("Ignore battery optimization")

        // Overlay
        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        if (!overlayGranted) missingGrants.add("Display over other apps")

        // If all grants present, skip dialog
        if (missingGrants.isEmpty()) {
            prefs.edit().putBoolean(MedicationStateEvaluator.KEY_ONBOARDING_DONE, true).apply()
            return
        }

        // (b) Show ONE non-blocking onboarding dialog
        val message = buildString {
            append("MedWake needs a few permissions to work reliably.\n\n")
            append("Missing:\n")
            missingGrants.forEach { append("• $it\n") }
            append("\nTap \"Grant permissions\" to open Settings, then enable each missing row.")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Set up MedWake")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("Grant permissions") { _, _ ->
                // Open app's Settings page so user can enable each missing grant
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Continue") { _, _ -> /* dismiss */ }
            .setOnDismissListener {
                // Mark onboarding done so it never shows again
                prefs.edit().putBoolean(MedicationStateEvaluator.KEY_ONBOARDING_DONE, true).apply()
            }
            .show()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // RecyclerViews
    // ────────────────────────────────────────────────────────────────────────────

    private fun setupRecyclerViews() {
        todayAdapter = DoseEventAdapter(
            onItemClick = { event, med -> showEditDoseDialog(event, med) },
            onTakeClick = { event, med -> handleInlineAction(event, med, DoseState.TAKEN) },
            onSkipClick = { event, med -> handleInlineAction(event, med, DoseState.SKIPPED) }
        )
        binding.rvTodayEvents.layoutManager = LinearLayoutManager(this)
        binding.rvTodayEvents.adapter = todayAdapter

        medsAdapter = MedicationAdapter(
            onItemClick = { med -> showAddOrEditMedDialog(med) },
            onEditClick = { med -> showAddOrEditMedDialog(med) },
            onDeleteClick = { med -> confirmDeleteMedication(med) }
        )
        binding.rvMedications.layoutManager = LinearLayoutManager(this)
        binding.rvMedications.adapter = medsAdapter

        historyAdapter = DoseEventAdapter(
            onItemClick = { event, med -> showEditDoseDialog(event, med) },
            onTakeClick = { event, med -> handleInlineAction(event, med, DoseState.TAKEN) },
            onSkipClick = { event, med -> handleInlineAction(event, med, DoseState.SKIPPED) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Calendar
    // ────────────────────────────────────────────────────────────────────────────

    private fun setupCalendarView() {
        binding.calendarViewAdherence.onMonthChangeListener = { year, month ->
            selectedCalendarYear = year
            selectedCalendarMonth = month
            filterDateStartMs = null
            filterDateEndMs = null
            binding.btnClearDateFilter.visibility = View.GONE
            binding.tvHistoryHeader.text = "Dose History (All)"
            updateHistoryAdapterFiltered()
            val summary = AdherenceCalculator.computeMonthSummary(year, month, cachedHistoryList.map { it.first })
            binding.calendarViewAdherence.setMonthSummary(summary)
            binding.tvCurrentStreak.text = "Streak: ${summary.currentStreakDays} Days"
            binding.tvAdherencePercentage.text = "${summary.adherencePercentage}%"
            binding.tvAdherenceSubtitle.text = "${summary.totalTaken} Taken \u2022 ${summary.totalSkipped} Skipped"
        }

        binding.calendarViewAdherence.onDayClickListener = { _, _, _, startMs, endMs ->
            filterDateStartMs = startMs
            filterDateEndMs = endMs
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            binding.tvHistoryHeader.text = "History: ${sdf.format(cal.time)}"
            binding.btnClearDateFilter.visibility = View.VISIBLE
            updateHistoryAdapterFiltered()
        }

        binding.btnClearDateFilter.setOnClickListener {
            filterDateStartMs = null
            filterDateEndMs = null
            binding.btnClearDateFilter.visibility = View.GONE
            binding.tvHistoryHeader.text = "Dose History (All)"
            updateHistoryAdapterFiltered()
        }
    }

    private fun updateHistoryAdapterFiltered() {
        val start = filterDateStartMs
        val end = filterDateEndMs
        val list = if (start != null && end != null) {
            cachedHistoryList.filter { it.first.scheduledAt in start..end }
        } else {
            cachedHistoryList
        }
        historyAdapter.submitList(list)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Inline actions
    // ────────────────────────────────────────────────────────────────────────────

    private fun handleInlineAction(event: DoseEventEntity, med: MedicationEntity, targetState: DoseState) {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            repository.resolveDoseEvent(event.doseEventId, targetState, now)
            stopAlarmAudioService()
            evaluator.evaluateMedicationState(now, launchFullScreenActivity = false, source = "app")
            val actionText = if (targetState == DoseState.TAKEN) "taken" else "skipped"
            Toast.makeText(this@MainActivity, "${med.name} marked as $actionText", Toast.LENGTH_SHORT).show()
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Edit dose dialog
    // ────────────────────────────────────────────────────────────────────────────

    private fun showEditDoseDialog(event: DoseEventEntity, med: MedicationEntity) {
        val dialogBinding = DialogEditDoseStatusBinding.inflate(LayoutInflater.from(this))
        val sdf = SimpleDateFormat("EEE, MMM dd \u2022 hh:mm a", Locale.getDefault())
        val actionTimeSdf = SimpleDateFormat("hh:mm a", Locale.getDefault())

        dialogBinding.tvEditMedName.text = "${med.name} (${med.dosage} ${med.unit})".trim()
        dialogBinding.tvEditScheduledTime.text = "Scheduled for ${sdf.format(Date(event.scheduledAt))}"

        when (event.state) {
            DoseState.TAKEN -> {
                val timeStr = event.actionTimestamp?.let { " at ${actionTimeSdf.format(Date(it))}" } ?: ""
                dialogBinding.tvEditCurrentStatus.text = "TAKEN$timeStr"
                dialogBinding.tvEditCurrentStatus.setBackgroundColor(Color.parseColor("#DCFCE7"))
                dialogBinding.tvEditCurrentStatus.setTextColor(Color.parseColor("#15803D"))
            }
            DoseState.SKIPPED -> {
                dialogBinding.tvEditCurrentStatus.text = "SKIPPED"
                dialogBinding.tvEditCurrentStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                dialogBinding.tvEditCurrentStatus.setTextColor(Color.parseColor("#DC2626"))
            }
            DoseState.RESCHEDULED -> {
                dialogBinding.tvEditCurrentStatus.text = "SNOOZED"
                dialogBinding.tvEditCurrentStatus.setBackgroundColor(Color.parseColor("#EDE9FE"))
                dialogBinding.tvEditCurrentStatus.setTextColor(Color.parseColor("#6D28D9"))
            }
            DoseState.DUE -> {
                dialogBinding.tvEditCurrentStatus.text = "DUE"
                dialogBinding.tvEditCurrentStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))
                dialogBinding.tvEditCurrentStatus.setTextColor(Color.parseColor("#B45309"))
            }
            DoseState.RINGING -> {
                dialogBinding.tvEditCurrentStatus.text = "RINGING"
                dialogBinding.tvEditCurrentStatus.setBackgroundColor(Color.parseColor("#FEE2E2"))
                dialogBinding.tvEditCurrentStatus.setTextColor(Color.parseColor("#DC2626"))
            }
            DoseState.MISSED -> {
                dialogBinding.tvEditCurrentStatus.text = "MISSED"
                dialogBinding.tvEditCurrentStatus.setBackgroundColor(Color.parseColor("#FEF3C7"))
                dialogBinding.tvEditCurrentStatus.setTextColor(Color.parseColor("#B45309"))
            }
            else -> {
                dialogBinding.tvEditCurrentStatus.text = event.state.name
                dialogBinding.tvEditCurrentStatus.setBackgroundColor(Color.parseColor("#E0F2FE"))
                dialogBinding.tvEditCurrentStatus.setTextColor(Color.parseColor("#0369A1"))
            }
        }

        if (event.state.isPending()) {
            dialogBinding.btnOpenAlarmScreen.visibility = View.VISIBLE
        } else {
            dialogBinding.btnOpenAlarmScreen.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit Dose Status")
            .setView(dialogBinding.root)
            .setNegativeButton("Close", null)
            .create()

        dialogBinding.btnSetTaken.setOnClickListener {
            lifecycleScope.launch {
                val now = System.currentTimeMillis()
                repository.resolveDoseEvent(event.doseEventId, DoseState.TAKEN, now)
                stopAlarmAudioService()
                evaluator.evaluateMedicationState(now, launchFullScreenActivity = false, source = "app")
                Toast.makeText(this@MainActivity, "${med.name} marked as Taken", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialogBinding.btnSetSkipped.setOnClickListener {
            lifecycleScope.launch {
                val now = System.currentTimeMillis()
                repository.resolveDoseEvent(event.doseEventId, DoseState.SKIPPED, now)
                stopAlarmAudioService()
                evaluator.evaluateMedicationState(now, launchFullScreenActivity = false, source = "app")
                Toast.makeText(this@MainActivity, "${med.name} marked as Skipped", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialogBinding.btnSetRescheduled.setOnClickListener {
            dialog.dismiss()
            showRescheduleDialogForEvent(event, med)
        }

        dialogBinding.btnOpenAlarmScreen.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, MedicationAlarmActivity::class.java).apply {
                putExtra("EXTRA_DOSE_EVENT_ID", event.doseEventId)
                putExtra("EXTRA_MEDICATION_ID", med.medicationId)
            }
            startActivity(intent)
        }

        dialogBinding.btnDeleteDoseRecord.setOnClickListener {
            dialog.dismiss()
            confirmDeleteDoseEvent(event, med)
        }

        dialog.show()
    }

    private fun showRescheduleDialogForEvent(event: DoseEventEntity, med: MedicationEntity) {
        val options = arrayOf(
            "In 15 minutes",
            "In 30 minutes",
            "In 1 hour",
            "In 2 hours",
            "Pick exact time today"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Reschedule ${med.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rescheduleDoseByMinutes(event, 15)
                    1 -> rescheduleDoseByMinutes(event, 30)
                    2 -> rescheduleDoseByMinutes(event, 60)
                    3 -> rescheduleDoseByMinutes(event, 120)
                    4 -> pickExactRescheduleTimeForEvent(event)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rescheduleDoseByMinutes(event: DoseEventEntity, minutes: Int) {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val snoozeEpoch = now + (minutes * 60_000L)
            val snoozedEvent = DoseEventEntity(
                medicationId = event.medicationId,
                scheduledAt = snoozeEpoch,
                dueAt = snoozeEpoch,
                state = DoseState.SCHEDULED,
                rescheduledFromId = event.doseEventId
            )
            var insertResult = repository.insertDoseEventIfNotExists(snoozedEvent)
            if (insertResult == -1L) {
                var attempt = 1
                while (insertResult == -1L && attempt <= 5) {
                    val retryEvent = snoozedEvent.copy(
                        scheduledAt = snoozeEpoch + attempt * 60_000L,
                        dueAt = snoozeEpoch + attempt * 60_000L
                    )
                    android.util.Log.w("MainActivity", "Reschedule collision at scheduledAt=$snoozeEpoch, retry #attempt=$attempt")
                    insertResult = repository.insertDoseEventIfNotExists(retryEvent)
                    attempt++
                }
            }
            evaluator.evaluateMedicationState(now, launchFullScreenActivity = false, source = "app")
            Toast.makeText(this@MainActivity, "Rescheduled for $minutes minutes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickExactRescheduleTimeForEvent(event: DoseEventEntity) {
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
                rescheduleDoseByMinutes(event, diffMins)
            } else {
                Toast.makeText(this, "Please select a future time", Toast.LENGTH_SHORT).show()
            }
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }

    private fun confirmDeleteDoseEvent(event: DoseEventEntity, med: MedicationEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Dose Record?")
            .setMessage("Are you sure you want to delete this dose event record for ${med.name}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteDoseEvent(event.doseEventId)
                    evaluator.evaluateMedicationState(System.currentTimeMillis(), launchFullScreenActivity = false, source = "app")
                    Toast.makeText(this@MainActivity, "Dose record deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopAlarmAudioService() {
        val stopIntent = Intent(this, MedicationAlarmService::class.java).apply {
            action = MedicationAlarmService.ACTION_STOP_ALARM
        }
        startService(stopIntent)
    }

    private fun confirmDeleteMedication(med: MedicationEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${med.name}?")
            .setMessage("Are you sure you want to delete this medication and its scheduled reminders?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteMedication(med)
                    evaluator.evaluateMedicationState(System.currentTimeMillis(), launchFullScreenActivity = false, source = "app")
                    Toast.makeText(this@MainActivity, "${med.name} deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Tabs
    // ────────────────────────────────────────────────────────────────────────────

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showTab(binding.containerToday, showFab = true)
                    1 -> showTab(binding.containerMeds, showFab = true)
                    2 -> showTab(binding.containerHistory, showFab = false)
                    3 -> {
                        showTab(binding.containerSettings, showFab = false)
                        refreshSettingsStatus()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showTab(containerToShow: View, showFab: Boolean) {
        binding.containerToday.visibility = View.GONE
        binding.containerMeds.visibility = View.GONE
        binding.containerHistory.visibility = View.GONE
        binding.containerSettings.visibility = View.GONE

        containerToShow.visibility = View.VISIBLE
        binding.fabAddMedication.visibility = if (showFab) View.VISIBLE else View.GONE
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Data observation
    // ────────────────────────────────────────────────────────────────────────────

    private fun observeData() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val endOfDay = startOfDay + 86_400_000L

        lifecycleScope.launch {
            combine(
                repository.getEventsForRangeFlow(startOfDay, endOfDay),
                repository.getAllMedicationsFlow()
            ) { events, meds ->
                val medsMap = meds.associateBy { it.medicationId }
                events.mapNotNull { event ->
                    medsMap[event.medicationId]?.let { med -> Pair(event, med) }
                }
            }.collectLatest { pairedList ->
                todayAdapter.submitList(pairedList)
                binding.tvEmptyToday.visibility = if (pairedList.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            repository.getAllMedicationsFlow().collectLatest { meds ->
                val pairedList = meds.map { med ->
                    val schedules = repository.getSchedulesForMedication(med.medicationId)
                    Pair(med, schedules)
                }
                medsAdapter.submitList(pairedList)
            }
        }

        lifecycleScope.launch {
            combine(
                repository.getRecentHistoryFlow(200),
                repository.getAllMedicationsFlow()
            ) { historyEvents, meds ->
                val medsMap = meds.associateBy { it.medicationId }
                val now = System.currentTimeMillis()
                val pairedList = historyEvents.filter { !(it.state == DoseState.SCHEDULED && it.scheduledAt > now) }.mapNotNull { event ->
                    medsMap[event.medicationId]?.let { med -> Pair(event, med) }
                }

                // Compute Monthly Summary
                val monthSummary = AdherenceCalculator.computeMonthSummary(
                    selectedCalendarYear,
                    selectedCalendarMonth,
                    historyEvents
                )

                binding.calendarViewAdherence.setMonthSummary(monthSummary)
                binding.tvCurrentStreak.text = "Streak: ${monthSummary.currentStreakDays} Days"
                binding.tvAdherencePercentage.text = "${monthSummary.adherencePercentage}%"
                binding.tvAdherenceSubtitle.text = "${monthSummary.totalTaken} Taken \u2022 ${monthSummary.totalSkipped} Skipped"

                cachedHistoryList = pairedList
                pairedList
            }.collectLatest {
                updateHistoryAdapterFiltered()
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FAB & Quick Test
    // ────────────────────────────────────────────────────────────────────────────

    private fun setupFab() {
        binding.fabAddMedication.setOnClickListener {
            showAddOrEditMedDialog(null)
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Add / Edit Medication Dialog
    // ────────────────────────────────────────────────────────────────────────────

    private fun showAddOrEditMedDialog(existingMed: MedicationEntity?) {
        val dialogBinding = DialogAddMedicationBinding.inflate(LayoutInflater.from(this))
        var selectedMinutes = 420 // 07:00 AM default
        var selectedDaysBitmask = ScheduleCalculator.PRESET_EVERYDAY
        var currentSoundUri: Uri? = existingMed?.soundUri?.let { if (it.isNotBlank()) Uri.parse(it) else null }
        var currentSoundTitle: String = existingMed?.soundTitle ?: "Default Alarm"

        fun updateTimeDisplay(mins: Int) {
            selectedMinutes = mins
            val h = mins / 60
            val m = mins % 60
            val amPm = if (h >= 12) "PM" else "AM"
            val displayH = if (h == 0 || h == 12) 12 else h % 12
            dialogBinding.etTime.setText(String.format("%02d:%02d %s", displayH, m, amPm))
        }

        fun updateDayButton(btn: TextView, isChecked: Boolean) {
            if (isChecked) {
                btn.setBackgroundColor(ContextCompat.getColor(btn.context, R.color.day_button_active_bg))
                btn.setTextColor(ContextCompat.getColor(btn.context, R.color.day_button_active_text))
            } else {
                btn.setBackgroundColor(ContextCompat.getColor(btn.context, R.color.day_button_inactive_bg))
                btn.setTextColor(ContextCompat.getColor(btn.context, R.color.day_button_inactive_text))
            }
        }

        fun updateDaysDisplay(bitmask: Int) {
            selectedDaysBitmask = bitmask
            updateDayButton(dialogBinding.btnDayMon, (bitmask and 1) != 0)
            updateDayButton(dialogBinding.btnDayTue, (bitmask and 2) != 0)
            updateDayButton(dialogBinding.btnDayWed, (bitmask and 4) != 0)
            updateDayButton(dialogBinding.btnDayThu, (bitmask and 8) != 0)
            updateDayButton(dialogBinding.btnDayFri, (bitmask and 16) != 0)
            updateDayButton(dialogBinding.btnDaySat, (bitmask and 32) != 0)
            updateDayButton(dialogBinding.btnDaySun, (bitmask and 64) != 0)

            when (bitmask) {
                ScheduleCalculator.PRESET_EVERYDAY -> dialogBinding.chipEveryday.isChecked = true
                ScheduleCalculator.PRESET_MWF_SUN -> dialogBinding.chipMwfSun.isChecked = true
                ScheduleCalculator.PRESET_TTS -> dialogBinding.chipTts.isChecked = true
                ScheduleCalculator.PRESET_WEEKDAYS -> dialogBinding.chipWeekdays.isChecked = true
                ScheduleCalculator.PRESET_WEEKENDS -> dialogBinding.chipWeekends.isChecked = true
                else -> dialogBinding.cgPresets.clearCheck()
            }
        }

        fun toggleDayBit(bit: Int) {
            selectedDaysBitmask = selectedDaysBitmask xor bit
            updateDaysDisplay(selectedDaysBitmask)
        }

        dialogBinding.btnDayMon.setOnClickListener { toggleDayBit(1) }
        dialogBinding.btnDayTue.setOnClickListener { toggleDayBit(2) }
        dialogBinding.btnDayWed.setOnClickListener { toggleDayBit(4) }
        dialogBinding.btnDayThu.setOnClickListener { toggleDayBit(8) }
        dialogBinding.btnDayFri.setOnClickListener { toggleDayBit(16) }
        dialogBinding.btnDaySat.setOnClickListener { toggleDayBit(32) }
        dialogBinding.btnDaySun.setOnClickListener { toggleDayBit(64) }

        // Preset Chip Listeners
        dialogBinding.chipEveryday.setOnClickListener { updateDaysDisplay(ScheduleCalculator.PRESET_EVERYDAY) }
        dialogBinding.chipMwfSun.setOnClickListener { updateDaysDisplay(ScheduleCalculator.PRESET_MWF_SUN) }
        dialogBinding.chipTts.setOnClickListener { updateDaysDisplay(ScheduleCalculator.PRESET_TTS) }
        dialogBinding.chipWeekdays.setOnClickListener { updateDaysDisplay(ScheduleCalculator.PRESET_WEEKDAYS) }
        dialogBinding.chipWeekends.setOnClickListener { updateDaysDisplay(ScheduleCalculator.PRESET_WEEKENDS) }

        updateTimeDisplay(selectedMinutes)
        updateDaysDisplay(selectedDaysBitmask)

        if (existingMed != null) {
            dialogBinding.etMedName.setText(existingMed.name)
            dialogBinding.etDosage.setText(existingMed.dosage)
            dialogBinding.etUnit.setText(existingMed.unit)
            dialogBinding.switchWakeGated.isChecked = existingMed.wakeGated
            dialogBinding.switchVibrate.isChecked = existingMed.vibrate
            dialogBinding.tvSelectedSound.text = "Sound: $currentSoundTitle"
            dialogBinding.etNotes.setText(existingMed.notes)
            existingMed.stockQuantity?.let { dialogBinding.etStock.setText(it.toString()) }

            lifecycleScope.launch {
                val scheds = repository.getSchedulesForMedication(existingMed.medicationId)
                if (scheds.isNotEmpty()) {
                    selectedMinutes = scheds.first().timeInMinutes
                    selectedDaysBitmask = scheds.first().daysOfWeek
                    updateTimeDisplay(selectedMinutes)
                    updateDaysDisplay(selectedDaysBitmask)
                }
            }
        }

        dialogBinding.etTime.setOnClickListener {
            val h = selectedMinutes / 60
            val m = selectedMinutes % 60
            TimePickerDialog(this, { _, hourOfDay, minute ->
                updateTimeDisplay(hourOfDay * 60 + minute)
            }, h, m, false).show()
        }

        dialogBinding.btnPickSound.setOnClickListener {
            pendingSoundUriCallback = { uri, title ->
                currentSoundUri = uri
                currentSoundTitle = title
                dialogBinding.tvSelectedSound.text = "Sound: $title"
            }
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentSoundUri)
            }
            ringtonePickerLauncher.launch(intent)
        }

        var isPreviewPlaying = false
        dialogBinding.btnPreviewSound.setOnClickListener {
            if (isPreviewPlaying) {
                stopSoundPreview()
                dialogBinding.btnPreviewSound.text = "Preview"
                isPreviewPlaying = false
            } else {
                if (currentSoundUri == null && currentSoundTitle.equals("Silent", ignoreCase = true)) {
                    Toast.makeText(this@MainActivity, "Sound is set to Silent", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val soundToPlay = currentSoundUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                playSoundPreview(soundToPlay)
                dialogBinding.btnPreviewSound.text = "Stop"
                isPreviewPlaying = true
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existingMed == null) "Add Medication" else "Edit Medication")
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ -> stopSoundPreview() }
            .setOnDismissListener { stopSoundPreview() }
            .create()

        dialog.show()

        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = dialogBinding.etMedName.text.toString().trim()
            if (name.isBlank()) {
                dialogBinding.etMedName.error = "Name required"
                Toast.makeText(this@MainActivity, "Name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (selectedDaysBitmask == 0) {
                Toast.makeText(this@MainActivity, "Select at least one active day", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val dosage = dialogBinding.etDosage.text.toString().trim()
            val unit = dialogBinding.etUnit.text.toString().trim()
            val wakeGated = dialogBinding.switchWakeGated.isChecked
            val vibrate = dialogBinding.switchVibrate.isChecked
            val notes = dialogBinding.etNotes.text.toString().trim()
            val stockStr = dialogBinding.etStock.text.toString().trim()
            val stock = stockStr.toDoubleOrNull()

            lifecycleScope.launch {
                val medToSave = existingMed?.copy(
                    name = name,
                    dosage = dosage,
                    unit = unit,
                    wakeGated = wakeGated,
                    soundUri = currentSoundUri?.toString() ?: "",
                    soundTitle = currentSoundTitle,
                    vibrate = vibrate,
                    notes = notes,
                    stockQuantity = stock
                ) ?: MedicationEntity(
                    name = name,
                    dosage = dosage,
                    unit = unit,
                    wakeGated = wakeGated,
                    soundUri = currentSoundUri?.toString() ?: "",
                    soundTitle = currentSoundTitle,
                    vibrate = vibrate,
                    notes = notes,
                    stockQuantity = stock
                )

                val schedule = ScheduleEntity(
                    medicationId = medToSave.medicationId,
                    timeInMinutes = selectedMinutes,
                    daysOfWeek = selectedDaysBitmask
                )

                if (existingMed == null) {
                    repository.insertMedicationWithSchedules(medToSave, listOf(schedule))
                } else {
                    repository.updateMedication(medToSave)
                    val existingScheds = repository.getSchedulesForMedication(medToSave.medicationId)
                    if (existingScheds.isNotEmpty()) {
                        val updatedSched = existingScheds.first().copy(
                            timeInMinutes = selectedMinutes,
                            daysOfWeek = selectedDaysBitmask
                        )
                        repository.upsertSchedule(updatedSched)
                    }
                    // FIX 2: Remove stale SCHEDULED/DUE events; expandRecurringSchedules rebuilds correct ones
                    repository.deletePendingEventsForMedication(existingMed.medicationId)
                }

                evaluator.evaluateMedicationState(System.currentTimeMillis(), launchFullScreenActivity = false, source = "app")
                dialog.dismiss()
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Sound preview
    // ────────────────────────────────────────────────────────────────────────────

    private fun playSoundPreview(uri: Uri?) {
        stopSoundPreview()
        try {
            val targetUri = uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            previewMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(this@MainActivity, targetUri)
                isLooping = false
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopSoundPreview() {
        previewMediaPlayer?.stop()
        previewMediaPlayer?.release()
        previewMediaPlayer = null
    }

    private fun startFadePreview(durationSeconds: Int) {
        stopFadePreview()
        stopSoundPreview()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            previewMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(this@MainActivity, uri)
                isLooping = true
                setVolume(0.05f, 0.05f)
                prepare()
                start()
            }
            isFadePreviewPlaying = true
            binding.btnPreviewGentleVolume.text = "Stop Fade Preview"

            fadePreviewJob = lifecycleScope.launch {
                val totalSteps = (durationSeconds * 1000L) / 500L
                val stepIncrement = (1.0f - 0.05f) / totalSteps.toFloat()
                var currentVol = 0.05f

                while (isActive && currentVol < 1.0f) {
                    delay(500L)
                    currentVol = (currentVol + stepIncrement).coerceAtMost(1.0f)
                    try {
                        previewMediaPlayer?.setVolume(currentVol, currentVol)
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopFadePreview() {
        fadePreviewJob?.cancel()
        fadePreviewJob = null
        isFadePreviewPlaying = false
        binding.btnPreviewGentleVolume.text = "Test Volume Fade-In"
        previewMediaPlayer?.stop()
        previewMediaPlayer?.release()
        previewMediaPlayer = null
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Settings tab
    // ────────────────────────────────────────────────────────────────────────────

    @SuppressLint("BatteryLife")
    private fun setupSettingsTab() {
        val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)

        // Alarm Themes & Personalization Setup
        val currentTheme = prefs.getString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_ELECTRIC_BLUE)
        when (currentTheme) {
            MedicationAlarmActivity.THEME_EMERALD -> binding.chipThemeEmerald.isChecked = true
            MedicationAlarmActivity.THEME_MIDNIGHT -> binding.chipThemeMidnight.isChecked = true
            MedicationAlarmActivity.THEME_SUNSET -> binding.chipThemeSunset.isChecked = true
            MedicationAlarmActivity.THEME_ROYAL -> binding.chipThemeRoyal.isChecked = true
            else -> binding.chipThemeElectricBlue.isChecked = true
        }

        binding.chipThemeElectricBlue.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_ELECTRIC_BLUE).apply(); recreate() }
        binding.chipThemeEmerald.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_EMERALD).apply(); recreate() }
        binding.chipThemeMidnight.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_MIDNIGHT).apply(); recreate() }
        binding.chipThemeSunset.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_SUNSET).apply(); recreate() }
        binding.chipThemeRoyal.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_ROYAL).apply(); recreate() }

        // Mascot Mode Setup
        val currentMascot = prefs.getString(MedicationAlarmActivity.KEY_ALARM_MASCOT, MedicationAlarmActivity.MASCOT_ROBOT)
        when (currentMascot) {
            MedicationAlarmActivity.MASCOT_ROBOT -> binding.chipMascotRobot.isChecked = true
            MedicationAlarmActivity.MASCOT_MINIMAL -> binding.chipMascotMinimal.isChecked = true
            else -> binding.chipMascotCustom.isChecked = true
        }

        binding.chipMascotCustom.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_MASCOT, MedicationAlarmActivity.MASCOT_CUSTOM).apply() }
        binding.chipMascotRobot.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_MASCOT, MedicationAlarmActivity.MASCOT_ROBOT).apply() }
        binding.chipMascotMinimal.setOnClickListener { prefs.edit().putString(MedicationAlarmActivity.KEY_ALARM_MASCOT, MedicationAlarmActivity.MASCOT_MINIMAL).apply() }

        binding.btnPickCustomWallpaper.setOnClickListener {
            wallpaperPickerLauncher.launch(arrayOf("image/*"))
        }

        binding.btnClearCustomWallpaper.setOnClickListener {
            prefs.edit()
                .remove(MedicationAlarmActivity.KEY_CUSTOM_BACKGROUND_URI)
                .putString(MedicationAlarmActivity.KEY_ALARM_MASCOT, MedicationAlarmActivity.MASCOT_CUSTOM)
                .apply()
            binding.chipMascotCustom.isChecked = true
            Toast.makeText(this, "Custom wallpaper cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnPreviewAlarmScreen.setOnClickListener {
            lifecycleScope.launch {
                val now = System.currentTimeMillis()
                val testMed = MedicationEntity(
                    name = "Vitamin D3 (Theme Preview)",
                    dosage = "1",
                    unit = "tablet",
                    wakeGated = true,
                    notes = "Take with water",
                    stockQuantity = 30.0
                )
                val medId = repository.insertMedicationWithSchedules(
                    testMed,
                    listOf(ScheduleEntity(medicationId = 0, timeInMinutes = 420))
                )
                val dueEvent = DoseEventEntity(
                    medicationId = medId,
                    scheduledAt = now - 60_000L,
                    dueAt = now - 60_000L,
                    state = DoseState.DUE
                )
                val eventId = repository.insertDoseEventIfNotExists(dueEvent)

                val intent = Intent(this@MainActivity, MedicationAlarmActivity::class.java).apply {
                    putExtra("EXTRA_DOSE_EVENT_ID", eventId)
                    putExtra("EXTRA_MEDICATION_ID", medId)
                }
                startActivity(intent)
            }
        }

        // Gentle Volume Settings
        val isGentleVolume = prefs.getBoolean(MedicationAlarmService.KEY_GENTLE_VOLUME_ENABLED, true)
        val fadeSecs = prefs.getInt(MedicationAlarmService.KEY_GENTLE_VOLUME_SECONDS, 15).coerceIn(5, 30)

        binding.switchGentleVolume.isChecked = isGentleVolume
        binding.sliderGentleVolume.value = fadeSecs.toFloat()
        binding.tvGentleVolumeDurationLabel.text = "Fade-in duration: $fadeSecs seconds"

        binding.switchGentleVolume.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MedicationAlarmService.KEY_GENTLE_VOLUME_ENABLED, isChecked).apply()
        }

        binding.sliderGentleVolume.addOnChangeListener { _, value, _ ->
            val secs = value.toInt()
            binding.tvGentleVolumeDurationLabel.text = "Fade-in duration: $secs seconds"
            prefs.edit().putInt(MedicationAlarmService.KEY_GENTLE_VOLUME_SECONDS, secs).apply()
        }

        binding.btnPreviewGentleVolume.setOnClickListener {
            if (isFadePreviewPlaying) {
                stopFadePreview()
            } else {
                val currentFadeSecs = binding.sliderGentleVolume.value.toInt()
                startFadePreview(currentFadeSecs)
            }
        }

        // FIX 4: Wake-Gated Preferences — numeric EditText instead of slider
        when (prefs.getString(MedicationStateEvaluator.KEY_UNLOCK_DELAY_MODE, "random")) {
            "fixed" -> binding.rbDelayFixed.isChecked = true
            "immediate" -> binding.rbDelayImmediate.isChecked = true
            else -> binding.rbDelayRandom.isChecked = true
        }

        val currentDelaySecs = prefs.getInt(MedicationStateEvaluator.KEY_UNLOCK_DELAY_SECONDS, 7)
        binding.etUnlockDelaySeconds.setText(currentDelaySecs.toString())
        binding.tvDelaySecondsLabel.text = "Delay seconds (max 120): $currentDelaySecs"

        binding.rgDelayMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbDelayFixed -> "fixed"
                R.id.rbDelayImmediate -> "immediate"
                else -> "random"
            }
            prefs.edit().putString(MedicationStateEvaluator.KEY_UNLOCK_DELAY_MODE, mode).apply()
        }

        val saveDelayFromText = {
            val raw = binding.etUnlockDelaySeconds.text?.toString()?.trim() ?: ""
            val parsed = raw.toIntOrNull() ?: 0
            val clamped = parsed.coerceIn(0, 120)
            binding.tvDelaySecondsLabel.text = "Delay seconds (max 120): $clamped"
            prefs.edit().putInt(MedicationStateEvaluator.KEY_UNLOCK_DELAY_SECONDS, clamped).apply()
        }

        binding.etUnlockDelaySeconds.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { saveDelayFromText() }
        })

        binding.etUnlockDelaySeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveDelayFromText()
        }

        binding.etUnlockDelaySeconds.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveDelayFromText()
                binding.etUnlockDelaySeconds.clearFocus()
                true
            } else false
        }


        // Snooze Minutes Settings
        val currentSnoozeMins = prefs.getInt(MedicationStateEvaluator.KEY_SNOOZE_MINUTES, 10)
        binding.etSnoozeMinutes.setText(currentSnoozeMins.toString())
        binding.tvSnoozeMinutesLabel.text = "Snooze minutes (1-120): $currentSnoozeMins"

        val saveSnoozeFromText = {
            val raw = binding.etSnoozeMinutes.text?.toString()?.trim() ?: ""
            val parsed = raw.toIntOrNull() ?: 10
            val clamped = parsed.coerceIn(1, 120)
            binding.tvSnoozeMinutesLabel.text = "Snooze minutes (1-120): $clamped"
            prefs.edit().putInt(MedicationStateEvaluator.KEY_SNOOZE_MINUTES, clamped).apply()
        }

        binding.etSnoozeMinutes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { saveSnoozeFromText() }
        })

        binding.etSnoozeMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveSnoozeFromText()
        }

        // ────────────────────────────────────────────────────────────────────────
        // Batch 6: Quick-Open Catch-Up Settings
        // ────────────────────────────────────────────────────────────────────────
        val isQuickOpen = prefs.getBoolean(MedicationStateEvaluator.KEY_QUICK_OPEN_ENABLED, false)
        val quickOpenSecs = prefs.getInt(MedicationStateEvaluator.KEY_QUICK_OPEN_WINDOW_SECONDS, 15).coerceIn(5, 120)

        binding.switchQuickOpen.isChecked = isQuickOpen
        binding.etQuickOpenSeconds.setText(quickOpenSecs.toString())
        binding.tvQuickOpenSecondsLabel.text = "Minimum unlock duration seconds (5-120): $quickOpenSecs"

        binding.switchQuickOpen.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MedicationStateEvaluator.KEY_QUICK_OPEN_ENABLED, isChecked).apply()
        }

        val saveQuickOpenFromText = {
            val raw = binding.etQuickOpenSeconds.text?.toString()?.trim() ?: ""
            val parsed = raw.toIntOrNull() ?: 15
            val clamped = parsed.coerceIn(5, 120)
            binding.tvQuickOpenSecondsLabel.text = "Minimum unlock duration seconds (5-120): $clamped"
            prefs.edit().putInt(MedicationStateEvaluator.KEY_QUICK_OPEN_WINDOW_SECONDS, clamped).apply()
        }

        binding.etQuickOpenSeconds.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { saveQuickOpenFromText() }
        })

        binding.etQuickOpenSeconds.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveQuickOpenFromText()
        }

        binding.etQuickOpenSeconds.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveQuickOpenFromText()
                binding.etQuickOpenSeconds.clearFocus()
                true
            } else false
        }

        // Usage Access button
        binding.btnUsageAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }

        // ────────────────────────────────────────────────────────────────────────
        // Batch 6: Safety Net Settings
        // ────────────────────────────────────────────────────────────────────────
        val isSafetyNet = prefs.getBoolean(MedicationStateEvaluator.KEY_SAFETY_NET_ENABLED, false)
        val safetyNetMins = prefs.getInt(MedicationStateEvaluator.KEY_SAFETY_NET_MINUTES, 30).coerceIn(10, 300)

        binding.switchSafetyNet.isChecked = isSafetyNet
        binding.etSafetyNetMinutes.setText(safetyNetMins.toString())
        binding.tvSafetyNetMinutesLabel.text = "Safety net minutes (10-300): $safetyNetMins"

        binding.switchSafetyNet.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MedicationStateEvaluator.KEY_SAFETY_NET_ENABLED, isChecked).apply()
        }

        val saveSafetyNetFromText = {
            val raw = binding.etSafetyNetMinutes.text?.toString()?.trim() ?: ""
            val parsed = raw.toIntOrNull() ?: 30
            val clamped = parsed.coerceIn(10, 300)
            binding.tvSafetyNetMinutesLabel.text = "Safety net minutes (10-300): $clamped"
            prefs.edit().putInt(MedicationStateEvaluator.KEY_SAFETY_NET_MINUTES, clamped).apply()
        }

        binding.etSafetyNetMinutes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { saveSafetyNetFromText() }
        })

        binding.etSafetyNetMinutes.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveSafetyNetFromText()
        }

        binding.etSafetyNetMinutes.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveSafetyNetFromText()
                binding.etSafetyNetMinutes.clearFocus()
                true
            } else false
        }

        // ────────────────────────────────────────────────────────────────────────
        // Batch 6: Ring Decision Log Debug Card
        // ────────────────────────────────────────────────────────────────────────
        binding.btnViewRingLog.setOnClickListener {
            val logContent = RingDecisionLog.readRecent(this, 100)
            MaterialAlertDialogBuilder(this)
                .setTitle("Ring Decision Log")
                .setMessage(logContent)
                .setPositiveButton("Copy") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ring-log", logContent)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        // System & Reliability
        binding.btnExactAlarmPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        binding.btnFullScreenPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        binding.btnBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        // Backup & Restore
        binding.btnExportBackup.setOnClickListener {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val fileName = "medwake-backup-${sdf.format(Date())}.json"
            exportBackupLauncher.launch(fileName)
        }

        binding.btnRestoreBackup.setOnClickListener {
            restoreBackupLauncher.launch(arrayOf("application/octet-stream"))
        }

    }


    // ────────────────────────────────────────────────────────────────────────────
    // Backup & Restore
    // ────────────────────────────────────────────────────────────────────────────

    private suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("version", 1)
        json.put("exportedAt", System.currentTimeMillis())

        // Medications
        val medsArray = JSONArray()
        for (med in repository.getAllMedications()) {
            val medObj = JSONObject()
            medObj.put("medicationId", med.medicationId)
            medObj.put("name", med.name)
            medObj.put("dosage", med.dosage)
            medObj.put("unit", med.unit)
            medObj.put("wakeGated", med.wakeGated)
            medObj.put("soundUri", med.soundUri)
            medObj.put("soundTitle", med.soundTitle)
            medObj.put("vibrate", med.vibrate)
            medObj.put("notes", med.notes)
            medObj.put("stockQuantity", med.stockQuantity ?: JSONObject.NULL)
            medObj.put("createdAt", med.createdAt)
            medsArray.put(medObj)
        }
        json.put("medications", medsArray)

        // Schedules
        val schedArray = JSONArray()
        for (sched in repository.getAllSchedules()) {
            val schedObj = JSONObject()
            schedObj.put("scheduleId", sched.scheduleId)
            schedObj.put("medicationId", sched.medicationId)
            schedObj.put("timeInMinutes", sched.timeInMinutes)
            schedObj.put("daysOfWeek", sched.daysOfWeek)
            schedArray.put(schedObj)
        }
        json.put("schedules", schedArray)

        // Dose Events
        val eventsArray = JSONArray()
        for (event in repository.getAllDoseEvents()) {
            val eventObj = JSONObject()
            eventObj.put("doseEventId", event.doseEventId)
            eventObj.put("medicationId", event.medicationId)
            eventObj.put("scheduledAt", event.scheduledAt)
            eventObj.put("dueAt", event.dueAt)
            eventObj.put("state", event.state.name)
            eventObj.put("actionTimestamp", event.actionTimestamp ?: JSONObject.NULL)
            eventObj.put("plannedTriggerAt", event.plannedTriggerAt ?: JSONObject.NULL)
            eventObj.put("stockHandled", event.stockHandled)
            eventsArray.put(eventObj)
        }
        json.put("doseEvents", eventsArray)

        // Prefs
        val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
        val prefsObj = JSONObject()
        prefsObj.put("unlock_delay_mode", prefs.getString(MedicationStateEvaluator.KEY_UNLOCK_DELAY_MODE, "random"))
        prefsObj.put("unlock_delay_seconds", prefs.getInt(MedicationStateEvaluator.KEY_UNLOCK_DELAY_SECONDS, 7))
        prefsObj.put("snooze_minutes", prefs.getInt(MedicationStateEvaluator.KEY_SNOOZE_MINUTES, 10))
        prefsObj.put("alarm_theme", prefs.getString(MedicationAlarmActivity.KEY_ALARM_THEME, MedicationAlarmActivity.THEME_ELECTRIC_BLUE))
        prefsObj.put("alarm_mascot_style", prefs.getString(MedicationAlarmActivity.KEY_ALARM_MASCOT, MedicationAlarmActivity.MASCOT_ROBOT))
        prefsObj.put("alarm_custom_bg_uri", prefs.getString(MedicationAlarmActivity.KEY_CUSTOM_BACKGROUND_URI, null) ?: JSONObject.NULL)
        // Batch 6 — new ring-gate prefs
        prefsObj.put("quick_open_enabled", prefs.getBoolean(MedicationStateEvaluator.KEY_QUICK_OPEN_ENABLED, false))
        prefsObj.put("quick_open_window_seconds", prefs.getInt(MedicationStateEvaluator.KEY_QUICK_OPEN_WINDOW_SECONDS, 15))
        prefsObj.put("safety_net_enabled", prefs.getBoolean(MedicationStateEvaluator.KEY_SAFETY_NET_ENABLED, false))
        prefsObj.put("safety_net_minutes", prefs.getInt(MedicationStateEvaluator.KEY_SAFETY_NET_MINUTES, 30))
        json.put("prefs", prefsObj)

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(json.toString(2).toByteArray())
        }
    }

    private suspend fun restoreBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Cannot read file")
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        inputStream.close()

        val json = JSONObject(jsonString)
        if (!json.has("version") || !json.has("medications")) {
            throw Exception("Invalid backup format")
        }

        // Validate parsed OK before confirming
        val medsArray = json.getJSONArray("medications")
        val schedArray = json.getJSONArray("schedules")
        val eventsArray = json.getJSONArray("doseEvents")

        // Show confirmation on main thread
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Restore Backup?")
                .setMessage("Replace all current data with this backup?\n\nThis will delete all current medications, schedules, and dose events.")
                .setPositiveButton("Replace") { _, _ ->
                    lifecycleScope.launch {
                        performRestore(json, medsArray, schedArray, eventsArray)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun performRestore(
        json: JSONObject,
        medsArray: JSONArray,
        schedArray: JSONArray,
        eventsArray: JSONArray
    ) = withContext(Dispatchers.IO) {
        // Delete all existing data (FK-safe order)
        repository.clearAll()

        // Restore medications — insert one-by-one with fresh auto-generated ids
        // and build oldMedicationId -> newMedicationId mapping
        val medIdMap = mutableMapOf<Long, Long>()
        for (i in 0 until medsArray.length()) {
            val obj = medsArray.getJSONObject(i)
            val entity = MedicationEntity(
                medicationId = 0, // let Room auto-generate
                name = obj.getString("name"),
                dosage = obj.optString("dosage", ""),
                unit = obj.optString("unit", ""),
                wakeGated = obj.optBoolean("wakeGated", true),
                soundUri = obj.optString("soundUri", ""),
                soundTitle = obj.optString("soundTitle", "Default Alarm"),
                vibrate = obj.optBoolean("vibrate", true),
                notes = obj.optString("notes", ""),
                stockQuantity = if (obj.isNull("stockQuantity")) null else obj.getDouble("stockQuantity"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
            val newId = repository.insertMedicationWithSchedules(entity, emptyList())
            medIdMap[obj.getLong("medicationId")] = newId
        }

        // Restore schedules — fresh ids, medicationId remapped
        for (i in 0 until schedArray.length()) {
            val obj = schedArray.getJSONObject(i)
            val schedule = ScheduleEntity(
                scheduleId = 0, // let Room auto-generate
                medicationId = medIdMap.getValue(obj.getLong("medicationId")),
                timeInMinutes = obj.getInt("timeInMinutes"),
                daysOfWeek = obj.optInt("daysOfWeek", 0b1111111)
            )
            repository.insertScheduleReturningId(schedule)
        }

        // Restore dose events — fresh ids, medicationId remapped, rescheduledFromId remapped
        val eventIdMap = mutableMapOf<Long, Long>()
        for (i in 0 until eventsArray.length()) {
            val obj = eventsArray.getJSONObject(i)
            val oldEventId = obj.getLong("doseEventId")
            val event = DoseEventEntity(
                doseEventId = 0, // let Room auto-generate
                medicationId = medIdMap.getValue(obj.getLong("medicationId")),
                scheduledAt = obj.getLong("scheduledAt"),
                dueAt = obj.getLong("dueAt"),
                state = DoseState.valueOf(obj.getString("state")),
                actionTimestamp = if (obj.isNull("actionTimestamp")) null else obj.getLong("actionTimestamp"),
                plannedTriggerAt = if (obj.isNull("plannedTriggerAt")) null else obj.getLong("plannedTriggerAt"),
                stockHandled = obj.optBoolean("stockHandled", false),
                rescheduledFromId = if (obj.isNull("rescheduledFromId")) null else {
                    val oldRefId = obj.getLong("rescheduledFromId")
                    eventIdMap[oldRefId] ?: oldRefId
                }
            )
            val newEventId = repository.insertDoseEventReturningId(event)
            eventIdMap[oldEventId] = newEventId
        }

        // Restore prefs
        if (json.has("prefs")) {
            val prefsObj = json.getJSONObject("prefs")
            val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (prefsObj.has("unlock_delay_mode")) editor.putString(MedicationStateEvaluator.KEY_UNLOCK_DELAY_MODE, prefsObj.getString("unlock_delay_mode"))
            if (prefsObj.has("unlock_delay_seconds")) editor.putInt(MedicationStateEvaluator.KEY_UNLOCK_DELAY_SECONDS, prefsObj.getInt("unlock_delay_seconds"))
            if (prefsObj.has("snooze_minutes")) editor.putInt(MedicationStateEvaluator.KEY_SNOOZE_MINUTES, prefsObj.getInt("snooze_minutes"))
            if (prefsObj.has("alarm_theme")) editor.putString(MedicationAlarmActivity.KEY_ALARM_THEME, prefsObj.getString("alarm_theme"))
            if (prefsObj.has("alarm_mascot_style")) editor.putString(MedicationAlarmActivity.KEY_ALARM_MASCOT, prefsObj.getString("alarm_mascot_style"))
            if (prefsObj.has("alarm_custom_bg_uri") && !prefsObj.isNull("alarm_custom_bg_uri")) {
                editor.putString(MedicationAlarmActivity.KEY_CUSTOM_BACKGROUND_URI, prefsObj.getString("alarm_custom_bg_uri"))
            }
            // Batch 6 — restore new ring-gate prefs
            if (prefsObj.has("quick_open_enabled")) editor.putBoolean(MedicationStateEvaluator.KEY_QUICK_OPEN_ENABLED, prefsObj.getBoolean("quick_open_enabled"))
            if (prefsObj.has("quick_open_window_seconds")) editor.putInt(MedicationStateEvaluator.KEY_QUICK_OPEN_WINDOW_SECONDS, prefsObj.getInt("quick_open_window_seconds"))
            if (prefsObj.has("safety_net_enabled")) editor.putBoolean(MedicationStateEvaluator.KEY_SAFETY_NET_ENABLED, prefsObj.getBoolean("safety_net_enabled"))
            if (prefsObj.has("safety_net_minutes")) editor.putInt(MedicationStateEvaluator.KEY_SAFETY_NET_MINUTES, prefsObj.getInt("safety_net_minutes"))
            editor.apply()
        }

        // Re-expand and re-arm
        evaluator.evaluateMedicationState(System.currentTimeMillis(), launchFullScreenActivity = false, source = "restore")

        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Backup restored", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSettingsStatus() {
        val exactAllowed = alarmScheduler.canScheduleExact()
        binding.tvExactAlarmStatus.text = if (exactAllowed) "Exact Alarms: Granted" else "Exact Alarms: Not Granted"
        binding.btnExactAlarmPermission.visibility = if (exactAllowed) View.GONE else View.VISIBLE

        // Batch 6: Usage access status
        val usageGranted = UsageStatsUnlockOracle.usageAccessGranted(this)
        binding.tvUsageAccessStatus.text = if (usageGranted) "Usage Access: Granted" else "Usage Access: Not Granted"
        binding.tvUsageAccessHint.visibility = if (usageGranted) View.GONE else View.VISIBLE
        binding.btnUsageAccess.visibility = if (usageGranted) View.GONE else View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val canFsi = notificationManager.canUseFullScreenIntent()
            binding.tvFullScreenStatus.text = if (canFsi) "Full-Screen Intent: Granted" else "Full-Screen Intent: Disabled"
            binding.btnFullScreenPermission.visibility = if (canFsi) View.GONE else View.VISIBLE
        } else {
            binding.tvFullScreenStatus.text = "Full-Screen Intent: Supported"
            binding.btnFullScreenPermission.visibility = View.GONE
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        binding.tvBatteryOptimizationStatus.text = if (isIgnoringBattery) "Battery Optimization: Unrestricted" else "Battery Optimization: Optimized (Tap below to exempt)"
        binding.btnBatteryOptimization.visibility = if (isIgnoringBattery) View.GONE else View.VISIBLE

        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        binding.tvOverlayStatus.text = if (canOverlay) "Display over other apps: Granted" else "Display over other apps: Off"
        binding.btnOverlayPermission.visibility = if (canOverlay) View.GONE else View.VISIBLE
    }
}
