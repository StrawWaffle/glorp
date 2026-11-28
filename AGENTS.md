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
  `-Pglorp.devSite=https://www.destiny.gg` points a dev build at the real site,
  for testing sign-in providers. Anything it writes then hits the real site, so
  use it with care. Only `https://` origins are accepted.
- Release builds are signed with the key in the owner's secrets folder,
  `%USERPROFILE%\.secrets\glorp\release.jks`, described by `release.properties`
  there. Every release must use it, or installed copies can't update; it is
  not in the repo and must never be. Losing it means a new package name.
- Releasing: set `glorp.version` in `gradle.properties`, commit, then
  `scripts/release.sh notes.md`. It builds the release APK, checks it is
  signed with the release key, writes its SHA-256, tags `v<version>`, pushes,
  and publishes `glorp-<version>.apk` and its `.sha256` on GitHub. Release
  builds look for a newer release once a day and offer it in the menu; the
  download must match the checksum before Android's installer gets it.
- Put **dev** builds on phones (`./gradlew :androidApp:assembleDev`): optimised
  like a release, signed with the debug key, named "Glorp (dev)", talking to the
  test site. Debug builds are for the debugger only: Compose runs several times
  slower in them, so they say nothing about performance.
- A test-site login for dev and debug builds: write it to the app's external
  files folder, `/sdcard/Android/data/io.github.strawwaffle.glorp.dev/files/dev-session`
  (adb can write there), as `k` and a login key, or `c`, `sid` and
  `rememberme`, one per line, piped from the owner's secrets folder. The next
  launch moves it into the encrypted store and deletes the file. Never put the
  values in the repo or on the host's command line.
- Emotes are drawn from the site's own `emotes.css`. After changing how
  (site/Emotes.kt, Motion.kt, Filter.kt, ui/ChatText.kt), run
  `scripts/emote-compare/compare.sh` with a dev build on the phone: it renders
  every animated emote with the real stylesheet in headless Chrome next to the
  app's frames at the same moments, and lists what Glorp doesn't read yet.
- Chat events the test account can't cause (polls, pins): write frames to
  `files/dev-frames` next to `dev-session`, one per line, as the server sends
  them (`POLLSTART {...}`); `sleep N` waits N ms and `$NOW` becomes the time.
  Dev builds play them into chat once it connects, then delete the file.
- Signing in: the site's own login page runs in a sign-in web view of its own
  (`ui/SiteLogin*.kt`, `auth/WebSignIn.kt`): every provider's page loads
  there. Once the login lands on `/profile` the app takes `sid` and
  `rememberme` from the web view's cookies, checks them like any cookie login
  and wipes the web views' cookies and storage. Google may still refuse at its
  password step, so "Sign in from a computer" (the QR) stays as the fallback.
  Passkeys can't work there: WebView's WebAuthn (`setWebAuthenticationSupport`)
  serves only sites whose `assetlinks.json` names the app (`FOR_APP`), or any
  site for browsers Google Password Manager has approved (`FOR_BROWSER`); the
  sign-in screen says to use a password or an authenticator code instead.
  The player's web view is no place to try logins: it has no
  `WebViewClient` (the web view cancels cross-site redirects) and, left to
  wrap its content, gives pages a zero-height `vh`, which blanks Twitch's.
  Login keys (developer page, Create Login Key; chat takes them as the
  `authtoken` cookie, the site's pages don't) are hidden from the sign-in
  screen unless built with `-Pglorp.loginKeys=true`; keys in use keep working.

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
- adb: use scrcpy's adb (see the folder's AGENTS.md) on its own server port,
  `ANDROID_ADB_SERVER_PORT=5038`. Another, older adb on this machine keeps
  restarting the default server and drops connections and port forwards.
- Players: dev builds take `am start -n io.github.strawwaffle.glorp.dev/io.github.strawwaffle.glorp.MainActivity --es embed kick/destiny`
  to open an embed, and their web views can be inspected over Chrome's
  debugging protocol (`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`).

## Layout

- `shared/src/commonMain/.../chat`: the chat socket, frame parsing, the line
  log, the view model.
- `shared/src/commonMain/.../site`: where a copy of the site lives (read from
  its `/embed/chat` page: `data-ws-url`, `data-cdn`) and its flairs.
- `shared/src/commonMain/.../ui`: Compose screens and the theme.
- `shared/src/{android,ios}Main`: the HTTP client per platform, the iOS entry.
- `androidApp/`: the Android shell (activity, manifest, build types).
- `docs/PLAN.md`: the architecture and the layers still to come.
- `grayjay/`: runs FUTO's Grayjay source plugins (YouTube so far) on QuickJS.
  Under FUTO's licence (`grayjay/LICENSE.md`, `grayjay/NOTICE.md`), kept apart
  from the rest. Plugins are downloaded from plugins.grayjay.app at run time
  and must match their signatures; never bundle one. Try the YouTube plugin
  on this PC with `./gradlew :grayjay:jvmTest -Pglorp.probe=<youtube watch url>`.
- `art/`: the icon art. `glorp-icon.png` is the original; `glorp-icon-plain.png`
  is the alien cut out onto plain navy, which the launcher icon is made from
  (`androidApp/src/main/res/mipmap-*`, an adaptive icon with a monochrome layer
  for themed icons).

## Screenshots

Live chat always has something unfit for a README in it. When taking
screenshots, leave such lines out and check every name and message in the
picture.
