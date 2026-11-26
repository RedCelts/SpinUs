"""Fills in missing artwork (Deezer's public API) and lyrics (LRCLIB).

After each library scan, every album without a cover, every artist without
a photo, every loose song (no album) without an embedded cover, and every
song without lyrics is looked up once. Hits are saved (JPEG / .lrc) in the data folder (the music
folder is never written to). Misses are remembered and retried after
RETRY_DAYS, so new releases that Deezer adds later still get picked up.
"""
from __future__ import annotations

import json
import logging
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from .library import Album, Artist, Library, Track, file_tag, fold

log = logging.getLogger("spinus.fetch")

API = "https://api.deezer.com"
LRCLIB = "https://lrclib.net/api"
RETRY_DAYS = 14
# Deezer allows 50 requests / 5 s; stay well under it.
REQUEST_GAP = 0.25


def norm(text: str) -> str:
    """Name key for matching: accent-free, no brackets ("(Deluxe)"), no punctuation."""
    t = fold(text)
    t = re.sub(r"[\(\[].*?[\)\]]", " ", t)
    t = re.sub(r"\b(feat|ft|featuring)\b.*", " ", t)
    return re.sub(r"[^\w]+", " ", t).strip()


def placeholder(url: str | None) -> bool:
    # Deezer's "no picture" images have an empty hash: /images/artist//...
    return not url or "/images/artist//" in url or "/images/cover//" in url


def default_get(url: str, timeout: float = 10) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "SpinUs-server/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


class ArtworkFetcher:
    def __init__(self, library: Library, folder: Path, http_get=default_get,
                 artwork: bool = True, lyrics: bool = True):
        self.library = library
        self.do_artwork = artwork
        self.do_lyrics = lyrics
        self.folder = folder
        self.folder.mkdir(parents=True, exist_ok=True)
        self.get = http_get
        self.running = False
        self.last_run: dict = {}
        # Live progress while a pass runs (shown on the web page).
        self.progress: dict = {}
        self._last_request = 0.0

    # ---- files

    def path(self, kind: str, item_id: str) -> Path:
        return self.folder / f"{kind}-{item_id}.{'lrc' if kind == 'lyrics' else 'jpg'}"

    def _miss(self, kind: str, item_id: str) -> Path:
        return self.folder / f"{kind}-{item_id}.none"

    def _recent_miss(self, kind: str, item_id: str) -> bool:
        m = self._miss(kind, item_id)
        return m.exists() and time.time() - m.stat().st_mtime < RETRY_DAYS * 86400

    def apply_existing(self):
        """Give items whose art was fetched earlier their image tags (after a scan)."""
        idx = self.library.index
        for alb in idx.albums.values():
            if alb.image_tag is None and self.path("album", alb.id).exists():
                alb.image_tag = file_tag(self.path("album", alb.id))
        for art in idx.artists.values():
            if art.image_tag is None and self.path("artist", art.id).exists():
                art.image_tag = file_tag(self.path("artist", art.id))
        for t in idx.tracks.values():
            if t.album_id is None and t.image_tag is None and self.path("track", t.id).exists():
                t.image_tag = file_tag(self.path("track", t.id))
            if not t.has_lyrics and self.path("lyrics", t.id).exists():
                t.has_lyrics = True

    # ---- network

    def _json(self, path: str, query: str) -> list[dict]:
        self._pace()
        body = self.get(f"{API}{path}?limit=10&q={urllib.parse.quote(query)}")
        data = json.loads(body)
        if "error" in data:
            raise IOError(str(data["error"]))
        return data.get("data", [])

    def _save(self, kind: str, item_id: str, url: str) -> Path | None:
        try:
            data = self.get(url, timeout=20)
        except Exception as e:
            log.info("download failed for %s %s: %s", kind, item_id, e)
            return None
        if len(data) < 1000:
            return None
        p = self.path(kind, item_id)
        tmp = p.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(p)
        self._miss(kind, item_id).unlink(missing_ok=True)
        return p

    # ---- lookups (each returns an image URL or None)

    def find_album(self, alb: Album) -> str | None:
        want_artist, want_title = norm(alb.artist), norm(alb.name)
        queries = [f'artist:"{alb.artist}" album:"{alb.name}"', f"{alb.artist} {alb.name}"]
        for q in queries:
            hits = self._json("/search/album", q)
            for h in hits:
                title, artist = norm(h.get("title", "")), norm((h.get("artist") or {}).get("name", ""))
                if artist == want_artist and (title == want_title or title.startswith(want_title) or want_title.startswith(title)):
                    url = h.get("cover_xl") or h.get("cover_big")
                    if not placeholder(url):
                        return url
        return None

    def find_artist(self, art: Artist) -> str | None:
        want = norm(art.name)
        for h in self._json("/search/artist", art.name):
            if norm(h.get("name", "")) == want:
                url = h.get("picture_xl") or h.get("picture_big")
                return None if placeholder(url) else url
        return None

    def find_track(self, t: Track) -> str | None:
        artist = t.artists[0] if t.artists else t.album_artist
        want_artist, want_title = norm(artist), norm(t.title)
        for q in (f'artist:"{artist}" track:"{t.title}"', f"{artist} {t.title}"):
            for h in self._json("/search/track", q):
                title = norm(h.get("title", ""))
                a = norm((h.get("artist") or {}).get("name", ""))
                if a == want_artist and (title == want_title or title.startswith(want_title)):
                    url = (h.get("album") or {}).get("cover_xl") or (h.get("album") or {}).get("cover_big")
                    if not placeholder(url):
                        return url
        return None

    def find_lyrics(self, t: Track) -> str | None:
        """LRC text (synced when LRCLIB has it, else plain), or None."""
        artist = t.artists[0] if t.artists else t.album_artist
        params = {"artist_name": artist, "track_name": t.title}
        if t.album:
            params["album_name"] = t.album
        if t.duration_ms:
            params["duration"] = str(round(t.duration_ms / 1000))
        self._pace()
        try:
            hit = json.loads(self.get(f"{LRCLIB}/get?{urllib.parse.urlencode(params)}"))
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            hit = None
        if not hit or not (hit.get("syncedLyrics") or hit.get("plainLyrics")):
            # Exact match failed (album / length differ): search, and accept a
            # same-artist, same-title result within 5 s of our length.
            self._pace()
            q = urllib.parse.urlencode({"artist_name": artist, "track_name": t.title})
            results = json.loads(self.get(f"{LRCLIB}/search?{q}")) or []
            want_a, want_t = norm(artist), norm(t.title)
            hit = None
            for r in results:
                if norm(r.get("artistName", "")) != want_a or norm(r.get("trackName", "")) != want_t:
                    continue
                if t.duration_ms and r.get("duration") and abs(r["duration"] - t.duration_ms / 1000) > 5:
                    continue
                if r.get("syncedLyrics") or r.get("plainLyrics"):
                    hit = r
                    if r.get("syncedLyrics"):
                        break
        if not hit:
            return None
        return hit.get("syncedLyrics") or hit.get("plainLyrics")

    def _save_text(self, kind: str, item_id: str, text: str) -> Path:
        p = self.path(kind, item_id)
        tmp = p.with_suffix(".tmp")
        tmp.write_text(text, "utf-8")
        tmp.replace(p)
        self._miss(kind, item_id).unlink(missing_ok=True)
        return p

    def _pace(self):
        wait = REQUEST_GAP - (time.time() - self._last_request)
        if wait > 0:
            time.sleep(wait)
        self._last_request = time.time()

    # ---- the pass

    def todo(self) -> list[tuple[str, object]]:
        idx = self.library.index
        jobs: list[tuple[str, object]] = []
        if self.do_lyrics:
            for t in idx.tracks.values():
                if not t.has_lyrics and not self._recent_miss("lyrics", t.id):
                    jobs.append(("lyrics", t))
        if not self.do_artwork:
            return jobs
        for alb in idx.albums.values():
            if alb.image_tag is None and not self._recent_miss("album", alb.id):
                jobs.append(("album", alb))
        for art in idx.artists.values():
            if art.image_tag is None and not self._recent_miss("artist", art.id):
                jobs.append(("artist", art))
        for t in idx.tracks.values():
            if t.album_id is None and t.image_tag is None and not self._recent_miss("track", t.id):
                jobs.append(("track", t))
        return jobs

    def run(self) -> dict:
        """Look up everything missing. Blocking; run in a worker thread."""
        if self.running:
            return {"status": "already-running"}
        self.running = True
        found = missed = failed = 0
        started = time.time()
        try:
            self.apply_existing()
            jobs = self.todo()
            if jobs:
                log.info("artwork: looking up %d items", len(jobs))
            kinds = {k: sum(1 for kk, _ in jobs if kk == k) for k in ("album", "artist", "track", "lyrics")}
            self.progress = {"done": 0, "total": len(jobs), "found": 0, "current": None,
                             "byKind": {k: {"done": 0, "total": n} for k, n in kinds.items() if n}}
            for kind, item in jobs:
                name = getattr(item, "name", None) or getattr(item, "title", "")
                self.progress["current"] = {"kind": kind, "name": name}
                try:
                    if kind == "lyrics":
                        text = self.find_lyrics(item)
                        p = self._save_text(kind, item.id, text) if text else None
                    else:
                        finder = {"album": self.find_album, "artist": self.find_artist, "track": self.find_track}[kind]
                        url = finder(item)
                        p = self._save(kind, item.id, url) if url else None
                except Exception as e:
                    # Offline / rate-limited: stop and try again next pass.
                    log.info("%s lookup failed (%s); will retry later", kind, e)
                    failed += 1
                    self.progress["done"] += 1
                    self.progress["byKind"][kind]["done"] += 1
                    if failed >= 10:
                        break
                    continue
                self.progress["done"] += 1
                self.progress["byKind"][kind]["done"] += 1
                if p:
                    self.progress["found"] += 1
                    if kind == "lyrics":
                        item.has_lyrics = True
                    else:
                        item.image_tag = file_tag(p)
                    found += 1
                else:
                    self._miss(kind, item.id).touch()
                    missed += 1
            self.last_run = {"found": found, "notFound": missed, "errors": failed,
                             "seconds": round(time.time() - started, 1)}
            if jobs:
                log.info("artwork: %s", self.last_run)
            return self.last_run
        finally:
            self.running = False
            self.progress = {}
