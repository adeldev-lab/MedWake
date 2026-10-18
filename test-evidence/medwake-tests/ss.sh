#!/bin/bash
# screenshot helper: ss <name> -> saves to /tmp/opencode/medwake-tests/<name>.png
NAME="$1"
if [ -z "$NAME" ]; then echo "usage: ss <name>"; exit 1; fi
DEVICE=emulator-5554
adb -s $DEVICE shell screencap -p /sdcard/_a5tmp.png >/dev/null 2>&1
adb -s $DEVICE pull /sdcard/_a5tmp.png "/tmp/opencode/medwake-tests/${NAME}.png" >/dev/null 2>&1
echo "saved ${NAME}.png"
