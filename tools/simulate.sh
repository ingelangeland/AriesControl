#!/bin/bash
# Automatic test in an Android emulator on GitHub, against a fake Aries on the build machine
# (the emulator reaches the build machine at 10.0.2.2).
set -x
mkdir -p sim
sudo python3 tools/fakearies/server.py "$PWD/sim" > sim/EMULATOR-fakearies.txt 2>&1 &
sleep 3
adb install -r ut/AriesControl.apk
adb shell pm grant no.ariescontrol android.permission.POST_NOTIFICATIONS || true
adb logcat -c

# 1) First start with no streamer known: searching
adb shell am start -n no.ariescontrol/.MainActivity
sleep 6
adb exec-out screencap -p > sim/1-searching.png
adb shell am force-stop no.ariescontrol

# 2) Each settings tab of the fake Aries
for t in 0 1 2 3; do
  adb shell am start -S -n no.ariescontrol/.MainActivity --es ip 10.0.2.2 --ei tab $t
  sleep 9
  adb exec-out screencap -p > sim/2-tab$t.png
done

# 3) Scroll the Processor tab
adb shell am start -S -n no.ariescontrol/.MainActivity --es ip 10.0.2.2 --ei tab 1
sleep 9
adb shell input swipe 540 1800 540 500 400
sleep 2
adb exec-out screencap -p > sim/3-processor-scrolled.png

# 4) Tap the first switch on the Streamer tab and check that the change reaches the "Aries"
adb shell am start -S -n no.ariescontrol/.MainActivity --es ip 10.0.2.2 --ei tab 0
sleep 9
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb pull /sdcard/ui.xml sim/ui.xml > /dev/null
XY=$(python3 - <<'EOF'
import re
s = open('sim/ui.xml', encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*class="android.widget.Switch"[^>]*>', s):
    tag = m.group(0)
    if 'enabled="true"' not in tag: continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if b:
        x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
EOF
)
echo "switch at: $XY"
if [ -n "$XY" ]; then adb shell input tap $XY; fi
sleep 4
adb exec-out screencap -p > sim/4-after-switch.png
if grep -q "SetRendererConfig" sim/SET-LOGG.txt 2>/dev/null; then echo "OK: setting reached the Aries" > sim/RAPPORT-set.txt; else echo "FEIL: no setting was sent (switch at '$XY')" > sim/RAPPORT-set.txt; fi
cat sim/SET-LOGG.txt >> sim/RAPPORT-set.txt 2>/dev/null

# 5) Classic view (tab at the bottom)
XY=$(python3 tools/find.py Classic); echo "classic at: $XY"
[ -n "$XY" ] && adb shell input tap $XY
sleep 8
adb exec-out screencap -p > sim/5-classic.png

# 6) Music dashboard: now playing from the fake Aries; tap Pause and check it reaches the "Aries"
adb shell am start -S -n no.ariescontrol/.MainActivity --es ip 10.0.2.2
sleep 12
adb exec-out screencap -p > sim/9-music.png
XY=$(python3 tools/find.py Pause --desc); [ -z "$XY" ] && XY=$(python3 tools/find.py Play --desc); echo "play/pause at: $XY"
[ -n "$XY" ] && adb shell input tap $XY
sleep 3
adb shell input swipe 540 1800 540 400 400
sleep 2
adb exec-out screencap -p > sim/9-music-scrolled.png
if grep -q "OH Playlist P" sim/SET-LOGG.txt 2>/dev/null; then echo "OK: play/pause reached the Aries" > sim/RAPPORT-music.txt; else echo "FEIL: play/pause was not sent (button at '$XY')" > sim/RAPPORT-music.txt; fi
grep "^OH" sim/SET-LOGG.txt >> sim/RAPPORT-music.txt 2>/dev/null

# 7) Network setup: list from the fake Aries, choose HomeWiFi, type a password, Join
adb shell am start -S -n no.ariescontrol/.MainActivity --es ip 10.0.2.2 --es setup wifi
sleep 10
adb exec-out screencap -p > sim/6-wifi-list.png
XY=$(python3 tools/find.py HomeWiFi); echo "HomeWiFi at: $XY"
[ -n "$XY" ] && adb shell input tap $XY
sleep 3
XY=$(python3 tools/find.py android.widget.EditText --class); echo "password field at: $XY"
[ -n "$XY" ] && adb shell input tap $XY && sleep 1 && adb shell input text "test1234"
adb shell input keyevent 111
sleep 1
adb exec-out screencap -p > sim/7-wifi-form.png
XY=$(python3 tools/find.py JOIN); [ -z "$XY" ] && XY=$(python3 tools/find.py Join); echo "join at: $XY"
[ -n "$XY" ] && adb shell input tap $XY
sleep 6
adb exec-out screencap -p > sim/8-wifi-done.png
if grep -q "wifi_ssid" sim/SET-LOGG.txt 2>/dev/null && grep -q "test1234" sim/SET-LOGG.txt; then echo "OK: Wi-Fi settings reached the Aries" > sim/RAPPORT-wifi.txt; else echo "FEIL: Wi-Fi settings were not sent" > sim/RAPPORT-wifi.txt; fi
grep "SetDeviceConfig" sim/SET-LOGG.txt >> sim/RAPPORT-wifi.txt 2>/dev/null

adb logcat -d > sim/LOGG-SISTE-full.txt
tail -c 300000 sim/LOGG-SISTE-full.txt > sim/LOGG-SISTE.txt; rm sim/LOGG-SISTE-full.txt
adb logcat -d -b crash > sim/crash.txt
if grep -q "no.ariescontrol" sim/crash.txt; then cp sim/crash.txt sim/KRASJ.txt; fi
rm -f sim/crash.txt sim/ui.xml sim/SET-LOGG.txt.tmp
grep -E "AndroidRuntime|ariescontrol" sim/LOGG-SISTE.txt | tail -100 > sim/RAPPORT.txt
sudo pkill -f "fakearies/server.py" || true
find sim -type f -empty -delete
exit 0
