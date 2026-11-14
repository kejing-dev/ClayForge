#!/bin/bash
# tools/scan.sh <serial> <logfile> : walks every panel state in zh and en; logcat streamed to <logfile> (BLANK / OVERFLOW lines)
S=$1; LOG=$2
ADB=/Users/bytedance/Library/Android/sdk/platform-tools/adb
B(){ $ADB -s $S shell "am broadcast -a io.embodit.clayforge.DEBUG -p io.embodit.clayforge --es cmd '$1'" >/dev/null; sleep ${2:-2}; }
$ADB -s $S logcat -c
$ADB -s $S logcat -v time -s ClayForge:* > "$LOG" & LP=$!
for L in zh en; do
  $ADB -s $S shell am force-stop io.embodit.clayforge; $ADB -s $S shell pm clear io.embodit.clayforge >/dev/null
  $ADB -s $S shell monkey -p io.embodit.clayforge -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2
  B "lang $L" 9
  B "page works" 3; B "page sculpt" 2
  B "tool add" 2; B "stroke add line" 6; B "turn90" 3
  B "state S05" 6; B "mirror on" 2; B "mirror off" 2
  B "state S20" 14; B "undo" 3
  B "state S05" 6; B "glaze junRed" 3
  B "fire" 1.5; B "dump" 9; B "export" 3
  B "newlump" 3; B "page sculpt" 2
  B "page works" 3; B "open sample vase" 4; B "detail back" 2
  B "open work 0" 4; B "delete" 3; B "delete" 0; B "page works" 2
  B "newlump" 3; B "page settings" 3; B "confirm exit" 3; B "page settings" 2
done
kill $LP
