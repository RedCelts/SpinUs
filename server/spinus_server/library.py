"""Music library: scans a folder of audio files into an in-memory index.

Tags are read with mutagen. Every scan is incremental: files whose size
and mtime have not changed reuse the metadata cached in the database, so
a rescan of a large library only reads the files that changed.

Ids are stable hashes (of the file's path relative to the music root for
tracks, of the names for albums, artists and genres), so favorites,
playlists and play counts survive rescans and server restarts.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import threading
import time
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

import mutagen

log = logging.getLogger("spinus.library")

AUDIO_EXTS = {
    ".mp3", ".flac", ".m4a", ".m4b", ".mp4", ".aac", ".ogg", ".oga", ".opus",
    ".wav", ".webm", ".wma", ".aif", ".aiff", ".ape", ".wv", ".alac",
}
# Played as-is by ExoPlayer on Android 8+. Everything else (ALAC, WMA,
# AIFF, APE, WavPack) is transcoded to MP3 on the fly with ffmpeg.
DIRECT_EXTS = {".mp3", ".flac", ".m4a", ".m4b", ".mp4", ".aac", ".ogg", ".oga", ".opus", ".wav", ".webm"}
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")
ALBUM_COVER_NAMES = ("cover", "folder", "front", "album", "albumart")
ARTIST_IMAGE_NAMES = ("artist", "folder", "poster", "thumb")
BACKDROP_NAMES = ("fanart", "backdrop", "background")

SCAN_CACHE_VERSION = 2


def make_id(kind: str, value: str) -> str:
    return hashlib.sha1(f"{kind}:{value}".encode("utf-8")).hexdigest()[:24]


def fold(text: str) -> str:
    """Lower-case, accent-free text for search and name matching."""
    norm = unicodedata.normalize("NFKD", text or "")
    return "".join(c for c in norm if not unicodedata.combining(c)).casefold().strip()


def file_tag(path: Path) -> str | None:
    try:
        st = path.stat()
    except OSError:
        return None
    return hashlib.sha1(f"{path}|{st.st_mtime_ns}|{st.st_size}".encode()).hexdigest()[:16]


# ---------------------------------------------------------------- tag reading

def _first(value):
    if value is None:
        return None
    if isinstance(value, (list, tuple)):
        for v in value:
            s = _first(v)
            if s:
                return s
        return None
    s = str(value).strip()
    return s or None


def _all(value) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, (list, tuple)):
        value = [value]
    out: list[str] = []
    for v in value:
        if v is None:
            continue
        for part in re.split(r"\s*;\s*|\x00", str(v)):
            part = part.strip()
            if part and part not in out:
                out.append(part)
    return out


def _num(value) -> int | None:
    if value is None:
        return None
    if isinstance(value, (list, tuple)):
        if not value:
            return None
        value = value[0]
    if isinstance(value, tuple):  # MP4 trkn/disk: (n, total)
        value = value[0]
    m = re.match(r"\s*(\d+)", str(value))
    if not m:
        return None
    n = int(m.group(1))
    return n or None


def _year(date: str | None) -> int | None:
    if not date:
        return None
    m = re.match(r"\s*(\d{4})", date)
    return int(m.group(1)) if m else None


def read_tags(path: Path) -> dict | None:
    """Tags + duration for one audio file, or None when it can't be read."""
    try:
        f = mutagen.File(path)
    except Exception as e:  # corrupt / unsupported
        log.debug("unreadable %s: %s", path, e)
        return None
    if f is None:
        return None
    tags = f.tags or {}
    kind = type(f).__name__  # MP3, FLAC, MP4, OggVorbis, OggOpus, ASF, WAVE, ...

    def get(*keys):
        for k in keys:
            try:
                if k in tags:
                    v = tags[k]
                    if hasattr(v, "text"):
                        v = v.text
                    elif hasattr(v, "value"):
                        v = v.value
                    if isinstance(v, list) and v and hasattr(v[0], "value"):
                        v = [x.value for x in v]
                    return v
            except (KeyError, ValueError, TypeError):
                continue
        return None

    keys = {
        "title": ("TIT2", "\xa9nam", "title", "TITLE", "Title"),
        "artist": ("TPE1", "\xa9ART", "artist", "ARTIST", "Author"),
        "albumartist": ("TPE2", "aART", "albumartist", "album artist", "ALBUMARTIST", "WM/AlbumArtist"),
        "album": ("TALB", "\xa9alb", "album", "ALBUM", "WM/AlbumTitle"),
        "track": ("TRCK", "trkn", "tracknumber", "TRACKNUMBER", "WM/TrackNumber"),
        "disc": ("TPOS", "disk", "discnumber", "DISCNUMBER", "WM/PartOfSet"),
        "genre": ("TCON", "\xa9gen", "genre", "GENRE", "WM/Genre"),
        "date": ("TDRC", "TYER", "\xa9day", "date", "DATE", "year", "originaldate", "WM/Year"),
        "label": ("TPUB", "label", "LABEL", "organization", "WM/Publisher"),
    }
    raw = {k: get(*v) for k, v in keys.items()}

    lyrics = None
    if hasattr(tags, "getall"):  # ID3
        for frame in tags.getall("USLT"):
            if str(frame.text).strip():
                lyrics = str(frame.text)
                break
    if lyrics is None:
        lyrics = _first(get("\xa9lyr", "lyrics", "LYRICS", "unsyncedlyrics", "UNSYNCEDLYRICS", "WM/Lyrics"))

    has_art = False
    try:
        if hasattr(tags, "getall"):
            has_art = bool(tags.getall("APIC"))
        elif "covr" in tags:
            has_art = True
        elif getattr(f, "pictures", None):
            has_art = True
        elif "metadata_block_picture" in tags or "WM/Picture" in tags:
            has_art = True
    except Exception:
        pass

    codec = getattr(f.info, "codec", None)
    artists = _all(raw["artist"])
    date = _first(raw["date"])
    return {
        "v": SCAN_CACHE_VERSION,
        "title": _first(raw["title"]) or path.stem,
        "artists": artists,
        "albumArtist": _first(raw["albumartist"]),
        "album": _first(raw["album"]),
        "track": _num(raw["track"]),
        "disc": _num(raw["disc"]),
        "genres": _all(raw["genre"]),
        "date": date,
        "year": _year(date),
        "label": _first(raw["label"]),
        "durationMs": int(round((getattr(f.info, "length", 0) or 0) * 1000)),
        "bitrate": getattr(f.info, "bitrate", None),
        "lyrics": lyrics,
        "embeddedArt": has_art,
        "kind": kind,
        "codec": str(codec) if codec else None,
    }


def embedded_picture(path: Path) -> bytes | None:
    """Raw bytes of a file's embedded cover, preferring the front cover."""
    try:
        f = mutagen.File(path)
    except Exception:
        return None
    if f is None or f.tags is None:
        return None
    tags = f.tags
    try:
        if hasattr(tags, "getall"):
            frames = tags.getall("APIC")
            frames.sort(key=lambda p: 0 if getattr(p, "type", 0) == 3 else 1)
            return frames[0].data if frames else None
        if "covr" in tags and tags["covr"]:
            return bytes(tags["covr"][0])
        pics = getattr(f, "pictures", None)
        if pics:
            pics = sorted(pics, key=lambda p: 0 if p.type == 3 else 1)
            return pics[0].data
        if "metadata_block_picture" in tags:
            import base64
            from mutagen.flac import Picture
            pic = Picture(base64.b64decode(tags["metadata_block_picture"][0]))
            return pic.data
        if "WM/Picture" in tags:
            value = tags["WM/Picture"][0].value
            # ASF picture: type(1) size(4) mime(utf16z) desc(utf16z) data
            i = 5
            for _ in range(2):
                while value[i:i + 2] != b"\x00\x00":
                    i += 2
                i += 2
            return value[i:]
    except Exception as e:
        log.debug("no embedded art in %s: %s", path, e)
    return None


# ---------------------------------------------------------------- lyrics

_LRC_TIME = re.compile(r"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")
_LRC_META = re.compile(r"^\[(ar|ti|al|au|by|length|offset|re|ve|#)\s*:.*\]$", re.I)


def parse_lyrics(text: str) -> list[dict]:
    """LRC or plain text -> [{text, startMs?}], synced lines sorted by time."""
    offset = 0
    m = re.search(r"\[offset\s*:\s*([+-]?\d+)\]", text, re.I)
    if m:
        offset = int(m.group(1))
    synced: list[dict] = []
    plain: list[dict] = []
    for raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw.strip()
        if _LRC_META.match(line):
            continue
        stamps = list(_LRC_TIME.finditer(line))
        if stamps:
            words = _LRC_TIME.sub("", line).strip()
            for s in stamps:
                frac = s.group(3) or "0"
                ms = int(frac.ljust(3, "0")[:3])
                start = (int(s.group(1)) * 60 + int(s.group(2))) * 1000 + ms - offset
                synced.append({"text": words, "startMs": max(0, start)})
        else:
            plain.append({"text": line})
    if synced:
        synced.sort(key=lambda x: x["startMs"])
        return synced
    while plain and not plain[0]["text"]:
        plain.pop(0)
    while plain and not plain[-1]["text"]:
        plain.pop()
    return plain


# ---------------------------------------------------------------- index

@dataclass
class Track:
    id: str
    path: Path
    title: str
    artists: list[str]
    artist_ids: list[str]
    album: str | None
    album_id: str | None
    album_artist: str
    album_artist_id: str | None
    duration_ms: int
    track: int | None
    disc: int | None
    genres: list[str]
    year: int | None
    date: str | None
    added: str
    direct: bool
    has_lyrics: bool
    folded: str = ""
    image_tag: str | None = None  # loose songs only: embedded or fetched cover


@dataclass
class Album:
    id: str
    name: str
    artist: str
    artist_id: str | None
    year: int | None
    date: str | None
    added: str
    folder: Path | None
    track_ids: list[str] = field(default_factory=list)
    genres: list[str] = field(default_factory=list)
    label: str | None = None
    cover_file: Path | None = None
    cover_track: Path | None = None
    image_tag: str | None = None
    folded: str = ""


@dataclass
class Artist:
    id: str
    name: str
    track_ids: list[str] = field(default_factory=list)
    album_ids: list[str] = field(default_factory=list)
    folder: Path | None = None
    image_file: Path | None = None
    backdrop_files: list[Path] = field(default_factory=list)
    image_tag: str | None = None
    folded: str = ""


@dataclass
class Genre:
    id: str
    name: str
    track_ids: list[str] = field(default_factory=list)
    album_ids: list[str] = field(default_factory=list)


class Index:
    """One immutable snapshot of the library. Rebuilt by each scan."""

    def __init__(self):
        self.tracks: dict[str, Track] = {}
        self.albums: dict[str, Album] = {}
        self.artists: dict[str, Artist] = {}
        self.genres: dict[str, Genre] = {}
        self.artist_by_name: dict[str, str] = {}
        self.built_at = time.time()


def _find_image(folder: Path, names: tuple[str, ...], allow_any: bool = False) -> Path | None:
    try:
        entries = {p.name.lower(): p for p in folder.iterdir() if p.is_file()}
    except OSError:
        return None
    for n in names:
        for ext in IMAGE_EXTS:
            p = entries.get(n + ext)
            if p:
                return p
    if allow_any:
        for name in sorted(entries):
            if name.endswith(IMAGE_EXTS):
                return entries[name]
    return None


def _find_all(folder: Path, names: tuple[str, ...]) -> list[Path]:
    out = []
    try:
        entries = sorted(p for p in folder.iterdir() if p.is_file())
    except OSError:
        return out
    for p in entries:
        stem, ext = os.path.splitext(p.name.lower())
        if ext in IMAGE_EXTS and any(stem == n or re.fullmatch(rf"{n}\d+", stem) for n in names):
            out.append(p)
    return out


def _iso(ts: float) -> str:
    ms = int((ts % 1) * 1000)
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(ts)) + f".{ms:03d}0000Z"


class Library:
    def __init__(self, root: Path, db, fetched_dir: Path | None = None):
        self.root = Path(root)
        self.db = db
        # Artwork / lyrics downloaded by artwork.ArtworkFetcher.
        self.fetched_dir = fetched_dir
        self.index = Index()
        self._lock = threading.Lock()
        self.scanning = False
        self.last_scan: dict = {}

    # The database keeps the per-file tag cache and the first time each
    # file was seen (= "date added", which survives rescans).

    def scan(self) -> dict:
        if not self._lock.acquire(blocking=False):
            return {"status": "already-scanning"}
        self.scanning = True
        started = time.time()
        read = reused = failed = 0
        try:
            cached = self.db.scan_cache()
            seen: dict[str, tuple] = {}
            new_rows = []
            for dirpath, dirnames, filenames in os.walk(self.root, followlinks=True):
                dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
                for name in sorted(filenames):
                    ext = os.path.splitext(name)[1].lower()
                    if ext not in AUDIO_EXTS or name.startswith("._"):
                        continue
                    path = Path(dirpath) / name
                    rel = path.relative_to(self.root).as_posix()
                    try:
                        st = path.stat()
                    except OSError:
                        continue
                    hit = cached.get(rel)
                    if hit and hit[0] == st.st_mtime_ns and hit[1] == st.st_size and hit[2].get("v") == SCAN_CACHE_VERSION:
                        meta, first_seen = hit[2], hit[3]
                        reused += 1
                    else:
                        meta = read_tags(path)
                        if meta is None:
                            failed += 1
                            continue
                        first_seen = hit[3] if hit else time.time()
                        new_rows.append((rel, st.st_mtime_ns, st.st_size, json.dumps(meta), first_seen))
                        read += 1
                    seen[rel] = (path, meta, first_seen)
            if not seen and cached:
                # Drive unmounted / folder empty: keep what we had rather
                # than forgetting every "date added".
                log.warning("scan found no music in %s; keeping the previous library", self.root)
                self.last_scan = {"status": "empty", "finishedAt": _iso(time.time())}
                return self.last_scan
            self.db.save_scan(new_rows, keep=set(seen))
            self.index = self._build(seen)
            self.last_scan = {
                "status": "ok", "tracks": len(self.index.tracks), "albums": len(self.index.albums),
                "artists": len(self.index.artists), "read": read, "reused": reused,
                "unreadable": failed, "seconds": round(time.time() - started, 2),
                "finishedAt": _iso(time.time()),
            }
            log.info("scan: %s", self.last_scan)
            return self.last_scan
        finally:
            self.scanning = False
            self._lock.release()

    def _build(self, seen: dict) -> Index:
        idx = Index()

        def artist(name: str) -> Artist:
            key = fold(name)
            aid = idx.artist_by_name.get(key)
            if aid is None:
                aid = make_id("artist", key)
                idx.artist_by_name[key] = aid
                idx.artists[aid] = Artist(id=aid, name=name, folded=key)
            return idx.artists[aid]

        for rel, (path, m, first_seen) in seen.items():
            artists = m["artists"] or ([m["albumArtist"]] if m["albumArtist"] else ["Unknown Artist"])
            album_artist = m["albumArtist"] or artists[0]
            artist_objs = [artist(a) for a in artists]
            aa = artist(album_artist)
            tid = make_id("track", rel)
            album_id = None
            if m["album"]:
                album_id = make_id("album", fold(album_artist) + "\x00" + fold(m["album"]))
                alb = idx.albums.get(album_id)
                if alb is None:
                    alb = Album(id=album_id, name=m["album"], artist=album_artist, artist_id=aa.id,
                                year=m["year"], date=m["date"], added=_iso(first_seen),
                                folder=path.parent, folded=fold(m["album"]))
                    idx.albums[album_id] = alb
                alb.track_ids.append(tid)
                alb.added = min(alb.added, _iso(first_seen))
                if alb.year is None and m["year"]:
                    alb.year, alb.date = m["year"], m["date"]
                for g in m["genres"]:
                    if g not in alb.genres:
                        alb.genres.append(g)
                alb.label = alb.label or m.get("label")
                if alb.cover_track is None and m.get("embeddedArt"):
                    alb.cover_track = path
                if album_id not in aa.album_ids:
                    aa.album_ids.append(album_id)
            has_lyrics = bool(m.get("lyrics")) or path.with_suffix(".lrc").exists() or path.with_suffix(".txt").exists()
            t = Track(
                id=tid, path=path, title=m["title"], artists=artists,
                artist_ids=[a.id for a in artist_objs], album=m["album"], album_id=album_id,
                album_artist=album_artist, album_artist_id=aa.id, duration_ms=m["durationMs"],
                track=m["track"], disc=m["disc"], genres=m["genres"], year=m["year"],
                date=m["date"], added=_iso(first_seen),
                direct=path.suffix.lower() in DIRECT_EXTS and (m.get("codec") or "").lower() != "alac",
                has_lyrics=has_lyrics,
                folded=" ".join(fold(x) for x in [m["title"], *artists, m["album"] or ""]),
            )
            if album_id is None and m.get("embeddedArt"):
                t.image_tag = file_tag(path)
            idx.tracks[tid] = t
            for a in artist_objs:
                a.track_ids.append(tid)
            if aa not in artist_objs:
                aa.track_ids.append(tid)
            for g in m["genres"]:
                gid = make_id("genre", fold(g))
                gen = idx.genres.setdefault(gid, Genre(id=gid, name=g))
                gen.track_ids.append(tid)
                if album_id and album_id not in gen.album_ids:
                    gen.album_ids.append(album_id)

        # Album folders: the folder its tracks live in (when they share one).
        for alb in idx.albums.values():
            folders = {idx.tracks[t].path.parent for t in alb.track_ids}
            if len(folders) > 1:
                # Multi-disc layouts (Album/CD1, Album/CD2): use the parent.
                parents = {f.parent for f in folders}
                alb.folder = parents.pop() if len(parents) == 1 else None
            if alb.folder is not None:
                alb.cover_file = _find_image(alb.folder, ALBUM_COVER_NAMES, allow_any=True)
                if alb.cover_file is None:
                    for sub in folders:
                        alb.cover_file = _find_image(sub, ALBUM_COVER_NAMES, allow_any=True)
                        if alb.cover_file:
                            break
            src = alb.cover_file or alb.cover_track
            alb.image_tag = file_tag(src) if src else None
            alb.track_ids.sort(key=lambda t: (idx.tracks[t].disc or 1, idx.tracks[t].track or 9999, fold(idx.tracks[t].title)))

        # Artist folders: Lidarr's layout is Music/<Artist>/<Album>/<track>,
        # so the artist folder is the shared parent of the album folders.
        root = self.root.resolve()
        for art in idx.artists.values():
            parents = {idx.albums[a].folder.parent for a in art.album_ids
                       if idx.albums[a].folder is not None and idx.albums[a].artist_id == art.id}
            if len(parents) == 1:
                folder = parents.pop()
                if folder.resolve() != root and root in folder.resolve().parents:
                    art.folder = folder
                    art.image_file = _find_image(folder, ARTIST_IMAGE_NAMES)
                    art.backdrop_files = _find_all(folder, BACKDROP_NAMES)
            art.image_tag = file_tag(art.image_file) if art.image_file else None
        return idx

    # ---- reads used by the API

    def nfo_text(self, folder: Path | None, filename: str, tags: tuple[str, ...]) -> str | None:
        """Biography / review from Kodi-style .nfo files (Lidarr can write these)."""
        if folder is None:
            return None
        p = folder / filename
        if not p.is_file():
            return None
        try:
            text = p.read_text("utf-8", errors="replace")
        except OSError:
            return None
        for tag in tags:
            m = re.search(rf"<{tag}>(.*?)</{tag}>", text, re.S | re.I)
            if m and m.group(1).strip():
                import html
                return html.unescape(re.sub(r"<!\[CDATA\[(.*?)\]\]>", r"\1", m.group(1), flags=re.S)).strip()
        return None

    def lyrics_for(self, track: Track) -> list[dict] | None:
        for ext in (".lrc", ".txt"):
            p = track.path.with_suffix(ext)
            if p.is_file():
                try:
                    lines = parse_lyrics(p.read_text("utf-8", errors="replace"))
                except OSError:
                    lines = []
                if lines:
                    return lines
        rel = track.path.relative_to(self.root).as_posix()
        meta = self.db.cached_meta(rel)
        if meta and meta.get("lyrics"):
            lines = parse_lyrics(meta["lyrics"])
            if lines:
                return lines
        if self.fetched_dir is not None:
            p = self.fetched_dir / f"lyrics-{track.id}.lrc"
            if p.is_file():
                lines = parse_lyrics(p.read_text("utf-8", errors="replace"))
                if lines:
                    return lines
        return None
