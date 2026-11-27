package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"runtime"
	"strings"
	"syscall"
	"time"
)

const defaultPort = 47822

func main() {
	listen := flag.String("listen", "", "local Tailscale IPv4 address (defaults to tailscale ip -4)")
	port := flag.Int("port", defaultPort, "TCP port")
	pair := flag.Bool("pair", false, "issue a new pairing code")
	flag.Parse()
	if flag.NArg() != 0 || *port < 1 || *port > 65535 {
		flag.Usage()
		os.Exit(2)
	}

	if err := run(*listen, uint16(*port), *pair); err != nil {
		log.Fatal(err)
	}
}

func run(listen string, port uint16, forcePair bool) error {
	address, err := tailscaleAddress(listen, port)
	if err != nil {
		return err
	}
	path, err := tokenPath()
	if err != nil {
		return err
	}
	token, err := loadToken(path)
	if err != nil {
		return err
	}

	listener, err := net.Listen("tcp4", address.String())
	if err != nil {
		return err
	}
	defer listener.Close()

	driver, err := openDriver()
	if err != nil {
		return err
	}
	defer driver.Close()
	c := &companion{driver: driver, tokenFile: path, token: token, idle: inputTimeout}

	if token == "" || forcePair {
		c.pairing = newPairing()
		if err := c.pairing.print(address.String()); err != nil {
			return err
		}
	} else {
		fmt.Printf("Listening on %s\n", address)
	}

	server := &http.Server{
		Handler:           c.handler(),
		ReadHeaderTimeout: 3 * time.Second,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      5 * time.Second,
		IdleTimeout:       15 * time.Second,
		MaxHeaderBytes:    8 * 1024,
	}
	signals, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	serveErr := make(chan error, 1)
	go func() { serveErr <- server.Serve(listener) }()
	select {
	case err := <-serveErr:
		c.stop()
		return err
	case <-signals.Done():
		c.stop()
		deadline, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := server.Shutdown(deadline); err != nil {
			return err
		}
		if err := <-serveErr; !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	}
}

func tailscaleAddress(requested string, port uint16) (netip.AddrPort, error) {
	deadline, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	cli := "tailscale"
	if runtime.GOOS == "darwin" {
		if _, err := exec.LookPath(cli); errors.Is(err, exec.ErrNotFound) {
			cli = "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
		}
	}
	output, err := exec.CommandContext(deadline, cli, "ip", "-4").Output()
	if err != nil {
		return netip.AddrPort{}, fmt.Errorf("detect local Tailscale IPv4: %w", err)
	}
	local, err := netip.ParseAddr(strings.TrimSpace(string(output)))
	if err != nil || !tailnet.Contains(local) {
		return netip.AddrPort{}, errors.New("tailscale ip -4 did not return a Tailscale IPv4 address")
	}
	if requested != "" {
		address, err := netip.ParseAddr(requested)
		if err != nil || address != local {
			return netip.AddrPort{}, fmt.Errorf("--listen must match the local Tailscale IPv4 (%s)", local)
		}
	}
	return netip.AddrPortFrom(local, port), nil
}
