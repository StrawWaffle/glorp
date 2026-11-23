# How Glorp's screens are put together

Notes from published guidance (researched 2026-11-23), and what they mean
for Glorp. Chat with the player above it is the app; everything else
should get out of its way and be easy to find.

## What the guidance says

- **Don't hide navigation behind one gesture or icon.** NN/g's study of
  hidden navigation found it cut discoverability almost in half and made
  people slower (15% on phones). Glorp's menu is reached only by Back, which
  is hidden navigation with no icon at all.
  ([NN/g](https://www.nngroup.com/articles/find-navigation-mobile-even-hamburger/))
- **Long press is an extra, never the only way.** Long-press menus are
  missed; Android's older guidelines advised against them for context
  menus, and Apple and SAP ask for a visible route to the same actions.
  ([NN/g contextual menus](https://www.nngroup.com/articles/contextual-menus-guidelines/),
  [Apple](https://developer.apple.com/design/human-interface-guidelines/context-menus))
- **Bottom sheets for a short list of actions on one thing.** A modal sheet
  with a visible scrim, opening no higher than half the screen, holding a
  list of actions, is Material's replacement for a menu on phones. Sheets
  are passing things, not places to stay.
  ([Material](https://material.io/components/sheets-bottom),
  [NN/g bottom sheets](https://www.nngroup.com/articles/bottom-sheet/))
- **Settings on their own screen, grouped.** Show the most used ones with
  their values first; group related ones under headings; move a group to a
  subscreen past about 15 items; more than 10–15 on one screen overwhelms.
  ([Android settings pattern](https://developer.android.com/design/ui/mobile/guides/patterns/settings),
  [AOSP settings guidelines](https://source.android.com/docs/core/settings/settings-guidelines))
- **Touch targets at least 48 dp**, 8 dp apart; the icon may be smaller than
  its target. ([Google](https://support.google.com/accessibility/android/answer/7101858))
- **A navigation bar is for 3–5 top-level places.** Glorp has one main place
  (chat and player) and a few side trips, so it gets a slim bar of icons
  instead of a bottom navigation bar, which would cost chat height.
  ([Android layout and navigation](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns))

## What that means for Glorp

1. **One bar between the player and chat**, always there (with the player
   hidden too): a menu button, what's playing (tap for the embeds list),
   whispers with an unread count, full screen and hide/show. Icons with
   48 dp targets instead of a row of small text buttons. Back still opens
   the menu.
2. **The menu is for going places**: account, whispers, the community
   screens, settings, about, and what's live. Settings move to their own
   screen, grouped (Chat, Highlights and tags, Player), each a switch or a
   value, the most used first.
3. **A name opens a sheet about that person**, by tap or long press, from
   chat, a pin or the log search: their name, flairs and tag, when they
   joined and what they're watching (from `/api/chat/userinfo`, as the
   site's user menu does), the tag note; then the actions as full-width
   rows: mention, whisper, recent messages, highlight, tag (the colours in
   a row), ignore, and mute or ban for moderators. Replaces the sideways
   strip above the input, where half the actions were off screen.
4. **A long press on a message** offers what fits a message: copy it, reply
   with a mention.
5. **Switches show their state**; labels say what the setting does
   ("Show timestamps"), not "Timestamps: on".
