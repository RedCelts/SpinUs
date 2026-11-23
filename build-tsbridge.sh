#!/usr/bin/env bash
# Builds app/libs/tsbridge.aar (embedded Tailscale) with gomobile.
# Needs: Go, the Android SDK + NDK (ANDROID_HOME / ANDROID_NDK_HOME).
# The CI workflow runs the same steps before the Gradle build.
set -euo pipefail
cd "$(dirname "$0")/tsbridge"
go get tailscale.com@latest golang.org/x/mobile@latest
go mod tidy
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init
mkdir -p ../app/libs
gomobile bind -target=android/arm64,android/arm -androidapi 26 \
  -ldflags="-s -w" -o ../app/libs/tsbridge.aar .
ls -la ../app/libs/tsbridge.aar
