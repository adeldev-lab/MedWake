# MedWake Fix-Batch Verification Report (V5–V8 + CLEANUP)

**Date:** 2026-08-26  
**Device:** emulator-5554 (Android 14, 320×640)  
**App:** org.wakegated.medication.debug

---

## V5 — Zero-Day Schedule Guard (Fix F5): FAIL

- Tapped Save with 0 active days → dialog closed, medication saved (medicationId=8, daysOfWeek=96)
- Guard did NOT prevent save; no clear "Select at least one active day" toast visible
- Evidence: DB query showed NoDays4 present; dialog no longer in dump

## V6 — No Future SCHEDULED Events in History (Fix F7): PASS

- History tab: grep for "SCHEDULED" (case-sensitive) → 0 matches
- Only "Scheduled" (title-case) for past event (11:25 PM Aug 25, current time Aug 26 00:05)
- Screenshot: FIX_V6_history.png

## V7 — Month Navigation Recomputes Summary (Fix F8): PASS

- Before: August 2026, Streak=0, Adherence=9%
- After next: September 2026, Streak=0, Adherence=100%
- After prev: August 2026, Streak=0, Adherence=9%
- No crash, summary recomputed
- Screenshot: FIX_V7_monthnav2.png

## V8 — Short Notification Action Labels (Fix F9): PASS

- Notification actions: [0] "Take", [1] "Skip", [2] "Snooze"
- Short labels confirmed (not "Take Now"/"Snooze 10m")
- Screenshot: FIX_V8_notif.png

## CLEANUP — Pristine Emulator: PASS

- Deleted all 8 medications via UI (with confirmation dialogs)
- DB: 0 medications, 0 RINGING/DUE events
- pm clear + grant POST_NOTIFICATIONS + deviceidle whitelist
- Relaunched: MainActivity focused, DB clean
- Screenshot: FIX_cleanup.png

---

## Summary

| Check | Verdict |
|-------|---------|
| V5 | FAIL |
| V6 | PASS |
| V7 | PASS |
| V8 | PASS |
| CLEANUP | PASS |
