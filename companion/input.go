package main

import (
	"encoding/json"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/gorilla/websocket"
)

type mouseButton string

const (
	leftButton  mouseButton = "left"
	rightButton mouseButton = "right"
)

type keyboardKey string

const (
	backspaceKey keyboardKey = "backspace"
	enterKey     keyboardKey = "enter"
	tabKey       keyboardKey = "tab"
	escapeKey    keyboardKey = "escape"
	leftKey      keyboardKey = "left"
	rightKey     keyboardKey = "right"
	upKey        keyboardKey = "up"
	downKey      keyboardKey = "down"
)

func (key keyboardKey) valid() bool {
	switch key {
	case backspaceKey, enterKey, tabKey, escapeKey, leftKey, rightKey, upKey, downKey:
		return true
	default:
		return false
	}
}

type keyboardIssue string

const (
	keyboardUnavailable keyboardIssue = "unavailable"
	modifiersActive     keyboardIssue = "modifiersActive"
	unsupportedLayout   keyboardIssue = "unsupportedLayout"
)

type inputDriver interface {
	Move(dx, dy int)
	Scroll(dx, dy int)
	Button(button mouseButton, down bool)
	TypeText(text string) keyboardIssue
	TapKey(key keyboardKey) keyboardIssue
	Close()
}

type inputRecord struct {
	Type   string      `json:"type"`
	DX     *int        `json:"dx"`
	DY     *int        `json:"dy"`
	Button mouseButton `json:"button"`
	Down   *bool       `json:"down"`
	Text   string      `json:"text"`
	Key    keyboardKey `json:"key"`
}

type inputSession struct {
	conn *websocket.Conn
	done chan struct{}
}

func (s *inputSession) run(driver inputDriver, idle time.Duration) {
	held := make(map[mouseButton]bool)
	defer func() {
		for button := range held {
			driver.Button(button, false)
		}
		s.conn.Close()
	}()

	s.conn.SetReadLimit(1024)
	refreshDeadline := func() error {
		return s.conn.SetReadDeadline(time.Now().Add(idle))
	}
	if refreshDeadline() != nil {
		return
	}
	s.conn.SetPongHandler(func(string) error { return refreshDeadline() })
	pong := s.conn.PingHandler()
	s.conn.SetPingHandler(func(message string) error {
		if err := refreshDeadline(); err != nil {
			return err
		}
		return pong(message)
	})
	stopPings := make(chan struct{})
	defer close(stopPings)
	go s.ping(stopPings)

	for {
		kind, data, err := s.conn.ReadMessage()
		if err != nil {
			return
		}
		if kind != websocket.TextMessage {
			s.close(websocket.CloseUnsupportedData, "expected JSON text")
			return
		}
		var record inputRecord
		if json.Unmarshal(data, &record) != nil {
			s.close(websocket.ClosePolicyViolation, "invalid input record")
			return
		}
		issue, valid := record.apply(driver, held)
		if !valid {
			s.close(websocket.ClosePolicyViolation, "invalid input record")
			return
		}
		if issue != "" {
			notice := struct {
				Type   string        `json:"type"`
				Reason keyboardIssue `json:"reason"`
			}{Type: "keyboardIssue", Reason: issue}
			if s.conn.SetWriteDeadline(time.Now().Add(writeTimeout)) != nil || s.conn.WriteJSON(notice) != nil {
				return
			}
		}
		if refreshDeadline() != nil {
			return
		}
	}
}

func (s *inputSession) ping(stop <-chan struct{}) {
	ticker := time.NewTicker(pingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			if err := s.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(writeTimeout)); err != nil {
				s.conn.Close()
				return
			}
		}
	}
}

func (s *inputSession) close(code int, reason string) {
	message := websocket.FormatCloseMessage(code, reason)
	s.conn.WriteControl(websocket.CloseMessage, message, time.Now().Add(writeTimeout))
}

func (r inputRecord) apply(driver inputDriver, held map[mouseButton]bool) (keyboardIssue, bool) {
	switch r.Type {
	case "move":
		if !r.deltasWithin(-32768, 32767) {
			return "", false
		}
		driver.Move(*r.DX, *r.DY)
	case "scroll":
		if !r.deltasWithin(-120, 120) {
			return "", false
		}
		driver.Scroll(*r.DX, *r.DY)
	case "button":
		if (r.Button != leftButton && r.Button != rightButton) || r.Down == nil {
			return "", false
		}
		if *r.Down != held[r.Button] {
			driver.Button(r.Button, *r.Down)
			if *r.Down {
				held[r.Button] = true
			} else {
				delete(held, r.Button)
			}
		}
	case "text":
		if r.Text == "" || utf8.RuneCountInString(r.Text) > 64 {
			return "", false
		}
		for _, char := range r.Text {
			if unicode.IsControl(char) {
				return "", false
			}
		}
		return driver.TypeText(r.Text), true
	case "keyTap":
		if !r.Key.valid() {
			return "", false
		}
		return driver.TapKey(r.Key), true
	default:
		return "", false
	}
	return "", true
}

func (r inputRecord) deltasWithin(minimum, maximum int) bool {
	return r.DX != nil && r.DY != nil &&
		*r.DX >= minimum && *r.DX <= maximum &&
		*r.DY >= minimum && *r.DY <= maximum
}
