#!/usr/bin/env bash
# app.sh <cases.json> <times.json> <outdir>: plays every case into the dev build on the spare device with
# the emote clock frozen, 12 lines per case at the site's moments, and saves a screenshot per case.
# Uses the one device adb sees, or the one ANDROID_SERIAL names.
set -u
PKG=io.github.strawwaffle.glorp.dev
cases=$1; times=$2; out=$3
GAP=${GAP:-5000}
mkdir -p "$out"
X=$(( $(adb shell date +%s | tr -d '\r') * 1000 - 20000 ))
python - "$cases" "$times" "$X" "$GAP" > "$out/frames" <<'EOF'
import json, sys
cases = json.load(open(sys.argv[1])); times = json.load(open(sys.argv[2])); X = int(sys.argv[3]); gap = sys.argv[4]
for c, t in zip(cases, times):
    for k, ms in enumerate(t['times']):
        msg = {"nick": f"t{k}", "features": c['features'], "timestamp": X - ms, "data": f"{c['text']} {k}"}
        if any(f.startswith('flair') for f in c['features']): msg["subscription"] = {"tier": 5}
        print('MSG ' + json.dumps(msg))
    print('sleep ' + gap)
EOF
tr -d '\r' < "$out/frames" > "$out/frames.lf"
MSYS_NO_PATHCONV=1 adb push "$(cygpath -w "$out/frames.lf")" /sdcard/Android/data/$PKG/files/dev-frames >/dev/null
rm "$out/frames" "$out/frames.lf"
adb logcat -c
adb shell am start -S -n $PKG/io.github.strawwaffle.glorp.MainActivity --es emote-cdn https://r2cdn.destiny.gg --es emote-clock $X >/dev/null
for i in $(seq 1 40); do adb logcat -d | grep -q "Glorp chat: connected" && break; sleep 0.5; done
start=$(date +%s%N)
n=$(python -c "import json; print(len(json.load(open('$cases'))))")
for i in $(seq 0 $((n - 1))); do
  # Lines of case i arrive 2 s after connecting plus i gaps; look 2 s into its gap (pictures load on the first case).
  due=$(( start / 1000000 + 2000 + i * GAP + (i == 0 ? 3500 : 2500) ))
  now=$(( $(date +%s%N) / 1000000 ))
  [ $due -gt $now ] && sleep "$(python -c "print(($due - $now) / 1000)")"
  adb exec-out screencap -p > "$out/app-$(printf %03d $i).png"
done
echo done $n
