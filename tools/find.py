#!/usr/bin/env python3
"""Prints the centre "x y" of the first on-screen element whose text (or class with --class, or description with --desc) matches. Used by simulate.sh."""
import re, subprocess, sys
subprocess.run(['adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'], capture_output=True)
xml = subprocess.run(['adb', 'exec-out', 'cat', '/sdcard/ui.xml'], capture_output=True).stdout.decode('utf-8', 'replace')
want = sys.argv[1]
attr = 'class' if '--class' in sys.argv else 'content-desc' if '--desc' in sys.argv else 'text'
for m in re.finditer(r'<node [^>]*>', xml):
    tag = m.group(0)
    v = re.search(attr + r'="([^"]*)"', tag)
    if not v or v.group(1) != want: continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if b:
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
