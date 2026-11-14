#!/bin/bash
# tools/shot.sh <serial> <out.png> : emulator console screenshot (2560²) → full frame + a crop of volume and panel
S=$1; OUT=$2; TMP=$(mktemp -d)
/Users/bytedance/Library/Android/sdk/platform-tools/adb -s $S emu screenrecord screenshot $TMP >/dev/null
f=$(ls $TMP/*.png | head -1)
~/.factory-venv/bin/python - "$f" "$OUT" <<'PY'
import sys
from PIL import Image
im=Image.open(sys.argv[1]).convert('RGB'); out=sys.argv[2]
im.save(out.replace('.png','-full.png'))
im.crop((960,900,2420,1720)).save(out)
PY
rm -rf $TMP
