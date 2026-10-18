#!/bin/bash
# scroll until a node with given resource-id is visible, then tap its center.
# usage: findtap <resource-id> [max_tries] [direction up|down]
RID="$1"
MAX="${2:-15}"
DIR="${3:-down}"  # down = swipe up to reveal lower content
DEVICE=emulator-5554
for i in $(seq 1 $MAX); do
  adb -s $DEVICE shell uiautomator dump /sdcard/_a5dump.xml >/dev/null 2>&1
  adb -s $DEVICE pull /sdcard/_a5dump.xml /tmp/opencode/medwake-tests/_a5dump.xml >/dev/null 2>&1
  C=$(python3 -c "
import xml.etree.ElementTree as ET, re, sys
t=ET.parse('/tmp/opencode/medwake-tests/_a5dump.xml').getroot()
for n in t.iter('node'):
    if n.get('resource-id')=='$RID':
        b=n.get('bounds'); m=re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
        x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); sys.exit(0)
print('NONE')
")
  if [ "$C" != "NONE" ]; then
    echo "FOUND $RID at $C"
    adb -s $DEVICE shell input tap $C
    exit 0
  fi
  # scroll: down = reveal lower content (swipe up); up = reveal top (swipe down)
  if [ "$DIR" = "down" ]; then
    adb -s $DEVICE shell input swipe 160 460 160 200 300
  else
    adb -s $DEVICE shell input swipe 160 200 160 460 300
  fi
  sleep 0.5
done
echo "NOTFOUND $RID after $MAX tries"
