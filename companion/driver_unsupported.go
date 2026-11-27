//go:build !linux || !cgo

package main

import "errors"

func openDriver() (inputDriver, error) {
	return nil, errors.New("the pointer driver requires Linux with CGO and X11/XTest development libraries")
}
