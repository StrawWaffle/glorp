# Plan

Glorp: destiny.gg on a phone. Chat that looks like the site's and can be used
logged in, the bigscreen embeds in proper native players with picture in
picture, and the rest of what a logged-in user can do on the site. Careful
with memory and battery throughout.

## Stack

- **Kotlin Multiplatform** for everything that isn't tied to a platform: the
  chat protocol, parsing, state, site API calls.
- **Compose Multiplatform** for the screens, shared between Android and iOS.
- **Ktor** for HTTP and the chat websocket (OkHttp on Android, NSURLSession on
  iOS).
- **Media3/ExoPlayer** on Android and **AVPlayer** on iOS for video, behind one
  shared player interface. Both use the hardware decoder and bring picture in
  picture.
- **Coil 3** (when emotes arrive) for images, with a bounded memory cache.

## What the site offers (read from omniliberal.dev, 2026-11-22)

- Chat page `/embed/chat` names the socket (`data-ws-url`) and CDN (`data-cdn`).
- Embeds on bigscreen: twitch, twitch-vod, twitch-clip, youtube, kick,
  kick-vod, rumble, facebook, vimeo, angelthump.
- Login providers: discord, google, kick, reddit, twitch, twitter.
- APIs seen in the site's scripts: notifications, upcoming events,
  fundraisers, gift check, TTS, Kick VODs.
- The OAuth in `destinygg/website/OAUTH.md` has only an `identify` scope and
  mixes the client secret into PKCE, so it can't carry chat for a phone app.

## Layers

1. **Read-only chat** (done): connect, backlog, live lines, flair name
   colours, mutes and bans, a fixed-size log, reconnect with backoff,
   socket closed off screen.
2. **Chat that looks like the site's** (started): emotes as whole words the
   sender's tier allows, sprite-strip animations read from `emotes.css`
   (played once from when the message was sent, then resting on a frame),
   GIFs, flair badges, links. Images go through one Coil loader with a
   capped memory cache and a disk cache. Still to do: the hand-written CSS
   effects on about 40 emotes (bounces, glows, neighbour effects), mentions
   and highlights, timestamps, combos, polls, the pinned message, emotes
   overlapping lines the way the site draws them rather than making lines
   taller.
3. **Logged in** (started): a login key made on the site's developer page in
   the phone's browser and pasted into the app; chat takes it as the
   `authtoken` cookie. (The site's own login page can't run in a web view:
   Twitch's and Kick's bot protection blank it and Google refuses in-app
   sign-in.) Pasting the browser's `sid`/`rememberme` cookies is the other
   way, needed later for site pages a key doesn't open; those are checked with
   `/api/chat/me`, which renews `sid`. Logins are kept encrypted with an
   Android Keystore key. Sending messages, `/me`, whispers out
   (`/w nick text`) and in, the server's error codes in chat-gui's words,
   mentions highlighted, a stop after `banned` or `toomanyconnections`.
   Still to do: a whisper inbox, ignore list, chat settings from the account,
   the rest of chat-gui's commands, cookies for site pages.
4. **Embeds** (started): a player above chat. It shows what bigscreen would
   (Destiny on the preferred platform, else the first live one, else what's
   hosted), or an embed picked from the list of what chat is watching, or
   from a `#platform/id` or bigscreen link in chat. The live socket
   (`wss://live.<host>`) pushes all of that, so nothing is polled. Each
   platform's own player runs in a web view, in a page whose origin is the
   site's, as bigscreen embeds it; the page is emptied whenever the app
   leaves the screen. Tested on the emulator: YouTube plays live; Kick plays
   after a tap and only at 360p ("Visit KICK for HD"). Still to do: landscape
   and full screen, remembering the chosen platform, the offline card's
   preview image.
5. **Native players** (started): a resolver turns an embed into an HLS
   stream and Media3/ExoPlayer plays it, with the web player as the fallback
   for anything it can't resolve. Kick is done: kick.com's own channel
   lookup gives `playback_url`, an IVS playlist up to 1080p60 (the embedded
   Kick player is held to 360p). Quality is capped at the screen's size, the
   screen stays on only while playing, and the player is released when the
   app leaves the screen. Live controls (LIVE / GO LIVE, quality menu).
   Picture in picture: leaving the app while a player shows shrinks it to
   the video (auto-enter on Android 12+, on leaving before that), chat stays
   connected, and a media session gives the window play and pause. Twitch
   live and VODs are done (a playback token from Twitch's web GraphQL API,
   then usher's playlist, as twitch.tv's player does); recordings and
   rewindable live streams get a timeline. Next: Grayjay's plugin engine for
   YouTube, Rumble and the rest; Twitch clips; background audio with the
   screen off.
   **Catch-up (wanted, not built yet).** Behind live, play faster until
   caught up, then return to normal speed without a stall. YouTube's version
   shows the trap: it speeds up until it reaches the newest segment with an
   empty buffer, stalls for seconds to refill, is now behind again, speeds up
   again, and loops. The fix is to steer by how much is buffered ahead as
   well as by the live offset:
   - ease the speed down as the gap closes (proportional, e.g. 1.5× far
     behind, approaching 1.0× near the target), rather than switching from
     fast to normal at the edge;
   - aim for a target offset that leaves a buffer of a couple of segments,
     never for the newest segment, and never speed up while the buffer ahead
     is below that floor;
   - hysteresis: start catching up only beyond, say, target + 10 s, and treat
     anything within target + 2 s as caught up, so small wobbles don't toggle
     it;
   - after a stall, don't resume catching up immediately.
   Media3's `DefaultLivePlaybackSpeedControl` already adjusts speed by live
   offset and buffer (by default only 0.97–1.03×); start from it with a higher
   maximum speed and a tuned proportional factor before writing our own.
   Tested well: unit tests that simulate offset, buffer and segment arrival
   (including late segments and a stall at the edge) and check there is no
   stall and no oscillation; then logging offset, speed, buffer and stalls on
   a phone over long real streams on Kick and Twitch.
6. **The rest of the site**: profile, subscriptions (opened in the browser),
   notifications, schedule, and whatever else a logged-in user can reach.
7. **Releases**: signed APKs on GitHub Releases, self-update from there
   (checksum checked, Android's installer), Obtainium-friendly. The signing
   key lives in `%USERPROFILE%\.secrets\glorp\`, never in the repo.

## iOS

Everything in `commonMain` is meant to run on iOS. What an iOS build still
needs, on a Mac: an Xcode project around `MainViewController()`, AVPlayer
behind the player interface, and Grayjay's plugin host rebuilt on
JavaScriptCore (Grayjay's engine runs on V8 and is Android only).
