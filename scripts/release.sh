#!/usr/bin/env bash
# Publishes the version in gradle.properties as a GitHub release:
# builds the release APK, checks it's signed with Glorp's release key,
# writes its SHA-256, tags the commit and uploads both to
# StrawWaffle/glorp, with the mapping file for crash reports. The app and Obtainium pick it up from there.
#
#   scripts/release.sh notes.md
#
# notes.md becomes the release text. A version ending in -test.N (set in
# gradle.properties) is published as a test version. Needs the release key in
# ~/.secrets/glorp/ and StrawWaffle's GitHub login in Git Credential Manager.
set -euo pipefail
cd "$(dirname "$0")/.."

notes=${1:?usage: scripts/release.sh notes.md}
version=$(sed -n 's/^glorp.version=//p' gradle.properties | tr -d '\r')
tag="v$version"
repo=StrawWaffle/glorp

[ -z "$(git status --porcelain)" ] || { echo "Commit everything first."; exit 1; }
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && { echo "$tag already exists."; exit 1; }

export JAVA_HOME=${JAVA_HOME:-"/c/Program Files/Android/Android Studio/jbr"}
./gradlew -q :androidApp:assembleRelease --console=plain

apk=androidApp/build/outputs/apk/release/androidApp-release.apk
out=build/release
mkdir -p "$out"
name="glorp-$version.apk"
cp "$apk" "$out/$name"
# The map from minified names back to the code, for reading crash reports
# (scripts/retrace.sh).
cp androidApp/build/outputs/mapping/release/mapping.txt "$out/mapping-glorp-$version.txt"

# Signed with the release key, or phones would refuse it as an update.
sdk=${ANDROID_HOME:-"$LOCALAPPDATA/Android/Sdk"}
apksigner=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)apksigner.bat
props="$HOME/.secrets/glorp/release.properties"
store=$(sed -n 's/^storeFile=//p' "$props" | tr -d '\r')
alias=$(sed -n 's/^keyAlias=//p' "$props" | tr -d '\r')
# The password goes to keytool through its environment, never the command line.
want=$(GLORP_STOREPASS=$(sed -n 's/^storePassword=//p' "$props" | tr -d '\r\n') \
  "$JAVA_HOME/bin/keytool" -list -v -keystore "$store" -alias "$alias" -storepass:env GLORP_STOREPASS 2>/dev/null \
  | sed -n 's/^\s*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
got=$("$apksigner" verify --print-certs "$out/$name" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr -d '\r')
[ -n "$want" ] && [ "$want" = "$got" ] || { echo "The APK isn't signed with the release key."; exit 1; }

(cd "$out" && sha256sum "$name" > "$name.sha256")

token=$(printf "protocol=https\nhost=github.com\nusername=StrawWaffle\n\n" | GCM_INTERACTIVE=never git credential fill | sed -n 's/^password=//p')
[ -n "$token" ] || { echo "No GitHub login for StrawWaffle."; exit 1; }
auth=(-H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json")

git tag -a "$tag" -m "Glorp $version"
GCM_INTERACTIVE=never git push -q origin main "$tag"

# A test version (0.1.23-test.1) is a pre-release: only the app's "Get test
# versions" switch offers it; Obtainium and everyone else stay on releases.
body=$(python -c 'import json,sys; v=sys.argv[2]; print(json.dumps({"tag_name":sys.argv[1],"name":"Glorp "+v+(" (test)" if "-test." in v else ""),"prerelease":"-test." in v,"body":open(sys.argv[3],encoding="utf-8").read()}))' "$tag" "$version" "$notes")
id=$(curl -sf "${auth[@]}" -X POST "https://api.github.com/repos/$repo/releases" -d "$body" | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')
for file in "$name" "$name.sha256" "mapping-glorp-$version.txt"; do
  type=$([ "${file##*.}" = apk ] && echo application/vnd.android.package-archive || echo text/plain)
  curl -sf "${auth[@]}" -H "Content-Type: $type" --data-binary "@$out/$file" \
    "https://uploads.github.com/repos/$repo/releases/$id/assets?name=$file" >/dev/null
done
echo "Published https://github.com/$repo/releases/tag/$tag"
