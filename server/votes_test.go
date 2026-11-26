package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestVoteFromBroadcast(t *testing.T) {
	got := broadcastsIn(`BROADCAST {"data":"https://dinkdonk.mov/poll/90Ypt","timestamp":1795568261000}`)
	if len(got) != 1 {
		t.Fatalf("broadcasts: %v", got)
	}
	vote, ok := voteIn(got[0].Text, got[0].At)
	if !ok || vote.Kind != "poll" || vote.ID != "90Ypt" || vote.URL != "https://dinkdonk.mov/poll/90Ypt" {
		t.Fatalf("vote: %+v %v", vote, ok)
	}
	if _, ok := voteIn("Check out the movie schedule https://dinkdonk.mov/schedule", 1); ok {
		t.Fatal("the schedule isn't a vote")
	}
	history := `HISTORY ["MSG {\"nick\":\"a\",\"data\":\"hi\",\"timestamp\":1}","BROADCAST {\"data\":\"https://dinkdonk.mov/rate/wSQyO\",\"timestamp\":2}"]`
	if b := broadcastsIn(history); len(b) != 1 || b[0].At != 2 {
		t.Fatalf("history: %v", b)
	}
	if m := messagesIn(`BROADCAST {"data":"x","timestamp":3}`); len(m) != 0 {
		t.Fatal("a broadcast isn't a chat message")
	}
}

func TestVotesStateAndNight(t *testing.T) {
	now := time.UnixMilli(10_000_000)
	v := NewVotes(func(Vote) string { return "SEEN MOVIES ALLOWED" })
	v.Add(Vote{Kind: "poll", ID: "a", At: now.UnixMilli() - 60_000}, now)
	v.Add(Vote{Kind: "poll", ID: "a", At: now.UnixMilli() - 60_000}, now) // the backlog again
	// An old one from a restart's backlog: no notification, but the night is on.
	v.Add(Vote{Kind: "rate", ID: "old", At: now.UnixMilli() - int64(time.Hour/time.Millisecond)}, now)
	state := v.State(now)
	open := state["open"].([]Vote)
	if len(open) != 1 || open[0].Title != "SEEN MOVIES ALLOWED" {
		t.Fatalf("open: %+v", open)
	}
	if until := state["nightUntil"].(int64); until != now.UnixMilli()-60_000+nightLasts.Milliseconds() {
		t.Fatalf("nightUntil: %d", until)
	}
	later := now.Add(4 * time.Hour)
	if s := v.State(later); len(s["open"].([]Vote)) != 0 || s["nightUntil"].(int64) != 0 {
		t.Fatalf("later: %+v", s)
	}
}

func TestLiveSocketTellsOfNewVotes(t *testing.T) {
	v := NewVotes(func(Vote) string { return "Pilot" })
	srv := httptest.NewServer(routes(NewStore(time.Hour), &Chat{}, v, newLimiter(60, time.Minute)))
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/votes/live", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	read := func() map[string]any {
		_, data, err := conn.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		_ = json.Unmarshal(data, &m)
		return m
	}
	if m := read(); m["type"] != "state" {
		t.Fatalf("first: %v", m)
	}
	// Wait until the server has the listener, then open a vote.
	for i := 0; i < 50 && func() bool { v.mu.Lock(); defer v.mu.Unlock(); return len(v.listeners) == 0 }(); i++ {
		time.Sleep(10 * time.Millisecond)
	}
	v.Add(Vote{Kind: "rate", ID: "wSQyO", URL: "https://dinkdonk.mov/rate/wSQyO", At: time.Now().UnixMilli()}, time.Now())
	m := read()
	vote, _ := m["vote"].(map[string]any)
	if m["type"] != "vote" || vote["id"] != "wSQyO" || vote["title"] != "Pilot" {
		t.Fatalf("vote: %v", m)
	}
}
