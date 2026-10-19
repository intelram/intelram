#!/usr/bin/env bash
# End-to-end test on an Android emulator (which has both Wi-Fi and cellular).
# Usage: e2e.sh path/to/app-debug.apk
set -u
APK="$1"
PKG=com.intelram.netsteer
ACT="$PKG/.MainActivity"
fail() {
  echo "E2E FAIL: $*"
  adb logcat -d -s NetSteer:V AndroidRuntime:E | tail -80
  echo "--- hev tunnel log"; adb shell run-as "$PKG" cat no_backup/tunnel.log 2>&1 | tail -40
  exit 1
}

echo "== device networks"
adb shell dumpsys connectivity | grep -oE "Transports: [A-Z|]+|VALIDATED" | sort | uniq -c

echo "== install"
adb install -r -g "$APK" || fail "install"
# Pre-grant VPN consent (same effect as the user tapping OK on the system dialog).
adb shell appops set "$PKG" ACTIVATE_VPN allow || fail "appops"
adb logcat -c

start() { adb shell am start -n "$ACT" -a "$PKG.START" >/dev/null; }
stop() { adb shell am start -n "$ACT" -a "$PKG.STOP" >/dev/null; }

wait_vpn() { # $1 = up|down
  for _ in $(seq 1 30); do
    if adb shell ip addr show tun0 2>/dev/null | grep -q "198.18.0.1"; then [ "$1" = up ] && return 0
    else [ "$1" = down ] && return 0; fi
    sleep 1
  done
  return 1
}

traffic() { # parallel HTTP requests from the shell user, which is routed through the VPN
  local ok=0 i
  rm -f /tmp/e2e_*.txt
  for i in $(seq 1 16); do
    host=$([ $((i % 2)) = 0 ] && echo example.com || echo www.google.com)
    adb shell "printf 'GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: netsteer-e2e\r\n\r\n' | timeout 25 nc $host 80" > /tmp/e2e_$i.txt 2>&1 &
  done
  wait
  for i in $(seq 1 16); do grep -q "^HTTP/1\.[01] [23]0[0-9]" /tmp/e2e_$i.txt && ok=$((ok + 1)); done
  echo "$ok"
}

stats_line() { adb logcat -d -s NetSteer:I | grep " stats " | tail -1; }

echo "== make sure Wi-Fi and mobile data are both on, and the app sees both validated"
adb shell svc wifi enable
adb shell svc data enable
adb shell am start -n "$ACT" >/dev/null
both=""
for _ in $(seq 1 90); do
  last=$(adb logcat -d -s NetSteer:I | grep " net: " | tail -1)
  if echo "$last" | grep -q "CELLULAR validated=true" && echo "$last" | grep -q "WIFI validated=true"; then both=1; break; fi
  sleep 1
done
adb logcat -d -s NetSteer:I | grep " net: " | tail -5
if [ -z "$both" ]; then
  adb shell dumpsys wifi | grep -iE "Wi-Fi is|mWifiInfo|current network|SSID" | head -15
  adb shell dumpsys connectivity | sed -n '/Current Networks/,/^$/p' | head -40
  fail "emulator did not provide validated Wi-Fi and mobile at the same time"
fi

echo "== start combining"
start
wait_vpn up || fail "VPN did not come up"
for _ in $(seq 1 45); do
  adb logcat -d -s NetSteer:I | grep -q "uplinks: \[cell, wifi\]" && break
  sleep 1
done
adb logcat -d -s NetSteer:I | grep "uplinks:" | tail -3
adb logcat -d -s NetSteer:I | grep -q "uplinks: \[cell, wifi\]" || fail "Wi-Fi and mobile were not both available"

echo "== traffic round 1"
ok=$(traffic)
echo "successful HTTP responses: $ok/16"
[ "$ok" -ge 14 ] || { head -c 600 /tmp/e2e_1.txt; fail "too many failed requests ($ok/16)"; }
sleep 6
line=$(stats_line); echo "$line"
wifi_rx=$(echo "$line" | sed -n 's/.*wifi=\([0-9]*\)\/.*/\1/p')
cell_rx=$(echo "$line" | sed -n 's/.*cell=\([0-9]*\)\/.*/\1/p')
[ "${wifi_rx:-0}" -gt 0 ] || fail "no bytes received over Wi-Fi"
[ "${cell_rx:-0}" -gt 0 ] || fail "no bytes received over mobile"
echo "both links carried traffic: wifi_rx=$wifi_rx cell_rx=$cell_rx"

echo "== notification present"
adb shell dumpsys notification --noredact | grep -q "pkg=$PKG" || fail "no notification"

echo "== stop"
stop
wait_vpn down || fail "VPN did not stop"
sleep 2
adb shell dumpsys notification --noredact | grep -q "pkg=$PKG" && fail "notification left behind after stop"

echo "== restart (native tunnel must restart cleanly in the same process)"
start
wait_vpn up || fail "VPN did not come up on restart"
sleep 5
ok=$(traffic)
echo "successful HTTP responses after restart: $ok/16"
[ "$ok" -ge 14 ] || fail "restart: too many failed requests ($ok/16)"

echo "== failover: turn Wi-Fi off while combining"
adb shell svc wifi disable
sleep 8
ok=$(traffic)
echo "successful HTTP responses on mobile only: $ok/16"
adb shell svc wifi enable
[ "$ok" -ge 14 ] || fail "failover to mobile failed ($ok/16)"

stop
wait_vpn down || fail "VPN did not stop at end"

echo "== crash check"
if adb logcat -d -b crash | grep -q "$PKG"; then adb logcat -d -b crash | tail -60; fail "app crashed"; fi
echo "E2E PASS"
