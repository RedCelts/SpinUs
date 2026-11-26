# Oniel Music — source code (v1.2)

Everything you need to build and change the app yourself.

## Open it
- On a computer: open this folder in **Android Studio** (it will download the
  libraries itself with Gradle). `local.properties` is not included — Android
  Studio creates it with your SDK location.
- `keystore/debug.keystore` is the signing key used for v1, v1.1 and v1.2.
  Keep it safe — builds signed with it install over the app on your phone.
  Without it, your own builds would need the app uninstalled first.

## Where things live
- `app/src/main/java/com/oniel/music/` — all the Kotlin code
  - `MainActivity.kt` — launch, first screen
  - `data/SpinUsClient.kt` — talks to the SpinUs server (login, library,
    search, lyrics, presence, mark-played)
  - `data/AuthStore.kt` / `data/SettingsStore.kt` — saved login + settings
  - `ui/PlayerController.kt` + `ui/PlaybackService.kt` — playback, queue,
    crossfade (dual-player volume ramp)
  - `ui/` screens — Home, Player, Library, Search, Settings, Friends, Stats
- `app/src/main/res/` — layouts, strings, the launcher icon
- `app/src/main/AndroidManifest.xml`
- `docs/SERVER-API.md` — the SpinUs server API the app uses
- `server/` — the SpinUs server itself (runs on the mini PC; see server/README.md)
- `BUILD-NOTES.md` — how it was built here, version history, fixes
- `manual-build/` — the scripts used to build it in this environment
  (Gradle is blocked here, so it was built by hand with kotlinc/aapt2/d8).
  On your own computer you do not need these — Gradle does it.

## Build it (normal way)
In Android Studio: Build > Make Project, then Run. Or `./gradlew assembleDebug`.
