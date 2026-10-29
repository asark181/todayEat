#!/bin/bash
# 用法：scripts/adb_tap.sh "<文字>"  点界面上第一个文字（或 content-desc）等于它的节点
export PATH=~/Library/Android/sdk/platform-tools:$PATH
DUMP=${TMPDIR:-/tmp}/ui_todayeat.xml
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml > "$DUMP"
python3 - "$1" "$DUMP" <<'PY'
import re, sys, subprocess
t, path = sys.argv[1], sys.argv[2]
x = open(path, encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    tx = re.search(r' text="([^"]*)"', n).group(1)
    cd = re.search(r'content-desc="([^"]*)"', n).group(1)
    if t in (tx, cd):
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        subprocess.run(['adb', 'shell', 'input', 'tap', str((a + c) // 2), str((b + d) // 2)])
        print('tapped', t)
        break
else:
    print('NOT FOUND', t)
PY
