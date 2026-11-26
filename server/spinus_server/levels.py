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

# Rank names, in order. Each title starts one level further after the
# last than the one before it did (levels 1, 2, 4, 7, 11, 16, 22, ...), and
# every level costs more XP than the last, so each title is harder to earn.
# After the last name, ranks keep coming forever: Immortal II, III, IV, ...
TITLE_NAMES = [
    "Newcomer", "Listener", "Explorer", "Crate Digger", "Tastemaker", "Audiophile",
    "Connoisseur", "Maestro", "Virtuoso", "Legend", "Icon", "Mythic", "Immortal",
]
CURVE = 2.2  # XP to reach a level grows faster than the level squared: no top level


def xp_for_level(level: int) -> int:
    """Total XP needed to reach [level] (level 1 = 0)."""
    return round(60 * (level - 1) ** CURVE)


def level_for_xp(xp: int) -> int:
    level = max(1, int((max(xp, 0) / 60) ** (1 / CURVE)) + 1)
    while xp_for_level(level + 1) <= xp:  # fix rounding at the edges
        level += 1
    while level > 1 and xp_for_level(level) > xp:
        level -= 1
    return level


def _roman(n: int) -> str:
    out = ""
    for value, sym in ((1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"),
                       (50, "L"), (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")):
        while n >= value:
            out += sym
            n -= value
    return out


def _rank_name(rank: int) -> str:
    """Rank 0 = Newcomer ... past the list: Immortal II, Immortal III, ..."""
    if rank < len(TITLE_NAMES):
        return TITLE_NAMES[rank]
    return f"{TITLE_NAMES[-1]} {_roman(rank - len(TITLE_NAMES) + 2)}"


def _rank_start(rank: int) -> int:
    """Level where rank [rank] begins: 1, 2, 4, 7, 11, 16, ... (gap grows by one each time)."""
    return 1 + rank * (rank + 1) // 2


def _rank_for_level(level: int) -> int:
    rank = 0
    while _rank_start(rank + 1) <= level:
        rank += 1
    return rank


def title_for(level: int) -> str:
    return _rank_name(_rank_for_level(level))


def next_title(level: int) -> tuple[int, str]:
    rank = _rank_for_level(level) + 1
    return _rank_start(rank), _rank_name(rank)


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
        "nextLevelXp": end,
        "progress": round((xp - start) / (end - start), 4),
        "nextTitle": {"level": nxt[0], "title": nxt[1]},
        "breakdown": breakdown,
    }
