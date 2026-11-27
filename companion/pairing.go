package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/skip2/go-qrcode"
)

const (
	pairLifetime = 10 * time.Minute
	pairWindow   = time.Minute
	pairAttempts = 5
	tokenBytes   = 32
)

type pairing struct {
	code        string
	expires     time.Time
	failed      int
	windowStart time.Time
}

func newPairing() *pairing {
	return &pairing{code: randomHex(6), expires: time.Now().Add(pairLifetime)}
}

func randomHex(size int) string {
	value := make([]byte, size)
	rand.Read(value)
	return hex.EncodeToString(value)
}

func (p *pairing) print(address string) error {
	payload := fmt.Sprintf("nullptr://pair?host=%s&code=%s", address, p.code)
	qr, err := qrcode.New(payload, qrcode.Medium)
	if err != nil {
		return err
	}
	info, err := os.Stdout.Stat()
	color := err == nil && info.Mode()&os.ModeCharDevice != 0 && os.Getenv("TERM") != "dumb"
	fmt.Printf("Pair at %s with code %s (expires in 10 minutes)\n%s\n\n", address, p.code, payload)
	bitmap := qr.Bitmap()
	if color {
		printTerminalQR(bitmap)
		return nil
	}
	for _, row := range bitmap {
		var line strings.Builder
		for _, dark := range row {
			if dark {
				line.WriteString("##")
			} else {
				line.WriteString("  ")
			}
		}
		fmt.Println(line.String())
	}
	return nil
}

func printTerminalQR(bitmap [][]bool) {
	colors := map[[2]bool]string{
		{false, false}: "\x1b[97;107m",
		{false, true}:  "\x1b[97;40m",
		{true, false}:  "\x1b[30;107m",
		{true, true}:   "\x1b[30;40m",
	}
	for row := 0; row < len(bitmap); row += 2 {
		var line strings.Builder
		var previous [2]bool
		for column, upper := range bitmap[row] {
			lower := row+1 < len(bitmap) && bitmap[row+1][column]
			pair := [2]bool{upper, lower}
			if column == 0 || pair != previous {
				line.WriteString(colors[pair])
				previous = pair
			}
			line.WriteRune('▀')
		}
		line.WriteString("\x1b[0m")
		fmt.Println(line.String())
	}
}

func tokenPath() (string, error) {
	config, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(config, "nullptr", "token"), nil
}

func loadToken(path string) (string, error) {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || info.Size() != tokenBytes*2+1 {
		return "", fmt.Errorf("invalid credential file %s; expected a regular 0600 token file", path)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	token := strings.TrimSuffix(string(data), "\n")
	if _, err := hex.DecodeString(token); err != nil || len(token) != tokenBytes*2 {
		return "", fmt.Errorf("invalid token in %s", path)
	}
	return token, nil
}

func saveToken(path, token string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	file, err := os.CreateTemp(dir, ".token-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if err := file.Chmod(0600); err != nil {
		file.Close()
		return err
	}
	_, writeErr := file.WriteString(token + "\n")
	if writeErr == nil {
		writeErr = file.Sync()
	}
	if err := errors.Join(writeErr, file.Close()); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
