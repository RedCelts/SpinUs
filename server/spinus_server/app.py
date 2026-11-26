"""SpinUs server: the HTTP + WebSocket API the SpinUs Android app talks to.

All routes live under /api. Auth is a bearer token from /api/auth/login,
sent as `Authorization: Bearer <token>` (or `?token=` on image and stream
URLs, which the image loader and player fetch directly).
"""
from __future__ import annotations

import asyncio
import collections
import io
import json
import logging
import mimetypes
import os
import random
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path

from aiohttp import web, WSMsgType

from . import __version__
from .db import Database
from .artwork import ArtworkFetcher
from .library import Library, embedded_picture, file_tag, fold

log = logging.getLogger("spinus")

MIME = {
    ".mp3": "audio/mpeg", ".flac": "audio/flac", ".m4a": "audio/mp4", ".m4b": "audio/mp4",
    ".mp4": "audio/mp4", ".aac": "audio/aac", ".ogg": "audio/ogg", ".oga": "audio/ogg",
    ".opus": "audio/ogg", ".wav": "audio/wav", ".webm": "audio/webm",
}
PUBLIC = {"/", "/api/info", "/api/auth/login", "/favicon.ico"}


@dataclass
class Config:
    music: Path
    data: Path
    port: int = 8484
    rescan_minutes: float = 15
    fetch_artwork: bool = True
    fetch_lyrics: bool = True
    ffmpeg: str | None = None

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            music=Path(os.environ.get("SPINUS_MUSIC", "/music")),
            data=Path(os.environ.get("SPINUS_DATA", "/data")),
            port=int(os.environ.get("SPINUS_PORT", "8484")),
            rescan_minutes=float(os.environ.get("SPINUS_RESCAN_MINUTES", "15")),
            fetch_artwork=os.environ.get("SPINUS_FETCH_ARTWORK", os.environ.get("SPINUS_ARTIST_PHOTOS", "1"))
            not in ("0", "false", "no"),
            fetch_lyrics=os.environ.get("SPINUS_FETCH_LYRICS", "1") not in ("0", "false", "no"),
            ffmpeg=shutil.which("ffmpeg"),
        )


# ---------------------------------------------------------------- presence

@dataclass
class Session:
    id: str
    user_id: str
    user_name: str
    client: str
    device_name: str
    sockets: set = field(default_factory=set)
    now_playing: dict | None = None  # trackId, positionMs, paused, at


class Presence:
    def __init__(self):
        self.sessions: dict[str, Session] = {}

    def get(self, info: dict) -> Session:
        s = self.sessions.get(info["session_id"])
        if s is None:
            s = Session(info["session_id"], info["user_id"], info["user_name"],
                        info.get("client") or "", info.get("device_name") or "")
            self.sessions[s.id] = s
        return s

    async def send(self, session_id: str, payload: dict) -> bool:
        s = self.sessions.get(session_id)
        if not s or not s.sockets:
            return False
        text = json.dumps(payload)
        ok = False
        for ws in list(s.sockets):
            try:
                await ws.send_str(text)
                ok = True
            except Exception:
                s.sockets.discard(ws)
        return ok


# ---------------------------------------------------------------- app

class SpinUs:
    def __init__(self, config: Config):
        self.config = config
        self.db = Database(config.data / "spinus.db")
        self.fetched = config.data / "fetched"
        self.library = Library(config.music, self.db, fetched_dir=self.fetched)
        self.fetcher = ArtworkFetcher(self.library, self.fetched,
                                      artwork=config.fetch_artwork, lyrics=config.fetch_lyrics)
        self.presence = Presence()
        self.cache = config.data / "cache"
        (self.cache / "img").mkdir(parents=True, exist_ok=True)
        self._scan_task = None
        self._fetch_task = None

    # ---- lifecycle

    def make_app(self) -> web.Application:
        app = web.Application(middlewares=[self.auth_middleware], client_max_size=8 * 1024 * 1024)
        r = app.router
        r.add_get("/", self.admin_page)
        r.add_get("/api/info", self.info)
        r.add_post("/api/auth/login", self.login)
        r.add_post("/api/auth/logout", self.logout)
        r.add_get("/api/me", self.me)
        r.add_post("/api/me/password", self.change_password)
        r.add_get("/api/users", self.users)
        r.add_post("/api/users", self.add_user)
        r.add_delete("/api/users/{name}", self.remove_user)
        r.add_get("/api/library/status", self.library_status)
        r.add_post("/api/library/scan", self.library_scan)

        r.add_get("/api/albums", self.albums)
        r.add_get("/api/albums/{id}/similar", self.similar_albums)
        r.add_get("/api/tracks", self.tracks)
        r.add_get("/api/artists", self.artists)
        r.add_get("/api/artists/by-name", self.artist_by_name)
        r.add_get("/api/genres", self.genres)
        r.add_get("/api/items/{id}", self.item_details)
        r.add_get("/api/items/{id}/mix", self.instant_mix)
        r.add_put("/api/favorites/{id}", self.favorite_on)
        r.add_delete("/api/favorites/{id}", self.favorite_off)
        r.add_post("/api/plays/{id}", self.mark_played)
        r.add_get("/api/playlists", self.playlists)
        r.add_post("/api/playlists", self.create_playlist)
        r.add_get("/api/playlists/{id}/tracks", self.playlist_tracks)
        r.add_post("/api/playlists/{id}/tracks", self.add_playlist_tracks)
        r.add_patch("/api/playlists/{id}", self.rename_playlist)
        r.add_delete("/api/playlists/{id}", self.delete_playlist)
        r.add_get("/api/lyrics/{id}", self.lyrics)
        r.add_get("/api/prefs/{key}", self.get_pref)
        r.add_put("/api/prefs/{key}", self.put_pref)

        r.add_get("/api/stream/{id}", self.stream)
        r.add_get("/api/images/{id}", self.image)

        r.add_get("/api/socket", self.socket)
        r.add_get("/api/sessions", self.sessions)
        r.add_get("/api/sessions/self", self.own_session)
        r.add_post("/api/sessions/playing", self.report_playing)
        r.add_post("/api/sessions/stopped", self.report_stopped)
        r.add_post("/api/sessions/{id}/message", self.message)

        app.on_startup.append(self._startup)
        app.on_cleanup.append(self._cleanup)
        return app

    async def scan(self) -> dict:
        """Rescan the library, then fetch missing artwork / lyrics in the background."""
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(None, self.library.scan)
        self.fetcher.apply_existing()
        if (self.config.fetch_artwork or self.config.fetch_lyrics) and not self.fetcher.running:
            self._fetch_task = asyncio.create_task(self._fetch())
        return result

    async def _fetch(self):
        try:
            await asyncio.get_running_loop().run_in_executor(None, self.fetcher.run)
        except Exception:
            log.exception("artwork / lyrics fetch failed")

    async def _startup(self, app):
        await self.scan()
        if self.config.rescan_minutes > 0:
            self._scan_task = asyncio.create_task(self._rescan_loop())

    async def _cleanup(self, app):
        if self._scan_task:
            self._scan_task.cancel()
        for s in self.presence.sessions.values():
            for ws in list(s.sockets):
                await ws.close()

    async def _rescan_loop(self):
        while True:
            await asyncio.sleep(self.config.rescan_minutes * 60)
            try:
                await self.scan()
            except Exception:
                log.exception("rescan failed")

    # ---- auth

    @web.middleware
    async def auth_middleware(self, request: web.Request, handler):
        if request.path in PUBLIC or request.method == "OPTIONS":
            return await handler(request)
        token = None
        auth = request.headers.get("Authorization", "")
        if auth.lower().startswith("bearer "):
            token = auth[7:].strip()
        token = token or request.query.get("token")
        info = self.db.token_info(token) if token else None
        if info is None:
            return web.json_response({"error": "not signed in"}, status=401)
        request["auth"] = info
        request["token"] = token
        return await handler(request)

    @staticmethod
    def uid(request) -> str:
        return request["auth"]["user_id"]

    @staticmethod
    def require_admin(request):
        if not request["auth"]["is_admin"]:
            raise web.HTTPForbidden(text=json.dumps({"error": "admin only"}), content_type="application/json")

    async def info(self, request):
        return web.json_response({
            "server": "SpinUs", "version": __version__, "needsSetup": self.db.user_count() == 0,
        })

    async def login(self, request):
        body = await request.json()
        name = str(body.get("username", "")).strip()
        password = str(body.get("password", ""))
        if not name or not password:
            return web.json_response({"error": "username and password are required"}, status=400)
        loop = asyncio.get_running_loop()
        user = None
        if self.db.user_count() == 0:
            # First sign-in claims the server: that account becomes the admin.
            user = await loop.run_in_executor(None, lambda: self.db.create_user(name, password, is_admin=True))
            log.warning("first sign-in: created admin account %r", name)
        else:
            user = await loop.run_in_executor(None, self.db.check_login, name, password)
        if user is None:
            await asyncio.sleep(0.5)
            return web.json_response({"error": "wrong username or password"}, status=401)
        token, session_id = self.db.issue_token(
            user["id"], str(body.get("deviceId") or "unknown"), str(body.get("deviceName") or ""),
            str(body.get("client") or ""), str(body.get("version") or ""))
        return web.json_response({"token": token, "sessionId": session_id, "user": user})

    async def logout(self, request):
        self.db.revoke_token(request["token"])
        return web.Response(status=204)

    async def me(self, request):
        a = request["auth"]
        return web.json_response({"id": a["user_id"], "name": a["user_name"], "isAdmin": bool(a["is_admin"]),
                                  "sessionId": a["session_id"]})

    async def change_password(self, request):
        body = await request.json()
        if not body.get("password"):
            return web.json_response({"error": "password required"}, status=400)
        self.db.set_password(request["auth"]["user_name"], body["password"])
        return web.Response(status=204)

    async def users(self, request):
        self.require_admin(request)
        return web.json_response({"items": self.db.list_users()})

    async def add_user(self, request):
        self.require_admin(request)
        body = await request.json()
        try:
            user = self.db.create_user(str(body.get("name", "")), str(body.get("password", "")),
                                       bool(body.get("isAdmin", False)))
        except ValueError as e:
            return web.json_response({"error": str(e)}, status=400)
        return web.json_response(user, status=201)

    async def remove_user(self, request):
        self.require_admin(request)
        name = request.match_info["name"]
        if name.casefold() == request["auth"]["user_name"].casefold():
            return web.json_response({"error": "you can't delete your own account"}, status=400)
        if not self.db.delete_user(name):
            raise web.HTTPNotFound()
        return web.Response(status=204)

    async def library_status(self, request):
        return web.json_response({"scanning": self.library.scanning, "lastScan": self.library.last_scan,
                                  "fetching": self.fetcher.running, "lastFetch": self.fetcher.last_run,
                                  "musicFolder": str(self.config.music)})

    async def library_scan(self, request):
        self.require_admin(request)
        return web.json_response(await self.scan())

    # ---- user data helpers

    def user_data(self, request):
        if "ud" not in request:
            uid = self.uid(request)
            request["ud"] = (self.db.favorites(uid), self.db.plays(uid))
        return request["ud"]

    def artist_image_tag(self, art) -> str | None:
        return art.image_tag

    def track_json(self, request, t) -> dict:
        favs, plays = self.user_data(request)
        idx = self.library.index
        pc, last = plays.get(t.id, (0, None))
        alb = idx.albums.get(t.album_id) if t.album_id else None
        return {
            "id": t.id, "title": t.title, "artists": t.artists, "artistIds": t.artist_ids,
            "album": t.album or "", "albumId": t.album_id, "albumArtist": t.album_artist,
            "albumArtistId": t.album_artist_id, "durationMs": t.duration_ms,
            "imageTag": alb.image_tag if alb else t.image_tag, "trackNumber": t.track, "discNumber": t.disc,
            "genres": t.genres, "year": t.year, "date": t.date, "dateAdded": t.added,
            "hasLyrics": t.has_lyrics,
            "userData": {"playCount": pc, "lastPlayed": last, "isFavorite": t.id in favs},
        }

    def album_json(self, request, a) -> dict:
        favs, plays = self.user_data(request)
        pcs = [plays.get(t, (0, None)) for t in a.track_ids]
        lasts = [p[1] for p in pcs if p[1]]
        return {
            "id": a.id, "name": a.name, "artist": a.artist, "artistId": a.artist_id, "year": a.year,
            "date": a.date, "dateAdded": a.added, "imageTag": a.image_tag, "trackCount": len(a.track_ids),
            "genres": a.genres,
            "userData": {"playCount": sum(p[0] for p in pcs), "lastPlayed": max(lasts) if lasts else None,
                         "isFavorite": a.id in favs},
        }

    def artist_json(self, request, art) -> dict:
        favs, _ = self.user_data(request)
        return {"id": art.id, "name": art.name, "imageTag": self.artist_image_tag(art),
                "albumCount": len(art.album_ids), "trackCount": len(art.track_ids),
                "isFavorite": art.id in favs}

    @staticmethod
    def limit(request, default=None) -> int | None:
        v = request.query.get("limit")
        try:
            return max(0, int(v)) if v else default
        except ValueError:
            return default

    @staticmethod
    def ids_param(request, key) -> list[str]:
        v = request.query.get(key, "")
        return [x for x in v.split(",") if x]

    # ---- library reads

    async def albums(self, request):
        idx = self.library.index
        items = list(idx.albums.values())
        q = request.query
        if ids := self.ids_param(request, "ids"):
            items = [idx.albums[i] for i in ids if i in idx.albums]
        if aid := q.get("artistId"):
            art = idx.artists.get(aid)
            wanted = set(art.album_ids) if art else set()
            # Also albums the artist only appears on as a track artist.
            if art:
                wanted |= {idx.tracks[t].album_id for t in art.track_ids if idx.tracks[t].album_id}
            items = [a for a in items if a.id in wanted]
        if gid := q.get("genreId"):
            g = idx.genres.get(gid)
            wanted = set(g.album_ids) if g else set()
            items = [a for a in items if a.id in wanted]
        if term := fold(q.get("q", "")):
            items = [a for a in items if term in a.folded or term in fold(a.artist)]
            items.sort(key=lambda a: (not a.folded.startswith(term), a.folded))
        if q.get("favorites") == "true":
            favs, _ = self.user_data(request)
            items = [a for a in items if a.id in favs]
        out = [self.album_json(request, a) for a in items]
        sort = q.get("sort")
        desc = q.get("order", "asc") == "desc"
        if sort == "added":
            out.sort(key=lambda a: a["dateAdded"] or "", reverse=desc)
        elif sort == "played":
            out = [a for a in out if a["userData"]["lastPlayed"]]
            out.sort(key=lambda a: a["userData"]["lastPlayed"], reverse=desc)
        elif sort == "year":
            out.sort(key=lambda a: (a["year"] or 0, a["date"] or "", fold(a["name"])), reverse=desc)
        elif sort == "random":
            random.shuffle(out)
        elif sort == "name" or not q.get("q"):
            out.sort(key=lambda a: fold(a["name"]), reverse=desc)
        if (n := self.limit(request)) is not None:
            out = out[:n]
        return web.json_response({"items": out, "total": len(out)})

    async def tracks(self, request):
        idx = self.library.index
        q = request.query
        items = None
        if ids := self.ids_param(request, "ids"):
            items = [idx.tracks[i] for i in ids if i in idx.tracks]
        elif aid := q.get("albumId"):
            alb = idx.albums.get(aid)
            items = [idx.tracks[t] for t in alb.track_ids] if alb else []
        else:
            items = list(idx.tracks.values())
        if artist_ids := self.ids_param(request, "artistIds"):
            wanted = set()
            for a in artist_ids:
                if a in idx.artists:
                    wanted |= set(idx.artists[a].track_ids)
            items = [t for t in items if t.id in wanted]
        if gid := q.get("genreId"):
            g = idx.genres.get(gid)
            wanted = set(g.track_ids) if g else set()
            items = [t for t in items if t.id in wanted]
        if term := fold(q.get("q", "")):
            items = [t for t in items if term in t.folded]
            items.sort(key=lambda t: (not fold(t.title).startswith(term), fold(t.title)))
        if q.get("favorites") == "true":
            favs, _ = self.user_data(request)
            items = [t for t in items if t.id in favs]
        sort = q.get("sort")
        desc = q.get("order", "asc") == "desc"
        if sort == "album":
            items.sort(key=lambda t: (fold(t.album or "￿"), t.disc or 1, t.track or 9999, fold(t.title)), reverse=desc)
        elif sort == "name":
            items.sort(key=lambda t: fold(t.title), reverse=desc)
        elif sort == "added":
            items.sort(key=lambda t: t.added, reverse=desc)
        elif sort == "playCount":
            _, plays = self.user_data(request)
            items.sort(key=lambda t: (plays.get(t.id, (0, None))[0], fold(t.title)), reverse=desc)
        elif sort == "played":
            _, plays = self.user_data(request)
            items = [t for t in items if plays.get(t.id, (0, None))[1]]
            items.sort(key=lambda t: plays[t.id][1], reverse=desc)
        elif sort == "random":
            random.shuffle(items)
        if (n := self.limit(request)) is not None:
            items = items[:n]
        return web.json_response({"items": [self.track_json(request, t) for t in items], "total": len(items)})

    async def artists(self, request):
        idx = self.library.index
        items = list(idx.artists.values())
        if term := fold(request.query.get("q", "")):
            items = [a for a in items if term in a.folded]
            items.sort(key=lambda a: (not a.folded.startswith(term), len(a.folded), a.folded))
        else:
            items.sort(key=lambda a: a.folded)
        out = [self.artist_json(request, a) for a in items]
        if request.query.get("withImage") == "true":
            out = [a for a in out if a["imageTag"]]
        if (n := self.limit(request)) is not None:
            out = out[:n]
        return web.json_response({"items": out, "total": len(out)})

    async def artist_by_name(self, request):
        idx = self.library.index
        aid = idx.artist_by_name.get(fold(request.query.get("name", "")))
        if not aid:
            raise web.HTTPNotFound()
        return web.json_response(self.details_json(request, aid))

    async def genres(self, request):
        idx = self.library.index
        items = sorted(idx.genres.values(), key=lambda g: fold(g.name))
        return web.json_response({"items": [
            {"id": g.id, "name": g.name, "imageTag": None, "trackCount": len(g.track_ids),
             "albumCount": len(g.album_ids)} for g in items]})

    def details_json(self, request, item_id: str) -> dict | None:
        idx = self.library.index
        favs, _ = self.user_data(request)
        if alb := idx.albums.get(item_id):
            art = idx.artists.get(alb.artist_id) if alb.artist_id else None
            overview = self.library.nfo_text(alb.folder, "album.nfo", ("review", "plot", "outline", "biography"))
            parent_backdrops = [file_tag(p) for p in art.backdrop_files] if art else []
            return {
                "id": alb.id, "name": alb.name, "type": "MusicAlbum", "overview": overview,
                "genres": alb.genres, "studios": [alb.label] if alb.label else [], "year": alb.year,
                "durationMs": sum(idx.tracks[t].duration_ms for t in alb.track_ids),
                "childCount": len(alb.track_ids), "imageTag": alb.image_tag, "backdropTags": [],
                "parentBackdropItemId": art.id if art and parent_backdrops else None,
                "parentBackdropTags": parent_backdrops, "artistName": alb.artist, "artistId": alb.artist_id,
                "isFavorite": alb.id in favs,
            }
        if art := idx.artists.get(item_id):
            genres = collections.Counter(g for t in art.track_ids for g in idx.tracks[t].genres)
            overview = self.library.nfo_text(art.folder, "artist.nfo", ("biography", "plot", "outline"))
            return {
                "id": art.id, "name": art.name, "type": "MusicArtist", "overview": overview,
                "genres": [g for g, _ in genres.most_common(5)], "studios": [], "year": None,
                "durationMs": sum(idx.tracks[t].duration_ms for t in art.track_ids),
                "childCount": len(art.album_ids), "imageTag": self.artist_image_tag(art),
                "backdropTags": [file_tag(p) for p in art.backdrop_files],
                "parentBackdropItemId": None, "parentBackdropTags": [], "artistName": art.name,
                "artistId": art.id, "isFavorite": art.id in favs,
            }
        if t := idx.tracks.get(item_id):
            alb = idx.albums.get(t.album_id) if t.album_id else None
            return {
                "id": t.id, "name": t.title, "type": "Audio", "overview": None, "genres": t.genres,
                "studios": [], "year": t.year, "durationMs": t.duration_ms, "childCount": 0,
                "imageTag": alb.image_tag if alb else None, "backdropTags": [],
                "parentBackdropItemId": None, "parentBackdropTags": [], "artistName": t.album_artist,
                "artistId": t.album_artist_id, "isFavorite": t.id in favs,
            }
        return None

    async def item_details(self, request):
        d = self.details_json(request, request.match_info["id"])
        if d is None:
            raise web.HTTPNotFound()
        return web.json_response(d)

    async def similar_albums(self, request):
        idx = self.library.index
        alb = idx.albums.get(request.match_info["id"])
        if not alb:
            raise web.HTTPNotFound()
        seed_genres = {fold(g) for g in alb.genres}
        scored = []
        for other in idx.albums.values():
            if other.id == alb.id:
                continue
            og = {fold(g) for g in other.genres}
            score = 0.0
            if seed_genres and og:
                score += 3 * len(seed_genres & og) / len(seed_genres | og)
            if other.artist_id == alb.artist_id:
                score += 1.0
            if alb.year and other.year and abs(alb.year - other.year) <= 5:
                score += 0.3
            if score > 0.3:
                scored.append((score + random.random() * 0.25, other))
        scored.sort(key=lambda x: -x[0])
        n = self.limit(request, 8)
        return web.json_response({"items": [self.album_json(request, a) for _, a in scored[:n]]})

    async def instant_mix(self, request):
        idx = self.library.index
        item_id = request.match_info["id"]
        seed: list = []
        if t := idx.tracks.get(item_id):
            seed = [t]
        elif alb := idx.albums.get(item_id):
            seed = [idx.tracks[x] for x in alb.track_ids]
        elif art := idx.artists.get(item_id):
            seed = [idx.tracks[x] for x in art.track_ids]
        elif g := idx.genres.get(item_id):
            seed = [idx.tracks[x] for x in g.track_ids]
        elif self.db.playlist_owner(item_id) == self.uid(request):
            seed = [idx.tracks[x] for x in self.db.playlist_tracks(item_id) if x in idx.tracks]
        if not seed:
            raise web.HTTPNotFound()
        n = self.limit(request, 100)
        genres = collections.Counter(fold(g) for t in seed for g in t.genres)
        artists = set(a for t in seed for a in t.artist_ids)
        total = sum(genres.values()) or 1
        scored = []
        for t in idx.tracks.values():
            s = sum(genres.get(fold(g), 0) for g in t.genres) / total * 4
            if artists & set(t.artist_ids):
                s += 1.5
            if s > 0:
                scored.append((s + random.random() * 2, t))
        scored.sort(key=lambda x: -x[0])
        per_album = collections.Counter()
        out, taken = [], set()
        if len(seed) == 1:
            out.append(seed[0])
            taken.add(seed[0].id)
            per_album[seed[0].album_id] += 1
        for _, t in scored:
            if len(out) >= n:
                break
            if t.id in taken or (t.album_id and per_album[t.album_id] >= 4):
                continue
            per_album[t.album_id] += 1
            taken.add(t.id)
            out.append(t)
        head, rest = (out[:1], out[1:]) if len(seed) == 1 else ([], out)
        random.shuffle(rest)
        return web.json_response({"items": [self.track_json(request, t) for t in head + rest]})

    # ---- favorites / plays

    def known_item(self, item_id: str) -> bool:
        idx = self.library.index
        return item_id in idx.tracks or item_id in idx.albums or item_id in idx.artists

    async def favorite_on(self, request):
        item_id = request.match_info["id"]
        if not self.known_item(item_id):
            raise web.HTTPNotFound()
        self.db.set_favorite(self.uid(request), item_id, True)
        return web.Response(status=204)

    async def favorite_off(self, request):
        self.db.set_favorite(self.uid(request), request.match_info["id"], False)
        return web.Response(status=204)

    async def mark_played(self, request):
        tid = request.match_info["id"]
        if tid not in self.library.index.tracks:
            raise web.HTTPNotFound()
        when = None
        if request.can_read_body:
            try:
                when = (await request.json()).get("datePlayed")
            except Exception:
                when = None
        when = when or request.query.get("datePlayed") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        self.db.mark_played(self.uid(request), tid, when)
        return web.Response(status=204)

    # ---- playlists

    def own_playlist(self, request) -> str:
        pid = request.match_info["id"]
        if self.db.playlist_owner(pid) != self.uid(request):
            raise web.HTTPNotFound()
        return pid

    async def playlists(self, request):
        rows = self.db.playlists(self.uid(request))
        if term := fold(request.query.get("q", "")):
            rows = [r for r in rows if term in fold(r["name"])]
        if (n := self.limit(request)) is not None:
            rows = rows[:n]
        return web.json_response({"items": [{"id": r["id"], "name": r["name"], "trackCount": r["n"]} for r in rows]})

    async def create_playlist(self, request):
        body = await request.json()
        name = str(body.get("name", "")).strip()
        if not name:
            return web.json_response({"error": "name required"}, status=400)
        ids = [t for t in body.get("trackIds", []) if t in self.library.index.tracks]
        pid = self.db.create_playlist(self.uid(request), name, ids)
        return web.json_response({"id": pid}, status=201)

    async def playlist_tracks(self, request):
        pid = self.own_playlist(request)
        idx = self.library.index
        items = [idx.tracks[t] for t in self.db.playlist_tracks(pid) if t in idx.tracks]
        return web.json_response({"items": [self.track_json(request, t) for t in items]})

    async def add_playlist_tracks(self, request):
        pid = self.own_playlist(request)
        body = await request.json()
        ids = [t for t in body.get("trackIds", []) if t in self.library.index.tracks]
        self.db.add_to_playlist(pid, ids)
        return web.Response(status=204)

    async def rename_playlist(self, request):
        pid = self.own_playlist(request)
        body = await request.json()
        if name := str(body.get("name", "")).strip():
            self.db.rename_playlist(pid, name)
        if "removePositions" in body:
            self.db.remove_from_playlist(pid, [int(p) for p in body["removePositions"]])
        return web.Response(status=204)

    async def delete_playlist(self, request):
        self.db.delete_playlist(self.own_playlist(request))
        return web.Response(status=204)

    # ---- lyrics / prefs

    async def lyrics(self, request):
        t = self.library.index.tracks.get(request.match_info["id"])
        if not t:
            raise web.HTTPNotFound()
        lines = await asyncio.get_running_loop().run_in_executor(None, self.library.lyrics_for, t)
        if not lines:
            raise web.HTTPNotFound()
        return web.json_response({"synced": "startMs" in lines[0], "lines": lines})

    async def get_pref(self, request):
        value = self.db.get_pref(self.uid(request), request.match_info["key"])
        return web.json_response(value if value is not None else {})

    async def put_pref(self, request):
        body = await request.json()
        if not isinstance(body, dict):
            return web.json_response({"error": "expected a JSON object"}, status=400)
        self.db.put_pref(self.uid(request), request.match_info["key"], body)
        return web.Response(status=204)

    # ---- streaming

    async def stream(self, request):
        t = self.library.index.tracks.get(request.match_info["id"])
        if not t or not t.path.is_file():
            raise web.HTTPNotFound()
        want_mp3 = request.query.get("format") == "mp3"
        if t.direct and not want_mp3:
            mime = MIME.get(t.path.suffix.lower()) or mimetypes.guess_type(t.path.name)[0] or "application/octet-stream"
            return web.FileResponse(t.path, headers={"Content-Type": mime, "Cache-Control": "private, max-age=86400"})
        if not self.config.ffmpeg:
            return web.json_response({"error": "this file needs ffmpeg to play, and ffmpeg isn't installed"}, status=415)
        try:
            kbps = max(64, min(320, int(request.query.get("bitrate", "320"))))
        except ValueError:
            kbps = 320
        proc = await asyncio.create_subprocess_exec(
            self.config.ffmpeg, "-nostdin", "-loglevel", "error", "-i", str(t.path), "-map", "0:a:0",
            "-vn", "-c:a", "libmp3lame", "-b:a", f"{kbps}k", "-f", "mp3", "pipe:1",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        resp = web.StreamResponse(headers={"Content-Type": "audio/mpeg", "Accept-Ranges": "none"})
        await resp.prepare(request)
        try:
            while chunk := await proc.stdout.read(64 * 1024):
                await resp.write(chunk)
            await resp.write_eof()
        except (ConnectionResetError, asyncio.CancelledError):
            pass
        finally:
            if proc.returncode is None:
                proc.kill()
            await proc.wait()
        return resp

    # ---- images

    async def image(self, request):
        item_id = request.match_info["id"]
        kind = request.query.get("type", "primary")
        try:
            width = max(32, min(2000, int(request.query.get("w", "600"))))
            index = int(request.query.get("index", "0"))
        except ValueError:
            raise web.HTTPBadRequest()
        loop = asyncio.get_running_loop()
        path = await loop.run_in_executor(None, self._image_file, item_id, kind, index, width)
        if path is None:
            raise web.HTTPNotFound()
        cache = "public, max-age=31536000, immutable" if request.query.get("tag") else "private, max-age=3600"
        return web.FileResponse(path, headers={"Content-Type": "image/jpeg", "Cache-Control": cache})

    def _fetched(self, kind: str, item_id: str):
        p = self.fetcher.path(kind, item_id)
        return (p, file_tag(p)) if p.is_file() else (None, None)

    def _image_source(self, item_id: str, kind: str, index: int):
        """(path to an image file | bytes from an embedded tag, tag) for an item.
        Files in the music folder win; downloaded artwork fills the gaps."""
        idx = self.library.index
        if t := idx.tracks.get(item_id):
            if t.album_id:
                item_id = t.album_id
            elif kind == "primary":
                data = embedded_picture(t.path)
                return (data, file_tag(t.path)) if data else self._fetched("track", t.id)
            else:
                return None, None
        if alb := idx.albums.get(item_id):
            if kind == "backdrop":
                art = idx.artists.get(alb.artist_id) if alb.artist_id else None
                return self._image_source(art.id, kind, index) if art else (None, None)
            if alb.cover_file:
                return alb.cover_file, file_tag(alb.cover_file)
            if alb.cover_track:
                data = embedded_picture(alb.cover_track)
                if data:
                    return data, file_tag(alb.cover_track)
            return self._fetched("album", alb.id)
        if art := idx.artists.get(item_id):
            if kind == "backdrop":
                if 0 <= index < len(art.backdrop_files):
                    p = art.backdrop_files[index]
                    return p, file_tag(p)
                return None, None
            if art.image_file:
                return art.image_file, art.image_tag
            return self._fetched("artist", art.id)
        return None, None

    def _image_file(self, item_id, kind, index, width) -> Path | None:
        src, tag = self._image_source(item_id, kind, index)
        if src is None:
            return None
        out = self.cache / "img" / f"{item_id}-{kind}{index}-{width}-{tag}.jpg"
        if out.exists():
            return out
        from PIL import Image, ImageOps
        try:
            img = Image.open(io.BytesIO(src) if isinstance(src, (bytes, bytearray)) else src)
            img = ImageOps.exif_transpose(img).convert("RGB")
            if img.width > width:
                img = img.resize((width, max(1, round(img.height * width / img.width))), Image.LANCZOS)
            tmp = out.with_suffix(".tmp")
            img.save(tmp, "JPEG", quality=85, optimize=True)
            tmp.replace(out)
        except Exception as e:
            log.info("bad image for %s: %s", item_id, e)
            return None
        return out

    # ---- presence

    async def socket(self, request):
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)
        session = self.presence.get(request["auth"])
        session.sockets.add(ws)
        await ws.send_str(json.dumps({"type": "hello", "sessionId": session.id}))
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT and msg.data == '{"type":"ping"}':
                    await ws.send_str('{"type":"pong"}')
                elif msg.type == WSMsgType.ERROR:
                    break
        finally:
            session.sockets.discard(ws)
        return ws

    async def own_session(self, request):
        return web.json_response({"sessionId": request["auth"]["session_id"]})

    async def report_playing(self, request):
        body = await request.json()
        tid = body.get("trackId")
        if tid not in self.library.index.tracks:
            raise web.HTTPNotFound()
        s = self.presence.get(request["auth"])
        s.now_playing = {"trackId": tid, "positionMs": int(body.get("positionMs", 0)),
                         "paused": bool(body.get("paused", False)), "at": time.time()}
        return web.Response(status=204)

    async def report_stopped(self, request):
        body = await request.json()
        s = self.presence.get(request["auth"])
        if s.now_playing and s.now_playing["trackId"] == body.get("trackId"):
            s.now_playing = None
        return web.Response(status=204)

    async def sessions(self, request):
        idx = self.library.index
        me = self.uid(request)
        now = time.time()
        out = []
        for s in list(self.presence.sessions.values()):
            np = s.now_playing
            if not np or np["paused"]:
                continue
            t = idx.tracks.get(np["trackId"])
            if not t:
                continue
            pos = np["positionMs"] + int((now - np["at"]) * 1000)
            # A session that stopped reporting (app killed) drops out once
            # its song would have ended.
            if pos > t.duration_ms + 60_000:
                continue
            if request.query.get("includeSelf") != "true" and s.user_id == me:
                continue
            alb = idx.albums.get(t.album_id) if t.album_id else None
            out.append({
                "sessionId": s.id, "userId": s.user_id, "userName": s.user_name, "client": s.client,
                "deviceName": s.device_name, "online": bool(s.sockets),
                "positionMs": min(pos, t.duration_ms),
                "nowPlaying": {"trackId": t.id, "title": t.title, "artists": t.artists,
                               "albumId": t.album_id, "imageTag": alb.image_tag if alb else None,
                               "durationMs": t.duration_ms},
            })
        return web.json_response({"items": out})

    async def message(self, request):
        body = await request.json()
        a = request["auth"]
        payload = {"type": "message", "header": str(body.get("header", "SpinUs")),
                   "text": str(body.get("text", "")), "timeoutMs": int(body.get("timeoutMs", 5000)),
                   "from": {"sessionId": a["session_id"], "userId": a["user_id"], "userName": a["user_name"]}}
        if not await self.presence.send(request.match_info["id"], payload):
            raise web.HTTPNotFound()
        return web.Response(status=204)

    # ---- admin web page

    async def admin_page(self, request):
        page = Path(__file__).with_name("admin.html")
        return web.FileResponse(page, headers={"Content-Type": "text/html; charset=utf-8"})
