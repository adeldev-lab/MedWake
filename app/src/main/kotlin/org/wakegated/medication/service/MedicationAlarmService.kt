package org.wakegated.medication.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import org.wakegated.medication.data.repository.MedicationRepository
import org.wakegated.medication.domain.evaluator.MedicationStateEvaluator
import org.wakegated.medication.domain.manager.MedicationNotificationHelper

class MedicationAlarmService : Service() {

    companion object {
        private const val TAG = "MedAlarmService"
        const val ACTION_START_ALARM = "org.wakegated.medication.START_ALARM"
        const val ACTION_STOP_ALARM = "org.wakegated.medication.STOP_ALARM"
        const val ACTION_TOGGLE_MUTE = "org.wakegated.medication.TOGGLE_MUTE"
        const val EXTRA_DOSE_EVENT_ID = "EXTRA_DOSE_EVENT_ID"
        const val EXTRA_MEDICATION_ID = "EXTRA_MEDICATION_ID"

        const val KEY_GENTLE_VOLUME_ENABLED = "gentle_volume_enabled"
        const val KEY_GENTLE_VOLUME_SECONDS = "gentle_volume_seconds"
    }

    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var muted = false
    private var vibrator: Vibrator? = null
    private var volumeFadeJob: Job? = null
    private var currentRingingDoseEventId: Long = -1L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationHelper: MedicationNotificationHelper
    private lateinit var repository: MedicationRepository

    override fun onCreate() {
        super.onCreate()
        notificationHelper = MedicationNotificationHelper(this)
        repository = MedicationRepository(this)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_ALARM
        val doseEventId = intent?.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L) ?: -1L
        val medicationId = intent?.getLongExtra(EXTRA_MEDICATION_ID, -1L) ?: -1L

        when (action) {
            ACTION_START_ALARM -> {
                if (doseEventId != -1L && medicationId != -1L) {
                    startAlarm(doseEventId, medicationId)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP_ALARM -> {
                stopAlarmAndSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                muted = !muted
                Log.i(TAG, "Alarm sound ${if (muted) "MUTED" else "UNMUTED"}")
                try {
                    mediaPlayer?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                } catch (e: Exception) { /* not playing */ }
            }
        }

        return START_STICKY
    }

    private fun startAlarm(doseEventId: Long, medicationId: Long) {
        // Audio idempotency: if already ringing for the same dose, do nothing
        if (currentRingingDoseEventId == doseEventId && mediaPlayer?.isPlaying == true) {
            return
        }

        serviceScope.launch {
            val med = repository.getMedicationById(medicationId)
            val event = repository.getDoseEventById(doseEventId)

            if (med == null || event == null) {
                stopSelf()
                return@launch
            }

            try {
                // Track current ringing dose
                currentRingingDoseEventId = doseEventId

                // 1. Start foreground notification
                val notification = notificationHelper.buildAlarmNotification(med, event, ringing = true)
                startForeground(MedicationNotificationHelper.ALARM_NOTIFICATION_ID, notification)

                // 2. Start Audio Playback with Gentle Volume Fade-in
                startSound(med.soundUri)

                // 3. Start Vibration
                if (med.vibrate) {
                    startVibration()
                }

            } catch (e: Exception) {
                Log.e(TAG, "startAlarm failed", e)
                stopSelf()
                return@launch
            }
        }
    }

    private fun startSound(customSoundUri: String) {
        try {
            volumeFadeJob?.cancel()
            mediaPlayer?.release()

            val uri = if (customSoundUri.isNotBlank()) {
                Uri.parse(customSoundUri)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val prefs = getSharedPreferences(MedicationStateEvaluator.PREFS_NAME, Context.MODE_PRIVATE)
            val isGentleVolume = prefs.getBoolean(KEY_GENTLE_VOLUME_ENABLED, true)
            val fadeSeconds = prefs.getInt(KEY_GENTLE_VOLUME_SECONDS, 15).coerceIn(5, 30)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(this@MedicationAlarmService, uri)
                isLooping = true
                if (isGentleVolume) {
                    setVolume(0.05f, 0.05f)
                } else {
                    setVolume(1.0f, 1.0f)
                }
                prepare()
                start()
            }

            if (isGentleVolume) {
                startVolumeRampUp(fadeSeconds)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm audio", e)
        }
    }

    private fun startVolumeRampUp(durationSeconds: Int) {
        volumeFadeJob = serviceScope.launch {
            val totalSteps = (durationSeconds * 1000L) / 500L
            val stepIncrement = (1.0f - 0.05f) / totalSteps.toFloat()
            var currentVol = 0.05f

            while (isActive && currentVol < 1.0f) {
                delay(500L)
                currentVol = (currentVol + stepIncrement).coerceAtMost(1.0f)
                try {
                    if (!muted) {
                        mediaPlayer?.setVolume(currentVol, currentVol)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun startVibration() {
        try {
            val pattern = longArrayOf(500, 500, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration", e)
        }
    }

    private fun stopAlarmAndSelf() {
        volumeFadeJob?.cancel()
        volumeFadeJob = null

        val stoppedDoseEventId = currentRingingDoseEventId
        currentRingingDoseEventId = -1L

        try {
            mediaPlayer?.stop()
        } catch (e: IllegalStateException) {
            // MediaPlayer not in a valid state; ignore
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer?.release()
        mediaPlayer = null

        vibrator?.cancel()
        vibrator = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancelAlarmNotification()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(MedicationNotificationHelper.ALARM_NOTIFICATION_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopAlarmAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
