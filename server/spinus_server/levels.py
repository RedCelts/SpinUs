"""Listener levels: XP from how much and how widely you listen.

Everything is worked out from what the server already keeps (plays,
favorites, playlists, friends, recommendations, listening days), so a
level can't drift from the data and friends see the same number you do.
"""
from __future__ import annotations

import collections
from dataclasses import dataclass

from .library import Index, fold

# (key, label, XP per unit). Tuned so a casual listener levels up every
# few days at first and a dedicated one keeps climbing for months.
SOURCES = [
    ("songs", "Different songs played", 10),
    ("minutes", "Minutes listened", 1),
    ("albums", "Different albums played", 25),
    ("artists", "Different artists played", 15),
    ("genres", "Genres explored (3+ plays)", 40),
    ("days", "Days you used SpinUs", 30),
    ("likes", "Songs, albums and artists liked", 5),
    ("playlists", "Playlists made (3+ songs)", 50),
    ("friends", "Friends", 30),
    ("recommendations", "Recommendations sent", 20),
]

# Titles by the level they start at.
TITLES = [
    (1, "Newcomer"),
    (3, "Listener"),
    (6, "Explorer"),
    (10, "Crate Digger"),
    (15, "Tastemaker"),
    (20, "Audiophile"),
    (30, "Maestro"),
    (40, "Virtuoso"),
    (50, "Legend"),
]
MAX_LEVEL = 99


def xp_for_level(level: int) -> int:
    """Total XP needed to reach [level] (level 1 = 0). Each level costs a bit more than the last."""
    return 60 * (level - 1) ** 2


def level_for_xp(xp: int) -> int:
    level = 1
    while level < MAX_LEVEL and xp >= xp_for_level(level + 1):
        level += 1
    return level


def title_for(level: int) -> str:
    return [t for start, t in TITLES if level >= start][-1]


def next_title(level: int) -> tuple[int, str] | None:
    for start, t in TITLES:
        if start > level:
            return start, t
    return None


@dataclass
class Counts:
    songs: int = 0
    minutes: int = 0
    albums: int = 0
    artists: int = 0
    genres: int = 0
    days: int = 0
    likes: int = 0
    playlists: int = 0
    friends: int = 0
    recommendations: int = 0


def count(db, idx: Index, user_id: str) -> Counts:
    plays = db.plays(user_id)  # track_id -> (play_count, last_played)
    c = Counts()
    album_ids, artist_keys = set(), set()
    genre_plays: collections.Counter = collections.Counter()
    ms = 0
    for tid, (n, _last) in plays.items():
        t = idx.tracks.get(tid)
        if t is None or n <= 0:
            continue
        c.songs += 1
        ms += n * t.duration_ms
        if t.album_id:
            album_ids.add(t.album_id)
        for a in t.artists or [t.album_artist]:
            artist_keys.add(fold(a))
        for g in t.genres:
            genre_plays[fold(g)] += n
    c.minutes = ms // 60000
    c.albums = len(album_ids)
    c.artists = len(artist_keys)
    c.genres = sum(1 for n in genre_plays.values() if n >= 3)
    c.days = db.listening_days(user_id)
    c.likes = len(db.favorites(user_id))
    c.playlists = sum(1 for p in db.playlists(user_id) if p["n"] >= 3)
    c.friends = len(db.friend_ids(user_id))
    c.recommendations = db.recommendations_sent(user_id)
    return c


def summary(db, idx: Index, user_id: str) -> dict:
    c = count(db, idx, user_id)
    breakdown = []
    xp = 0
    for key, label, per in SOURCES:
        n = getattr(c, key)
        gained = n * per
        xp += gained
        breakdown.append({"key": key, "label": label, "count": n, "xpEach": per, "xp": gained})
    level = level_for_xp(xp)
    start, end = xp_for_level(level), xp_for_level(level + 1)
    nxt = next_title(level)
    return {
        "level": level,
        "title": title_for(level),
        "xp": xp,
        "levelStartXp": start,
        "nextLevelXp": end if level < MAX_LEVEL else None,
        "progress": 1.0 if level >= MAX_LEVEL else round((xp - start) / (end - start), 4),
        "nextTitle": {"level": nxt[0], "title": nxt[1]} if nxt else None,
        "breakdown": breakdown,
    }
