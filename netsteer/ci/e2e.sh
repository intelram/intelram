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
log() { adb logcat -d -s NetSteer:I; }

echo "== device networks"
adb shell dumpsys connectivity | grep -oE "Transports: [A-Z|]+|VALIDATED" | sort | uniq -c

echo "== install"
adb install -r -g "$APK" || fail "install"
# Pre-grant VPN consent (same effect as the user tapping OK on the system dialog).
adb shell appops set "$PKG" ACTIVATE_VPN allow || fail "appops"
adb logcat -c

# Debug-build hooks: START/STOP plus settings extras (see MainActivity.handleAction).
start() { adb shell am start -n "$ACT" -a "$PKG.START" "$@" >/dev/null; }
stop() { adb shell am start -n "$ACT" -a "$PKG.STOP" >/dev/null; }
set_opts() { adb shell am start -n "$ACT" "$@" >/dev/null; }

wait_vpn() { # $1 = up|down
  for _ in $(seq 1 30); do
    if adb shell ip addr show tun0 2>/dev/null | grep -q "198.18.0.1"; then [ "$1" = up ] && return 0
    else [ "$1" = down ] && return 0; fi
    sleep 1
  done
  return 1
}

# One HTTP request from the shell user (routed through the VPN); prints the status line or nothing.
http() { # host path [connect-to]
  adb shell "printf 'GET $2 HTTP/1.1\r\nHost: $1\r\nConnection: close\r\nUser-Agent: netsteer-e2e\r\n\r\n' | timeout 20 nc ${3:-$1} 80" 2>/dev/null | head -1 | tr -d '\r'
}

traffic() { # 16 parallel requests; echoes the number of HTTP responses
  local ok=0 i host path
  rm -f /tmp/e2e_*.txt
  for i in $(seq 1 16); do
    if [ $((i % 2)) = 0 ]; then host=connectivitycheck.gstatic.com; path=/generate_204; else host=www.google.com; path=/; fi
    adb shell "printf 'GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: netsteer-e2e\r\n\r\n' | timeout 25 nc $host 80" > /tmp/e2e_$i.txt 2>&1 &
  done
  wait
  # Any HTTP status line proves the full path (DNS -> TUN -> hev -> proxy -> link -> server -> back).
  for i in $(seq 1 16); do grep -q "^HTTP/1\.[01] [1-5][0-9][0-9]" /tmp/e2e_$i.txt && ok=$((ok + 1)); done
  for i in $(seq 1 16); do echo "  req $i: $(head -1 /tmp/e2e_$i.txt | tr -d '\r' | cut -c1-80)" >&2; done
  echo "$ok"
}

stats_line() { log | grep " stats " | tail -1; }
cell_rx() { stats_line | sed -n 's/.*cell=\([0-9]*\)\/.*/\1/p'; }
cell_opened() { stats_line | grep -o "opened=[^ ]*" | tr ',' '\n' | sed -n 's/.*cell:\([0-9]*\)$/\1/p'; }
wifi_rx() { stats_line | sed -n 's/.*wifi=\([0-9]*\)\/.*/\1/p'; }
fresh_stats() { sleep 6; stats_line; } # stats are logged every 5 s

echo "== make sure Wi-Fi and mobile data are both on, and the app sees both validated"
adb shell svc wifi enable
adb shell svc data enable
adb shell am start -n "$ACT" >/dev/null
both=""
for _ in $(seq 1 90); do
  last=$(log | grep " net: " | tail -1)
  if echo "$last" | grep -q "CELLULAR validated=true" && echo "$last" | grep -q "WIFI validated=true"; then both=1; break; fi
  sleep 1
done
log | grep " net: " | tail -3
if [ -z "$both" ]; then
  adb shell dumpsys wifi | grep -iE "Wi-Fi is|mWifiInfo|current network|SSID" | head -15
  fail "emulator did not provide validated Wi-Fi and mobile at the same time"
fi

echo "== start with DEFAULT settings (fresh install must use Wi-Fi + mobile together)"
start
wait_vpn up || fail "VPN did not come up"
for _ in $(seq 1 45); do log | grep -q "uplinks: \[cell, wifi\]" && break; sleep 1; done
log | grep "uplinks:" | tail -2
log | grep -q "uplinks: \[cell, wifi\]" || fail "Wi-Fi and mobile were not both available"
sleep 6
log | grep " links " | tail -1
log | grep " links " | tail -1 | grep -q "cell=ACTIVE" || fail "mobile is not ACTIVE with default settings (standby bug)"
log | grep " links " | tail -1 | grep -q "wifi=ACTIVE" || fail "Wi-Fi is not ACTIVE with default settings"

echo "== traffic round 1 (both links)"
ok=$(traffic)
echo "successful HTTP responses: $ok/16"
[ "$ok" -ge 15 ] || fail "too many failed requests ($ok/16)"
line=$(fresh_stats); echo "$line"
w=$(wifi_rx); c=$(cell_rx)
[ "${w:-0}" -gt 0 ] || fail "no bytes received over Wi-Fi"
[ "${c:-0}" -gt 0 ] || fail "no bytes received over mobile"
echo "both links carried traffic: wifi_rx=$w cell_rx=$c"

echo "== notification present"
adb shell dumpsys notification --noredact | grep -q "pkg=$PKG" || fail "no notification"

echo "== ad blocking: filter lists download on the device"
for _ in $(seq 1 120); do
  n=$(log | grep " filters: " | tail -1 | sed -n 's/.*filters: \([0-9]*\) rules.*/\1/p')
  [ "${n:-0}" -gt 100000 ] && break
  sleep 1
done
log | grep " filters: " | tail -2
[ "${n:-0}" -gt 100000 ] || fail "filter lists not downloaded/compiled (rules=${n:-0})"

echo "== ad blocking: ad domain via DNS is blocked, normal site works"
r=$(http doubleclick.net /); echo "  doubleclick.net -> '${r}'"
[ -z "$r" ] || fail "doubleclick.net was not blocked"
r=$(http pagead2.googlesyndication.com /pagead/show_ads.js); echo "  pagead2.googlesyndication.com -> '${r}'"
[ -z "$r" ] || fail "googlesyndication was not blocked"
r=$(http www.google.com /); echo "  www.google.com -> '${r}'"
echo "$r" | grep -q "^HTTP/1" || fail "normal site broken by ad blocking"

echo "== ad blocking: app using a bare IP (own DNS) is still blocked by Host header"
GIP=$(getent ahostsv4 www.google.com | awk '{print $1}' | head -1)
echo "  google IP: $GIP"
r=$(http pagead2.googlesyndication.com / "$GIP"); echo "  Host: ad domain @ $GIP -> '${r}'"
[ -z "$r" ] || fail "Host-header sniffing did not block"
r=$(http www.google.com / "$GIP"); echo "  Host: www.google.com @ $GIP -> '${r}'"
echo "$r" | grep -q "^HTTP/1" || fail "bare-IP request to allowed host failed"
line=$(fresh_stats); echo "$line"
echo "$line" | grep -q "blocked=0$" && fail "blocked counter did not increase"

echo "== battery saver: light traffic must not touch mobile data"
set_opts --ez smart true
sleep 3
c0=$(fresh_stats >/dev/null; cell_opened)
ok=$(traffic)
echo "successful HTTP responses (saver on): $ok/16"
[ "$ok" -ge 15 ] || fail "saver mode: requests failed ($ok/16)"
line=$(fresh_stats); echo "$line"
c1=$(cell_opened)
echo "  connections opened on mobile: before=$c0 after=$c1"
if log | grep " stats " | tail -3 | grep -q "boost=true"; then
  echo "  NOTE: device background traffic triggered a boost during this window (legitimate); not asserting idle mobile"
else
  [ -n "$c1" ] && [ "$c1" = "$c0" ] || fail "battery saver put light traffic on mobile"
  echo "$line" | grep -q "mobileReq=false" || fail "battery saver kept requesting mobile data"
  echo "  OK: mobile stayed idle and unrequested"
fi

echo "== battery saver: sustained download brings mobile in (boost)"
BIG=""
for u in "dl.google.com /android/repository/platform-tools-latest-linux.zip" \
         "speedtest.tele2.net /100MB.zip" "proof.ovh.net /files/100Mb.dat" "ipv4.download.thinkbroadband.com /50MB.zip"; do
  set -- $u
  s=$(adb shell "printf 'HEAD $2 HTTP/1.1\r\nHost: $1\r\nConnection: close\r\n\r\n' | timeout 15 nc $1 80" 2>/dev/null | head -1 | tr -d '\r')
  echo "  probe $1$2 -> $s"
  if echo "$s" | grep -q " 200"; then BIG="$1 $2"; break; fi
done
if [ -z "$BIG" ]; then
  echo "  WARNING: no large HTTP test file reachable; skipping the boost-on check"
else
  set -- $BIG
  for k in 1 2 3 4; do
    adb shell "end=\$((\$(date +%s)+25)); while [ \$(date +%s) -lt \$end ]; do printf 'GET $2 HTTP/1.1\r\nHost: $1\r\nConnection: close\r\n\r\n' | timeout 25 nc $1 80 >/dev/null; done" &
  done
  boosted=""
  for _ in $(seq 1 20); do
    sleep 1
    stats_line | grep -q "boost=true" && { boosted=1; break; }
  done
  stats_line
  [ -n "$boosted" ] || { wait; fail "sustained download did not turn on boost"; }
  sleep 4 # mobile comes up
  c2=$(cell_opened)
  ok=$(traffic)
  wait
  line=$(fresh_stats); echo "$line"
  c3=$(cell_opened)
  echo "  connections opened on mobile during boost: before=$c2 after=$c3 (requests ok: $ok/16)"
  [ "$ok" -ge 15 ] || fail "boost: requests failed ($ok/16)"
  [ "$c3" -gt "$c2" ] || fail "boost did not put new connections on mobile"
fi
set_opts --ez smart false

echo "== speed test (per link and together)"
adb shell am start -n "$ACT" -a "$PKG.SPEEDTEST" >/dev/null
for _ in $(seq 1 90); do log | grep -q " speedtest " && break; sleep 1; done
log | grep " speedtest " | tail -1
st=$(log | grep " speedtest " | tail -1)
[ -n "$st" ] || fail "speed test did not finish"
echo "$st" | grep -q "combined=[1-9]" || fail "speed test measured nothing"

echo "== exclude an app: its traffic must bypass NetSteer (tunnel reloads itself)"
set_opts --es exclude com.android.shell
for _ in $(seq 1 20); do log | grep -q "excluded apps 1" && break; sleep 1; done
log | grep -q "excluded apps 1" || fail "VPN did not reload with the excluded app"
wait_vpn up || fail "VPN not up after reload"
sleep 3
ok=$(traffic)
echo "successful HTTP responses while excluded: $ok/16"
[ "$ok" -ge 15 ] || fail "excluded app lost internet ($ok/16)"
# Decisive check: an excluded app bypasses NetSteer, so it is not ad-filtered either.
r=$(http doubleclick.net /); echo "  doubleclick.net from excluded shell -> '${r}'"
echo "$r" | grep -q "^HTTP/1" || fail "excluded app's traffic still went through NetSteer (ad domain blocked)"
n0=$(log | grep -c "excluded apps 0")
set_opts --es exclude ""
for _ in $(seq 1 20); do [ "$(log | grep -c "excluded apps 0")" -gt "$n0" ] && break; sleep 1; done
[ "$(log | grep -c "excluded apps 0")" -gt "$n0" ] || fail "VPN did not reload after re-including the app"
wait_vpn up || fail "VPN not up after second reload"
sleep 3
ok=$(traffic)
[ "$ok" -ge 15 ] || fail "traffic failed after re-including the app ($ok/16)"
r=$(http doubleclick.net /); echo "  doubleclick.net after re-including -> '${r}'"
[ -z "$r" ] || fail "re-included app is not filtered again"

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
[ "$ok" -ge 15 ] || fail "restart: too many failed requests ($ok/16)"

echo "== failover: turn Wi-Fi off while running"
adb shell svc wifi disable
sleep 8
ok=$(traffic)
echo "successful HTTP responses on mobile only: $ok/16"
adb shell svc wifi enable
[ "$ok" -ge 15 ] || fail "failover to mobile failed ($ok/16)"

stop
wait_vpn down || fail "VPN did not stop at end"

echo "== crash check"
if adb logcat -d -b crash | grep -q "$PKG"; then adb logcat -d -b crash | tail -60; fail "app crashed"; fi
echo "E2E PASS"
