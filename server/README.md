# SpinUs server

The music server for the SpinUs Android app. It reads your music folder
directly (tags, covers, `.lrc` lyrics) and keeps accounts, favorites,
playlists, play counts, listening history and Friends presence in one
SQLite file. Jellyfin isn't needed.

## Run it on the mini PC (CasaOS)

1. CasaOS → **App Store** → **Custom Install** (the + at the top right) → **Import**.
2. Paste the contents of `docker-compose.yml` → **Submit** → **Install**.

It pulls the prebuilt image `ghcr.io/redcelts/spinus-server`, mounts
`/DATA/Media/Music` read-only and keeps its database in
`/DATA/AppData/spinus`. Change those two lines if your folders differ.

Plain Docker instead: `docker compose up -d` next to the file.
To build the image yourself: `docker build -t ghcr.io/redcelts/spinus-server server/`.

Then:

1. Open `http://<server-IP>:8484` in a browser and create the admin account,
   or just sign in from the app: **the first sign-in on a new server
   becomes the admin**.
2. In the app, the server address is the server's IP (`192.168.x.x`) or its
   Tailscale address (`100.x.x.x` / MagicDNS name). Port 8484 is added
   automatically.
3. Add friends' accounts on the web page (People who can sign in).
   Everyone on the server shows up in each other's Friends list.

New music is picked up every 15 minutes, or right away with **Scan for new
music** on the web page.

## Without Docker

```sh
cd server
pip install -r requirements.txt          # Python 3.10+; install ffmpeg too
SPINUS_MUSIC=/DATA/Media/Music SPINUS_DATA=~/.spinus python -m spinus_server
```

Command line helpers: `python -m spinus_server adduser NAME [--admin]`,
`passwd NAME`, `deluser NAME`, `users`, `scan`.

## Settings (environment variables)

| Variable | Default | What it does |
|---|---|---|
| `SPINUS_MUSIC` | `/music` | Music folder (read-only is fine) |
| `SPINUS_DATA` | `/data` | Database and image cache |
| `SPINUS_PORT` | `8484` | Port |
| `SPINUS_RESCAN_MINUTES` | `15` | How often to look for new files (0 = only on start / button) |
| `SPINUS_ARTIST_PHOTOS` | `1` | Fetch missing artist photos from Deezer (cached) |
| `SPINUS_ADMIN_USER` / `SPINUS_ADMIN_PASSWORD` | – | Create the admin on first start instead of on first sign-in |

## What it reads from your files

- **Tags** (MP3, FLAC, M4A/AAC/ALAC, Ogg/Opus, WAV, WMA, AIFF): title, artists
  (multi-value or `;`-separated), album artist, album, track/disc, genre,
  date, label, embedded lyrics and cover.
- **Album covers**: `cover.jpg` / `folder.jpg` / `front.jpg` in the album folder
  (or any image there), else the embedded cover.
- **Artist pictures and backgrounds**: `folder.jpg` / `artist.jpg` and
  `fanart.jpg` / `backdrop.jpg` in the artist folder (Lidarr's
  `Music/Artist/Album/` layout); otherwise a photo from Deezer.
- **Bios / album notes**: `artist.nfo` / `album.nfo` (turn on Kodi metadata in
  Lidarr to get these).
- **Lyrics**: a `.lrc` (synced) or `.txt` file next to the song, or the lyrics tag.

ALAC, WMA, AIFF and APE files are converted to MP3 on the fly (needs
ffmpeg, which the Docker image includes); everything else streams as the
original file, so seeking and crossfade work.

## Tests

```sh
pip install pytest pytest-aiohttp && python -m pytest
```

The API is documented in [`../docs/SERVER-API.md`](../docs/SERVER-API.md).
