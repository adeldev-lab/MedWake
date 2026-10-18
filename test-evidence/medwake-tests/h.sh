#!/bin/bash
# helpers for medwake A5 hunt
DEVICE=emulator-5554
PKG=org.wakegated.medication.debug
DB=/data/data/$PKG/databases/medwake_database.db
PREFS=/data/data/$PKG/shared_prefs/medwake_prefs.xml

case "$1" in
  ss) # ss <name>
    adb -s $DEVICE shell screencap -p /sdcard/_a5tmp.png >/dev/null 2>&1
    adb -s $DEVICE pull /sdcard/_a5tmp.png "/tmp/opencode/medwake-tests/$2.png" >/dev/null 2>&1
    echo "saved $2.png"
    ;;
  tap) adb -s $DEVICE shell input tap "$2" "$3" ;;
  text) adb -s $DEVICE shell input text "$2" ;;
  key) adb -s $DEVICE shell input keyevent "$2" ;;
  db) # db <sql>
    adb -s $DEVICE shell "run-as $PKG sqlite3 $DB '$2'" ;;
  prefs) adb -s $DEVICE shell "run-as $PKG cat $PREFS" ;;
  dump) adb -s $DEVICE shell uiautomator dump /sdcard/_a5dump.xml >/dev/null 2>&1; adb -s $DEVICE pull /sdcard/_a5dump.xml /tmp/opencode/medwake-tests/_a5dump.xml >/dev/null 2>&1; echo "dumped" ;;
  *) echo "unknown cmd" ;;
esac
