"""End-to-end tests: a small real library (made with ffmpeg) served by the
real app through aiohttp's test client."""
import asyncio
import json
import shutil
import subprocess
from pathlib import Path

import pytest
from mutagen.id3 import ID3, APIC, USLT
from PIL import Image

from spinus_server.app import Config, SpinUs
from spinus_server.library import parse_lyrics

FFMPEG = shutil.which("ffmpeg")
pytestmark = pytest.mark.skipif(not FFMPEG, reason="needs ffmpeg")


def make_audio(path: Path, seconds: int, codec_args: list[str], tags: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    meta = []
    for k, v in tags.items():
        meta += ["-metadata", f"{k}={v}"]
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
                    *codec_args, *meta, str(path)], check=True)


@pytest.fixture(scope="session")
def music(tmp_path_factory) -> Path:
    root = tmp_path_factory.mktemp("music")
    a = root / "Test Artist" / "First Album (2021)"
    for i, title in enumerate(["Synced Song", "Plain Song", "No Lyrics Song"], 1):
        make_audio(a / f"{i:02d} {title}.mp3", 3, ["-c:a", "libmp3lame", "-b:a", "64k"],
                   {"title": title, "artist": "Test Artist", "album_artist": "Test Artist", "album": "First Album",
                    "track": f"{i}/3", "genre": "Worship", "date": "2021-05-02"})
    Image.new("RGB", (800, 800), (200, 40, 80)).save(a / "cover.jpg")
    (a / "01 Synced Song.lrc").write_text("[ar:Test]\n[00:01.50]Line two\n[00:00.20]Line one\n[00:02.00][00:02.50]Echo\n")
    tags = ID3(a / "02 Plain Song.mp3")
    tags.add(USLT(encoding=3, lang="eng", desc="", text="Just words\nNo times"))
    tags.save()

    b = root / "Test Artist" / "Second Album"
    for disc in (1, 2):
        make_audio(b / f"CD{disc}" / f"{disc}-01 Disc {disc}.flac", 2, ["-c:a", "flac"],
                   {"title": f"Disc {disc} Song", "artist": "Test Artist; Guest", "album_artist": "Test Artist",
                    "album": "Second Album", "track": "1", "disc": str(disc), "genre": "Worship;Gospel"})
    Image.new("RGB", (400, 400), (10, 10, 200)).save(root / "Test Artist" / "folder.jpg")
    Image.new("RGB", (1920, 1080), (40, 40, 40)).save(root / "Test Artist" / "fanart.jpg")
    (root / "Test Artist" / "artist.nfo").write_text("<artist><biography>A test &amp; artist.</biography></artist>")

    c = root / "Other Band" / "Lossless"
    make_audio(c / "01 Alac.m4a", 2, ["-c:a", "alac"],
               {"title": "Alac Song", "artist": "Other Band", "album": "Lossless", "genre": "Rock"})
    # Embedded cover only (no cover file in the folder).
    loose = root / "Loose"
    make_audio(loose / "single.mp3", 2, ["-c:a", "libmp3lame"], {"title": "Loose Single", "artist": "Solo Person"})
    buf = Path(loose / "art.tmp")
    Image.new("RGB", (300, 300), (0, 200, 0)).save(buf, "JPEG")
    t = ID3(loose / "single.mp3")
    t.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="", data=buf.read_bytes()))
    t.save()
    buf.unlink()
    (root / "notes.txt").write_text("not music")
    return root


@pytest.fixture
async def client(aiohttp_client, music, tmp_path):
    server = SpinUs(Config(music=music, data=tmp_path / "data", rescan_minutes=0, artist_photos=False, ffmpeg=FFMPEG))
    c = await aiohttp_client(server.make_app())
    c.server_obj = server
    return c


async def login(client, name="Oniel", pw="secret1", device="phone-1"):
    r = await client.post("/api/auth/login", json={"username": name, "password": pw, "deviceId": device,
                                                    "client": "SpinUs", "version": "2.0.0"})
    assert r.status == 200, await r.text()
    body = await r.json()
    return {"Authorization": "Bearer " + body["token"]}, body


async def j(client, path, h, status=200, method="GET", body=None):
    r = await client.request(method, path, headers=h, json=body)
    assert r.status == status, f"{method} {path}: {r.status} {await r.text()}"
    return await r.json() if r.content_type == "application/json" else None


async def test_auth_and_first_admin(client):
    assert (await (await client.get("/api/info")).json())["needsSetup"] is True
    assert (await client.get("/api/albums")).status == 401
    h, body = await login(client)
    assert body["user"]["isAdmin"] is True and body["sessionId"]
    assert (await client.post("/api/auth/login", json={"username": "Oniel", "password": "nope"})).status == 401
    await j(client, "/api/users", h, 201, "POST", {"name": "Friend", "password": "pw2"})
    fh, fbody = await login(client, "friend", "pw2", "phone-2")  # names are case-insensitive
    assert fbody["user"]["isAdmin"] is False
    await j(client, "/api/users", fh, 403)
    # Signing in again from the same device replaces the old token.
    h2, _ = await login(client, device="phone-1")
    assert (await client.get("/api/me", headers=h)).status == 401
    await j(client, "/api/me", h2)


async def test_library(client):
    h, _ = await login(client)
    albums = (await j(client, "/api/albums?sort=name", h))["items"]
    assert [a["name"] for a in albums] == ["First Album", "Lossless", "Second Album"]
    first = albums[0]
    assert first["trackCount"] == 3 and first["year"] == 2021 and first["imageTag"]
    second = next(a for a in albums if a["name"] == "Second Album")
    assert second["imageTag"] is None  # no cover in the album folder, no embedded art

    tracks = (await j(client, f"/api/tracks?albumId={first['id']}", h))["items"]
    assert [t["title"] for t in tracks] == ["Synced Song", "Plain Song", "No Lyrics Song"]
    assert tracks[0]["durationMs"] > 2500 and tracks[0]["trackNumber"] == 1
    disc = (await j(client, f"/api/tracks?albumId={second['id']}", h))["items"]
    assert [t["discNumber"] for t in disc] == [1, 2]
    assert disc[0]["artists"] == ["Test Artist", "Guest"] and disc[0]["genres"] == ["Worship", "Gospel"]

    loose = (await j(client, "/api/tracks?q=loose", h))["items"]
    assert len(loose) == 1 and loose[0]["albumId"] is None

    artists = (await j(client, "/api/artists", h))["items"]
    names = [a["name"] for a in artists]
    assert names == ["Guest", "Other Band", "Solo Person", "Test Artist"]
    ta = next(a for a in artists if a["name"] == "Test Artist")
    assert ta["imageTag"] and ta["albumCount"] == 2
    assert [a["name"] for a in (await j(client, "/api/artists?withImage=true", h))["items"]] == ["Test Artist"]

    d = await j(client, f"/api/items/{ta['id']}", h)
    assert d["type"] == "MusicArtist" and d["overview"] == "A test & artist." and len(d["backdropTags"]) == 1
    ad = await j(client, f"/api/items/{first['id']}", h)
    assert ad["type"] == "MusicAlbum" and ad["parentBackdropItemId"] == ta["id"]
    by_name = await j(client, "/api/artists/by-name?name=test%20artist", h)
    assert by_name["id"] == ta["id"]
    await j(client, "/api/artists/by-name?name=nobody", h, 404) if False else None
    assert (await client.get("/api/artists/by-name?name=nobody", headers=h)).status == 404

    by_artist = (await j(client, f"/api/albums?artistId={ta['id']}&sort=year&order=desc", h))["items"]
    assert {a["name"] for a in by_artist} == {"First Album", "Second Album"}
    genres = (await j(client, "/api/genres", h))["items"]
    assert [g["name"] for g in genres] == ["Gospel", "Rock", "Worship"]
    worship = next(g for g in genres if g["name"] == "Worship")
    assert len((await j(client, f"/api/tracks?genreId={worship['id']}", h))["items"]) == 5
    assert len((await j(client, f"/api/albums?genreId={worship['id']}", h))["items"]) == 2

    # Accent- and case-insensitive search.
    assert (await j(client, "/api/albums?q=FÍRST", h))["items"][0]["name"] == "First Album"
    sim = (await j(client, f"/api/albums/{first['id']}/similar", h))["items"]
    assert sim and sim[0]["name"] == "Second Album"
    mix = (await j(client, f"/api/items/{tracks[0]['id']}/mix?limit=10", h))["items"]
    assert mix[0]["id"] == tracks[0]["id"] and all(t["title"] != "Alac Song" for t in mix)


async def test_user_data(client):
    h, _ = await login(client)
    fh, _ = (None, None)
    await j(client, "/api/users", h, 201, "POST", {"name": "Friend", "password": "pw2"})
    fh, _ = await login(client, "Friend", "pw2", "phone-2")
    tracks = (await j(client, "/api/tracks?sort=album", h))["items"]
    t0, t1 = tracks[0], tracks[1]

    assert (await client.put(f"/api/favorites/{t0['id']}", headers=h)).status == 204
    assert (await client.put("/api/favorites/nope", headers=h)).status == 404
    got = (await j(client, f"/api/tracks?ids={t0['id']}", h))["items"][0]
    assert got["userData"]["isFavorite"] is True
    assert (await j(client, f"/api/tracks?ids={t0['id']}", fh))["items"][0]["userData"]["isFavorite"] is False
    assert [t["id"] for t in (await j(client, "/api/tracks?favorites=true", h))["items"]] == [t0["id"]]
    await client.delete(f"/api/favorites/{t0['id']}", headers=h)
    assert (await j(client, "/api/tracks?favorites=true", h))["items"] == []

    await j(client, f"/api/plays/{t1['id']}", h, 204, "POST", {"datePlayed": "2026-01-02T00:00:00Z"})
    await j(client, f"/api/plays/{t1['id']}", h, 204, "POST", {"datePlayed": "2025-01-02T00:00:00Z"})
    got = (await j(client, f"/api/tracks?ids={t1['id']}", h))["items"][0]["userData"]
    assert got == {"playCount": 2, "lastPlayed": "2026-01-02T00:00:00Z", "isFavorite": False}
    alb = (await j(client, "/api/albums?sort=played&order=desc", h))["items"]
    assert len(alb) == 1 and alb[0]["userData"]["playCount"] == 2
    top = (await j(client, "/api/tracks?sort=playCount&order=desc&limit=1", h))["items"]
    assert top[0]["id"] == t1["id"]

    p = await j(client, "/api/playlists", h, 201, "POST", {"name": "Mine", "trackIds": [t0["id"], "bogus"]})
    await j(client, f"/api/playlists/{p['id']}/tracks", h, 204, "POST", {"trackIds": [t1["id"], t0["id"]]})
    items = (await j(client, f"/api/playlists/{p['id']}/tracks", h))["items"]
    assert [t["id"] for t in items] == [t0["id"], t1["id"], t0["id"]]
    pls = (await j(client, "/api/playlists", h))["items"]
    assert pls == [{"id": p["id"], "name": "Mine", "trackCount": 3}]
    assert (await j(client, "/api/playlists", fh))["items"] == []
    assert (await client.get(f"/api/playlists/{p['id']}/tracks", headers=fh)).status == 404
    assert len((await j(client, f"/api/items/{p['id']}/mix", h))["items"]) > 0

    assert await j(client, "/api/prefs/history", h) == {}
    await j(client, "/api/prefs/history", h, 204, "PUT", {"CustomPrefs": {"h1.0000": "[]"}})
    assert await j(client, "/api/prefs/history", h) == {"CustomPrefs": {"h1.0000": "[]"}}
    assert await j(client, "/api/prefs/history", fh) == {}


async def test_lyrics(client):
    h, _ = await login(client)
    tracks = {t["title"]: t for t in (await j(client, "/api/tracks", h))["items"]}
    synced = await j(client, f"/api/lyrics/{tracks['Synced Song']['id']}", h)
    assert synced["synced"] is True
    assert synced["lines"] == [{"text": "Line one", "startMs": 200}, {"text": "Line two", "startMs": 1500},
                               {"text": "Echo", "startMs": 2000}, {"text": "Echo", "startMs": 2500}]
    plain = await j(client, f"/api/lyrics/{tracks['Plain Song']['id']}", h)
    assert plain == {"synced": False, "lines": [{"text": "Just words"}, {"text": "No times"}]}
    assert (await client.get(f"/api/lyrics/{tracks['No Lyrics Song']['id']}", headers=h)).status == 404
    assert tracks["Synced Song"]["hasLyrics"] and not tracks["No Lyrics Song"]["hasLyrics"]


def test_parse_lyrics_offset():
    assert parse_lyrics("[offset:+500]\n[00:01.00]a") == [{"text": "a", "startMs": 500}]


async def test_stream_and_images(client):
    h, body = await login(client)
    tok = body["token"]
    tracks = {t["title"]: t for t in (await j(client, "/api/tracks", h))["items"]}
    mp3 = tracks["Synced Song"]["id"]
    r = await client.get(f"/api/stream/{mp3}?token={tok}")
    assert r.status == 200 and r.headers["Content-Type"] == "audio/mpeg"
    full = await r.read()
    assert r.headers.get("Accept-Ranges") == "bytes"
    r = await client.get(f"/api/stream/{mp3}", headers={**h, "Range": "bytes=10-19"})
    assert r.status == 206 and await r.read() == full[10:20]
    r = await client.get(f"/api/stream/{tracks['Disc 1 Song']['id']}?token={tok}")
    assert r.headers["Content-Type"] == "audio/flac"
    r = await client.get(f"/api/stream/{tracks['Alac Song']['id']}?token={tok}")  # ALAC -> MP3
    data = await r.read()
    assert r.status == 200 and r.headers["Content-Type"] == "audio/mpeg" and len(data) > 1000
    assert (await client.get(f"/api/stream/{mp3}")).status == 401

    album = tracks["Synced Song"]["albumId"]
    r = await client.get(f"/api/images/{album}?w=200&token={tok}&tag={tracks['Synced Song']['imageTag']}")
    assert r.status == 200 and "immutable" in r.headers["Cache-Control"]
    import io
    img = Image.open(io.BytesIO(await r.read()))
    assert img.size == (200, 200)
    r = await client.get(f"/api/images/{tracks['Loose Single']['id']}?token={tok}")  # embedded art
    assert r.status == 200
    artist = tracks["Synced Song"]["albumArtistId"]
    assert (await client.get(f"/api/images/{artist}?token={tok}")).status == 200
    r = await client.get(f"/api/images/{artist}?type=backdrop&index=0&w=1280&token={tok}")
    assert r.status == 200 and Image.open(io.BytesIO(await r.read())).size == (1280, 720)
    second = tracks["Disc 1 Song"]["albumId"]
    assert (await client.get(f"/api/images/{second}?token={tok}")).status == 404


async def test_presence(client):
    h, me = await login(client)
    await j(client, "/api/users", h, 201, "POST", {"name": "Friend", "password": "pw2"})
    fh, friend = await login(client, "Friend", "pw2", "phone-2")
    tracks = (await j(client, "/api/tracks", h))["items"]

    ws_me = await client.ws_connect(f"/api/socket?token={me['token']}")
    hello = json.loads((await ws_me.receive()).data)
    assert hello == {"type": "hello", "sessionId": me["sessionId"]}
    assert (await j(client, "/api/sessions/self", h))["sessionId"] == me["sessionId"]

    assert (await j(client, "/api/sessions", h))["items"] == []
    await j(client, "/api/sessions/playing", fh, 204, "POST", {"trackId": tracks[0]["id"], "positionMs": 1000})
    s = (await j(client, "/api/sessions", h))["items"]
    assert len(s) == 1 and s[0]["userName"] == "Friend" and s[0]["nowPlaying"]["title"] == tracks[0]["title"]
    assert s[0]["client"] == "SpinUs" and s[0]["positionMs"] >= 1000
    assert (await j(client, "/api/sessions", fh))["items"] == []  # never yourself

    # Friend messages me; only works while my socket is open.
    await j(client, f"/api/sessions/{me['sessionId']}/message", fh, 204, "POST", {"text": "🔥", "header": "SpinUs"})
    msg = json.loads((await ws_me.receive()).data)
    assert msg["type"] == "message" and msg["text"] == "🔥" and msg["from"]["userName"] == "Friend"
    assert (await client.post(f"/api/sessions/{friend['sessionId']}/message", headers=h, json={"text": "x"})).status == 404

    await j(client, "/api/sessions/playing", fh, 204, "POST", {"trackId": tracks[0]["id"], "positionMs": 0, "paused": True})
    assert (await j(client, "/api/sessions", h))["items"] == []
    await j(client, "/api/sessions/playing", fh, 204, "POST", {"trackId": tracks[0]["id"], "positionMs": 0})
    await j(client, "/api/sessions/stopped", fh, 204, "POST", {"trackId": tracks[0]["id"], "positionMs": 0})
    assert (await j(client, "/api/sessions", h))["items"] == []
    await ws_me.close()


async def test_rescan_keeps_ids_and_dates(client, music):
    h, _ = await login(client)
    before = {t["id"]: t for t in (await j(client, "/api/tracks", h))["items"]}
    new = music / "Other Band" / "Lossless" / "02 New.mp3"
    make_audio(new, 1, ["-c:a", "libmp3lame"], {"title": "Brand New", "artist": "Other Band", "album": "Lossless"})
    try:
        res = await j(client, "/api/library/scan", h, 200, "POST")
        assert res["read"] == 1 and res["tracks"] == len(before) + 1
        after = {t["id"]: t for t in (await j(client, "/api/tracks", h))["items"]}
        for tid, t in before.items():
            assert after[tid]["dateAdded"] == t["dateAdded"]
        newest = (await j(client, "/api/tracks?sort=added&order=desc&limit=1", h))["items"][0]
        assert newest["title"] == "Brand New"
    finally:
        new.unlink()


async def test_admin_page(client):
    r = await client.get("/")
    assert r.status == 200 and "SpinUs server" in await r.text()
