#!/usr/bin/env bash
# Emulator robustness scenarios for TelTV. Runs inside the emulator job. Test-only.
# Without a Telegram login the app stays on its login screen, so this exercises the
# process/lifecycle/network/TDLib layer, not logged-in playback.
set -u
PKG=com.velastudio.teltv
OUT=stress
mkdir -p "$OUT"
FAIL=0
: > "$OUT/summary.md"
log() { echo "$*"; echo "$*" >> "$OUT/summary.md"; }
ok() { log "- ok: $*"; }
bad() { log "- FAIL: $*"; FAIL=1; }
alive() { [ -n "$(adb shell pidof $PKG | tr -d '\r')" ]; }
start_app() { adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; }
begin() { SCEN="$1"; adb logcat -c; log "### $SCEN"; }
finish() {
  sleep 3
  adb logcat -d > "$OUT/logcat-$SCEN.txt"
  if alive; then ok "$SCEN: process alive"; else bad "$SCEN: process not running"; fi
  if grep -E "FATAL EXCEPTION|Fatal signal|ANR in $PKG" "$OUT/logcat-$SCEN.txt" > /dev/null; then
    bad "$SCEN: crash or ANR in logcat"
    grep -E -A25 "FATAL EXCEPTION|Fatal signal|ANR in $PKG" "$OUT/logcat-$SCEN.txt" | head -80 | tee "$OUT/crash-$SCEN.txt"
  fi
}
mem() { adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL:" | head -2 | tr -s ' ' | sed "s/^/  mem($1): /" >> "$OUT/summary.md"; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

begin cold-start
start_app; sleep 20
finish
if grep -q "ConnectionStateReady" "$OUT/logcat-cold-start.txt"; then ok "cold-start: TDLib reached ready"; else bad "cold-start: TDLib never reached ready"; fi
mem start

begin standby-wake-x5
for i in 1 2 3 4 5; do
  adb shell input keyevent KEYCODE_SLEEP; sleep 4
  adb shell input keyevent KEYCODE_WAKEUP; sleep 3
  adb shell wm dismiss-keyguard; sleep 1
done
finish

begin long-standby-60s
adb shell input keyevent KEYCODE_SLEEP; sleep 60
adb shell input keyevent KEYCODE_WAKEUP; sleep 5
adb shell wm dismiss-keyguard; sleep 10
finish
if grep -q "ConnectionStateReady" "$OUT/logcat-long-standby-60s.txt"; then ok "long-standby: TDLib ready again after wake (log shows ready)"; else log "- note: long-standby: no ConnectionStateReady line after wake (may simply not be re-logged)"; fi

begin network-drops-x3
for i in 1 2 3; do
  adb shell svc wifi disable; adb shell svc data disable; sleep 8
  adb shell svc wifi enable; adb shell svc data enable; sleep 10
done
finish
mem network

begin network-drop-during-standby
adb shell input keyevent KEYCODE_SLEEP; sleep 2
adb shell svc wifi disable; adb shell svc data disable; sleep 15
adb shell svc wifi enable; adb shell svc data enable; sleep 5
adb shell input keyevent KEYCODE_WAKEUP; sleep 3
adb shell wm dismiss-keyguard; sleep 12
finish

begin rotation-and-config-change
adb shell settings put system accelerometer_rotation 0
for r in 1 0 3 0; do adb shell settings put system user_rotation $r; sleep 3; done
adb shell settings put system user_rotation 0
finish

begin background-kill-restore
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell am kill $PKG; sleep 2
start_app; sleep 12
finish
adb shell am force-stop $PKG; sleep 2
begin force-stop-restart
start_app; sleep 15
finish

begin trim-memory
adb shell am send-trim-memory $PKG RUNNING_CRITICAL; sleep 3
adb shell am send-trim-memory $PKG COMPLETE; sleep 3
start_app; sleep 5
finish
mem trim

begin remote-dpad-spam
start_app; sleep 5
adb shell 'for i in $(seq 1 60); do input keyevent 20; input keyevent 22; input keyevent 66; input keyevent 19; input keyevent 21; done'
sleep 3
start_app; sleep 3
finish

begin monkey-1500-events
adb shell monkey -p $PKG --pct-syskeys 0 --throttle 200 -s 1337 --ignore-timeouts 1500 > "$OUT/monkey.txt" 2>&1
tail -5 "$OUT/monkey.txt" >> "$OUT/summary.md"
if grep -E "CRASH|ANR" "$OUT/monkey.txt" > /dev/null; then bad "monkey: crash or ANR reported"; fi
finish
mem end

cp "$OUT/summary.md" "${GITHUB_STEP_SUMMARY:-/dev/null}" 2>/dev/null || true
echo "stress failures: $FAIL"
exit $FAIL
