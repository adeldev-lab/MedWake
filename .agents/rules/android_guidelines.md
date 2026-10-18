---
description: Android Development Guidelines for Standalone Wake-Gated Medication App
trigger: always_on
---

# Android Development Guidelines: Wake-Gated Medication Alarm

## Core Architecture Principles
1. **Separation of Concerns**:
   - Medication becoming DUE is completely separated from ringing an alarm.
   - Persistent Room database is the single source of truth.
   - `evaluateMedicationState(now: Instant)` must be callable deterministically from all lifecycle entry points.
2. **Transactional Event Creation**:
   - Every scheduled dose event must satisfy unique constraints `UNIQUE(medicationId, scheduledAt)`.
   - Never generate duplicate dose events when multiple triggers evaluate simultaneously.
3. **Android 14 / 15 / 16 Compliance**:
   - Check `canScheduleExactAlarms()` before calling exact AlarmManager APIs; provide fallback or deep link to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
   - Use `USE_FULL_SCREEN_INTENT` with runtime validation (`NotificationManager.canUseFullScreenIntent()`). Fall back gracefully to high-importance Heads-Up notifications with action buttons.
   - Any dynamic receiver registration (e.g. `ACTION_USER_PRESENT` for unlock detection) must specify `RECEIVER_NOT_EXPORTED` where applicable on Android 14+.
4. **Resilience & Fail-Safe Behavior**:
   - Persistent triggers (like the 0–15s delayed unlock trigger `plannedTriggerAt`) must be written to Room/DataStore so process death before alarm execution does not drop the reminder.
   - Never mark a dose Taken or Skipped silently.
5. **Licensing & Attribution**:
   - Main codebase is licensed under GPL-3.0 (derived from Fossify Clock).
   - Acknowledge MedTimer (MIT) for medication data modeling and scheduling reference in file headers and `ARCHITECTURE.md`.

## Build System & Resource Limits
- Always use `--no-daemon` and limit Gradle workers to `org.gradle.workers.max=2`.
- Keep JVM heap max at `-Xmx2048m` to prevent OOM on 20-thread CPU hosts.
