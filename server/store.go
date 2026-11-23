package main

import (
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Message is one chat message as kept: who, what, and when (milliseconds).
type Message struct {
	Nick string `json:"nick"`
	Text string `json:"text"`
	At   int64  `json:"at"`
}

// Store keeps chat's messages for a while, oldest first, and finds the
// mentions of a name among them. Nothing else is kept: not who asked, not
// for whom.
type Store struct {
	mu       sync.RWMutex
	messages []Message
	keep     time.Duration
	seen     map[string]struct{}
}

func NewStore(keep time.Duration) *Store {
	return &Store{keep: keep, seen: map[string]struct{}{}}
}

// Add keeps a message unless it's already here: chat sends its backlog again
// on every reconnect.
func (s *Store) Add(m Message, now time.Time) {
	key := identity(m)
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, dup := s.seen[key]; dup {
		return
	}
	s.seen[key] = struct{}{}
	// Usually the newest; a backlog line can be older than what's kept.
	i := len(s.messages)
	for i > 0 && s.messages[i-1].At > m.At {
		i--
	}
	s.messages = append(s.messages, Message{})
	copy(s.messages[i+1:], s.messages[i:])
	s.messages[i] = m
	s.prune(now)
}

func identity(m Message) string {
	return strings.ToLower(m.Nick) + "\x00" + m.Text + "\x00" + strconv.FormatInt(m.At, 10)
}

// prune drops what's older than keep. Called with the lock held.
func (s *Store) prune(now time.Time) {
	cut := now.Add(-s.keep).UnixMilli()
	n := 0
	for n < len(s.messages) && s.messages[n].At < cut {
		delete(s.seen, identity(s.messages[n]))
		n++
	}
	if n > 0 {
		s.messages = append(s.messages[:0], s.messages[n:]...)
	}
}

// Mentions are the messages after since (milliseconds) that name nick as a
// word, as chat highlights them, by someone else; at most limit, the newest.
func (s *Store) Mentions(nick string, since int64, limit int) []Message {
	word := regexp.MustCompile(`(?i)(^|[^\w])` + regexp.QuoteMeta(nick) + `($|[^\w])`)
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []Message{}
	for i := len(s.messages) - 1; i >= 0 && len(out) < limit; i-- {
		m := s.messages[i]
		if m.At <= since {
			break
		}
		if strings.EqualFold(m.Nick, nick) || !word.MatchString(m.Text) {
			continue
		}
		out = append(out, m)
	}
	// Oldest first, as they were said.
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return out
}

// Span says how many messages are kept and since when (zero when none).
func (s *Store) Span() (int, int64) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if len(s.messages) == 0 {
		return 0, 0
	}
	return len(s.messages), s.messages[0].At
}
