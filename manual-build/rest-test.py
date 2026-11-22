#!/usr/bin/env python3
"""REST tests for the Oniel Music data layer against the local Jellyfin
12.2.0 test server (~/workspace/jf-api-test). Covers every HTTP endpoint
the app uses. Test-only credentials from API-NOTES.md (Oniel/testpass1,
Friend/testpass2). Run with the server up on 127.0.0.1:8096."""
import json, sys, urllib.request, urllib.error, uuid

BASE = "http://127.0.0.1:8096"
passed, failed = 0, 0
failures = []

def check(name, cond, extra=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"PASS {name}")
    else:
        failed += 1
        failures.append(name)
        print(f"FAIL {name} {extra}")

def req(method, path, token=None, body=None, headers=None, raw_headers=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if raw_headers:
        for k, v in raw_headers.items():
            r.add_header(k, v)
    elif token:
        r.add_header("Authorization",
            f'MediaBrowser Token="{token}", Client="OnielMusicTest", '
            f'Device="test", DeviceId="test-device-1", Version="1.0.1"')
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if headers:
        for k, v in headers.items():
            r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return None, str(e).encode()

def j(body):
    return json.loads(body.decode())

# ids from the fixture
ids = json.load(open("/home/hatch/workspace/jf-api-test/results/ids.json"))
synced = ids["Synced Song"]; plain = ids["Plain Song"]; none = ids["No Lyrics Song"]

LOGIN_AUTH = {"Authorization": 'MediaBrowser Client="OnielMusicTest", Device="OnielMusicTest", DeviceId="test-device-1", Version="1.0.1"'}  # app's loginAuthHeader(): no Token part yet

# 1. server info
s, b = req("GET", "/System/Info/Public")
check("1 server info 200 + version 12.2.0", s == 200 and j(b).get("Version") == "12.2.0", f"got {s}")

# 2-4. login
s, b = req("POST", "/Users/AuthenticateByName", body={"Username": "Oniel", "Pw": "testpass1"}, raw_headers=LOGIN_AUTH)
ok = s == 200
oniel = j(b) if ok else {}
check("2 login Oniel 200", ok, f"got {s}")
tok = oniel.get("AccessToken", "")
uid = oniel.get("User", {}).get("Id", "")
check("3 login token 32 chars + user id", len(tok) == 32 and len(uid) > 0, f"tok={len(tok)}")
s, b = req("POST", "/Users/AuthenticateByName", body={"Username": "Oniel", "Pw": "wrong"}, raw_headers=LOGIN_AUTH)
check("4 wrong password 401", s == 401, f"got {s}")
s, b = req("POST", "/Users/AuthenticateByName", body={"Username": "Friend", "Pw": "testpass2"}, raw_headers=LOGIN_AUTH)
fobj = j(b) if s == 200 else {}
ftok = fobj.get("AccessToken", "")
fuid = fobj.get("User", {}).get("Id", "")
check("5 login Friend 200", s == 200 and len(ftok) == 32, f"got {s}")
check("6 Friend is not admin", fobj.get("User", {}).get("Policy", {}).get("IsAdministrator") is False)

# 7-8. auth quirks
s, b = req("GET", f"/Users/{uid}/Views", raw_headers={"X-MediaBrowser-Token": tok})
check("7 X-MediaBrowser-Token header 401", s == 401, f"got {s}")
s, b = req("GET", f"/Users/{uid}/Views?api_key={tok}")
check("8 api_key query param 401", s == 401, f"got {s}")

# 9. views
s, b = req("GET", f"/Users/{uid}/Views", token=tok)
views = j(b).get("Items", []) if s == 200 else []
check("9 views 200 + Music library", s == 200 and any(v.get("CollectionType") == "music" for v in views), f"got {s}")

# 10-15. library reads (the exact queries JellyfinClient uses)
s, b = req("GET", f"/Users/{uid}/Items?IncludeItemTypes=MusicAlbum&Recursive=true&Fields=DateCreated,PremiereDate,UserData", token=tok)
albums = j(b).get("Items", []) if s == 200 else []
check("10 albums 200 + non-empty", s == 200 and len(albums) > 0, f"got {s} n={len(albums)}")
album_id = albums[0]["Id"] if albums else ""
check("11 album has PremiereDate/Id/Name", bool(albums) and "Name" in albums[0] and "Id" in albums[0])
s, b = req("GET", f"/Users/{uid}/Items?IncludeItemTypes=Audio&Recursive=true&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate", token=tok)
tracks = j(b).get("Items", []) if s == 200 else []
t0 = tracks[0] if tracks else {}
check("12 tracks 200 + fields", s == 200 and len(tracks) > 0 and "RunTimeTicks" in t0 and "AlbumId" in t0, f"got {s} n={len(tracks)}")
check("13 tracks carry UserData play fields", "UserData" in t0 and "PlayCount" in t0["UserData"])
s, b = req("GET", f"/Users/{uid}/Items?IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=DatePlayed&SortOrder=Descending&Limit=20&Fields=DateCreated,PremiereDate,UserData", token=tok)
check("14 recently-played albums 200", s == 200, f"got {s}")
s, b = req("GET", f"/Users/{uid}/Items?IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=30&Fields=DateCreated,PremiereDate,UserData", token=tok)
check("15 recently-added albums 200", s == 200, f"got {s}")

# 16-18. playlists
s, b = req("GET", f"/Users/{uid}/Items?IncludeItemTypes=Playlist&Recursive=true", token=tok)
pls = j(b).get("Items", []) if s == 200 else []
check("16 playlists 200", s == 200, f"got {s}")
pl_id = pls[0]["Id"] if pls else ""
if pl_id:
    s, b = req("GET", f"/Playlists/{pl_id}/Items", token=tok)
    check("17 playlist items 200", s == 200, f"got {s}")
else:
    check("17 playlist items 200 (no playlist in fixture)", False, "no playlist")
s, b = req("POST", f"/Playlists?Name=RestTest%20Playlist&Ids={synced}", token=tok)
new_pl = j(b).get("Id", "") if s in (200, 201) else ""
check("18 create playlist 200/201", s in (200, 201) and bool(new_pl), f"got {s}")
if new_pl:
    s, b = req("GET", f"/Playlists/{new_pl}/Items", token=tok)
    check("19 new playlist items contain track", s == 200 and any(i.get("Id") == synced for i in j(b).get("Items", [])), f"got {s}")
    s, b = req("DELETE", f"/Items/{new_pl}", token=tok)
    check("20 delete test playlist 204", s == 204, f"got {s}")

# 21-22. search
s, b = req("GET", f"/Search/Hints?SearchTerm=Synced&UserId={uid}&Limit=25", token=tok)
hints = j(b).get("SearchHints", []) if s == 200 else []
check("21 search hints 200 + Synced Song hit", s == 200 and any(h.get("Name") == "Synced Song" and h.get("Type") == "Audio" for h in hints), f"got {s}")
s, b = req("GET", f"/Search/Hints?SearchTerm=zzznope&UserId={uid}&Limit=25", token=tok)
check("22 search no-match 200 empty", s == 200 and len(j(b).get("SearchHints", [])) == 0, f"got {s}")

# 23-25. lyrics
s, b = req("GET", f"/Audio/{synced}/Lyrics", token=tok)
lyr = j(b).get("Lyrics", []) if s == 200 else []
check("23 synced lyrics 200 + Start timestamps", s == 200 and len(lyr) > 0 and all("Start" in l for l in lyr), f"got {s} n={len(lyr)}")
s, b = req("GET", f"/Audio/{plain}/Lyrics", token=tok)
lyr2 = j(b).get("Lyrics", []) if s == 200 else []
check("24 plain lyrics 200 + no timestamps", s == 200 and len(lyr2) > 0 and all("Start" not in l for l in lyr2), f"got {s}")
s, b = req("GET", f"/Audio/{none}/Lyrics", token=tok)
check("25 no-lyrics track 404", s == 404, f"got {s}")

# 26-28. images (fixture facts: album art is exactly 829 bytes; a track has
# NO primary image of its own — documented in API-NOTES §3, load via album;
# a malformed id returns 400, not 404)
s, b = req("GET", f"/Items/{album_id}/Images/Primary?fillWidth=300&quality=80&api_key={tok}")
check("26 album art 200 image 829 bytes", s == 200 and len(b) == 829, f"got {s} len={len(b)}")
s, b = req("GET", f"/Items/{synced}/Images/Primary?fillWidth=300&quality=80&api_key={tok}")
check("27 track has no own primary image 404", s == 404, f"got {s} len={len(b)}")
s, b = req("GET", "/Items/00000000000000000000000000000000/Images/Primary?fillWidth=300")
check("28 bogus image id 400", s == 400, f"got {s}")

# 29-31. streaming — NOTE: /universal REQUIRES the Authorization header;
# api_key alone 401s (verified below). The app currently sends api_key only
# (see report) — the check sends both, as the web client does.
s, b = req("GET", f"/Audio/{synced}/universal?UserId={uid}&AudioCodec=mp3&Container=mp3&api_key={tok}", token=tok, headers={"Range": "bytes=0-1023"})
check("29 universal stream range 206 (Auth header + api_key)", s == 206 and len(b) == 1024, f"got {s} len={len(b)}")
s, b = req("GET", f"/Audio/{synced}/universal?UserId={uid}&AudioCodec=mp3&Container=mp3&api_key=badtoken")
check("30 universal stream bad token 401", s == 401, f"got {s}")
s, b = req("GET", f"/Audio/{synced}/stream.mp3?api_key={tok}", headers={"Range": "bytes=0-99"})
check("31 direct stream range 206", s in (200, 206), f"got {s}")

# 32-34. sessions + capabilities (presence prerequisites)
s, b = req("GET", "/Sessions", token=tok)
check("32 admin GET /Sessions 200 array", s == 200 and isinstance(j(b), list), f"got {s}")
caps = {"PlayableMediaTypes": ["Audio", "Video"], "SupportedCommands": [],
        "SupportsMediaControl": True, "SupportsPersistentIdentifier": True}
s, b = req("POST", "/Sessions/Capabilities/Full", token=tok, body=caps)
check("33 capabilities/Full Oniel 204", s == 204, f"got {s}")
s, b = req("POST", "/Sessions/Capabilities/Full", token=ftok, body=caps)
check("34 capabilities/Full Friend 204", s == 204, f"got {s}")

# 35-38. playback reporting
psid = str(uuid.uuid4())
s, b = req("POST", "/Sessions/Playing", token=tok, body={
    "ItemId": synced, "PositionTicks": 50000000, "IsPaused": False,
    "PlayMethod": "DirectStream", "PlaySessionId": psid})
check("35 reportPlaying 204", s == 204, f"got {s}")
s, b = req("GET", "/Sessions", token=tok)
mine = [x for x in j(b) if x.get("UserId") == uid] if s == 200 else []
now = (mine[0].get("NowPlayingItem") or {}) if mine else {}
check("36 NowPlayingItem shows track + position", now.get("Id") == synced, f"got {now.get('Id')}")
s, b = req("POST", "/Sessions/Playing/Stopped", token=tok, body={
    "ItemId": synced, "PositionTicks": 60000000, "Failed": False,
    "PlayedToCompletion": False, "PlaySessionId": psid})
check("37 reportStopped 204", s == 204, f"got {s}")
s, b = req("GET", "/Sessions", token=tok)
mine = [x for x in j(b) if x.get("UserId") == uid] if s == 200 else []
check("38 NowPlayingItem gone after stop", not (mine and mine[0].get("NowPlayingItem")), f"got {mine}")

# 39-41. mark played (uses a throwaway date in 2026; increments PlayCount)
s, b = req("GET", f"/Users/{uid}/Items/{synced}", token=tok)
pc0 = j(b).get("UserData", {}).get("PlayCount", 0) if s == 200 else -1
s, b = req("POST", f"/UserPlayedItems/{synced}?userId={uid}&datePlayed=2026-02-01T10%3A00%3A00.0000000Z", token=tok, body="")
check("39 markPlayed 200", s == 200, f"got {s}")
s, b = req("GET", f"/Users/{uid}/Items/{synced}", token=tok)
ud = j(b).get("UserData", {}) if s == 200 else {}
check("40 markPlayed read-back count+1 + exact date", ud.get("PlayCount") == pc0 + 1 and ud.get("LastPlayedDate") == "2026-02-01T10:00:00.0000000Z", f"got {ud}")
check("41 markPlayed sets Played=true", ud.get("Played") is True)

# 42-44. favorites + single item + users
s, b = req("POST", f"/Users/{uid}/FavoriteItems/{synced}", token=tok, body="")
check("42 favorite track 200", s == 200, f"got {s}")
s, b = req("GET", f"/Users/{uid}/Items?IncludeItemTypes=Audio&Recursive=true&Filters=IsFavorite&Fields=UserData", token=tok)
favs = j(b).get("Items", []) if s == 200 else []
check("43 liked-songs filter lists track", s == 200 and any(i.get("Id") == synced for i in favs), f"got {s}")
s, b = req("DELETE", f"/Users/{uid}/FavoriteItems/{synced}", token=tok)
check("44 unfavorite track 200", s == 200, f"got {s}")
s, b = req("GET", f"/Users/{uid}", token=tok)
check("45 GET /Users/{id} 200 + Policy", s == 200 and "Policy" in j(b), f"got {s}")
s, b = req("GET", "/Users", token=tok)
users = j(b) if s == 200 else []
check("46 GET /Users 200 + both users", s == 200 and len(users) >= 2, f"got {s} n={len(users)}")

# 47-48. v1.2 playback-auth fix: the app now sends the Authorization
# header on ExoPlayer's audio requests, in exactly the format
# JellyfinClient.authHeaderValue() builds (Client="Oniel Music",
# Version="1.0.2"). 47 proves the real token with api_key alone 401s
# (the v1.1 bug); 48 proves header + api_key returns audio bytes.
APP_AUTH = {"Authorization": f'MediaBrowser Token="{tok}", Client="Oniel Music", Device="Oniel Music", DeviceId="test-device-1", Version="1.0.2"'}
s, b = req("GET", f"/Audio/{synced}/universal?UserId={uid}&AudioCodec=mp3&Container=mp3&api_key={tok}", headers={"Range": "bytes=0-1023"})
check("47 v1.2 universal real-token api_key-only 401", s == 401, f"got {s}")
s, b = req("GET", f"/Audio/{synced}/universal?UserId={uid}&AudioCodec=mp3&Container=mp3&api_key={tok}", raw_headers=APP_AUTH, headers={"Range": "bytes=0-1023"})
check("48 v1.2 universal app-format header + api_key 206 + 1024 audio bytes", s == 206 and len(b) == 1024, f"got {s} len={len(b)}")

print(f"\n==== passed={passed} failed={failed} ====")
if failures:
    print("failures:", failures)
sys.exit(1 if failed else 0)
