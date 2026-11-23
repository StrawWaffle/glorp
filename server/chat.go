package main

import (
	"context"
	"encoding/json"
	"log"
	"math/rand/v2"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

// UserAgent names this service to destiny.gg, so whoever runs its servers can
// see what is connecting and where to find it.
const UserAgent = "Glorp mentions (+https://github.com/StrawWaffle/glorp)"

const (
	// One that stays up this long resets the backoff: the server completes
	// the handshake before it can reject a client.
	stableAfter = 5 * time.Second
	// Chat pings every 10 s; half a minute of nothing means it's gone.
	silenceLimit     = 30 * time.Second
	handshakeTimeout = 10 * time.Second
	// The user list on connect is a few hundred kilobytes at busy times.
	readLimit = 8 << 20
)

// Chat holds one anonymous connection to destiny.gg chat and hands every
// message to OnMessage. An anonymous connection gets `ME null`, the backlog
// (HISTORY), the user list (NAMES), then the live stream.
type Chat struct {
	URL       string
	OnMessage func(Message)
	connected atomic.Bool
}

func (c *Chat) Connected() bool { return c.connected.Load() }

// Run connects and reconnects, with chat-gui's backoff, until ctx ends.
func (c *Chat) Run(ctx context.Context) {
	attempts := 0
	for ctx.Err() == nil {
		attempts++
		openedAt := c.connectOnce(ctx)
		if !openedAt.IsZero() && time.Since(openedAt) >= stableAfter {
			attempts = 0
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(retryDelay(attempts, rand.Float64)):
		}
	}
}

// retryDelay is chat-gui's backoff: full jitter over a window that doubles
// with each failed attempt, from 3 s up to a minute.
func retryDelay(attempts int, random func() float64) time.Duration {
	const window, windowCap, least = 3 * time.Second, 60 * time.Second, 500 * time.Millisecond
	w := windowCap
	if attempts < 6 {
		w = min(windowCap, window*time.Duration(1<<attempts))
	}
	return least + time.Duration(random()*float64(w-least))
}

func (c *Chat) connectOnce(ctx context.Context) time.Time {
	var last atomic.Int64
	touch := func() { last.Store(time.Now().UnixNano()) }
	dialCtx, cancelDial := context.WithTimeout(ctx, handshakeTimeout)
	// No Origin header: destiny.gg refuses other sites' origins and takes none.
	conn, _, err := websocket.Dial(dialCtx, c.URL, &websocket.DialOptions{
		HTTPHeader:     http.Header{"User-Agent": []string{UserAgent}},
		OnPingReceived: func(context.Context, []byte) bool { touch(); return true },
		OnPongReceived: func(context.Context, []byte) { touch() },
	})
	cancelDial()
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("chat: %v", err)
		}
		return time.Time{}
	}
	conn.SetReadLimit(readLimit)
	opened := time.Now()
	touch()
	c.connected.Store(true)
	log.Printf("chat: connected")
	defer func() {
		c.connected.Store(false)
		_ = conn.CloseNow()
	}()

	connCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		tick := time.NewTicker(5 * time.Second)
		defer tick.Stop()
		for {
			select {
			case <-connCtx.Done():
				return
			case <-tick.C:
				if time.Since(time.Unix(0, last.Load())) >= silenceLimit {
					log.Printf("chat: went quiet, reconnecting")
					_ = conn.CloseNow()
					return
				}
			}
		}
	}()
	for {
		kind, data, err := conn.Read(connCtx)
		if err != nil {
			if ctx.Err() == nil {
				log.Printf("chat: %v", err)
			}
			return opened
		}
		touch()
		if kind == websocket.MessageText {
			for _, m := range messagesIn(string(data)) {
				c.OnMessage(m)
			}
		}
	}
}

type wireMessage struct {
	Nick      string `json:"nick"`
	Data      string `json:"data"`
	Timestamp int64  `json:"timestamp"`
}

// messagesIn reads the chat messages in one frame: a MSG, or the MSGs of a
// HISTORY backlog. Anything else gives none.
func messagesIn(raw string) []Message {
	event, payload, _ := strings.Cut(raw, " ")
	switch strings.ToUpper(event) {
	case "MSG":
		var w wireMessage
		if json.Unmarshal([]byte(payload), &w) != nil || w.Nick == "" || w.Timestamp == 0 {
			return nil
		}
		return []Message{{Nick: w.Nick, Text: w.Data, At: w.Timestamp}}
	case "HISTORY":
		var lines []string
		if json.Unmarshal([]byte(payload), &lines) != nil {
			return nil
		}
		var out []Message
		for _, line := range lines {
			out = append(out, messagesIn(line)...)
		}
		return out
	}
	return nil
}
