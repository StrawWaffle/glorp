# Glorp

<img src="art/glorp-icon-plain.png" alt="Glorp, a green furry alien with antennae" width="96" align="right">

An unofficial destiny.gg app for Android: chat as the site draws it, and
bigscreen's streams in native players with picture in picture, made to go
easy on memory and battery.

Glorp is a community project. It is not made by or affiliated with destiny.gg
or Destiny.

## What it does

- **Chat**: emotes (animated, combos), flairs, links, mentions, whispers with
  their own conversations, polls you can vote in, the pinned message, the
  site's commands, and an input with emote, name and command suggestions.
  Tags with notes, highlight words, timestamps, flair icons and the ignore
  and highlight lists follow your account's chat settings when you sign in
  with cookies.
- **Bigscreen**: plays what bigscreen shows, or anything chat is watching.
  Kick, Twitch (live, VODs and clips), YouTube (live and recordings) and AngelThump play in
  Android's own player, with a quality menu, a live button and picture in picture, and keep
  their sound with the screen off. Other platforms use their web player.
- **Sideways and full screen**: the video beside chat, or the whole screen.
- **Community screens**: chat log search (rustlesearch.dev), the poll archive
  (mitchdev.net) and movie nights (dinkdonk.mov).

Still to come is in [docs/PLAN.md](docs/PLAN.md).

## Install

Download the APK from [Releases](https://github.com/StrawWaffle/glorp/releases),
or add this repository to [Obtainium](https://obtainium.imranr.dev/). Glorp
also checks for new releases itself and offers them at the top of its menu.
The first time, Android asks you to let Glorp install apps, and Google Play
Protect may offer to scan it, as it does for any app from outside the Play
Store.

Android 8 or newer.

## Signing in

Glorp can't use the site's login page (Twitch, Kick and Google block sign-in
inside apps), so it signs in one of two ways:

- **A login key** (the simplest): on destiny.gg in your phone's browser, open
  your account's developer settings, create a login key and paste it into
  Glorp. It lets you chat and whisper.
- **The site's cookies** (`sid` and `rememberme` from your browser): these
  also open your whisper history.

Logins are kept on the phone, encrypted with a key in Android's keystore.

## Thanks

- [rustlesearch.dev](https://rustlesearch.dev/), [mitchdev.net](https://mitchdev.net/dgg/)
  (by Mitchdev) and [dinkdonk.mov](https://dinkdonk.mov/) (by Mitch), whose
  data the community screens show.
- The "AngelThump Edge Picker" userscript, whose way of choosing and
  falling back between AngelThump's servers Glorp follows.
- [FUTO's Grayjay](https://grayjay.app/): YouTube plays through Grayjay's
  YouTube plugin, run by code adapted from Grayjay.

## Licence

Glorp's own code: [PolyForm Noncommercial 1.0.0](LICENSE.md), free to use,
change and share for anything non-commercial. The `grayjay/` module holds
code adapted from Grayjay and is under FUTO's
[Source First License 1.1](grayjay/LICENSE.md).
