#!/usr/bin/env bash
# Builds app/libs/tsbridge.aar (embedded Tailscale) with gomobile.
# Needs: Go, the Android SDK + NDK (ANDROID_HOME / ANDROID_NDK_HOME).
# The CI workflow runs the same steps before the Gradle build.
set -euo pipefail
cd "$(dirname "$0")/tsbridge"
# Pinned: bump deliberately.
go get tailscale.com@v1.104.7 golang.org/x/mobile@v0.0.0-20261117202452-47699113848a
go mod tidy
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init
mkdir -p ../app/libs
gomobile bind -target=android/arm64 -androidapi 26 \
  -ldflags="-s -w" -o ../app/libs/tsbridge.aar .
ls -la ../app/libs/tsbridge.aar
