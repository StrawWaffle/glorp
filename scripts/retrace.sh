#!/usr/bin/env bash
# Turns the minified stack trace in a crash report back into Glorp's own
# names and lines, with the mapping file published with that release.
#
#   scripts/retrace.sh 0.1.23 report.txt     (or the report on stdin)
set -euo pipefail
version=${1:?usage: scripts/retrace.sh <version> [report.txt]}
cache="${TMPDIR:-/tmp}/glorp-mapping"
mapping="$cache/mapping-glorp-$version.txt"
mkdir -p "$cache"
[ -s "$mapping" ] || curl -sfL -o "$mapping" \
  "https://github.com/StrawWaffle/glorp/releases/download/v$version/mapping-glorp-$version.txt" \
  || { echo "No mapping file for $version (releases before 0.1.23 have none)."; exit 1; }
sdk=${ANDROID_HOME:-"$LOCALAPPDATA/Android/Sdk"}
retrace=$(ls "$sdk"/cmdline-tools/*/bin/retrace* | head -1)
"$retrace" "$mapping" "${2:-/dev/stdin}"
