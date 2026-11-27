package main

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	inputTimeout = 15 * time.Second
	pingInterval = 5 * time.Second
	writeTimeout = time.Second
)

var tailnet = netip.MustParsePrefix("100.64.0.0/10")

type companion struct {
	driver    inputDriver
	tokenFile string
	idle      time.Duration

	mu       sync.Mutex
	token    string
	pairing  *pairing
	active   *inputSession
	stopping bool
}

func (c *companion) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/pair", c.pair)
	mux.HandleFunc("GET /v1/input", c.input)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		peer, parseErr := netip.ParseAddr(host)
		if err != nil || parseErr != nil || !tailnet.Contains(peer) {
			http.Error(w, "Tailscale IPv4 required", http.StatusForbidden)
			return
		}
		if _, present := r.Header["Origin"]; present {
			http.Error(w, "browser origin forbidden", http.StatusForbidden)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func (c *companion) pair(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Code string `json:"code"`
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1024))
	if err != nil || json.Unmarshal(body, &request) != nil {
		http.Error(w, "invalid pairing request", http.StatusBadRequest)
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	if c.stopping {
		http.Error(w, "companion stopping", http.StatusServiceUnavailable)
		return
	}
	p := c.pairing
	now := time.Now()
	if p == nil || !now.Before(p.expires) {
		http.Error(w, "invalid or expired pairing code", http.StatusUnauthorized)
		return
	}
	if elapsed := now.Sub(p.windowStart); elapsed >= pairWindow {
		p.windowStart = now
		p.failed = 0
	}
	if p.failed >= pairAttempts {
		retry := math.Ceil(time.Until(p.windowStart.Add(pairWindow)).Seconds())
		w.Header().Set("Retry-After", fmt.Sprintf("%.0f", max(1, retry)))
		http.Error(w, "too many pairing attempts", http.StatusTooManyRequests)
		return
	}
	if subtle.ConstantTimeCompare([]byte(p.code), []byte(strings.ToLower(request.Code))) != 1 {
		p.failed++
		http.Error(w, "invalid or expired pairing code", http.StatusUnauthorized)
		return
	}
	if c.active != nil {
		http.Error(w, "input client already connected", http.StatusConflict)
		return
	}

	token := randomHex(tokenBytes)
	if err := saveToken(c.tokenFile, token); err != nil {
		log.Printf("save token: %v", err)
		http.Error(w, "could not save credential", http.StatusInternalServerError)
		return
	}
	c.token = token
	c.pairing = nil
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(struct {
		Token string `json:"token"`
	}{Token: token})
}

func (c *companion) input(w http.ResponseWriter, r *http.Request) {
	session := c.upgrade(w, r)
	if session == nil {
		return
	}
	session.run(c.driver, c.idle)
	c.mu.Lock()
	c.active = nil
	close(session.done)
	c.mu.Unlock()
}

func (c *companion) upgrade(w http.ResponseWriter, r *http.Request) *inputSession {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.stopping {
		http.Error(w, "companion stopping", http.StatusServiceUnavailable)
		return nil
	}
	auth := r.Header.Values("Authorization")
	if len(auth) != 1 || !validBearer(auth[0], c.token) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		http.Error(w, "valid bearer token required", http.StatusUnauthorized)
		return nil
	}
	if c.active != nil {
		http.Error(w, "input client already connected", http.StatusConflict)
		return nil
	}
	upgrader := websocket.Upgrader{HandshakeTimeout: writeTimeout}
	conn, err := upgrader.Upgrade(w, r, http.Header{"X-Nullptr-Keyboard": {"1"}})
	if err != nil {
		return nil
	}
	c.active = &inputSession{conn: conn, done: make(chan struct{})}
	return c.active
}

func validBearer(header, token string) bool {
	scheme, credential, found := strings.Cut(header, " ")
	return token != "" && found && strings.EqualFold(scheme, "Bearer") &&
		subtle.ConstantTimeCompare([]byte(credential), []byte(token)) == 1
}

func (c *companion) stop() {
	c.mu.Lock()
	c.stopping = true
	session := c.active
	c.mu.Unlock()
	if session != nil {
		session.close(websocket.CloseGoingAway, "companion stopping")
		session.conn.Close()
		<-session.done
	}
}
