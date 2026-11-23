# Glorp mentions service

One anonymous connection to destiny.gg chat, the last two hours of messages
in memory, and an endpoint saying who mentioned a name since a given time.
Glorp asks it in the background for mention notifications, so phones need
no chat connection and no site login for them. It stores nothing on disk and
keeps nothing about who asks.

    GET /mentions?nick=<name>&since=<ms>   {"mentions":[{"nick":"…","text":"…","at":1795400612944}]}
    GET /health                            {"connected":true,"messages":161,"since":1795404963119}

A mention is the name as a word (as chat highlights it) in someone else's
message; at most the newest 50 after `since` (default: the last hour). Each
caller may ask 60 times a minute. `/health` answers 503 while chat isn't
connected, which is also Docker's health check.

## Running it

    docker compose up -d --build

from this folder. It listens on `127.0.0.1:8087`; put HTTPS in front with a
reverse proxy (any path prefix is fine: the app is given the full base URL).
Settings, as environment variables: `KEEP` (how long chat is kept, default
`2h`), `LISTEN` (default `:8080` inside the container), `CHAT_URL`.

### With Komodo, updating itself from GitHub

A Stack from the git repo:

- Repo `StrawWaffle/glorp`, branch `main`, run directory `server`, file
  `compose.yaml`.
- Poll for updates (or a GitHub webhook) and redeploy when it changes; the
  compose file builds the image from this folder, so a new commit is a new
  build. Only changes under `server/` change the image.

## Testing

    docker run --rm -v "$PWD:/src" -w /src golang:1.27-alpine go test ./...

(the Dockerfile runs the tests on every build too).
