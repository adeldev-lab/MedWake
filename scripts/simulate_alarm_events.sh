#!/bin/bash
# ==============================================================================
# MedWake Test Automation & Simulation Helper Script
# ==============================================================================
set -e

PACKAGE="org.wakegated.medication.debug"

echo "=== MedWake Android Testing Helper ==="
echo "Connected Devices:"
adb devices

usage() {
    echo "Usage: $0 [command]"
    echo "Commands:"
    echo "  install          - Build and install debug APK onto device"
    echo "  unlock           - Simulate user unlock broadcast (ACTION_USER_PRESENT)"
    echo "  screen-on        - Simulate screen turning on (ACTION_SCREEN_ON)"
    echo "  boot             - Simulate boot completed (ACTION_BOOT_COMPLETED)"
    echo "  show-alarm       - Trigger external Intent API to show due alarm"
    echo "  mark-taken <id>  - Trigger external Intent API to mark dose <id> as TAKEN"
    echo "  mark-skipped <id>- Trigger external Intent API to mark dose <id> as SKIPPED"
    echo "  snooze <id> <m>  - Trigger external Intent API to snooze dose <id> for <m> mins"
    echo "  logcat           - Stream filtered MedWake logs"
    echo "  dump-db          - Query Room database dose events via adb shell"
}

case "$1" in
    install)
        echo "Installing MedWake debug APK..."
        adb install -r app/build/outputs/apk/debug/app-debug.apk
        ;;
    unlock)
        echo "Sending ACTION_USER_PRESENT..."
        adb shell am broadcast -a android.intent.action.USER_PRESENT
        ;;
    screen-on)
        echo "Sending ACTION_SCREEN_ON..."
        adb shell am broadcast -a android.intent.action.SCREEN_ON
        ;;
    boot)
        echo "Sending ACTION_BOOT_COMPLETED..."
        adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $PACKAGE
        ;;
    show-alarm)
        echo "Sending ACTION_SHOW_DUE_ALARM..."
        adb shell am broadcast -a org.wakegated.medication.ACTION_SHOW_DUE_ALARM
        ;;
    mark-taken)
        DOSE_ID=${2:-1}
        echo "Marking dose $DOSE_ID as TAKEN..."
        adb shell am broadcast -a org.wakegated.medication.ACTION_MARK_TAKEN --el EXTRA_DOSE_EVENT_ID $DOSE_ID
        ;;
    mark-skipped)
        DOSE_ID=${2:-1}
        echo "Marking dose $DOSE_ID as SKIPPED..."
        adb shell am broadcast -a org.wakegated.medication.ACTION_MARK_SKIPPED --el EXTRA_DOSE_EVENT_ID $DOSE_ID
        ;;
    snooze)
        DOSE_ID=${2:-1}
        MINUTES=${3:-10}
        echo "Snoozing dose $DOSE_ID for $MINUTES minutes..."
        adb shell am broadcast -a org.wakegated.medication.ACTION_SNOOZE --el EXTRA_DOSE_EVENT_ID $DOSE_ID --ei EXTRA_MINUTES $MINUTES
        ;;
    logcat)
        echo "Streaming logs (Ctrl+C to stop)..."
        adb logcat -s MedWake:* MedWakeApp:* MedWakeEvaluator:* MedAlarmService:* UnlockMonitorService:*
        ;;
    dump-db)
        echo "Querying dose_events table..."
        adb shell "run-as $PACKAGE sqlite3 /data/data/$PACKAGE/databases/medwake_database.db 'SELECT doseEventId, medicationId, datetime(scheduledAt/1000, \"unixepoch\", \"localtime\"), state, plannedTriggerAt, actionTimestamp FROM dose_events;'" || echo "Note: Device must support run-as or root to direct query sqlite."
        ;;
    *)
        usage
        ;;
esac
