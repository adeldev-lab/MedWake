#!/bin/bash
set -e

echo "=== Wake-Gated Live Test Demonstration ==="

# Step 1: Ensure MedWake is running and has a test DUE dose
echo "1. Triggering state evaluation..."
adb shell am broadcast -a org.wakegated.medication.ACTION_SHOW_DUE_ALARM

# Step 2: Lock the screen (simulate user asleep)
echo "2. Locking screen (User asleep)..."
adb shell input keyevent 26
sleep 3

echo "3. Device is asleep & locked. No loud alarms sound."

# Step 3: Wake and unlock phone (simulate user waking up)
echo "4. User wakes up and unlocks the phone..."
adb shell input keyevent 224
sleep 1
# Swipe up to unlock lockscreen
adb shell input swipe 160 500 160 100 200 || adb shell input keyevent 82
echo "5. Phone unlocked! Application detects unlock, waits for configured delay..."

# Wait 5 seconds for unlock delay and alarm presentation
sleep 6

echo "6. Capturing active screen..."
adb exec-out screencap -p > app_alarm_active.png || true
ls -lh app_alarm_active.png
