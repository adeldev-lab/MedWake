# Architecture & Technical Design: Standalone Wake-Gated Medication Alarm App

## 1. Executive Overview & Core Problem Statement

Traditional medication reminder applications suffer from a fundamental usability failure:
- Alarms scheduled for early morning (e.g., 07:00) sound loudly while the user is still sleeping, disturbing rest.
- Passive notifications are ignored, dismissed while groggy, or silenced by Doze mode.

The **Wake-Gated Medication Alarm App** solves this problem by enforcing a strict architectural separation:

> **Separation Principle: "Medication becoming DUE" is completely independent from "disturbing the user with an alarm".**

### Wake-Gated Workflow
1. **Configured Due Time (e.g., 07:00)**:
   - Medication transitions persistently from `SCHEDULED` to `DUE`.
   - The device remains silent while the user sleeps.
2. **First Qualifying Unlock (e.g., 08:12)**:
   - Device detects user interaction / screen unlock (`ACTION_USER_PRESENT` + keyguard check).
   - Application evaluates persistent database state and finds the `DUE` dose.
   - An optional configurable delay (0–15s random or fixed) elapses (with `plannedTriggerAt` persisted to disk).
   - The **Full-Screen Medication Alarm** launches with sound, vibration, and actionable controls (`Take Now`, `Skip`, `Reschedule`, `Snooze`).
3. **Already Interactive at Threshold (07:00)**:
   - If the user is already using the phone when the threshold arrives, the alarm activates immediately.

---

## 2. Donor Project Analysis & Mapping

This application synthesizes architectural strengths from two open-source projects:

```
+------------------------------------+       +------------------------------------+
|       FOSSIFY CLOCK (GPL-3.0)      |       |          MEDTIMER (MIT)            |
+------------------------------------+       +------------------------------------+
| - Exact AlarmManager Scheduling    |       | - Medication & Schedule Modeling   |
| - Full-Screen Alarm Presentation   |       | - Dose/Event History Tracking      |
| - Audio & Vibration (AudioAttr)    |       | - Taken/Skipped State Persistence  |
| - Boot & Lifecycle Recovery        |       | - Stock Tracking & Decrementing    |
| - Lockscreen Overlay Flags         |       | - CSV Export & JSON Backup         |
+------------------------------------+       +------------------------------------+
                   \                                       /
                    \                                     /
                     ▼                                   ▼
          +---------------------------------------------------------+
          |         STANDALONE WAKE-GATED MEDICATION ALARM          |
          |                      (GPL-3.0)                          |
          | - Room Database (Single Source of Truth)                |
          | - Deterministic Lazy Evaluation: evaluateState(now)    |
          | - Dynamic Unlock Detection & Persisted Delays (0-15s)   |
          | - Android 14/15/16 & Samsung One UI Compliance          |
          | - Standalone Operation (No Tasker, No ADB, No Cloud)    |
          +---------------------------------------------------------+
```

### Fossify Clock (Alarm Engine Reference)
- **Source**: `https://github.com/FossifyOrg/Clock` (Commit: `9525dc715a59215fef4bcc526d0113bba38844ad`)
- **Key Modules Reused/Adapted**:
  - `AlarmService`: Foreground service for audio playback using `MediaPlayer` + `AudioAttributes.USAGE_ALARM` and vibration wave patterns.
  - `AlarmActivity`: Full-screen overlay activity using `setShowWhenLocked(true)` and `setTurnScreenOn(true)`.
  - `AlarmNotificationHelper`: Construction of high-priority Heads-Up notifications with `setFullScreenIntent` and action buttons.
  - Exact Alarm Management: `setAlarmClock()` and `setExactAndAllowWhileIdle()` integration with `AlarmManagerCompat`.

### MedTimer (Medication Domain Reference)
- **Source**: `https://github.com/Futsch1/medTimer` (Commit: `b7d604ab90c33f08b717f16a0fa39e3e0a045c87`)
- **Key Concepts Adapted**:
  - Medication data structures (Name, dosage, units, notes, color coding, icon identifiers).
  - Flexible recurring schedules (daily, custom weekdays, interval cycles, start/end validity periods).
  - Dose history logging with exact timestamps, stock level tracking, and export capabilities.

---

## 3. Core Architecture & Persistence Design

### Persistent Room Database (Single Source of Truth)

The persistent database is the sole authority for medication state. Alarms, receivers, and services are purely transient mechanisms for evaluating and presenting that state.

```
                    +-----------------------------+
                    |       MedicationEntity      |
                    +-----------------------------+
                    | PK  medicationId: Long      |
                    |     name: String            |
                    |     dosage: String          |
                    |     unit: String            |
                    |     notes: String           |
                    |     wakeGated: Boolean      |
                    |     wakeThreshold: String   | (e.g. "07:00")
                    |     soundUri: String        |
                    |     vibrate: Boolean        |
                    |     stockQuantity: Double?  |
                    +-----------------------------+
                                   │ 1
                                   │
                                   │ *
                    +-----------------------------+
                    |      ScheduleEntity         |
                    +-----------------------------+
                    | PK  scheduleId: Long        |
                    | FK  medicationId: Long      |
                    |     timeInMinutes: Int      | (0 - 1439)
                    |     daysOfWeek: Int         | (Bitmask: Mon-Sun)
                    |     cycleDaysOn: Int        |
                    |     cycleDaysOff: Int       |
                    |     startDate: Long?        |
                    |     endDate: Long?          |
                    +-----------------------------+
                                   │ 1
                                   │
                                   │ *
                    +-----------------------------+
                    |       DoseEventEntity       |
                    +-----------------------------+
                    | PK  doseEventId: Long       |
                    | FK  medicationId: Long      |
                    |     scheduledAt: Long       | (Epoch ms)
                    |     dueAt: Long             | (Epoch ms)
                    |     plannedTriggerAt: Long? | (Epoch ms - persisted delay)
                    |     actionTimestamp: Long?  | (Epoch ms)
                    |     state: DoseState        | (Enum)
                    |     rescheduledFromId: Long?|
                    |     stockHandled: Boolean   |
                    +-----------------------------+
                    | UNIQUE(medicationId,        |
                    |        scheduledAt)         |
                    +-----------------------------+
```

### Dose State Machine
```
   [ Schedule Trigger ]
            │
            ▼
     +-------------+       Time >= Due & (Not WakeGated OR Unlocked)
     |  SCHEDULED  | ------------------------------------+
     +-------------+                                     │
            │                                            │
            │ Time >= Due & WakeGated & Asleep           │
            ▼                                            │
     +-------------+                                     │
     |     DUE     | <--- User Unlocks Device            │
     +-------------+                                     │
            │                                            │
            ▼                                            ▼
     +-------------------------------------------------------+
     |                        RINGING                        |
     |         (Full-Screen Alarm Activity & Audio)          |
     +-------------------------------------------------------+
            │                   │                    │
   [ Take Now ]             [ Skip ]          [ Reschedule ]
            │                   │                    │
            ▼                   ▼                    ▼
     +-------------+     +-------------+     +---------------+
     |    TAKEN    |     |   SKIPPED   |     |  RESCHEDULED  |
     +-------------+     +-------------+     +---------------+
                                                     │
                                                     ▼
                                            (New SCHEDULED Event)
```

---

## 4. The Central Lazy Evaluator: `evaluateMedicationState(now)`

Every lifecycle entry point invokes `evaluateMedicationState(now: Instant)` to ensure zero missed reminders:

```kotlin
class MedicationStateEvaluator @Inject constructor(
    private val database: AppDatabase,
    private val alarmEngine: AlarmEngine,
    private val unlockDetector: UnlockDetector,
    private val notificationHelper: MedicationNotificationHelper
) {
    suspend fun evaluateMedicationState(now: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        // 1. Transactionally expand schedules up to 'now'
        database.doseEventDao().generateScheduledEvents(now)

        // 2. Query all unresolved events (SCHEDULED, DUE, RINGING)
        val pendingEvents = database.doseEventDao().getPendingDoseEvents(now.toEpochMilli())

        for (event in pendingEvents) {
            val med = database.medicationDao().getById(event.medicationId) ?: continue
            val isDue = now.toEpochMilli() >= event.dueAt

            if (isDue) {
                if (!med.wakeGated || unlockDetector.isDeviceUnlocked()) {
                    // Qualifying condition met -> Present alarm
                    alarmEngine.triggerMedicationAlarm(event, med)
                } else {
                    // Wake-gated & device is locked -> Mark DUE silently & monitor unlock
                    database.doseEventDao().updateState(event.doseEventId, DoseState.DUE)
                    unlockDetector.ensureUnlockMonitoringActive()
                }
            }
        }

        // 3. Schedule next exact alarm for future events
        alarmEngine.scheduleNextExactAlarm()
    }
}
```

### Guaranteed Evaluation Entry Points
1. **Application Launch (`Application.onCreate`)**
2. **Device Boot (`ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`)**
3. **Screen Unlock (`ACTION_USER_PRESENT`)**
4. **Exact Alarm Fired (`AlarmManager` RTC wakeups)**
5. **Foreground Service Restart / Process Resurrection**
6. **Clock & Timezone Changes (`ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED`, DST shifts)**
7. **Medication / Schedule Modification**

---

## 5. Unlock Detection & Persisted Delayed Triggers

### Modern Android Unlock Detection (Android 14+)
- Manifest-registered `ACTION_USER_PRESENT` is restricted on modern Android.
- Dynamic registration (`registerReceiver(..., IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)`) is used.
- When wake-gated doses transition to `DUE`, if the app is backgrounded, a lightweight unlock monitoring service / foreground notification maintains the dynamic receiver.

### Persisted 0–15s Delayed Trigger
To prevent abrupt alarms immediately upon pressing the power button, a configurable delay (0–15 seconds, fixed or randomized) is supported:
1. On qualifying unlock at `T_unlock`:
   - `plannedTriggerAt = T_unlock + delaySeconds`
   - Stored transactionally in Room: `DoseEvent.plannedTriggerAt = plannedTriggerAt`
2. An exact alarm or coroutine delay is queued for `plannedTriggerAt`.
3. If the process is killed at `T_unlock + 2s`:
   - On restart at `T_unlock + 10s`, `evaluateMedicationState` observes `now >= plannedTriggerAt` and immediately displays the alarm. No reminder is lost.

---

## 6. Android 14 / 15 / 16 & Samsung One UI Hardening

| Area | Android Constraint | App Solution & Graceful Fallback |
| :--- | :--- | :--- |
| **Full-Screen Alarms** | `USE_FULL_SCREEN_INTENT` requires permission; Android 14+ may deny by default. | Check `NotificationManager.canUseFullScreenIntent()`. Deep-link to settings if denied, and display high-priority Heads-Up Notification with action buttons as fail-safe fallback. |
| **Exact Alarms** | `SCHEDULE_EXACT_ALARM` restricted on Android 14+. | Use `AlarmManager.setAlarmClock()` (primary) and `canScheduleExactAlarms()` validation. |
| **Receiver Export** | Android 14 requires explicit `RECEIVER_EXPORTED` or `RECEIVER_NOT_EXPORTED`. | All dynamic receivers registered with `RECEIVER_NOT_EXPORTED`. |
| **Audio Routing** | DND rules can mute standard media. | Audio played via `AudioAttributes.USAGE_ALARM` and `FLAG_BYPASS_DND` notification channels. |
| **Samsung One UI Background Management** | Samsung aggressive deep sleeping can freeze background apps. | Settings screen includes an interactive Reliability Guide linking to Samsung battery optimization exemption and auto-start settings. |

---

## 7. External Automation API

To enable integration with external automation tools (Tasker, Home Assistant, etc.) without requiring them as runtime dependencies, the app exposes safe, validated Intent actions:

| Action | Intent Extra | Behavior |
| :--- | :--- | :--- |
| `org.wakegated.medication.ACTION_SHOW_DUE_ALARM` | None | Evaluates state and opens full-screen alarm if any dose is DUE. |
| `org.wakegated.medication.ACTION_MARK_TAKEN` | `EXTRA_DOSE_EVENT_ID: Long` | Marks the specified dose as TAKEN and updates stock. |
| `org.wakegated.medication.ACTION_MARK_SKIPPED` | `EXTRA_DOSE_EVENT_ID: Long` | Marks the specified dose as SKIPPED. |
| `org.wakegated.medication.ACTION_SNOOZE` | `EXTRA_MINUTES: Int` | Snoozes active ringing alarm for N minutes. |

---

## 8. License Compliance & Attribution

- **Project License**: GNU General Public License v3.0 (GPL-3.0).
- **Fossify Clock**: GPL-3.0 (Copyright (C) FossifyOrg). Reused for alarm services, audio playback, and lockscreen activity lifecycle.
- **MedTimer**: MIT License (Copyright (c) Futsch1). Reused for medication domain concepts, recurring schedules, and export logic.
- Full copyright headers and attribution preserved across all derived components.
