//go:build (!linux && !darwin) || !cgo

package main

import "errors"

func openDriver() (inputDriver, error) {
	return nil, errors.New("the input driver requires a CGO build on macOS or Linux with X11/XTest development libraries")
}
