# Jellyfin API notes — native Android music app

**Verified live against a real Jellyfin Server 12.2.0** running locally
(`ProductName: "Jellyfin Server"`, `Version: "12.2.0"`, ServerId
`05b86c29cb634d85b9cdf729baa2afba`) on 2026-11-22. Official 12.2 tarball,
fresh datadir, no plugins installed. Every endpoint below was called
against that server and returned the status shown; sample responses are
real responses with the access token redacted. Anything **not** verified
live is marked **UNVERIFIED** at the end — do not code against those
shapes from this document alone.

Raw evidence (full JSON responses, stream files, presence log) is kept in
the test workspace `~/workspace/jf-api-test/results/` (tokens in there are
test-only, for a throwaway local server).

## Test fixture facts (local test server only — NOT real credentials)

- Base URL used in all tests: `http://127.0.0.1:8096` (the user's real
  server will be `http://<their-server-IP>:8096`, same paths).
- Admin user: **Oniel**, password `testpass1` (test-only),
  Id `c06869d6689146bc98a3f00ae7db4a2f`, `Policy.IsAdministrator: true`,
  session Id `3093ebaa45f86033c73ba99cc2bd97a5`.
- Non-admin user: **Friend**, password `testpass2` (test-only),
  Id `d01d454b7b5442879f1dfa946276e31d`, `Policy.IsAdministrator: false`,
  session Id `2085577ea92ef43778061766672e5f60`.
- Test library "Music" (CollectionType `music`), one album:
  **"Test Album"** by **"Test Artist"**, Id
  `83184692e6f32cf7676fdb8684f482d3`, `AlbumPrimaryImageTag`
  `43debae068319765e50b99733a7a542e`, 3 tracks, 20 s each.
- Tracks (all MP3, 64 kbps, 44100 Hz, mono, 20.0 s):
  - **"Synced Song"** Id `1db6e046eed9a1dffc0af728a5965323`, `HasLyrics: true`
    (synced `.lrc` sidecar, 4 timestamped lines).
  - **"Plain Song"** Id `7ceb605431fe02e0422e37154564e593`, `HasLyrics: true`
    (plain `.lrc` sidecar, no timestamps).
  - **"No Lyrics Song"** Id `33f329e3ccd1df7fb388b4ff0e2d7c35`,
    `HasLyrics: false`.
- Artist Id `62fd7f490c70b787b152367e98545c8a` ("Test Artist").
- Test playlists: "Test Playlist" Id `dd25e6492d903a79d2560368a436f42d`
  (2 tracks), "TestPlaylist2" Id `f4a8e0ef82789505c31a7dc39675e32c`.
- Music library view Id `7e64e319657a9516ec78490da03edccb`; Playlists view
  Id `4cb93aaebdc0a0a335a92137115996d9`.

## Base URL and auth — READ THIS FIRST, it differs from the docs

### Base URL

`{scheme}://{host}:{port}` — no path prefix. Default port **8096**.
All paths below are relative to it. The user types their server address
(IP or hostname) into the app; the app must not hardcode one.

### Auth header — the ONLY form that works in 12.2.0

Every authenticated request must carry the token inside the
`Authorization` header, MediaBrowser scheme:

```
Authorization: MediaBrowser Token="<32-char-access-token>", Client="<app-name>", Device="<device-name>", DeviceId="<stable-uuid>", Version="<app-version>"
```

Example (from the live test):

```
Authorization: MediaBrowser Token="…", Client="MusicLookAPKTest", Device="test", DeviceId="test-device-001", Version="1.0.0"
```

- `Client`, `Device`, `DeviceId`, `Version` are required on the
  **login** call (see §1). On later calls the token alone in the header
  is enough, but send the full header anyway — keep `DeviceId` stable
  per install, because the session (and therefore presence, §6) is keyed
  to it.
- **Verified failures (do NOT use these):**
  - `X-MediaBrowser-Token: <token>` → **401** on every endpoint.
  - `X-Emby-Token` — same family, not tested separately; do not use.
  - `?api_key=<token>` query param → **401** (`EnableLegacyAuthorization`
    is off by default in Jellyfin 12).
  - `X-Emby-Authorization: MediaBrowser Client=…` on login → **400**
    (`System.ArgumentNullException: Value cannot be null. (Parameter
    'request.App')`). Jellyfin 12 reads client info from the plain
    `Authorization` header, not the `X-Emby-` form.
  - No client header at all on login → **400**.
  - Wrong password on login → **401**.

### Startup / first-run notes (only matters for a brand-new server)

- A fresh server answers **503** (a startup HTML page) on API routes for
  roughly 25 s after `/health` first returns 200. Poll a real API route
  until it stops 503-ing; do not treat the first 200 from `/health` as
  "ready".
- First user is created through the startup wizard, not `POST /Users/New`
  (that returns 401 with no users yet):
  - `GET /Startup/User` → `200 {"Name":"root"}` (creates the default
    user; `POST /Startup/User` alone 404s when no user exists).
  - `POST /Startup/User` `{"Name":"Oniel","Password":"testpass1"}`
    → **204** (renames the default user and sets the password).
  - After that, `POST /Users/New` works with the admin token (see §9).

### Creating a music library (test setup, for reference)

`POST /Library/VirtualFolders?name=Music&collectionType=music&paths=<url-encoded-absolute-path>&refreshLibrary=false`
→ **204**, empty body. The parameters are **query parameters**, not a
JSON body — a JSON body with `Name`/`CollectionType`/`Paths` returns
**400** `{"errors":{"name":["The name field is required."]}}`.
Then `POST /Library/Refresh` → **204** to scan. The scan in the test
found all 3 tracks within seconds of the refresh call.

---

## 1. Login — POST /Users/AuthenticateByName

```
POST /Users/AuthenticateByName
Authorization: MediaBrowser Client="MusicLookAPKTest", Device="test", DeviceId="test-device-001", Version="1.0.0"
Content-Type: application/json

{"Username": "Oniel", "Pw": "testpass1"}
```

→ **200**. Response (redacted; full shape saved in test evidence):

```json
{
  "User": {
    "Name": "Oniel",
    "ServerId": "05b86c29cb634d85b9cdf729baa2afba",
    "Id": "c06869d6689146bc98a3f00ae7db4a2f",
    "HasPassword": true,
    "HasConfiguredPassword": true,
    "HasConfiguredEasyPassword": false,
    "EnableAutoLogin": false,
    "LastLoginDate": "2026-11-22T19:48:38.1000297Z",
    "LastActivityDate": "2026-11-22T19:48:38.1000297Z",
    "Configuration": {
      "PlayDefaultAudioTrack": true,
      "SubtitleLanguagePreference": "",
      "DisplayMissingEpisodes": false,
      "GroupedFolders": [],
      "SubtitleMode": "Default",
      "DisplayCollectionsView": false,
      "EnableLocalPassword": false,
      "OrderedViews": [],
      "LatestItemsExcludes": [],
      "MyMediaExcludes": [],
      "HidePlayedInLatest": true,
      "RememberAudioSelections": true,
      "RememberSubtitleSelections": true,
      "EnableNextEpisodeAutoPlay": true,
      "CastReceiverId": "F007D354"
    },
    "Policy": { "IsAdministrator": true, "EnableRemoteControlOfOtherUsers": true }
  },
  "SessionInfo": {
    "PlayState": { "CanSeek": false, "IsPaused": false, "IsMuted": false,
                   "RepeatMode": "RepeatNone", "PlaybackOrder": "Default" },
    "AdditionalUsers": [],
    "Capabilities": { "PlayableMediaTypes": [], "SupportedCommands": [],
                      "SupportsMediaControl": false, "SupportsPersistentIdentifier": true },
    "RemoteEndPoint": "127.0.0.1",
    "PlayableMediaTypes": [],
    "Id": "3093ebaa45f86033c73ba99cc2bd97a5",
    "UserId": "c06869d6689146bc98a3f00ae7db4a2f",
    "UserName": "Oniel",
    "Client": "MusicLookAPKTest",
    "LastActivityDate": "2026-11-22T19:48:38.1595048Z",
    "LastPlaybackCheckIn": "0001-01-01T00:00:00.0000000Z",
    "DeviceName": "test",
    "DeviceId": "test-device-001",
    "ApplicationVersion": "1.0.0",
    "IsActive": true
  },
  "AccessToken": "<redacted-32-char-token>",
  "ServerId": "05b86c29cb634d85b9cdf729baa2afba"
}
```

- Store `AccessToken` (32 chars) and `User.Id`. The token is the session
  credential — send it in the `Authorization` header (§ above), never as
  `api_key`.
- `SessionInfo.Id` is this device's session id — needed for presence
  (§6) and messaging (§7). The same `DeviceId` reuses the same session
  id across a WebSocket reconnect (verified: the WS test re-attached to
  session `3093ebaa…`).
- Note `SessionInfo.Capabilities.SupportsMediaControl: false` at login —
  it only becomes true after the app reports capabilities over an open
  WebSocket (§6). Do not rely on the login-time value.

## 2. Library reads (user token in Authorization header)

All of these are `GET` with the `Authorization` header from §1. `{userId}`
is the logged-in user's Id from §1.

### 2a. Albums

```
GET /Users/{userId}/Items?IncludeItemTypes=MusicAlbum&Recursive=true
GET /Users/{userId}/Items?IncludeItemTypes=MusicAlbum&Recursive=true&Fields=DateCreated,PremiereDate
```

→ **200**. Envelope: `{"Items":[…],"TotalRecordCount":1,"StartIndex":0}`.
One album item (abridged — full JSON in test evidence):

```json
{
  "Name": "Test Album",
  "ServerId": "05b86c29cb634d85b9cdf729baa2afba",
  "Id": "83184692e6f32cf7676fdb8684f482d3",
  "PremiereDate": "2026-01-01T00:00:00.0000000Z",
  "DateCreated": "2026-11-22T19:49:08.8265371Z",
  "RunTimeTicks": 600000000,
  "ProductionYear": 2026,
  "IsFolder": true,
  "Type": "MusicAlbum",
  "UserData": { "PlaybackPositionTicks": 0, "PlayCount": 0, "IsFavorite": false,
                "Played": false, "Key": "Test Artist-Test Album",
                "ItemId": "83184692e6f32cf7676fdb8684f482d3" },
  "Artists": ["Test Artist"],
  "ArtistItems": [{ "Name": "Test Artist", "Id": "62fd7f490c70b787b152367e98545c8a" }],
  "AlbumArtist": "Test Artist",
  "AlbumArtists": [
    { "Name": "Test Artist", "Id": "62fd7f490c70b787b152367e98545c8a" },
    { "Name": "MusicBrainz Test Artist", "Id": "39d63c85b067c03f3f213117df62bd2f" }
  ],
  "ImageTags": { "Primary": "43debae068319765e50b99733a7a542e" },
  "BackdropImageTags": [],
  "ImageBlurHashes": { "Primary": { "43debae068319765e50b99733a7a542e": "e1Fx;3$qfQ$qfQ$qfRfQfRfQfQfQfQfQfQ$qfRfQfRfQfQfQfQfQfQ" } },
  "LocationType": "FileSystem",
  "MediaType": "Unknown"
}
```

- **`DateCreated` is NOT returned by the plain list call** — it only
  appears when requested via `Fields=DateCreated` (or on a single-item
  read, `GET /Users/{userId}/Items/{itemId}`, which returned
  `"DateCreated": "2026-11-22T19:49:08.8265371Z"`). Always pass
  `Fields=DateCreated` (and any other optional field) explicitly.
  `DateCreated` here is the scan/import time, not the release date.
- **`PremiereDate` is year-normalised**: the test MP3s were tagged
  `date=2026-10-15`, but Jellyfin returned
  `"PremiereDate": "2026-01-01T00:00:00.0000000Z"` and
  `"ProductionYear": 2026`. A "New releases (last 90 days)" shelf driven
  by `PremiereDate` will therefore behave oddly for albums whose tags
  carry only a year or a date Jellyfin collapses — on this server the
  test album qualifies as "2026" but not as "October 2026". Do not
  assume `PremiereDate` preserves the exact tagged date.
- `RunTimeTicks` is in 100-ns ticks: 600,000,000 = 60 s (3 × 20 s tracks).
  Seconds = ticks / 10,000,000.
- Oddity (test-data quirk, record, don't code around it): `AlbumArtists`
  contains a second entry, `"MusicBrainz Test Artist"`, even though the
  album's `ProviderIds` is `null` and the files name only "Test Artist".
  Jellyfin's metadata providers can add artist entries the files never
  named. Prefer `AlbumArtist` (string) for display; treat `AlbumArtists`
  as a list that may contain extras.
- `ImageTags.Primary` is the cache-buster tag for the album image (§3).
  The album item itself carries **no** `DateCreated` unless requested
  (see above) and no `Genres` when untagged (`Genres: null` was absent
  from the JSON entirely in the list response).

### 2b. Audio tracks

```
GET /Users/{userId}/Items?IncludeItemTypes=Audio&Recursive=true&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate
```

→ **200**, `TotalRecordCount: 3`. One track item (abridged):

```json
{
  "Name": "Synced Song",
  "ServerId": "05b86c29cb634d85b9cdf729baa2afba",
  "Id": "1db6e046eed9a1dffc0af728a5965323",
  "HasLyrics": true,
  "Container": "mp3",
  "PremiereDate": "2026-01-01T00:00:00.0000000Z",
  "ChannelId": null,
  "RunTimeTicks": 200000000,
  "ProductionYear": 2026,
  "IndexNumber": 1,
  "IsFolder": false,
  "Type": "Audio",
  "UserData": { "PlaybackPositionTicks": 0, "PlayCount": 0, "IsFavorite": false,
                "Played": false, "Key": "Test Artist-Test Album-0001Synced Song",
                "ItemId": "1db6e046eed9a1dffc0af728a5965323" },
  "Artists": ["Test Artist"],
  "ArtistItems": [{ "Name": "Test Artist", "Id": "62fd7f490c70b787b152367e98545c8a" }],
  "Album": "Test Album",
  "AlbumId": "83184692e6f32cf7676fdb8684f482d3",
  "AlbumPrimaryImageTag": "43debae068319765e50b99733a7a542e",
  "AlbumArtist": "Test Artist",
  "AlbumArtists": [{ "Name": "Test Artist", "Id": "62fd7f490c70b787b152367e98545c8a" }],
  "ImageTags": {},
  "BackdropImageTags": [],
  "ImageBlurHashes": { "Primary": { "43debae068319765e50b99733a7a542e": "e1Fx;3$qfQ$qfQ$qfRfQfRfQfQfQfQfQfQ$qfRfQfRfQfQfQfQfQfQ" } },
  "LocationType": "FileSystem",
  "MediaType": "Audio"
}
```

- `UserData.PlayCount` / `LastPlayedDate`: `LastPlayedDate` is **absent**
  (not `null`) when a track was never played; `PlayCount: 0`. After a
  mark-played (§8) the field appears. Code must treat a missing
  `LastPlayedDate` as "never played".
- `HasLyrics` is reliable: `true` for both `.lrc` sidecar tracks,
  `false` for the track with no lyrics file.
- `ImageTags` is `{}` on a track even when the album has art — a track
  has **no own Primary image** (§3b). Use `AlbumPrimaryImageTag` +
  the album id for track art.
- `RunTimeTicks: 200000000` = 20 s. `IndexNumber` is the track number.

### 2c. Playlists

```
GET /Users/{userId}/Items?IncludeItemTypes=Playlist&Recursive=true
```

→ **200**. Empty library: `TotalRecordCount: 0`, `Items: []`. After
creating two playlists (§2d): `TotalRecordCount: 2`, each item like
`{"Name": "Test Playlist", "Id": "dd25e6492d903a79d2560368a436f42d",
"ChildCount": 2, …}`.

#### Creating a playlist (verified)

```
POST /Playlists?Name=Test%20Playlist&Ids=<trackId1>,<trackId2>
```

→ **200**, body `{"Id": "dd25e6492d903a79d2560368a436f42d"}` (only the Id;
the name comes back from the list read). `Ids` is a comma-separated
list of audio item ids. (Create with the `Authorization` header; query
params, not a JSON body.)

#### Playlist contents (verified)

```
GET /Playlists/{playlistId}/Items
```

→ **200**, `TotalRecordCount: 2`, `Items` are the same Audio-item
objects as §2b (Name + Id verified; full shape identical to the Audio
list).

### 2d. Search

```
GET /Search/Hints?SearchTerm=Synced&UserId={userId}
```

→ **200**. `{"SearchHints": [{"Name": "Synced Song", "Type": "Audio",
"Id": "1db6e046eed9a1dffc0af728a5965323", …}], "TotalRecordCount": 1, …}`.
`Type` is the item type (`Audio`, `MusicAlbum`, `MusicArtist`, …).
Pass `UserId` — the hints are scoped to what that user may see.

### 2e. Libraries (views) — bonus, verified

```
GET /Users/{userId}/Views
```

→ **200**, `TotalRecordCount: 2`:
`{"Name": "Music", "CollectionType": "music", "Id": "7e64e319657a9516ec78490da03edccb"}`,
`{"Name": "Playlists", "CollectionType": "playlists", "Id": "4cb93aaebdc0a0a335a92137115996d9"}`.
Use this to discover the music library instead of assuming its name.

## 3. Images

```
GET /Items/{itemId}/Images/Primary?fillWidth=300&quality=80&api_key=
```

- Album: → **200**, `Content-Type: image/jpeg`, 829 bytes, **300×300 px**
  (the source `folder.jpg` was 600×600; `fillWidth=300` resized it).
  Verified with an **empty** `api_key=` exactly as written above, and
  also with a valid token as `api_key=` — same 200 / same bytes.
- **Surprise — images need no auth at all on this server:** the same
  URL with **no `api_key` parameter at all**, and with a **deliberately
  wrong** `api_key=00000000000000000000000000000000`, both returned
  **200** with the identical 829-byte JPEG. On the test server's default
  config the image endpoint is effectively public. Keep sending
  `api_key=<token>` anyway (harmless, and a hardened server may require
  it) — but do not treat an image 200 as proof the token is valid, and
  do not fail the app if an image loads without auth.
- Track-level image does **NOT** exist / does **NOT** inherit album art:

```
GET /Items/{audioId}/Images/Primary?fillWidth=300&quality=80&api_key=
→ 404  application/json; charset=utf-8
"Synced Song does not have an image of type Primary"
```

  For a track, load the image through its album:
  `GET /Items/{AlbumId}/Images/Primary?fillWidth=…&quality=…&api_key=…`
  (or `GET /Items/{AlbumId}/Images/Primary?fillHeight=…`). The
  `AlbumPrimaryImageTag` from §2b can be appended as `&tag=<tag>` for
  cache-busting; it is not required.
- Useful sizes: `fillWidth=300&quality=80` for grid/list art;
  `fillWidth=600` (or larger) for the full-player cover. `quality` is
  0–100.

## 4. Audio streaming

### 4a. Universal (verified — USE THIS for playback)

```
GET /Audio/{audioId}/universal?UserId={userId}&AudioCodec=mp3&Container=mp3&api_key={token}
```

→ **200**, `Content-Type: audio/mpeg`, `Content-Length: 160643`,
`Accept-Ranges: bytes`. The bytes are **byte-for-byte identical to the
source MP3 on disk** (md5 `4aed09f5aae436c8dcd3ba36065127fa`, verified with
`cmp`): for an MP3 source with `AudioCodec=mp3&Container=mp3` Jellyfin
direct-streams without transcoding. First bytes are the ID3 header
(`49 44 33 …` = `ID3`), then MPEG frames — a normal MP3 file.

- Seeking works: `Range: bytes=1000-1999` → **206 Partial Content**,
  `Content-Range: bytes 1000-1999/160643`, exactly 1000 bytes.
- Send the `Authorization` header as well as `api_key=` (both were sent
  in the test). The `api_key` value here **was** the real token and
  worked; whether `api_key=` alone suffices was not isolated for this
  endpoint — send both, as the web client does.
- For non-MP3 sources (FLAC etc.) the same URL transcodes to MP3; the
  response then has no `Content-Length` and `Accept-Ranges: none`
  (same behaviour as §4b). **UNVERIFIED** for non-MP3 sources — no FLAC
  was in the test library.

### 4b. Direct stream (verified — works, but it TRANSCODED)

```
GET /Audio/{audioId}/stream.mp3?api_key={token}
```

→ **200**, `Content-Type: audio/mpeg`, **no `Content-Length`**,
`Accept-Ranges: none`. The returned file is a **re-encode at 128 kbps**,
not the original: 321,293 bytes vs the 160,643-byte 64 kbps source,
same 20.0 s duration (ffprobe: `bit_rate=128000`, 44100 Hz, mono).
First bytes are still a valid ID3 header + MPEG frames, and ffprobe
reads it as a valid 20.04 s MP3.

- So `stream.mp3` **works**, but by default it transcodes (default
  audio bitrate 128k) and therefore gives no length and no seeking.
  Prefer the `universal` URL (§4a) for in-app playback; use
  `stream.mp3` only if direct streaming is unavailable.
- Both URLs returned `audio/mpeg` for MP3. Do not sniff the container
  from the URL — use the `Content-Type` / the requested codec.

## 5. Lyrics — GET /Audio/{audioId}/Lyrics

```
GET /Audio/{audioId}/Lyrics
```

(Authorization header; no query params needed.)

### 5a. Track WITH synced lyrics (`.lrc` sidecar with timestamps) → 200

```json
{
  "Metadata": {},
  "Lyrics": [
    { "Text": "First synced line",  "Start": 10000000,  "Cues": [] },
    { "Text": "Second synced line", "Start": 50000000,  "Cues": [] },
    { "Text": "Third synced line",  "Start": 100000000, "Cues": [] },
    { "Text": "Fourth synced line", "Start": 150000000, "Cues": [] }
  ]
}
```

- `Start` is in **100-ns ticks**: divide by 10,000,000 for seconds
  (1 s, 5 s, 10 s, 15 s here — exactly the `.lrc` timestamps).
- **`Metadata` is `{}` — there is NO `Metadata.IsSynced` field** in
  Jellyfin 12.2.0's response, even for a synced `.lrc`. The MusicLook
  code already handles this: `lyricsFrom` treats `IsSynced` as
  `undefined` (not `false`), and `lyricsFix` computes `synced` from the
  timestamps themselves (needs ≥ 2 distinct timed lines). Do the same:
  **a song is synced iff ≥ 2 lines have distinct non-null `Start`
  values**; never trust a server `IsSynced` flag.
- `Cues` is always `[]` here (word-level timings are not supplied).

### 5b. Track with PLAIN lyrics (`.lrc` sidecar, no timestamps) → 200

```json
{
  "Metadata": {},
  "Lyrics": [
    { "Text": "First plain line" },
    { "Text": "Second plain line" },
    { "Text": "Third plain line" },
    { "Text": "" }
  ]
}
```

- Plain lines have **no `Start` key at all** (not `Start: null`).
- **Trailing empty line**: Jellyfin appends a final `{"Text": ""}`.
  Filter out lines whose text is blank before displaying/counting, or
  the lyrics view gets a phantom empty line at the end.
- Per the app's existing rule (MusicLook v8), plain lyrics count as "no
  lyrics" for the scrolling views — show them only in a static view, if
  at all.

### 5c. Track with NO lyrics → 404

```
404  application/json; charset=utf-8
{"type":"https://tools.ietf.org/html/rfc9110#section-15.5.5","title":"Not Found","status":404,"traceId":"00-…-00"}
```

- Treat 404 as "no lyrics", not as an error. `HasLyrics` (§2b) lets the
  app avoid the call entirely.

## 6. Presence — GET /Sessions (the hard one; all steps verified live)

Session shape (per session, abridged from live responses):

```json
{
  "Id": "3093ebaa45f86033c73ba99cc2bd97a5",
  "UserId": "c06869d6689146bc98a3f00ae7db4a2f",
  "UserName": "Oniel",
  "DeviceName": "test",
  "DeviceId": "test-device-001",
  "SupportsRemoteControl": true,
  "SupportsMediaControl": true,
  "Capabilities": { "SupportsMediaControl": true, "PlayableMediaTypes": ["Audio", "Video"],
                    "SupportedCommands": [], "SupportsPersistentIdentifier": true },
  "NowPlayingItem": null
}
```

### 6a. Admin sees everyone (plain call)

```
GET /Sessions            (Authorization header, admin token)
→ 200, 2 sessions (Oniel + Friend)
```

No query params. An admin always sees all sessions.

### 6b. Non-admin sees ONLY herself (plain call) — the original bug

```
GET /Sessions            (Authorization header, Friend token)
→ 200, 1 session (Friend only)
```

### 6c. Non-admin controllable query — the fix

```
GET /Sessions?ControllableByUserId={friendUserId}     (Friend token)
```

- **Before** the permission (§6e) and **before** any WebSocket was open:
  → **200, 0 sessions** (empty list — not even herself).
- With WebSockets open but **before** the permission: → **200, 1 session**
  (Friend only).
- With WebSockets open **and after** the permission: → **200, 2 sessions**
  (Friend + Oniel). **This is the call the Friends sheet / listener icon
  must use for non-admin accounts.** Admins keep the plain `GET /Sessions`.
- After revoking the permission: back to **200, 1 session** (Friend only)
  — the permission is what unlocks it (verified by revoking and
  re-querying).

### 6d. What makes a session "controllable" — prerequisites (ALL required)

A session only shows `SupportsRemoteControl: true` (and therefore only
appears in another user's controllable query) when **all** of these hold:

1. **The client holds an open WebSocket to the server.** Connect to
   `ws://{host}:8096/socket?ApiKey=<token>&deviceId=<same-DeviceId-as-login>`
   (note: `ApiKey`, capital A, no underscore — the legacy `api_key`
   query param fails the handshake: the server logs `Token is required.
   URL GET /socket.`). Both test sockets opened successfully with
   `ApiKey`. The socket must stay open **while the app is running** —
   this is what real clients (jellyfin-web, the Jellyfin Android app)
   do. When both test sockets were open, the sessions attached to the
   **same session ids as the REST logins** (same `DeviceId` ⇒ same
   session).
2. **The client reports capabilities while the socket is open:**
   `POST /Sessions/Capabilities/Full` with the token in the
   `Authorization` header and body
   `{"PlayableMediaTypes":["Audio","Video"],"SupportedCommands":[],
   "SupportsMediaControl":true,"SupportsPersistentIdentifier":true}`
   → **204**. Verified live for both users (204 each).
   - The query-param form `POST /Sessions/Capabilities?supportsMediaControl=true…`
     also returns 204 but **does not** make the session controllable
     (verified in the earlier v21 run, not re-run here — it only sets
     `session.Capabilities`, the top-level flags stay false). Use the
     `/Full` JSON form.
   - `Capabilities/Full` alone is **not enough**: after the 204s, the
     sessions' `Capabilities.SupportsMediaControl` was `true` but the
     top-level `SupportsRemoteControl`/`SupportsMediaControl` stayed
     `false` until the WebSocket was actually open. Both steps are
     needed, in this order: open socket → report capabilities → then
     the flags flip.
3. **Before any WebSocket was open**, admin `GET /Sessions` returned both
   sessions with `SupportsRemoteControl: false`,
   `SupportsMediaControl: false`, `Capabilities.SupportsMediaControl: false`.
   After sockets + capabilities: both `true` on all three flags.

### 6e. The permission half (dashboard / API)

The friend's account needs **"Allow remote control of other users"**:

- Read the policy from `GET /Users/{friendId}` → `.Policy`
  (`EnableRemoteControlOfOtherUsers: false` initially for Friend,
  `true` for the admin). There is **no** `GET /Users/{id}/Policy` —
  verified: it returns **405**.
- Write it with `POST /Users/{friendId}/Policy` (admin token), body =
  the **full `Policy` object as read, with
  `EnableRemoteControlOfOtherUsers` flipped to `true`** → **204**;
  re-GET confirms `true`. (Round-trip the whole object; do not send
  only the one field.)
- In the dashboard this is Users → friend's name → Permissions →
  "Allow remote control of other users" → Save.
- **Honest warning:** this permission is not view-only — it also lets
  that user pause/play the other user's session from Jellyfin's remote
  control. The app should say so when asking for it.
- Without the permission, `POST /Sessions/{sessionId}/Message` from the
  friend to the admin's session returns **403** (§7).

### 6f. How to tell "who is listening to the same song right now"

Match on the session's `NowPlayingItem` (same song id as the track the
local user is playing) — and, for the Friends sheet, show
`NowPlayingItem.Name` / artists plus `PlayState.PositionTicks` as the
progress bar. **UNVERIFIED (see §10):** in this test nothing was ever
actually playing, so every session returned `"NowPlayingItem": null`;
the field names are from the Jellyfin `SessionInfo` schema / the
MusicLook code, not from a populated live response.

## 7. Send an emoji — POST /Sessions/{sessionId}/Message

```
POST /Sessions/{sessionId}/Message
Authorization: MediaBrowser Token="<sender-token>", …
Content-Type: application/json

{"Text": "❤️", "Header": "MusicLook", "TimeoutMs": 5000}
```

- After the permission (§6e), Friend → Oniel's session:
  → **204**, empty body. **Verified.**
- Admin → Friend's session: → **204**, empty body. **Verified.**
- Before the permission, Friend → Oniel's session:
  → **403**, body `Error processing request.` **Verified.**
- `{sessionId}` is the **target** session's `Id` from §6
  (`3093ebaa…` for Oniel, `2085577e…` for Friend), not a user id.
- `Header: "MusicLook"` is what the receiving client displays as the
  sender label; `TimeoutMs: 5000` is how long the toast shows.
  `Text` is the single emoji (❤️ 👍 😊 🔥 🎵 👏).
- Delivery is best-effort: 204 means the server accepted the message
  for the session, not that the other device displayed it — the target
  must be connected (open WebSocket) to receive it.

## 8. Mark played — POST /UserPlayedItems/{itemId}

```
POST /UserPlayedItems/{itemId}?userId={userId}&datePlayed={ISO-8601}
```

No body. (`datePlayed` must be URL-encoded: `:` → `%3A`.)

Verified live on "Synced Song" (`1db6e0…`), user Oniel:

| Step | Call | Status | Read-back (`GET /Users/{userId}/Items/{itemId}` → `UserData`) |
|---|---|---|---|
| Before | — | — | `PlayCount: 0`, `LastPlayedDate` absent, `Played: false` |
| 1st | `datePlayed=2026-01-15T12%3A00%3A00.0000000Z` | **200** | `PlayCount: 1`, `LastPlayedDate: "2026-01-15T12:00:00.0000000Z"` |
| 2nd | `datePlayed=2026-03-20T18%3A30%3A00.0000000Z` | **200** | `PlayCount: 2`, `LastPlayedDate: "2026-03-20T18:30:00.0000000Z"` |

- `LastPlayedDate` comes back **exactly** the date sent (to the tick),
  and `PlayCount` increments by 1 per call — so the Spotify-import
  "one play at a time, oldest first" approach works, and re-running an
  import **would double-count** (keep the resume checkpoint the editor
  already uses).
- The POST itself returns **200** with an **empty body** (not 204).
- The same values are visible in the list read
  (`GET /Users/{userId}/Items?IncludeItemTypes=Audio&Fields=UserData`):
  Synced Song `PlayCount: 2`, `LastPlayedDate: 2026-03-20…`; the other
  tracks stayed `PlayCount: 0` with no `LastPlayedDate` key.
- No library rescan is needed after marking played.

## 9. Users — GET /Users and GET /Users/{id}

```
GET /Users
→ 200, array of user objects (same shape as the "User" object in §1,
  including full "Policy" and "Configuration" per user).
  Verified: 2 users — Oniel (IsAdministrator: true) and Friend (false).
  Use this for the Friends sheet roster.

GET /Users/{userId}
→ 200, single user object with .Policy.
  Policy keys include: IsAdministrator, IsDisabled, IsHidden,
  EnableRemoteControlOfOtherUsers, EnableMediaPlayback,
  EnableAudioPlaybackTranscoding, EnableLyricManagement,
  EnableCollectionManagement, EnableAllFolders, EnabledFolders,
  EnableAllDevices, EnabledDevices, MaxActiveSessions,
  InvalidLoginAttemptCount, LoginAttemptsBeforeLockout, … (full list in
  test evidence, user-oniel.json).
  Use .Policy.IsAdministrator to pick the §6a vs §6c branch.
```

- `GET /Users/{id}/Policy` → **405** (does not exist in 12.2.0).
  Read policy via `GET /Users/{id}` → `.Policy`; write via
  `POST /Users/{id}/Policy` with the full policy object (§6e).
- Creating a user (admin token):
  `POST /Users/New` `{"Name":"Friend","Password":"testpass2"}`
  → **200**, returns the created user object
  (`Id: d01d454b…`, `Policy.IsAdministrator: false`).

---

## 10. UNVERIFIED — not tested live in this run

- **Populated `NowPlayingItem` / `PlayState` on a session** (what song a
  friend is playing, `PositionTicks` for the progress bar). No real
  playback happened in this test, so `NowPlayingItem` was `null` in
  every session read. The field names come from Jellyfin's `SessionInfo`
  schema and the MusicLook code — verify against a real playing session
  before relying on them (play a track in jellyfin-web with two users
  and re-read `GET /Sessions`).
- **Playback reporting** (`POST /Sessions/Playing`,
  `/Sessions/Playing/Progress`, `/Sessions/Playing/Stopped`) — out of
  scope for this run; needed if the app itself reports "now playing"
  so other users can see it. The MusicLook web client reports these;
  the native app must do the same or its own session will show
  `NowPlayingItem: null` to everyone else.
- **Non-MP3 sources** (FLAC/OGG) through `/Audio/{id}/universal` —
  expect a transcode (no `Content-Length`, no seeking), but no FLAC was
  in the test library.
- **Image auth on a hardened server** — images were public on the test
  server's defaults; a server with different settings may require the
  token. Always send `api_key=<token>` (§3).
- **Anything on a real phone / real server** — all of the above is a
  local throwaway server on `127.0.0.1`. The user's real server is the
  same Jellyfin 12.2.0, but network, reverse-proxy and Tailscale setups
  can change behaviour (e.g. WebSocket upgrades through a proxy).
- **Jellyfin versions other than 12.2.0** — the auth-header rules (§1)
  and the missing `GET /Users/{id}/Policy` are 12.x behaviours; older
  servers differ (e.g. `X-Emby-Authorization` worked on 10.x).

## Surprises worth knowing (all verified live)

1. **Only the `Authorization: MediaBrowser Token="…"` header
   authenticates.** `X-MediaBrowser-Token` and `?api_key=` both 401;
   `X-Emby-Authorization` 400s on login. This is the single most
   likely thing to get wrong.
2. **Images are public** on default settings — no token needed at all.
3. **`/Audio/{id}/stream.mp3` transcodes** (128 kbps, no length, no
   seeking) while **`/Audio/{id}/universal?AudioCodec=mp3&Container=mp3`
   direct-streams** the original file byte-for-byte.
4. **Track images don't exist** — `GET /Items/{audioId}/Images/Primary`
   404s; use the album's image.
5. **Lyrics `Metadata` is `{}`** — no `IsSynced` flag in 12.2.0; compute
   "synced" from the timestamps (≥ 2 distinct `Start` values). Plain
   lyrics end with a phantom `{"Text": ""}` line.
6. **Presence needs three things at once**: open WebSocket (with
   `ApiKey=`, not `api_key=`), `POST /Sessions/Capabilities/Full`
   (the query-param capabilities call is not enough), **and** the
   "Allow remote control of other users" permission. Before the
   permission, the controllable query returns **0 sessions**, and
   messaging returns **403**.
7. **`PremiereDate` collapses to 1 January** of the tagged year
   (`date=2026-10-15` → `2026-01-01`); `DateCreated` only appears when
   requested via `Fields=`.
8. **A fresh server 503s for ~25 s** after `/health` says 200 — poll a
   real API route for readiness.
