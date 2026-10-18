# MedWake — Standalone Wake-Gated Medication Alarm for Android

MedWake is an open-source, privacy-first medication reminder and alarm application for modern Android (Android 14, 15, and 16), with explicit optimizations for Samsung Galaxy (One UI) devices.

---

## 🌟 The Wake-Gated Innovation

Traditional medication reminder apps either wake you up prematurely with loud morning alarms or get lost in passive notification trays.

MedWake enforces a strict architectural principle:

> **Separation of Concerns: Medication becoming DUE is completely separated from disturbing the user with an alarm.**

### How it Works
1. **At Threshold (e.g. 07:00 AM)**: The dose transitions persistently to `DUE`. If you are sleeping, the phone remains completely silent.
2. **On First Unlock (e.g. 08:12 AM)**: The application detects phone unlock, executes deterministic lazy evaluation, waits for an optional 0–15s configurable delay (persisted to disk), and presents a **Full-Screen Medication Alarm**.
3. **If Already Active at Threshold**: If you are already actively using your phone at 07:00 AM, the alarm triggers immediately.

---

## 🚀 Key Features

- **Wake-Gated Reminders**: Sleep peacefully without early morning alarm interruptions.
- **Full-Screen Medication Alarm**: High-visibility lockscreen overlay with Take Now, Skip, Reschedule, and Snooze buttons.
- **Persistent Source of Truth**: Room SQLite database with transactional uniqueness `UNIQUE(medicationId, scheduledAt)` preventing duplicate alerts across reboots and concurrent evaluations.
- **Fail-Safe Lazy Evaluation**: `evaluateMedicationState(now)` runs on boot, unlock, app launch, exact alarm, and time/timezone shifts.
- **Persisted Delayed Triggers**: 0–15s random/fixed delays are written to Room (`plannedTriggerAt`) so process kills never lose reminders.
- **Standalone & Offline**: 100% local, no account, no cloud, no Tasker, no ADB Wi-Fi, and no root required.
- **External Intent Automation API**: Safe exported Intent actions for optional integration with external smart home or automation tools.
- **Stock Tracking & History**: Track pill quantities and view historical adherence logs.

---

## 📱 Android 14 / 15 / 16 & Samsung One UI Compliance

- **Exact Alarms**: Checks `AlarmManager.canScheduleExactAlarms()` and uses `setAlarmClock()`.
- **Full-Screen Intent**: Runtime validation (`NotificationManager.canUseFullScreenIntent()`) with high-importance Heads-Up notification fallback.
- **Dynamic Receivers**: `ACTION_USER_PRESENT` dynamically registered with `RECEIVER_NOT_EXPORTED`.
- **Samsung One UI Optimization**: Settings screen includes direct guides and links for battery optimization exemption.

---

## 🛠️ Building & Running

### Prerequisites
- JDK 21 or JDK 17
- Android SDK 34 / 35 / 36

### Build Commands
```bash
# Run unit tests
./gradlew testDebugUnitTest --no-daemon --max-workers=2

# Assemble debug APK
./gradlew assembleDebug --no-daemon --max-workers=2

# Install on connected device
./scripts/simulate_alarm_events.sh install
```

---

## 📜 Attribution & Licensing

- **License**: GNU General Public License v3.0 ([GPL-3.0](LICENSE)).
- **Fossify Clock** (GPL-3.0, Copyright (C) FossifyOrg): Alarm engine, audio playback, and lockscreen activity patterns.
- **MedTimer** (MIT, Copyright (c) Futsch1): Medication domain models, recurring schedules, and history tracking concepts.
