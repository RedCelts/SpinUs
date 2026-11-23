// Package tsbridge embeds Tailscale (tsnet, userspace networking) in the
// Oniel Music Android app, so the app can reach the Jellyfin server over
// the tailnet without the Tailscale app or a VPN.
//
// The app becomes its own device on the tailnet ("oniel-music"). All the
// Kotlin side needs is a local SOCKS5 proxy: requests for tailnet hosts
// (100.x addresses, MagicDNS names) are sent to 127.0.0.1:SocksPort(),
// and tsnet carries them over WireGuard. Everything else connects normally.
//
// Built with gomobile into app/libs/tsbridge.aar (Java class
// tsbridge.Tsbridge). See build-tsbridge.sh and the CI workflow.
package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/net/socks5"
	"tailscale.com/tsnet"
)

// AppContext is implemented in Kotlin.
type AppContext interface {
	// GetInterfaces lists the phone's network interfaces, one per line:
	// name|index|mtu|flags|cidr,cidr,...  (flags: comma list of
	// up,loopback,multicast,p2p,broadcast). Go's own net.Interfaces()
	// is blocked for apps on Android 11+, so Tailscale asks Kotlin.
	GetInterfaces() string
	// Log receives Tailscale's user-facing log lines.
	Log(line string)
}

var (
	mu        sync.Mutex
	srv       *tsnet.Server
	socksLn   net.Listener
	socksPort int
	app       AppContext
	lastErr   string
	getterSet bool
)

// SetCrashFile makes a Go crash (panic / fatal error) write its report
// to path, so the app can show it on the next launch. Android's crash
// dialog alone says nothing about what went wrong.
func SetCrashFile(path string) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	return debug.SetCrashOutput(f, debug.CrashOptions{})
}

// Start brings the embedded Tailscale node up. dir is a private,
// persistent folder (the node's identity and keys live there), hostname
// is the device name shown in the Tailscale admin console. Safe to call
// more than once.
func Start(dir, hostname string, ctx AppContext) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	app = ctx
	if !getterSet {
		netmon.RegisterInterfaceGetter(getInterfaces)
		getterSet = true
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		lastErr = err.Error()
		return err
	}
	// Android apps have no $HOME / XDG folders. Without them Tailscale's
	// log policy panics ("no safe place found to store log state") —
	// the v1.0.5 crash. Point everything at the app's private folder.
	// (Kotlin sets the same variables before the library loads.)
	logsDir := filepath.Join(dir, "logs")
	cacheDir := filepath.Join(dir, "cache")
	for _, d := range []string{logsDir, cacheDir} {
		os.MkdirAll(d, 0o700)
	}
	setIfEmpty := func(k, v string) {
		if os.Getenv(k) == "" {
			os.Setenv(k, v)
		}
	}
	setIfEmpty("HOME", dir)
	setIfEmpty("XDG_CACHE_HOME", cacheDir)
	setIfEmpty("XDG_CONFIG_HOME", dir)
	setIfEmpty("TMPDIR", cacheDir)
	setIfEmpty("TS_LOGS_DIR", logsDir)
	// Don't upload diagnostic logs to Tailscale.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	s := &tsnet.Server{
		Dir:      dir,
		Hostname: hostname,
		Logf:     func(string, ...any) {}, // backend chatter: dropped
		UserLogf: func(format string, args ...any) {
			if app != nil {
				app.Log(fmt.Sprintf(format, args...))
			}
		},
	}
	if err := s.Start(); err != nil {
		lastErr = err.Error()
		return err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		s.Close()
		lastErr = err.Error()
		return err
	}
	proxy := &socks5.Server{
		Logf:   func(string, ...any) {},
		Dialer: s.Dial,
	}
	go proxy.Serve(ln)
	srv = s
	socksLn = ln
	socksPort = ln.Addr().(*net.TCPAddr).Port
	lastErr = ""
	return nil
}

// SocksPort is the local SOCKS5 port (0 when stopped).
func SocksPort() int {
	mu.Lock()
	defer mu.Unlock()
	return socksPort
}

type statusJSON struct {
	State   string   `json:"state"` // Stopped, NoState, NeedsLogin, Starting, Running, ...
	AuthURL string   `json:"authUrl"`
	IPs     []string `json:"ips"`
	DNSName string   `json:"dnsName"`
	Tailnet string   `json:"tailnet"`
	Error   string   `json:"error"`
}

// Status returns the node's state as JSON (see statusJSON).
func Status() string {
	mu.Lock()
	s := srv
	errText := lastErr
	mu.Unlock()
	out := statusJSON{State: "Stopped", Error: errText}
	if s != nil {
		out.State = "Starting"
		lc, err := s.LocalClient()
		if err != nil {
			out.Error = err.Error()
		} else {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			st, err := lc.StatusWithoutPeers(ctx)
			if err != nil {
				out.Error = err.Error()
			} else {
				out.State = st.BackendState
				out.AuthURL = st.AuthURL
				for _, ip := range st.TailscaleIPs {
					out.IPs = append(out.IPs, ip.String())
				}
				if st.Self != nil {
					out.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
				}
				if st.CurrentTailnet != nil {
					out.Tailnet = st.CurrentTailnet.Name
				}
			}
		}
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// Login returns the URL to open in a browser to sign this device in
// (starting an interactive login if none is pending). Empty when the
// node is already signed in.
func Login() (string, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return "", errors.New("tailscale is not running")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		return "", err
	}
	if st.BackendState == "Running" {
		return "", nil
	}
	if st.AuthURL != "" {
		return st.AuthURL, nil
	}
	if err := lc.StartLoginInteractive(ctx); err != nil {
		return "", err
	}
	for {
		select {
		case <-ctx.Done():
			return "", errors.New("timed out waiting for the Tailscale sign-in link")
		case <-time.After(300 * time.Millisecond):
		}
		st, err = lc.StatusWithoutPeers(ctx)
		if err == nil && st.AuthURL != "" {
			return st.AuthURL, nil
		}
		if err == nil && st.BackendState == "Running" {
			return "", nil
		}
	}
}

// Logout signs this device out of the tailnet (it stays running, ready
// for a new sign-in).
func Logout() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return nil
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

// Stop shuts the node and the proxy down.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if socksLn != nil {
		socksLn.Close()
		socksLn = nil
	}
	if srv != nil {
		srv.Close()
		srv = nil
	}
	socksPort = 0
}

func getInterfaces() ([]netmon.Interface, error) {
	a := app
	if a == nil {
		return nil, errors.New("no app context")
	}
	var ifs []netmon.Interface
	for _, line := range strings.Split(a.GetInterfaces(), "\n") {
		parts := strings.Split(strings.TrimSpace(line), "|")
		if len(parts) < 5 || parts[0] == "" {
			continue
		}
		index, _ := strconv.Atoi(parts[1])
		mtu, _ := strconv.Atoi(parts[2])
		var flags net.Flags
		for _, f := range strings.Split(parts[3], ",") {
			switch f {
			case "up":
				flags |= net.FlagUp | net.FlagRunning
			case "loopback":
				flags |= net.FlagLoopback
			case "multicast":
				flags |= net.FlagMulticast
			case "p2p":
				flags |= net.FlagPointToPoint
			case "broadcast":
				flags |= net.FlagBroadcast
			}
		}
		var addrs []net.Addr
		for _, cidr := range strings.Split(parts[4], ",") {
			if cidr == "" {
				continue
			}
			ip, ipNet, err := net.ParseCIDR(cidr)
			if err != nil {
				continue
			}
			ipNet.IP = ip
			addrs = append(addrs, ipNet)
		}
		ifs = append(ifs, netmon.Interface{
			Interface: &net.Interface{Index: index, MTU: mtu, Name: parts[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	return ifs, nil
}
