#!/bin/bash
# tools/tour.sh <serial> <outdir> [states…] : drives each design state with the debug broadcast and shoots it.
S=$1; D=$2; shift 2
ADB=/Users/bytedance/Library/Android/sdk/platform-tools/adb
B(){ $ADB -s $S shell "am broadcast -a io.embodit.clayforge.DEBUG -p io.embodit.clayforge --es cmd '$1'" >/dev/null; }
shot(){ "$(dirname $0)/shot.sh" $S "$D/$1.png"; echo "shot $1"; }
mkdir -p "$D"
for st in "$@"; do case $st in
  S03) B "state S03"; sleep 7; shot S03 ;;
  S04) B "state S04"; sleep 12; shot S04 ;;
  S05) B "state S05"; sleep 5; shot S05 ;;
  S06) B "state S06"; sleep 8; shot S06; B "release"; sleep 1 ;;
  S06-carve) B "state S05"; sleep 4; B "stroke carve zigzag half"; sleep 5; shot S06-carve; B release; sleep 1 ;;
  S06-smooth) B "state S05"; sleep 4; B "stroke smooth zigzag half"; sleep 5; shot S06-smooth; B release; sleep 1 ;;
  S06-pull) B "state S05"; sleep 4; B "movetip 0.07 0"; sleep 2; B "stroke pull line half"; sleep 4; shot S06-pull; B release; sleep 1 ;;
  S07) B "state S07"; sleep 6; shot S07; B "release"; sleep 1 ;;
  S08) B "state S08"; sleep 6; shot S08; B "yaw 45"; sleep 1; shot S08-yaw45 ;;
  S09) B "state S09"; sleep 10; shot S09 ;;
  S10) B "state S10"; sleep 6; shot S10; B "jar junRed"; sleep 2; shot S10-junRed ;;
  S11) B "state S11"; sleep 3.8; shot S11 ;;
  S12) B "state S12"; sleep 10; shot S12 ;;
  S13) B "state S13"; sleep 18; shot S13 ;;
  S14) B "state S14"; sleep 3; shot S14 ;;
  S15) B "state S15"; sleep 4; shot S15 ;;
  S16) B "state S16"; sleep 3; shot S16 ;;
  S17) B "state S17"; sleep 2; shot S17 ;;
  S17-en) B "lang en; state S17"; sleep 2; shot S17-en; B "confirm exit"; sleep 1; shot S17b-en; B "lang zh"; sleep 1 ;;
  S18) B "state S05"; sleep 4; B "state S18"; sleep 3; shot S18; B reopen; sleep 3 ;;
  S20) B "state S05"; sleep 4; B "state S20"; sleep 60; shot S20 ;;
esac; done
