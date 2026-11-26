# SpinUs server API

Base URL `http://<host>:8484`. JSON in and out. Auth:
`Authorization: Bearer <token>`, or `?token=<token>` on stream and image
URLs. Ids stay the same across rescans. Times are milliseconds; dates are
ISO-8601 UTC strings.

| Method & path | What |
|---|---|
| `GET /api/info` | `{server, version, needsSetup}` (no auth) |
| `POST /api/auth/login` | `{username, password, deviceId, deviceName, client, version}` → `{token, sessionId, user:{id,name,isAdmin}}`. The first sign-in on an empty server creates the admin. One token per device. |
| `POST /api/auth/logout` | Revoke this token |
| `GET /api/me` · `POST /api/me/password` | Current user · change password |
| `GET/POST /api/users` · `DELETE /api/users/{name}` | Admin: list / add `{name,password,isAdmin}` / remove |
| `GET /api/library/status` · `POST /api/library/scan` | Scan state · rescan now (admin) |
| `GET /api/albums` | `?sort=name\|added\|played\|year\|random&order=asc\|desc&limit&artistId&genreId&q&ids&favorites=true` |
| `GET /api/albums/{id}/similar` | `?limit` |
| `GET /api/tracks` | `?albumId&artistIds=a,b&genreId&q&ids&favorites=true&sort=album\|name\|added\|playCount\|played\|random&order&limit` |
| `GET /api/artists` | `?q&limit&withImage=true` |
| `GET /api/artists/by-name?name=` | Artist details (404 if none) |
| `GET /api/genres` | All genres |
| `GET /api/items/{id}` | Details of an album / artist / track (overview, genres, label, backdrops, favorite) |
| `GET /api/items/{id}/mix` | Station from a track, album, artist, genre or your own playlist. `?limit` |
| `PUT/DELETE /api/favorites/{id}` | Favorite on / off |
| `POST /api/plays/{trackId}` | `{datePlayed}`: +1 play count |
| `GET/POST /api/playlists` | `?q&limit` / `{name, trackIds}` → `{id}` |
| `GET/POST /api/playlists/{id}/tracks` | Songs in order / append `{trackIds}` |
| `PATCH/DELETE /api/playlists/{id}` | `{name?, removePositions?}` / delete |
| `GET /api/lyrics/{trackId}` | `{synced, lines:[{text, startMs?}]}` or 404 |
| `GET/PUT /api/prefs/{key}` | Per-user JSON document (listening-history sync) |
| `GET /api/stream/{trackId}` | Original file with byte ranges; ALAC/WMA/AIFF/APE → MP3. `?format=mp3&bitrate=128` forces MP3 |
| `GET /api/images/{id}` | `?w=600&type=primary\|backdrop&index=0&tag=` → JPEG (album, artist or track id) |
| `GET /api/socket` | WebSocket. The server sends `{type:"hello",sessionId}` and `{type:"message",header,text,from}` |
| `POST /api/sessions/playing` | `{trackId, positionMs, paused}` |
| `POST /api/sessions/stopped` | `{trackId, positionMs, playedToCompletion}` |
| `GET /api/sessions` | Other people playing right now: `{items:[{sessionId,userId,userName,client,online,positionMs,nowPlaying:{trackId,title,artists,albumId,imageTag,durationMs}}]}` |
| `GET /api/sessions/self` | `{sessionId}` |
| `POST /api/sessions/{id}/message` | `{header, text, timeoutMs}`; 404 if that phone isn't connected |

Track JSON: `id, title, artists, artistIds, album, albumId, albumArtist,
albumArtistId, durationMs, imageTag, trackNumber, discNumber, genres, year,
date, dateAdded, hasLyrics, userData{playCount, lastPlayed, isFavorite}`.

Album JSON: `id, name, artist, artistId, year, date, dateAdded, imageTag,
trackCount, genres, userData` (play data summed over the album's tracks).
