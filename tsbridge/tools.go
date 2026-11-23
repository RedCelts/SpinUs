//go:build tools

package tsbridge

// Keeps golang.org/x/mobile/bind in go.mod: gomobile bind needs it.
import _ "golang.org/x/mobile/bind"
