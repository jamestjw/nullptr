//go:build darwin && cgo

package main

/*
#cgo LDFLAGS: -framework CoreGraphics -framework CoreFoundation
#include <CoreGraphics/CoreGraphics.h>
#include <Carbon/Carbon.h>

static bool cursorLocation(CGPoint *position) {
	CGEventRef event = CGEventCreate(NULL);
	if (!event) {
		return false;
	}
	*position = CGEventGetLocation(event);
	CFRelease(event);
	return true;
}

static void postMouse(CGEventType type, CGPoint position, CGMouseButton button,
                      int64_t eventNumber, int64_t clicks) {
	CGEventRef event = CGEventCreateMouseEvent(NULL, type, position, button);
	if (!event) {
		return;
	}
	if (clicks > 0) {
		CGEventSetIntegerValueField(event, kCGMouseEventNumber, eventNumber);
		CGEventSetIntegerValueField(event, kCGMouseEventClickState, clicks);
	}
	CGEventPost(kCGSessionEventTap, event);
	CFRelease(event);
}

static void postScroll(int32_t dx, int32_t dy) {
	CGEventRef event = CGEventCreateScrollWheelEvent(NULL, kCGScrollEventUnitLine, 2, -dy, -dx);
	if (event) {
		CGEventPost(kCGSessionEventTap, event);
		CFRelease(event);
	}
}

static bool postKey(CGKeyCode code, const UniChar *text, UniCharCount length) {
	CGEventRef down = CGEventCreateKeyboardEvent(NULL, code, true);
	CGEventRef up = CGEventCreateKeyboardEvent(NULL, code, false);
	bool ready = down && up;
	if (ready) {
		CGEventSetFlags(down, 0);
		CGEventSetFlags(up, 0);
		if (length > 0) {
			CGEventKeyboardSetUnicodeString(down, length, text);
			CGEventKeyboardSetUnicodeString(up, length, text);
		}
		CGEventPost(kCGSessionEventTap, down);
		CGEventPost(kCGSessionEventTap, up);
	}
	if (down) {
		CFRelease(down);
	}
	if (up) {
		CFRelease(up);
	}
	return ready;
}
*/
import "C"

import (
	"errors"
	"math"
	"time"
	"unicode/utf16"
	"unsafe"
)

type quartzDriver struct {
	left, right quartzButton
	eventNumber C.int64_t
	lastButton  mouseButton
}

type quartzButton struct {
	held        bool
	eventNumber C.int64_t
	clicks      C.int64_t
	pressTime   time.Time
	pressAt     C.CGPoint
}

func openDriver() (inputDriver, error) {
	if !C.CGRequestPostEventAccess() {
		return nil, errors.New("macOS Accessibility permission is required; allow the terminal or nullptr-companion in System Settings > Privacy & Security > Accessibility, then restart the companion")
	}
	return &quartzDriver{}, nil
}

func (d *quartzDriver) Move(dx, dy int) {
	var position C.CGPoint
	if !C.cursorLocation(&position) {
		return
	}
	position.x += C.CGFloat(dx)
	position.y += C.CGFloat(dy)
	switch {
	case d.left.held:
		C.postMouse(C.kCGEventLeftMouseDragged, position, C.kCGMouseButtonLeft, d.left.eventNumber, d.left.clicks)
	case d.right.held:
		C.postMouse(C.kCGEventRightMouseDragged, position, C.kCGMouseButtonRight, d.right.eventNumber, d.right.clicks)
	default:
		C.postMouse(C.kCGEventMouseMoved, position, C.kCGMouseButtonLeft, 0, 0)
	}
}

func (d *quartzDriver) Scroll(dx, dy int) {
	C.postScroll(C.int32_t(dx), C.int32_t(dy))
}

func (d *quartzDriver) Button(button mouseButton, down bool) {
	state := &d.left
	number := C.CGMouseButton(C.kCGMouseButtonLeft)
	press, release := C.CGEventType(C.kCGEventLeftMouseDown), C.CGEventType(C.kCGEventLeftMouseUp)
	if button == rightButton {
		state = &d.right
		number = C.kCGMouseButtonRight
		press, release = C.kCGEventRightMouseDown, C.kCGEventRightMouseUp
	}
	var position C.CGPoint
	if !C.cursorLocation(&position) {
		position = state.pressAt
	}
	state.held = down
	event := release
	nearPress := math.Abs(float64(position.x-state.pressAt.x)) <= 2 &&
		math.Abs(float64(position.y-state.pressAt.y)) <= 2
	if down {
		now := time.Now()
		if d.lastButton == button && nearPress && now.Sub(state.pressTime) <= 500*time.Millisecond {
			state.clicks++
		} else {
			state.clicks = 1
		}
		d.eventNumber++
		d.lastButton = button
		state.eventNumber = d.eventNumber
		state.pressTime = now
		state.pressAt = position
		event = press
	} else if !nearPress {
		state.pressTime = time.Time{}
	}
	C.postMouse(event, position, number, state.eventNumber, state.clicks)
}

var quartzKeys = map[keyboardKey]C.CGKeyCode{
	backspaceKey: C.kVK_Delete,
	enterKey:     C.kVK_Return,
	tabKey:       C.kVK_Tab,
	escapeKey:    C.kVK_Escape,
	leftKey:      C.kVK_LeftArrow,
	rightKey:     C.kVK_RightArrow,
	upKey:        C.kVK_UpArrow,
	downKey:      C.kVK_DownArrow,
}

func quartzKeyboardIssue() keyboardIssue {
	if !C.CGPreflightPostEventAccess() {
		return keyboardUnavailable
	}
	const modifiers = C.kCGEventFlagMaskShift | C.kCGEventFlagMaskControl |
		C.kCGEventFlagMaskAlternate | C.kCGEventFlagMaskCommand | C.kCGEventFlagMaskSecondaryFn
	if C.CGEventSourceFlagsState(C.kCGEventSourceStateCombinedSessionState)&modifiers != 0 {
		return modifiersActive
	}
	return ""
}

func (d *quartzDriver) TypeText(text string) keyboardIssue {
	if issue := quartzKeyboardIssue(); issue != "" {
		return issue
	}
	for _, char := range text {
		chars := utf16.Encode([]rune{char})
		if !C.postKey(C.kVK_Space, (*C.UniChar)(unsafe.Pointer(&chars[0])), C.UniCharCount(len(chars))) {
			return keyboardUnavailable
		}
	}
	return ""
}

func (d *quartzDriver) TapKey(key keyboardKey) keyboardIssue {
	if issue := quartzKeyboardIssue(); issue != "" {
		return issue
	}
	if !C.postKey(quartzKeys[key], nil, 0) {
		return keyboardUnavailable
	}
	return ""
}

func (d *quartzDriver) Close() {}
