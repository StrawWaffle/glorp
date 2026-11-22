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
   capped memory cache and a disk cache. The stylesheet's hand-made
   movements are read generically from each emote's own rule and keyframes
   (transform, opacity, brightness; timing, delays, repeats, easing,
   `steps()`, `transform-origin`) and drawn in a graphics layer, so they
   don't re-lay out the line. Still to do: the effects that depend on a
   neighbouring emote (`:has()`, `+`), on a combo's size, or on pseudo-
   elements, filters other than brightness, timestamps, emotes
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
   The input completes emotes (only those the user's tier allows), names
   (recent speakers, then the `NAMES` roster, read only when signed in) and
   commands in a strip above the keyboard, offers the last twenty messages
   sent, counts down near 512 characters and keeps its draft in the view
   model. A face button swaps the keyboard for an emote grid. chat-gui's
   commands are read as it reads them: `/r`, `/ignore`, `/highlight`,
   `/help`, `/emotes`, `/embed` are handled in the app, moderator commands
   go to the server as the same events, and the ones that need the site's
   login or features Glorp lacks (`/stalk`, tags) say so. Polls show above
   chat as chat-gui shows them (bars, a timer, hidden and sub-weighted
   polls, the winner); tapping an option or typing its number votes, and
   `/poll`, `/pollstop`, `/showpoll` work for accounts with the `POLLS`
   role. The pinned message sits above chat and can be closed per pin. The ignore
   and highlight lists and the history are kept on the device, not in the
   account's chat settings (login keys don't open the site's API). Tapping
   a name offers mention, whisper, highlight and ignore.
   Whispers have their own screen (menu → Whispers, `/open nick`, or the
   "new whispers" tab over chat): conversations newest first with unread
   counts, each with a reply box. A whisper shows as sent only once the
   server takes it (`PRIVMSGSENT`); a refusal shows in the conversation.
   They're kept in memory only. With the site's cookies the site's
   conversation list and history load too (`/api/messages/...`, which
   refuses login keys).
   Still to do: chat settings from the account,
   cookies for site pages.
4. **Embeds** (started): a player above chat. It shows what bigscreen would
   (Destiny on the preferred platform, else the first live one, else what's
   hosted), or an embed picked from the list of what chat is watching, or
   from a `#platform/id` or bigscreen link in chat. The live socket
   (`wss://live.<host>`) pushes all of that, so nothing is polled. Each
   platform's own player runs in a web view, in a page whose origin is the
   site's, as bigscreen embeds it; the page is emptied whenever the app
   leaves the screen. Tested on the emulator: YouTube plays live; Kick plays
   after a tap and only at 360p ("Visit KICK for HD"). Sideways, the video sits
   beside chat; "Full screen" (under the video, or in the native player's
   controls) gives it the whole screen, hides the system bars and turns the
   phone sideways, and Back leaves it. The player moves between these
   layouts without being rebuilt, so the stream doesn't reload. Still to
   do: remembering the chosen platform, the offline card's preview image.
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
   **Rumble** stays on its web player: rumble.com answers anything that
   isn't a browser with Cloudflare's "Just a moment" check, so a native
   lookup would break; the embed works in the web view. Twitch clips (MP4)
   and Kick VODs (HLS from kick.com's video lookup) are native.

   **AngelThump (done, 0.1.3).** `video/AngelThump.kt`: the API's stream
   listing (offline when none), a token from `vigor.angelthump.com/<ch>/token`
   (with the id AngelThump's web player sends), the master playlist, then its
   media playlist timed on all five edges at once and played from the
   fastest. `EdgeFailoverDataSource` sends every edge request to the chosen
   edge, retries a failed one on the two nearest, moves on after a read
   fails, and after three stalls in a minute. Catch-up covers falling behind.
   The research it follows: the community userscript "AngelThump Edge
   Picker" (greasyfork.org/en/scripts/597526, MIT) shows how to play it well:
   the same `/hls/...` stream is served by five edge servers
   (`nyc1`, `sfo1`, `ams1`, `fra1`, `sgp1` `.angelthump.com`), so any request
   can be rewritten to another edge. It times each edge's media playlist to
   pick one automatically (with a manual override), retries a stalled
   segment on the nearest other edges (up to two backups, nearest first:
   nyc1 -> sfo1, ams1...; ams1 -> fra1, nyc1...), switches edge after three
   rebuffers in a minute, and jumps back to live past 15 s of delay. Natively
   that is an ExoPlayer DataSource that swaps the host, plus a load error
   policy that retries on a neighbour; the stream address still has to come
   from AngelThump's own lookup (not in the script). Patreon viewers use a
   separate path the script leaves alone.
   **Catch-up (done, 0.1.2).** Media3's `DefaultLivePlaybackSpeedControl`,
   tuned in `Playback.catchUpControl()`: up to 1.5x, proportional (0.05 per
   second behind), normal speed within 1.5 s, +2 s on the target after a
   stall. It already keeps the target above the smallest offset the buffer
   safely allows, which is what avoids YouTube's loop. Measured on the
   emulator (Twitch, `GlorpLive` log in dev builds): 46.8 s behind after a
   25 s pause, back to the stream's usual 22 s in about 50 s, easing off on
   the way, buffer never below 17 s, no stall. Media3 doesn't report its
   speed, so the controls' "Catching up" reads it off the shrinking offset.
   The notes the design started from:
   **Catch-up (as first planned).** Behind live, play faster until
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
7. **Releases** (done): signed APKs on GitHub Releases
   (github.com/StrawWaffle/glorp), published by `scripts/release.sh`.
   Release builds check for a newer one once a day and offer it at the top
   of the menu; the download is checked against its SHA-256 and handed to
   Android's installer, which refuses another key's signature. Obtainium
   reads the same releases. Still to do: an APK per processor type.

## Community sites (researched 2026-11-22)

None of these publish terms or a versioned API. Ask each maintainer before
shipping, identify Glorp with its User-Agent, fetch only when a screen is
open, and fall back to a link to the site when something changes.

- **rustlesearch.dev** (chat log search). `https://api-v2.rustlesearch.dev`,
  anonymous, CORS open, **200 requests a day per IP**.
  `/anon/search?channel=Destinygg&username=&text=a|b&start_date=&end_date=&search_after=`
  returns 100 messages per page, newest first (`{data:{messages:[{channel,text,ts,username,searchAfter}]}}`),
  paged by the last `searchAfter`; `/anon/surrounds?channel=&username=&datetime=`
  gives the lines around one; `/anon/channels`. chat-gui uses it for the
  user menu's recent messages. Its /stalk and /mentions use destiny.gg's own
  `/api/chat/stalk` and `/api/chat/mentions` instead (signed-in cookies).
  Plan: a search screen rendered as chat lines, a user sheet from tapping a
  nick, and mentions from destiny.gg's API so the shared quota isn't spent.
- **mitchdev.net/dgg** (by Mitchdev). Poll archive and embed history.
  `https://api.mitchdev.net/dgg/polls?before=&search=&startedBy=&sort=`,
  `/dgg/poll/<id>`, `/dgg/polls/makers`, `/dgg/polls/yeevpepe`, and
  `/dgg/embeds/chart?from=&to=` (a compact chart format with outages,
  hosting, bans and Destiny's stream status); a `wss://api.mitchdev.net/live-ws`
  pushes embeds every 30 s. Plan: a poll archive screen (paged by cursor) and
  an embed-history tab; keep destiny.gg's live socket as the live source.
- **dinkdonk.mov** (by Mitch). Community movie-night votes and ratings, no
  media of its own. Mostly server-rendered pages; one public JSON endpoint,
  `/api/page/history/full?offset=&limit=`; a keyed API exists behind login.
  "Login with DGG" runs through Mitch's OpenID Connect server
  `https://auth.mitch.my` (scopes `openid profile dgg`, public clients with
  PKCE allowed), which might also give Glorp a cleaner destiny.gg login if
  Mitch registers a client for it. Plan: read-only schedule, history and
  results screens first; voting once there's a client or API key.

## iOS

Everything in `commonMain` is meant to run on iOS. What an iOS build still
needs, on a Mac: an Xcode project around `MainViewController()`, AVPlayer
behind the player interface, and Grayjay's plugin host rebuilt on
JavaScriptCore (Grayjay's engine runs on V8 and is Android only).
