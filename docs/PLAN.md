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
4. **Embeds**: the bigscreen list and what chat is watching. First every embed
   in a WebView with the platform's own player, so all of them work.
5. **Native players**: Grayjay's plugin engine resolves an embed to its HLS or
   DASH stream, ExoPlayer plays it. Kick and Twitch first, then YouTube and
   Rumble. Picture in picture and background audio, with chat kept connected
   while playing.
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
