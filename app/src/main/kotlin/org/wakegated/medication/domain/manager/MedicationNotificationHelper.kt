package org.wakegated.medication.domain.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import org.wakegated.medication.R
import org.wakegated.medication.data.entity.DoseEventEntity
import org.wakegated.medication.data.entity.MedicationEntity
import org.wakegated.medication.receiver.ExternalActionReceiver
import org.wakegated.medication.ui.MainActivity
import org.wakegated.medication.ui.alarm.MedicationAlarmActivity

class MedicationNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ALARM_ID = "medwake_alarm_channel"
        const val CHANNEL_ALARM_DUE_ID = "alarm_due"
        const val CHANNEL_MONITOR_ID = "medwake_monitor_channel"
        const val ALARM_NOTIFICATION_ID = 1001
        const val MONITOR_NOTIFICATION_ID = 1002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // High priority channel for active alarms (ringing — sound + vibration)
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "Medication Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen alerts and sound for due medications"
                setBypassDnd(true)
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(null, audioAttributes) // Sound is driven by MedicationAlarmService
            }

            // Silent FSI channel for due notification while locked
            val dueChannel = NotificationChannel(
                CHANNEL_ALARM_DUE_ID,
                "Dose due",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Silent full-screen alert while the phone is locked"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Low priority channel for unlock monitoring service
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR_ID,
                "Unlock Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains wake-gated unlock readiness when medications are due"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(alarmChannel)
            notificationManager.createNotificationChannel(dueChannel)
            notificationManager.createNotificationChannel(monitorChannel)
        }
    }

    fun buildAlarmNotification(
        medication: MedicationEntity,
        doseEvent: DoseEventEntity,
        ringing: Boolean = true
    ): Notification {
        val channelId = if (ringing) CHANNEL_ALARM_ID else CHANNEL_ALARM_DUE_ID

        val fullScreenIntent = Intent(context, MedicationAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_DOSE_EVENT_ID", doseEvent.doseEventId)
            putExtra("EXTRA_MEDICATION_ID", medication.medicationId)
        }

        val requestBase = doseEvent.doseEventId.hashCode()
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            requestBase,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Take Now
        val takeIntent = Intent(context, ExternalActionReceiver::class.java).apply {
            action = "org.wakegated.medication.ACTION_MARK_TAKEN"
            putExtra("EXTRA_DOSE_EVENT_ID", doseEvent.doseEventId)
        }
        val takePendingIntent = PendingIntent.getBroadcast(
            context,
            requestBase * 4 + 1,
            takeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Skip
        val skipIntent = Intent(context, ExternalActionReceiver::class.java).apply {
            action = "org.wakegated.medication.ACTION_MARK_SKIPPED"
            putExtra("EXTRA_DOSE_EVENT_ID", doseEvent.doseEventId)
        }
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            requestBase * 4 + 2,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Snooze (configurable minutes)
        val prefs = context.getSharedPreferences("medwake_prefs", Context.MODE_PRIVATE)
        val snoozeMinutes = prefs.getInt("snooze_minutes", 10)
        val snoozeIntent = Intent(context, ExternalActionReceiver::class.java).apply {
            action = "org.wakegated.medication.ACTION_SNOOZE"
            putExtra("EXTRA_DOSE_EVENT_ID", doseEvent.doseEventId)
            putExtra("EXTRA_MINUTES", snoozeMinutes)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            requestBase * 4 + 3,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dosageText = if (medication.dosage.isNotBlank()) "${medication.dosage} ${medication.unit}".trim() else ""
        val contentText = if (dosageText.isNotBlank()) "Take $dosageText" else "Time to take your medication"

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("\u23F0 ${medication.name}")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPendingIntent)
            .apply {
                // Batch 6: FSI runtime check — heads-up fallback if FSI not available
                if (ringing) {
                    val canUseFsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        notificationManager.canUseFullScreenIntent()
                    } else {
                        true
                    }
                    if (canUseFsi) {
                        setFullScreenIntent(fullScreenPendingIntent, true)
                    }
                    // Without FSI, heads-up fallback with Take/Skip/Snooze actions still present
                }
            }
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_agenda, "Take", takePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Skip", skipPendingIntent)
            .addAction(android.R.drawable.ic_popup_sync, "Snooze ${snoozeMinutes}m", snoozePendingIntent)
            .build()
    }

    fun buildMonitorNotification(pendingDueCount: Int): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (pendingDueCount == 1) {
            "1 medication is due and waiting for phone unlock"
        } else {
            "$pendingDueCount medications are due and waiting for unlock"
        }

        return NotificationCompat.Builder(context, CHANNEL_MONITOR_ID)
            .setContentTitle("MedWake Ready")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun cancelAlarmNotification() {
        notificationManager.cancel(ALARM_NOTIFICATION_ID)
    }

    fun postAlarmNotification(medication: MedicationEntity, doseEvent: DoseEventEntity, ringing: Boolean = true): Boolean {
        return try {
            notificationManager.notify(ALARM_NOTIFICATION_ID, buildAlarmNotification(medication, doseEvent, ringing))
            true
        } catch (e: Exception) { false }
    }
}
