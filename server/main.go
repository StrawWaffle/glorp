// Glorp's mentions service: one anonymous connection to destiny.gg chat,
// the last couple of hours of messages in memory, and an endpoint that says
// who mentioned a name since a given time. The app asks it in the background
// for its mention notifications, so phones need neither a chat connection
// nor the site's login for them.
//
//	GET /mentions?nick=<name>&since=<ms>  →  {"mentions":[{"nick","text","at"}...]}
//	GET /health                           →  {"connected":true,"messages":n,"since":ms}
//
// It keeps nothing about who asks.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"strconv"
	"sync"
	"syscall"
	"time"
)

func main() {
	listen := flag.String("listen", env("LISTEN", ":8080"), "address to serve on")
	keep := flag.Duration("keep", envDuration("KEEP", 2*time.Hour), "how long messages are kept")
	chatURL := flag.String("chat", env("CHAT_URL", "wss://chat.destiny.gg/ws"), "destiny.gg chat socket")
	check := flag.Bool("check", false, "ask a running server's /health and exit 0 if chat is connected (for Docker's health check)")
	flag.Parse()
	if *check {
		os.Exit(healthCheck(*listen))
	}

	store := NewStore(*keep)
	chat := &Chat{URL: *chatURL, OnMessage: func(m Message) { store.Add(m, time.Now()) }}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go chat.Run(ctx)

	server := &http.Server{
		Addr:              *listen,
		Handler:           routes(store, chat, newLimiter(60, time.Minute)),
		ReadHeaderTimeout: 5 * time.Second,
		WriteTimeout:      10 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	log.Printf("serving on %s, keeping %s of chat", *listen, *keep)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

var nickShape = regexp.MustCompile(`^\w{3,20}$`)

const maxMentions = 50

func routes(store *Store, chat *Chat, limit *limiter) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /mentions", func(w http.ResponseWriter, r *http.Request) {
		if !limit.allow(clientIP(r)) {
			writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "slow down"})
			return
		}
		nick := r.URL.Query().Get("nick")
		if !nickShape.MatchString(nick) {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "nick: 3 to 20 letters, digits or _"})
			return
		}
		since, err := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
		if err != nil {
			since = time.Now().Add(-time.Hour).UnixMilli()
		}
		writeJSON(w, http.StatusOK, map[string]any{"mentions": store.Mentions(nick, since, maxMentions)})
	})
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		count, since := store.Span()
		status := http.StatusOK
		if !chat.Connected() {
			status = http.StatusServiceUnavailable
		}
		writeJSON(w, status, map[string]any{"connected": chat.Connected(), "messages": count, "since": since})
	})
	return mux
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// clientIP is the caller, behind a reverse proxy if there is one. Used only
// to slow down one caller asking too often; never stored or logged.
func clientIP(r *http.Request) string {
	if ip := r.Header.Get("X-Real-IP"); ip != "" {
		return ip
	}
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		for i := 0; i < len(fwd); i++ {
			if fwd[i] == ',' {
				return fwd[:i]
			}
		}
		return fwd
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// limiter allows each caller so many requests per window, in memory only.
type limiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	counts map[string]int
	reset  time.Time
}

func newLimiter(max int, window time.Duration) *limiter {
	return &limiter{max: max, window: window, counts: map[string]int{}, reset: time.Now().Add(window)}
}

func (l *limiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if now := time.Now(); now.After(l.reset) {
		clear(l.counts)
		l.reset = now.Add(l.window)
	}
	l.counts[key]++
	return l.counts[key] <= l.max
}

func healthCheck(listen string) int {
	_, port, err := net.SplitHostPort(listen)
	if err != nil {
		return 1
	}
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/health")
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}

func env(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func envDuration(name string, fallback time.Duration) time.Duration {
	if d, err := time.ParseDuration(os.Getenv(name)); err == nil && d > 0 {
		return d
	}
	return fallback
}
