#!/bin/bash
# Automatic test in an Android emulator on GitHub:
# 1) start the app with no streamer known (search + scan), 2) open a fake Aries page.
set -x
mkdir -p sim
# Fake "Aries" web page on the build machine (the emulator reaches it at 10.0.2.2)
sudo python3 -m http.server 80 --directory tools/fakearies > sim/EMULATOR-webserver.txt 2>&1 &
sleep 2
adb install -r ut/AriesControl.apk
adb shell pm grant no.ariescontrol android.permission.POST_NOTIFICATIONS || true
adb logcat -c

adb shell am start -n no.ariescontrol/.MainActivity
sleep 6
adb exec-out screencap -p > sim/1-searching.png
sleep 30
adb exec-out screencap -p > sim/2-search-result.png

adb shell am force-stop no.ariescontrol
adb shell am start -n no.ariescontrol/.MainActivity --es ip 10.0.2.2
sleep 12
adb exec-out screencap -p > sim/3-settings-page.png

adb logcat -d > sim/LOGG-SISTE-full.txt
tail -c 300000 sim/LOGG-SISTE-full.txt > sim/LOGG-SISTE.txt; rm sim/LOGG-SISTE-full.txt
adb logcat -d -b crash > sim/crash.txt
if grep -q "no.ariescontrol" sim/crash.txt; then cp sim/crash.txt sim/KRASJ.txt; fi
rm -f sim/crash.txt
grep -E "AndroidRuntime|ariescontrol" sim/LOGG-SISTE.txt | tail -100 > sim/RAPPORT.txt
sudo pkill -f "http.server 80" || true
exit 0
