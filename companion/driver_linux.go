//go:build linux && cgo

package main

/*
#cgo pkg-config: x11 xtst
#include <X11/extensions/XTest.h>
*/
import "C"

import "errors"

type x11Driver struct {
	display *C.Display
}

func openDriver() (pointerDriver, error) {
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

func (d *x11Driver) Close() {
	C.XCloseDisplay(d.display)
}
