package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type testDriver chan string

func (d testDriver) Move(dx, dy int) {
	d <- fmt.Sprintf("move %d %d", dx, dy)
}

func (d testDriver) Scroll(dx, dy int) {
	d <- fmt.Sprintf("scroll %d %d", dx, dy)
}

func (d testDriver) Button(button mouseButton, down bool) {
	d <- fmt.Sprintf("%s %t", button, down)
}

func (d testDriver) Close() {}

func (d testDriver) expect(t *testing.T, want string) {
	t.Helper()
	select {
	case got := <-d:
		if got != want {
			t.Fatalf("pointer event = %q, want %q", got, want)
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("did not receive pointer event %q", want)
	}
}

const testToken = "existing-token"

func startTestServer(t *testing.T, pending *pairing, idle time.Duration, peer string) (*httptest.Server, *companion, testDriver) {
	t.Helper()
	driver := make(testDriver, 16)
	c := &companion{
		driver:    driver,
		tokenFile: filepath.Join(t.TempDir(), "nullptr", "token"),
		idle:      idle,
		token:     testToken,
		pairing:   pending,
	}
	handler := c.handler()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if peer != "" {
			r.RemoteAddr = peer
		}
		handler.ServeHTTP(w, r)
	}))
	t.Cleanup(server.Close)
	t.Cleanup(c.stop)
	return server, c, driver
}

func dialInput(t *testing.T, server *httptest.Server, header http.Header, want int) *websocket.Conn {
	t.Helper()
	dialer := websocket.Dialer{HandshakeTimeout: 2 * time.Second}
	conn, response, err := dialer.Dial("ws"+strings.TrimPrefix(server.URL, "http")+"/v1/input", header)
	if response == nil || response.StatusCode != want {
		t.Fatalf("upgrade status: got %v (%v), want %d", response, err, want)
	}
	if want != http.StatusSwitchingProtocols {
		response.Body.Close()
		return nil
	}
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func bearer(token string) http.Header {
	return http.Header{"Authorization": []string{"Bearer " + token}}
}

func sendRecord(t *testing.T, conn *websocket.Conn, record string) {
	t.Helper()
	if err := conn.WriteMessage(websocket.TextMessage, []byte(record)); err != nil {
		t.Fatal(err)
	}
}

func postPair(t *testing.T, server *httptest.Server, code string, want int) string {
	t.Helper()
	body := strings.NewReader(fmt.Sprintf(`{"code":%q}`, code))
	response, err := server.Client().Post(server.URL+"/v1/pair", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != want {
		t.Fatalf("pair status = %d (%s), want %d", response.StatusCode, data, want)
	}
	if want != http.StatusOK {
		return ""
	}
	var result struct{ Token string }
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatal(err)
	}
	if result.Token == "" || result.Token == testToken {
		t.Fatal("pair did not issue a new token")
	}
	return result.Token
}

func TestInputRequiresTailnetBearerAndNoOrigin(t *testing.T) {
	public, _, _ := startTestServer(t, nil, inputTimeout, "")
	headers := bearer(testToken)
	headers.Set("X-Forwarded-For", "100.65.0.1")
	dialInput(t, public, headers, http.StatusForbidden)

	server, _, _ := startTestServer(t, nil, inputTimeout, "100.65.0.1:6123")
	dialInput(t, server, nil, http.StatusUnauthorized)
	dialInput(t, server, bearer("incorrect"), http.StatusUnauthorized)
	headers.Set("Origin", "")
	dialInput(t, server, headers, http.StatusForbidden)
	dialInput(t, server, bearer(testToken), http.StatusSwitchingProtocols)
	dialInput(t, server, bearer(testToken), http.StatusConflict)
}

func TestPairRefusesWhileInputActive(t *testing.T) {
	pending := newPairing()
	server, _, driver := startTestServer(t, pending, inputTimeout, "100.65.0.1:6123")
	conn := dialInput(t, server, bearer(testToken), http.StatusSwitchingProtocols)
	sendRecord(t, conn, `{"type":"button","button":"left","down":true}`)
	postPair(t, server, pending.code, http.StatusConflict)
	sendRecord(t, conn, `{"type":"button","button":"left","down":false}`)
	driver.expect(t, "left true")
	driver.expect(t, "left false")
}

func TestPairRotatesCredentialOnce(t *testing.T) {
	pending := newPairing()
	server, c, _ := startTestServer(t, pending, inputTimeout, "100.65.0.1:6123")
	token := postPair(t, server, strings.ToUpper(pending.code), http.StatusOK)
	postPair(t, server, pending.code, http.StatusUnauthorized)
	dialInput(t, server, bearer(testToken), http.StatusUnauthorized)
	dialInput(t, server, bearer(token), http.StatusSwitchingProtocols)
	stored, err := os.ReadFile(c.tokenFile)
	if err != nil || string(stored) != token+"\n" {
		t.Fatalf("credential file did not preserve token: %v", err)
	}
	info, err := os.Stat(c.tokenFile)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("token permissions = %o, want 0600", info.Mode().Perm())
	}
}

func TestPairRejectsExpiredAndRateLimitsFailures(t *testing.T) {
	expired := newPairing()
	expired.expires = time.Now().Add(-time.Second)
	server, _, _ := startTestServer(t, expired, inputTimeout, "100.65.0.1:6123")
	postPair(t, server, expired.code, http.StatusUnauthorized)

	pending := newPairing()
	server, _, _ = startTestServer(t, pending, inputTimeout, "100.65.0.1:6123")
	for range 5 {
		postPair(t, server, "wrong", http.StatusUnauthorized)
	}
	postPair(t, server, pending.code, http.StatusTooManyRequests)
}

func TestInputReleasesButtons(t *testing.T) {
	for _, ending := range []string{"disconnect", "timeout", "shutdown", "invalid record"} {
		t.Run(ending, func(t *testing.T) {
			idle := inputTimeout
			if ending == "timeout" {
				idle = 250 * time.Millisecond
			}
			server, c, driver := startTestServer(t, nil, idle, "100.65.0.1:6123")
			conn := dialInput(t, server, bearer(testToken), http.StatusSwitchingProtocols)
			sendRecord(t, conn, `{"type":"move","dx":3,"dy":-2}`)
			sendRecord(t, conn, `{"type":"scroll","dx":1,"dy":2}`)
			sendRecord(t, conn, `{"type":"button","button":"left","down":true}`)
			sendRecord(t, conn, `{"type":"button","button":"left","down":true}`)
			sendRecord(t, conn, `{"type":"button","button":"right","down":true}`)
			sendRecord(t, conn, `{"type":"button","button":"left","down":false}`)
			driver.expect(t, "move 3 -2")
			driver.expect(t, "scroll 1 2")
			driver.expect(t, "left true")
			driver.expect(t, "right true")
			driver.expect(t, "left false")

			switch ending {
			case "disconnect":
				conn.Close()
			case "shutdown":
				c.stop()
				_, _, err := conn.ReadMessage()
				if !websocket.IsCloseError(err, websocket.CloseGoingAway) {
					t.Fatalf("shutdown close: %v", err)
				}
			case "invalid record":
				sendRecord(t, conn, `{"type":"button","button":"right","down":null}`)
				_, _, err := conn.ReadMessage()
				if !websocket.IsCloseError(err, websocket.ClosePolicyViolation) {
					t.Fatalf("invalid record close: %v", err)
				}
			}
			driver.expect(t, "right false")
		})
	}
}

func TestInputAnswersProtocolPing(t *testing.T) {
	server, _, _ := startTestServer(t, nil, inputTimeout, "100.65.0.1:6123")
	conn := dialInput(t, server, bearer(testToken), http.StatusSwitchingProtocols)
	received := make(chan string, 1)
	conn.SetPongHandler(func(payload string) error {
		received <- payload
		return nil
	})
	done := make(chan struct{})
	go func() {
		defer close(done)
		conn.ReadMessage()
	}()
	defer func() {
		conn.Close()
		<-done
	}()
	if err := conn.WriteControl(websocket.PingMessage, []byte("round trip"), time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	select {
	case payload := <-received:
		if payload != "round trip" {
			t.Fatalf("pong payload = %q", payload)
		}
	case <-time.After(time.Second):
		t.Fatal("no pong")
	}
}
