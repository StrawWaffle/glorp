package main

import (
	"context"
	"encoding/json"
	"html"
	"io"
	"log"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Movie-night votes on dinkdonk.mov (by Mitch). Each poll and each rating
// opens with a chat broadcast whose text is the link
// (https://dinkdonk.mov/poll/<id> or /rate/<id>), and stays open about three
// to five minutes: too short for a phone's background check. This keeps the
// recent ones and tells connected phones the moment one opens.
//
//	GET /votes       →  {"open":[vote...],"nightUntil":ms}
//	GET /votes/live  →  websocket: {"type":"state",...} on connecting, then {"type":"vote","vote":{...}}
//
// A vote is {"kind":"poll"|"rate","id","url","title","at"}. "nightUntil" is
// when the movie night counts as over: three hours after its last vote, or 0.
// Nothing about who listens is kept.

const (
	dinkdonk = "https://dinkdonk.mov"
	// Polls close after about three minutes and ratings after five; ten is a margin.
	voteOpenFor = 10 * time.Minute
	// A movie night is on while votes keep coming: films and episodes run up to a few hours.
	nightLasts = 3 * time.Hour
	// Listening phones are pinged this often so Cloudflare and NATs keep the socket open.
	livePing    = 30 * time.Second
	maxListener = 2000
	perCaller   = 4
)

var voteLink = regexp.MustCompile(`https://(?:www\.)?dinkdonk\.mov/(poll|rate)/([A-Za-z0-9]{3,12})\b`)

type Vote struct {
	Kind  string `json:"kind"`
	ID    string `json:"id"`
	URL   string `json:"url"`
	Title string `json:"title,omitempty"`
	At    int64  `json:"at"`
}

// voteIn reads a broadcast: a dinkdonk poll or rating link, or nothing.
func voteIn(text string, at int64) (Vote, bool) {
	m := voteLink.FindStringSubmatch(text)
	if m == nil {
		return Vote{}, false
	}
	return Vote{Kind: m[1], ID: m[2], URL: dinkdonk + "/" + m[1] + "/" + m[2], At: at}, true
}

type Votes struct {
	mu        sync.Mutex
	recent    []Vote
	lastAt    int64
	listeners map[chan Vote]string
	perIP     map[string]int
	// Looks up a vote's title (the poll's question, the rated film); empty if it can't.
	title func(Vote) string
}

func NewVotes(title func(Vote) string) *Votes {
	return &Votes{listeners: map[chan Vote]string{}, perIP: map[string]int{}, title: title}
}

// Add takes a vote from a broadcast. Chat repeats its backlog on every
// reconnect, so one already here is ignored, and an old one (from the
// backlog of a restart) only counts towards the night.
func (v *Votes) Add(vote Vote, now time.Time) {
	v.mu.Lock()
	for _, r := range v.recent {
		if r.ID == vote.ID && r.Kind == vote.Kind {
			v.mu.Unlock()
			return
		}
	}
	v.lastAt = max(v.lastAt, vote.At)
	fresh := now.UnixMilli()-vote.At < voteOpenFor.Milliseconds()
	v.mu.Unlock()
	if !fresh {
		return
	}
	// The title costs one request to dinkdonk.mov, once per vote, not per phone.
	vote.Title = v.title(vote)
	v.mu.Lock()
	defer v.mu.Unlock()
	v.prune(now)
	v.recent = append(v.recent, vote)
	for ch := range v.listeners {
		select {
		case ch <- vote:
		default: // a listener that isn't reading misses it rather than holding up the rest
		}
	}
	log.Printf("votes: %s %s open, told %d listening", vote.Kind, vote.ID, len(v.listeners))
}

func (v *Votes) prune(now time.Time) {
	cut := now.Add(-voteOpenFor).UnixMilli()
	n := 0
	for n < len(v.recent) && v.recent[n].At < cut {
		n++
	}
	v.recent = append(v.recent[:0], v.recent[n:]...)
}

// State is what's open now and until when the night counts as on.
func (v *Votes) State(now time.Time) map[string]any {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.prune(now)
	open := append([]Vote{}, v.recent...)
	until := int64(0)
	if v.lastAt > 0 && now.UnixMilli() < v.lastAt+nightLasts.Milliseconds() {
		until = v.lastAt + nightLasts.Milliseconds()
	}
	return map[string]any{"open": open, "nightUntil": until}
}

func (v *Votes) listen(ip string) (chan Vote, bool) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if len(v.listeners) >= maxListener || v.perIP[ip] >= perCaller {
		return nil, false
	}
	ch := make(chan Vote, 4)
	v.listeners[ch] = ip
	v.perIP[ip]++
	return ch, true
}

func (v *Votes) leave(ch chan Vote) {
	v.mu.Lock()
	defer v.mu.Unlock()
	ip := v.listeners[ch]
	delete(v.listeners, ch)
	if v.perIP[ip]--; v.perIP[ip] <= 0 {
		delete(v.perIP, ip)
	}
}

// serveLive holds one phone's quiet socket: the state on connecting, each
// vote as it opens, and a ping every half minute. The phone sends nothing.
func (v *Votes) serveLive(w http.ResponseWriter, r *http.Request) {
	ch, ok := v.listen(clientIP(r))
	if !ok {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "too many listening"})
		return
	}
	defer v.leave(ch)
	// The server's write timeout is for plain requests, not a socket held for hours.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	ctx := conn.CloseRead(r.Context())
	send := func(body any) error {
		data, _ := json.Marshal(body)
		wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		return conn.Write(wctx, websocket.MessageText, data)
	}
	state := v.State(time.Now())
	state["type"] = "state"
	if send(state) != nil {
		return
	}
	tick := time.NewTicker(livePing)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case vote := <-ch:
			if send(map[string]any{"type": "vote", "vote": vote}) != nil {
				return
			}
		case <-tick.C:
			pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := conn.Ping(pctx)
			cancel()
			if err != nil {
				return
			}
		}
	}
}

var (
	heading  = regexp.MustCompile(`class="section-header__heading">\s*([^<]+)`)
	filmLink = regexp.MustCompile(`<a class="link"[^>]*>\s*([^<]+)`)
)

// dinkdonkTitle reads a vote's results page: a poll's question, or the film
// or episode a rating is for.
func dinkdonkTitle(client *http.Client) func(Vote) string {
	return func(vote Vote) string {
		req, _ := http.NewRequest(http.MethodGet, vote.URL+"/results", nil)
		req.Header.Set("User-Agent", UserAgent)
		resp, err := client.Do(req)
		if err != nil {
			return ""
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return ""
		}
		page, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		re := heading
		if vote.Kind == "rate" {
			re = filmLink
		}
		if m := re.FindSubmatch(page); m != nil {
			return strings.TrimSpace(html.UnescapeString(string(m[1])))
		}
		return ""
	}
}
