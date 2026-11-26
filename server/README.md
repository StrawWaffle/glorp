# Glorp mentions service

One anonymous connection to destiny.gg chat, the last two hours of messages
in memory, and an endpoint saying who mentioned a name since a given time.
Glorp asks it in the background for mention notifications, so phones need
no chat connection and no site login for them. It stores nothing on disk and
keeps nothing about who asks.

    GET /mentions?nick=<name>&since=<ms>   {"mentions":[{"nick":"…","text":"…","at":1795400612944}]}
    GET /health                            {"connected":true,"messages":161,"since":1795404963119}

It also watches chat's broadcasts for dinkdonk.mov's movie-night votes
(every poll and rating opens with a broadcast that is just its link, and
closes after three to five minutes) and tells phones at once:

    GET /votes        {"open":[{"kind":"poll","id":"…","url":"…","title":"…","at":ms}],"nightUntil":ms}
    GET /votes/live   websocket: {"type":"state",…} on connecting, then {"type":"vote","vote":{…}}

`nightUntil` is three hours after the last vote (0 when no night is on).
The live socket carries nothing else, pinged every 30 s; Glorp holds it only
during a movie night. Each vote's title (the poll's question, the rated film)
is read once from its results page on dinkdonk.mov. At most 4 sockets per
caller and 2000 in all; nothing about who listens is kept.

A mention is the name as a word (as chat highlights it) in someone else's
message; at most the newest 50 after `since` (default: the last hour). Each
caller may ask 60 times a minute. `/health` answers 503 while chat isn't
connected, which is also Docker's health check.

## Running it

From the repo root (`compose.yaml` there builds this folder):

    docker compose up -d --build

- **Port:** host port `8087` (set `GLORP_MENTIONS_PORT` to change it), plain
  HTTP to the container's 8080, serving `/mentions` and `/health`. It needs
  HTTPS in front: the app only talks to it over HTTPS.
- **Settings:** environment variables, all optional; see `.env.example` at the
  repo root (`GLORP_MENTIONS_PORT`, `KEEP`, `CHAT_URL`). No secrets.
- **Data:** none kept; no volumes. A restart starts with chat's backlog and
  fills up again.
- **Nothing to do by hand.** It shuts down cleanly on SIGTERM.
- **Resources:** about 11 MB of memory (limited to 128 MB); the build is a
  small Go program on `golang:1.27-alpine`, and the image is distroless.

A deploy that rebuilds on every push to `main` rebuilds this too; Docker's
cache keeps that cheap when `server/` hasn't changed.

## Testing

    docker run --rm -v "$PWD:/src" -w /src golang:1.27-alpine go test ./...

(the Dockerfile runs the tests on every build too).
