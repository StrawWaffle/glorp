package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestMessagesInFrames(t *testing.T) {
	msg := `MSG {"nick":"Friend","features":[],"timestamp":1795400612944,"data":"hey Glorp"}`
	got := messagesIn(msg)
	if len(got) != 1 || got[0] != (Message{Nick: "Friend", Text: "hey Glorp", At: 1795400612944}) {
		t.Fatalf("MSG: %+v", got)
	}
	history, _ := json.Marshal([]string{msg, `MSG {"nick":"Other","timestamp":1795400613000,"data":"x"}`, `JOIN {"nick":"x"}`})
	if got := messagesIn("HISTORY " + string(history)); len(got) != 2 {
		t.Fatalf("HISTORY: %+v", got)
	}
	for _, raw := range []string{"NAMES {}", "MSG not json", `MSG {"data":"no nick"}`, ""} {
		if got := messagesIn(raw); len(got) != 0 {
			t.Fatalf("%q gave %+v", raw, got)
		}
	}
}

func TestMentionsAreWordsBySomeoneElse(t *testing.T) {
	now := time.UnixMilli(1_000_000_000)
	s := NewStore(2 * time.Hour)
	add := func(nick, text string, at int64) { s.Add(Message{Nick: nick, Text: text, At: at}, now) }
	add("Friend", "hey glorp look", 999_000_000)
	add("Friend", "glorpy things", 999_100_000)
	add("Glorp", "me, Glorp", 999_200_000)
	add("Pal", "@Glorp you there?", 999_300_000)
	add("Pal", "@Glorp you there?", 999_300_000) // the backlog again on reconnect
	got := s.Mentions("glorp", 0, 50)
	if len(got) != 2 || got[0].Nick != "Friend" || got[1].Nick != "Pal" {
		t.Fatalf("mentions: %+v", got)
	}
	if got := s.Mentions("glorp", 999_000_000, 50); len(got) != 1 || got[0].Nick != "Pal" {
		t.Fatalf("since: %+v", got)
	}
	if got := s.Mentions("glorp", 0, 1); len(got) != 1 || got[0].Nick != "Pal" {
		t.Fatalf("limit keeps the newest: %+v", got)
	}
}

func TestOldMessagesGo(t *testing.T) {
	s := NewStore(time.Hour)
	start := time.UnixMilli(10 * 3_600_000)
	s.Add(Message{Nick: "a", Text: "glorp", At: start.UnixMilli()}, start)
	later := start.Add(2 * time.Hour)
	s.Add(Message{Nick: "b", Text: "glorp", At: later.UnixMilli()}, later)
	if n, since := s.Span(); n != 1 || since != later.UnixMilli() {
		t.Fatalf("kept %d since %d", n, since)
	}
	// A backlog line older than the newest still goes in its place.
	s.Add(Message{Nick: "c", Text: "glorp", At: later.UnixMilli() - 1000}, later)
	if got := s.Mentions("glorp", 0, 50); len(got) != 2 || got[0].Nick != "c" {
		t.Fatalf("order: %+v", got)
	}
}

func TestEndpoints(t *testing.T) {
	s := NewStore(time.Hour)
	now := time.Now()
	s.Add(Message{Nick: "Friend", Text: "hi Glorp", At: now.UnixMilli()}, now)
	chat := &Chat{}
	h := routes(s, chat, NewVotes(func(Vote) string { return "" }), newLimiter(2, time.Minute))

	get := func(path string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
		return w
	}
	w := get("/mentions?nick=Glorp&since=0")
	var body struct{ Mentions []Message }
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &body) != nil || len(body.Mentions) != 1 {
		t.Fatalf("mentions: %d %s", w.Code, w.Body)
	}
	if w := get("/mentions?nick=bad!name"); w.Code != 400 {
		t.Fatalf("bad nick: %d", w.Code)
	}
	if w := get("/mentions?nick=Glorp"); w.Code != 429 {
		t.Fatalf("limit: %d", w.Code)
	}
	if w := get("/health"); w.Code != 503 {
		t.Fatalf("health while not connected: %d", w.Code)
	}
}

func TestRetryDelayStaysInItsWindow(t *testing.T) {
	if d := retryDelay(1, func() float64 { return 0 }); d != 500*time.Millisecond {
		t.Fatal(d)
	}
	if d := retryDelay(10, func() float64 { return 1 }); d != 60*time.Second {
		t.Fatal(d)
	}
}
