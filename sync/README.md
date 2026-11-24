# Glorp sync

Keeps Glorp's own settings (theme, name icons, hidden phrases and the rest
that destiny.gg has no place for) the same across the app and the
userscript.

## How it works

A sync code is 16 random bytes, made on the first device and shown there as
a QR code and as text (`glorp-sync:1:<32 hex digits>`). Every device with
the code derives the same two things from it:

- an ID, the first 32 hex digits of SHA-256 of `glorp-sync-id:` and the
  code's bytes, which is the address the settings are kept under;
- an AES-256 key, SHA-256 of `glorp-sync-key:` and the code's bytes.

The settings go up as JSON encrypted with AES-GCM under that key, with a
fresh 12-byte IV each time (`base64(iv || ciphertext)`). The server keeps
that blob and nothing else, so it can't read the settings, and it never
sees the code. Each setting carries the time it was last changed; a device
fetches, keeps the newer of each setting, and sends the result back.

## Running it

It's a Netlify site: one function (`netlify/functions/sync.mjs`) and
Netlify Blobs. Create a Netlify site from this repository with `sync` as the
base directory. The app and the userscript use
`https://glorp-sync.netlify.app` unless told otherwise.
