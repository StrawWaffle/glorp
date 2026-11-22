# Project instructions

## Identity

- Published as **StrawWaffle**. Commit only with this repo's local
  `user.name`/`user.email`, and add no co-author or attribution lines; the
  hooks in `.git/hooks` reject them.
- Use only StrawWaffle's credentials for GitHub. Don't use the `gh` CLI here
  unless it is logged in as StrawWaffle.
- No links to, or mentions of, other projects or accounts of the owner. That
  includes where the palette came from.
- Glorp is an unofficial community app. Every place that describes it (README,
  About screen, release notes) says it is not affiliated with destiny.gg or
  Destiny. Don't use destiny.gg's logo, or emote art, in the icon or the UI
  chrome.

## Engineering

- Android first, built so an iOS app can follow: shared Kotlin Multiplatform
  code (`shared/`), with platform code behind small `expect`/`actual` seams.
  The iOS half can only be built on a Mac; keep `iosMain` compiling in
  principle and don't let Android-only APIs leak into `commonMain`.
- Memory and battery are features. Before adding anything that runs
  continuously, say what it costs:
  - chat keeps a fixed number of lines (`ChatLog`), nothing grows with time;
  - frames are applied in batches, so the list redraws a few times a second;
  - events the app doesn't use are skipped before their JSON is decoded;
  - one socket, held only while the app is on screen (later: or in PiP);
  - no polling where the server pushes.
- Grow in layers. Each change should leave a working app.
- Remove obsolete paths instead of keeping compatibility layers.
- Be gentle with destiny.gg: one chat connection, chat-gui's backoff on
  reconnect, a User-Agent that names the app.
- The chat server never answers websocket pings, so don't turn on OkHttp's
  `pingInterval` (it would call every healthy connection dead). `ChatSocket`
  sends the protocol's own `PING` event after 30 s of silence instead, and
  answers the server's `PING` events with `PONG` as chat-gui does.
- Dev builds (`debug`, package `…glorp.dev`) talk to destiny.gg's test site
  `https://www.omniliberal.dev`; release builds to `https://www.destiny.gg`.
  Try anything that writes (sending messages, settings) on the test site first.
- A test-site login for dev builds: put `sid` and `rememberme` on two lines in
  the app's private `files/dev-session` (`adb shell run-as <package> sh -c
  'cat > files/dev-session'`, fed from the owner's secrets folder); the next
  launch moves it into the encrypted store and deletes the file. Never put
  the values in the repo, a command line, or the clipboard.

## Licence

Glorp's own code is under the PolyForm Noncommercial License 1.0.0
(`LICENSE.md`, with its `Required Notice:` line).

Video playback will build on Grayjay's plugin engine, which is under FUTO's
Source First License 1.1: free, non-commercial, FUTO's notices kept, a
"modified" notice, and that licence shipped with the app. Code taken from
Grayjay goes in its own module with its own LICENSE and notices intact, and
the About screen names it. Each Grayjay plugin bundled or loaded keeps its own
licence; check it before using one.

## Building

- JDK: Android Studio's bundled runtime, not the system Java 25:
  `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug`
- Emulators: `Pixel_9a`, `Medium_Phone_API_36.1` (`$LOCALAPPDATA/Android/Sdk/emulator`).
  Install with `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Layout

- `shared/src/commonMain/.../chat`: the chat socket, frame parsing, the line
  log, the view model.
- `shared/src/commonMain/.../site`: where a copy of the site lives (read from
  its `/embed/chat` page: `data-ws-url`, `data-cdn`) and its flairs.
- `shared/src/commonMain/.../ui`: Compose screens and the theme.
- `shared/src/{android,ios}Main`: the HTTP client per platform, the iOS entry.
- `androidApp/`: the Android shell (activity, manifest, build types).
- `docs/PLAN.md`: the architecture and the layers still to come.
- `art/`: the icon art. `glorp-icon.png` is the original; `glorp-icon-plain.png`
  is the alien cut out onto plain navy, which the launcher icon is made from
  (`androidApp/src/main/res/mipmap-*`, an adaptive icon with a monochrome layer
  for themed icons).

## Screenshots

Live chat always has something unfit for a README in it. When taking
screenshots, leave such lines out and check every name and message in the
picture.
