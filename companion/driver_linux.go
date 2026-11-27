//go:build linux && cgo

package main

/*
#cgo pkg-config: x11 xtst xkbcommon
#include <X11/XKBlib.h>
#include <X11/extensions/XTest.h>
#include <X11/keysym.h>
#include <xkbcommon/xkbcommon.h>

static KeyCode findKey(XkbDescPtr map, XkbStateRec *state, unsigned int mods, KeySym key, uint32_t character) {
	for (int code = map->min_key_code; code <= map->max_key_code; code++) {
		KeySym symbol;
		unsigned int consumed;
		if (!XkbTranslateKeyCode(map, code, XkbBuildCoreState(mods, state->group), &consumed, &symbol)) {
			continue;
		}
		if (character ? xkb_keysym_to_utf32(symbol) == character : symbol == key) {
			return code;
		}
	}
	return 0;
}

static KeyCode findShift(XkbDescPtr map) {
	for (int code = map->min_key_code; code <= map->max_key_code; code++) {
		if (map->map->modmap[code] == ShiftMask) {
			return code;
		}
	}
	return 0;
}
*/
import "C"

import "errors"

type x11Driver struct {
	display *C.Display
}

func openDriver() (inputDriver, error) {
	display := C.XOpenDisplay(nil)
	if display == nil {
		return nil, errors.New("open X11 display; run inside the X11 session or set DISPLAY and XAUTHORITY")
	}
	var eventBase, errorBase, major, minor C.int
	if C.XTestQueryExtension(display, &eventBase, &errorBase, &major, &minor) == 0 {
		C.XCloseDisplay(display)
		return nil, errors.New("X11 display does not support XTest")
	}
	return &x11Driver{display: display}, nil
}

func (d *x11Driver) Move(dx, dy int) {
	C.XTestFakeRelativeMotionEvent(d.display, C.int(dx), C.int(dy), C.CurrentTime)
	C.XFlush(d.display)
}

func (d *x11Driver) Scroll(dx, dy int) {
	d.wheel(dx, 6, 7)
	d.wheel(dy, 4, 5)
	C.XFlush(d.display)
}

func (d *x11Driver) wheel(ticks int, negative, positive C.uint) {
	button := positive
	if ticks < 0 {
		button = negative
		ticks = -ticks
	}
	for range ticks {
		C.XTestFakeButtonEvent(d.display, button, C.True, C.CurrentTime)
		C.XTestFakeButtonEvent(d.display, button, C.False, C.CurrentTime)
	}
}

func (d *x11Driver) Button(button mouseButton, down bool) {
	number := C.uint(1)
	if button == rightButton {
		number = 3
	}
	pressed := C.False
	if down {
		pressed = C.True
	}
	C.XTestFakeButtonEvent(d.display, number, C.int(pressed), C.CurrentTime)
	C.XFlush(d.display)
}

var x11Keys = map[keyboardKey]C.KeySym{
	backspaceKey: C.XK_BackSpace,
	enterKey:     C.XK_Return,
	tabKey:       C.XK_Tab,
	escapeKey:    C.XK_Escape,
	leftKey:      C.XK_Left,
	rightKey:     C.XK_Right,
	upKey:        C.XK_Up,
	downKey:      C.XK_Down,
}

func (d *x11Driver) keyboard() (C.XkbDescPtr, C.XkbStateRec) {
	var state C.XkbStateRec
	if C.XkbGetState(d.display, C.XkbUseCoreKbd, &state) != C.Success {
		return nil, state
	}
	allowedLocks := C.uint(C.LockMask) | C.XkbKeysymToModifiers(d.display, C.XK_Num_Lock)
	if state.base_mods|state.latched_mods != 0 || C.uint(state.locked_mods)&^allowedLocks != 0 {
		return nil, state
	}
	return C.XkbGetMap(d.display, C.XkbAllClientInfoMask, C.XkbUseCoreKbd), state
}

func (d *x11Driver) TypeText(text string) {
	keymap, state := d.keyboard()
	if keymap == nil {
		return
	}
	defer C.XkbFreeKeyboard(keymap, C.XkbAllComponentsMask, C.True)

	mods := C.uint(state.mods)
	shift := C.findShift(keymap)
	for _, char := range text {
		code := C.findKey(keymap, &state, mods, 0, C.uint32_t(char))
		if code != 0 {
			d.tap(code)
			continue
		}
		if shift != 0 {
			code = C.findKey(keymap, &state, mods|C.ShiftMask, 0, C.uint32_t(char))
			if code != 0 {
				C.XTestFakeKeyEvent(d.display, C.uint(shift), C.True, C.CurrentTime)
				d.tap(code)
				C.XTestFakeKeyEvent(d.display, C.uint(shift), C.False, C.CurrentTime)
			}
		}
	}
	C.XFlush(d.display)
}

func (d *x11Driver) TapKey(key keyboardKey) {
	keymap, state := d.keyboard()
	if keymap == nil {
		return
	}
	defer C.XkbFreeKeyboard(keymap, C.XkbAllComponentsMask, C.True)

	code := C.findKey(keymap, &state, C.uint(state.mods), x11Keys[key], 0)
	if code != 0 {
		d.tap(code)
		C.XFlush(d.display)
	}
}

func (d *x11Driver) tap(code C.KeyCode) {
	C.XTestFakeKeyEvent(d.display, C.uint(code), C.True, C.CurrentTime)
	C.XTestFakeKeyEvent(d.display, C.uint(code), C.False, C.CurrentTime)
}

func (d *x11Driver) Close() {
	C.XCloseDisplay(d.display)
}
