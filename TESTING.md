# MedWake Test Suite & Manual Verification Matrix

## 1. Automated Unit Tests

Run unit tests via Gradle:
```bash
./gradlew testDebugUnitTest --no-daemon --max-workers=2
```

Tests verify:
- **`ScheduleCalculatorTest`**: Weekday bitmask calculations, daily schedules, and exact occurrence timestamps.
- **`DoseStateTest`**: State machine invariants (`isPending()`, `isResolved()`).
- **Room Uniqueness**: Uniqueness constraint `UNIQUE(medicationId, scheduledAt)` prevents duplicate doses.

---

## 2. Manual Verification Matrix (Real Device / Emulator)

| Test Case | Steps | Expected Result |
| :--- | :--- | :--- |
| **1. Pre-Threshold Unlock** | Configure medication at 07:00 (Wake-gated: ON). Unlock at 06:59. | No alarm triggers; status remains `SCHEDULED`. |
| **2. Asleep at Threshold** | Screen locked at 07:00. | Device remains silent; dose marked `DUE` in Room database. |
| **3. Late Unlock** | Unlock phone at 08:12. | Full-screen alarm appears within configured delay (0–120s). |
| **4. Already Unlocked** | Phone active & in use at 07:00. | Alarm triggers immediately without waiting for future unlock. |
| **5. Take Now Action** | Press "Take Now" on alarm. | Event transitions to `TAKEN`, stock decrements, alarm stops. Subsequent unlocks do not re-trigger. |
| **6. Skip Action** | Press "Skip" on alarm. | Event transitions to `SKIPPED`, alarm stops. |
| **7. Snooze** | Press "Snooze" and choose a duration (the first option uses the configured snooze minutes). | Active event becomes `RESCHEDULED`; a replacement dose is scheduled at `now + duration`. |
| **7b. Snooze then Take** | Snooze, let the replacement ring, press Take Now. | Only the replacement appears in History; the day is green and the streak is not broken. |
| **8. Process Death** | Dose is `DUE` -> Kill process (`adb shell am force-stop`) -> Launch app. | Dose remains `DUE` and visible on Today dashboard. |
| **9. Reboot Recovery** | Reboot phone while dose is `DUE` -> Unlock phone. | Alarm evaluates and presents immediately. |
| **10. Permission Fallback** | Deny Full-Screen Intent permission in system settings. | High-priority Heads-Up Notification appears with actionable buttons. |

---

## 3. Automation & ADB Helper Script

Use `scripts/simulate_alarm_events.sh`:
```bash
# Simulate screen unlock
./scripts/simulate_alarm_events.sh unlock

# Simulate boot completed
./scripts/simulate_alarm_events.sh boot

# Trigger external Intent API
./scripts/simulate_alarm_events.sh show-alarm
./scripts/simulate_alarm_events.sh mark-taken 1
```
