#!/usr/bin/env bash
# Compares Glorp's emotes with the site's, frame by frame.
#
#   scripts/emote-compare/compare.sh [workdir]            every case
#   scripts/emote-compare/compare.sh workdir 9 10 14      redo the app's side of these cases
#
# 1. Fetches destiny.gg's emotes.css and emotes.json (once per work folder).
# 2. coverage.py lists every rule that reaches an emote and what Glorp doesn't
#    read in it (coverage.json).
# 3. cases.py picks the lines to compare: each emote whose own rules animate it,
#    each `A + B` pair rule and each flair rule (cases.json).
# 4. site.py renders every case with the real stylesheet in headless Chrome,
#    12 lines per case at evenly spaced moments of its animation.
# 5. app.sh plays the same lines into a dev build on a phone over adb, with the
#    emote clock frozen at the same moments (`--es emote-clock`) and emotes from
#    destiny.gg (`--es emote-cdn`), and screenshots each case.
# 6. sheets.py puts the two side by side: sheet-NN.png.
#
# Needs the dev build installed on the phone, Chrome, and Python with Pillow and
# websockets. Only the site's public stylesheet and made-up test lines are used.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=${1:-build/emote-compare}
shift || true
mkdir -p "$work"
cd "$work"

if [ ! -s emotes.css ]; then
  curl -sf -o emotes.css https://r2cdn.destiny.gg/emotes/emotes.css
  curl -sf -o emotes.json https://r2cdn.destiny.gg/emotes/emotes.json
fi

if [ $# -gt 0 ]; then
  python - "$@" <<'EOF'
import json, sys
ids = [int(i) for i in sys.argv[1:]]
c = json.load(open('cases.json')); t = json.load(open('out/times.json'))
json.dump([c[i] for i in ids], open('cs.json', 'w')); json.dump([t[i] for i in ids], open('ts.json', 'w'))
EOF
  rm -rf chunk
  bash "$here/app.sh" cs.json ts.json chunk
  k=0
  for i in "$@"; do mv "chunk/app-$(printf %03d $k).png" "app/app-$(printf %03d "$i").png"; k=$((k + 1)); done
  rm -rf chunk cs.json ts.json
  python "$here/sheets.py" "$@"
  echo "sheet-picked.png"
  exit 0
fi

python "$here/coverage.py" emotes.css emotes.json coverage.json > coverage.txt
python "$here/cases.py" emotes.css emotes.json cases.json
python "$here/site.py" cases.json out
# The app's side in runs of 20 cases: over a long run the screenshots drift.
mkdir -p app
n=$(python -c "import json; print(len(json.load(open('cases.json'))))")
for ((s = 0; s < n; s += 20)); do
  python -c "
import json; s=$s; c=json.load(open('cases.json'))[s:s+20]; t=json.load(open('out/times.json'))[s:s+20]
json.dump(c,open('cs.json','w')); json.dump(t,open('ts.json','w'))"
  rm -rf chunk
  bash "$here/app.sh" cs.json ts.json chunk >/dev/null
  for f in chunk/app-*.png; do i=$((10#$(basename "$f" .png | cut -d- -f2) + s)); mv "$f" "app/app-$(printf %03d $i).png"; done
done
rm -rf chunk cs.json ts.json
python "$here/sheets.py"
echo "Sheets in $work; what Glorp doesn't read is in $work/coverage.txt"
