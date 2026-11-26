"""SQLite storage: users, logins, favorites, plays, playlists, app prefs and
the library scan cache. One connection, guarded by a lock; every call is
short, so the aiohttp handlers call these directly."""
from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import sqlite3
import threading
import time
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE,
  pw_hash TEXT NOT NULL,
  salt TEXT NOT NULL,
  is_admin INTEGER NOT NULL DEFAULT 0,
  created REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS tokens (
  token TEXT PRIMARY KEY,
  session_id TEXT NOT NULL UNIQUE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  device_name TEXT,
  client TEXT,
  version TEXT,
  created REAL NOT NULL,
  last_seen REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS favorites (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  item_id TEXT NOT NULL,
  added REAL NOT NULL,
  PRIMARY KEY (user_id, item_id)
);
CREATE TABLE IF NOT EXISTS plays (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  track_id TEXT NOT NULL,
  play_count INTEGER NOT NULL DEFAULT 0,
  last_played TEXT,
  PRIMARY KEY (user_id, track_id)
);
CREATE TABLE IF NOT EXISTS playlists (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created REAL NOT NULL,
  updated REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_items (
  playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
  pos INTEGER NOT NULL,
  track_id TEXT NOT NULL,
  PRIMARY KEY (playlist_id, pos)
);
CREATE TABLE IF NOT EXISTS prefs (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  key TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (user_id, key)
);
CREATE TABLE IF NOT EXISTS recommendations (
  id TEXT PRIMARY KEY,
  from_user TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_user TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  item_type TEXT NOT NULL,
  item_id TEXT NOT NULL,
  message TEXT,
  created REAL NOT NULL,
  read_at REAL
);
CREATE INDEX IF NOT EXISTS rec_to ON recommendations(to_user, created);
CREATE TABLE IF NOT EXISTS friendships (
  requester TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  addressee TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status TEXT NOT NULL,              -- 'pending' or 'accepted'
  created REAL NOT NULL,
  PRIMARY KEY (requester, addressee)
);
CREATE TABLE IF NOT EXISTS listening_days (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  day TEXT NOT NULL,                 -- YYYY-MM-DD (UTC)
  PRIMARY KEY (user_id, day)
);
CREATE TABLE IF NOT EXISTS scan_cache (
  rel_path TEXT PRIMARY KEY,
  mtime_ns INTEGER NOT NULL,
  size INTEGER NOT NULL,
  meta TEXT NOT NULL,
  first_seen REAL NOT NULL
);
"""


def hash_password(password: str, salt: str) -> str:
    return hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=2 ** 14, r=8, p=1).hex()


class Database:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA foreign_keys=ON")
        self.conn.executescript(SCHEMA)
        cols = {r[1] for r in self.conn.execute("PRAGMA table_info(users)")}
        if "must_change" not in cols:
            # Accounts made by an admin carry a temporary password until
            # their owner sets one.
            self.conn.execute("ALTER TABLE users ADD COLUMN must_change INTEGER NOT NULL DEFAULT 0")
        if "friend_code" not in cols:
            # Shown as a QR code: scanning it connects two people at once.
            self.conn.execute("ALTER TABLE users ADD COLUMN friend_code TEXT")
        self.lock = threading.RLock()

    def q(self, sql: str, args=()) -> list[sqlite3.Row]:
        with self.lock:
            return self.conn.execute(sql, args).fetchall()

    def x(self, sql: str, args=()) -> int:
        with self.lock:
            return self.conn.execute(sql, args).rowcount

    # ---- users

    def user_count(self) -> int:
        return self.q("SELECT COUNT(*) c FROM users")[0]["c"]

    def create_user(self, name: str, password: str, is_admin: bool = False, temporary: bool = False) -> dict:
        name = name.strip()
        if not name or not password:
            raise ValueError("name and password are required")
        salt = secrets.token_hex(16)
        uid = secrets.token_hex(16)
        try:
            self.x("INSERT INTO users (id, name, pw_hash, salt, is_admin, created, must_change) VALUES (?,?,?,?,?,?,?)",
                   (uid, name, hash_password(password, salt), salt, int(is_admin), time.time(), int(temporary)))
        except sqlite3.IntegrityError:
            raise ValueError(f"user {name!r} already exists")
        return {"id": uid, "name": name, "isAdmin": is_admin, "mustChangePassword": temporary}

    def set_password(self, name: str, password: str, temporary: bool = False) -> bool:
        salt = secrets.token_hex(16)
        return self.x("UPDATE users SET pw_hash=?, salt=?, must_change=? WHERE name=?",
                      (hash_password(password, salt), salt, int(temporary), name)) > 0

    def revoke_other_tokens(self, user_id: str, keep_token: str):
        self.x("DELETE FROM tokens WHERE user_id=? AND token<>?", (user_id, keep_token))

    def delete_user(self, name: str) -> bool:
        return self.x("DELETE FROM users WHERE name=?", (name,)) > 0

    def list_users(self) -> list[dict]:
        return [{"id": r["id"], "name": r["name"], "isAdmin": bool(r["is_admin"]),
                 "mustChangePassword": bool(r["must_change"])}
                for r in self.q("SELECT * FROM users ORDER BY name COLLATE NOCASE")]

    def check_login(self, name: str, password: str) -> dict | None:
        rows = self.q("SELECT * FROM users WHERE name=?", (name.strip(),))
        if not rows:
            hash_password(password, "00" * 16)  # same work either way
            return None
        r = rows[0]
        if not hmac.compare_digest(r["pw_hash"], hash_password(password, r["salt"])):
            return None
        return {"id": r["id"], "name": r["name"], "isAdmin": bool(r["is_admin"]),
                "mustChangePassword": bool(r["must_change"])}

    # ---- tokens (one per signed-in device; the token row is the "session")

    def issue_token(self, user_id: str, device_id: str, device_name: str, client: str, version: str) -> tuple[str, str]:
        now = time.time()
        token = secrets.token_urlsafe(32)
        session_id = secrets.token_hex(12)
        with self.lock:
            self.conn.execute("DELETE FROM tokens WHERE user_id=? AND device_id=?", (user_id, device_id))
            self.conn.execute("INSERT INTO tokens VALUES (?,?,?,?,?,?,?,?,?)",
                              (token, session_id, user_id, device_id, device_name, client, version, now, now))
        return token, session_id

    def token_info(self, token: str) -> dict | None:
        rows = self.q("""SELECT t.*, u.name user_name, u.is_admin, u.must_change FROM tokens t
                         JOIN users u ON u.id=t.user_id WHERE t.token=?""", (token,))
        if not rows:
            return None
        r = dict(rows[0])
        if time.time() - r["last_seen"] > 300:
            self.x("UPDATE tokens SET last_seen=? WHERE token=?", (time.time(), token))
        return r

    def revoke_token(self, token: str):
        self.x("DELETE FROM tokens WHERE token=?", (token,))

    # ---- favorites

    def favorites(self, user_id: str) -> set[str]:
        return {r["item_id"] for r in self.q("SELECT item_id FROM favorites WHERE user_id=?", (user_id,))}

    def set_favorite(self, user_id: str, item_id: str, on: bool):
        if on:
            self.x("INSERT OR IGNORE INTO favorites VALUES (?,?,?)", (user_id, item_id, time.time()))
        else:
            self.x("DELETE FROM favorites WHERE user_id=? AND item_id=?", (user_id, item_id))

    # ---- plays

    def plays(self, user_id: str) -> dict[str, tuple[int, str | None]]:
        return {r["track_id"]: (r["play_count"], r["last_played"])
                for r in self.q("SELECT * FROM plays WHERE user_id=?", (user_id,))}

    def add_listening_day(self, user_id: str, day: str):
        self.x("INSERT OR IGNORE INTO listening_days VALUES (?,?)", (user_id, day[:10]))

    def listening_days(self, user_id: str) -> int:
        n = self.q("SELECT COUNT(*) c FROM listening_days WHERE user_id=?", (user_id,))[0]["c"]
        if n == 0:
            # Before days were recorded: count the distinct last-played days.
            rows = self.q("SELECT DISTINCT substr(last_played,1,10) d FROM plays WHERE user_id=? AND last_played IS NOT NULL",
                          (user_id,))
            for r in rows:
                self.add_listening_day(user_id, r["d"])
            n = len(rows)
        return n

    def recommendations_sent(self, user_id: str) -> int:
        return self.q("SELECT COUNT(*) c FROM recommendations WHERE from_user=?", (user_id,))[0]["c"]

    def mark_played(self, user_id: str, track_id: str, when_iso: str):
        self.add_listening_day(user_id, when_iso)
        self.x("""INSERT INTO plays VALUES (?,?,1,?)
                  ON CONFLICT(user_id, track_id) DO UPDATE SET
                    play_count = play_count + 1,
                    last_played = CASE WHEN last_played IS NULL OR excluded.last_played > last_played
                                       THEN excluded.last_played ELSE last_played END""",
               (user_id, track_id, when_iso))

    # ---- playlists

    def playlists(self, user_id: str) -> list[dict]:
        return [dict(r) for r in self.q(
            """SELECT p.id, p.name, p.updated, COUNT(i.track_id) n FROM playlists p
               LEFT JOIN playlist_items i ON i.playlist_id=p.id
               WHERE p.user_id=? GROUP BY p.id ORDER BY p.name COLLATE NOCASE""", (user_id,))]

    def playlist_owner(self, playlist_id: str) -> str | None:
        rows = self.q("SELECT user_id FROM playlists WHERE id=?", (playlist_id,))
        return rows[0]["user_id"] if rows else None

    def create_playlist(self, user_id: str, name: str, track_ids: list[str]) -> str:
        pid = secrets.token_hex(12)
        now = time.time()
        with self.lock:
            self.conn.execute("INSERT INTO playlists VALUES (?,?,?,?,?)", (pid, user_id, name, now, now))
        self.add_to_playlist(pid, track_ids)
        return pid

    def add_to_playlist(self, playlist_id: str, track_ids: list[str]):
        with self.lock:
            start = self.conn.execute("SELECT COALESCE(MAX(pos)+1, 0) FROM playlist_items WHERE playlist_id=?",
                                      (playlist_id,)).fetchone()[0]
            self.conn.executemany("INSERT INTO playlist_items VALUES (?,?,?)",
                                  [(playlist_id, start + i, t) for i, t in enumerate(track_ids)])
            self.conn.execute("UPDATE playlists SET updated=? WHERE id=?", (time.time(), playlist_id))

    def playlist_tracks(self, playlist_id: str) -> list[str]:
        return [r["track_id"] for r in self.q(
            "SELECT track_id FROM playlist_items WHERE playlist_id=? ORDER BY pos", (playlist_id,))]

    def remove_from_playlist(self, playlist_id: str, positions: list[int]):
        with self.lock:
            ids = self.playlist_tracks(playlist_id)
            keep = [t for i, t in enumerate(ids) if i not in set(positions)]
            self.conn.execute("DELETE FROM playlist_items WHERE playlist_id=?", (playlist_id,))
        self.add_to_playlist(playlist_id, keep)

    def rename_playlist(self, playlist_id: str, name: str):
        self.x("UPDATE playlists SET name=?, updated=? WHERE id=?", (name, time.time(), playlist_id))

    def delete_playlist(self, playlist_id: str):
        self.x("DELETE FROM playlists WHERE id=?", (playlist_id,))

    # ---- per-user app storage

    def get_pref(self, user_id: str, key: str) -> dict | None:
        rows = self.q("SELECT value FROM prefs WHERE user_id=? AND key=?", (user_id, key))
        return json.loads(rows[0]["value"]) if rows else None

    def put_pref(self, user_id: str, key: str, value: dict):
        self.x("INSERT OR REPLACE INTO prefs VALUES (?,?,?)", (user_id, key, json.dumps(value)))

    # ---- friends (mutual: a request, then an accept; or a scanned QR code)

    def user_name(self, user_id: str) -> str | None:
        rows = self.q("SELECT name FROM users WHERE id=?", (user_id,))
        return rows[0]["name"] if rows else None

    def friend_ids(self, user_id: str) -> set[str]:
        rows = self.q("""SELECT addressee o FROM friendships WHERE requester=? AND status='accepted'
                         UNION SELECT requester o FROM friendships WHERE addressee=? AND status='accepted'""",
                      (user_id, user_id))
        return {r["o"] for r in rows}

    def friends(self, user_id: str) -> list[dict]:
        ids = self.friend_ids(user_id)
        return [u for u in self.list_users() if u["id"] in ids]

    def relation(self, me: str, other: str) -> str | None:
        """'friends', 'requested' (I asked them), 'incoming' (they asked me) or None."""
        rows = self.q("SELECT * FROM friendships WHERE (requester=? AND addressee=?) OR (requester=? AND addressee=?)",
                      (me, other, other, me))
        for r in rows:
            if r["status"] == "accepted":
                return "friends"
        for r in rows:
            return "requested" if r["requester"] == me else "incoming"
        return None

    def friend_requests(self, user_id: str) -> dict:
        def users(sql):
            return [{"id": r["id"], "name": r["name"]} for r in self.q(sql, (user_id,))]
        return {
            "incoming": users("""SELECT u.id, u.name FROM friendships f JOIN users u ON u.id=f.requester
                                 WHERE f.addressee=? AND f.status='pending' ORDER BY f.created DESC"""),
            "outgoing": users("""SELECT u.id, u.name FROM friendships f JOIN users u ON u.id=f.addressee
                                 WHERE f.requester=? AND f.status='pending' ORDER BY f.created DESC"""),
        }

    def incoming_request_count(self, user_id: str) -> int:
        return self.q("SELECT COUNT(*) c FROM friendships WHERE addressee=? AND status='pending'", (user_id,))[0]["c"]

    def request_friend(self, me: str, other: str) -> str:
        """Ask [other]; if they already asked me, that makes us friends. Returns the new relation."""
        rel = self.relation(me, other)
        if rel == "incoming":
            return self.befriend(me, other)
        if rel is None:
            self.x("INSERT INTO friendships VALUES (?,?,'pending',?)", (me, other, time.time()))
            return "requested"
        return rel

    def accept_friend(self, me: str, other: str) -> bool:
        return self.x("UPDATE friendships SET status='accepted' WHERE requester=? AND addressee=? AND status='pending'",
                      (other, me)) > 0

    def befriend(self, a: str, b: str) -> str:
        with self.lock:
            self.conn.execute("DELETE FROM friendships WHERE (requester=? AND addressee=?) OR (requester=? AND addressee=?)",
                              (a, b, b, a))
            self.conn.execute("INSERT INTO friendships VALUES (?,?,'accepted',?)", (a, b, time.time()))
        return "friends"

    def remove_friend(self, me: str, other: str) -> bool:
        """Unfriend, cancel my request, or decline theirs."""
        return self.x("DELETE FROM friendships WHERE (requester=? AND addressee=?) OR (requester=? AND addressee=?)",
                      (me, other, other, me)) > 0

    def search_users(self, me: str, q: str, limit: int = 20) -> list[dict]:
        rows = self.q("SELECT id, name FROM users WHERE id<>? AND name LIKE ? ORDER BY name COLLATE NOCASE LIMIT ?",
                      (me, f"%{q.strip()}%", limit))
        return [{"id": r["id"], "name": r["name"], "relation": self.relation(me, r["id"])} for r in rows]

    def friend_code(self, user_id: str, reset: bool = False) -> str:
        rows = self.q("SELECT friend_code FROM users WHERE id=?", (user_id,))
        code = rows[0]["friend_code"] if rows else None
        if code and not reset:
            return code
        alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I
        code = "".join(secrets.choice(alphabet) for _ in range(8))
        self.x("UPDATE users SET friend_code=? WHERE id=?", (code, user_id))
        return code

    def user_by_code(self, code: str) -> str | None:
        rows = self.q("SELECT id FROM users WHERE friend_code=?", (code.strip().upper(),))
        return rows[0]["id"] if rows else None

    # ---- recommendations

    def add_recommendation(self, from_user: str, to_user: str, item_type: str, item_id: str,
                           message: str | None) -> str:
        rid = secrets.token_hex(12)
        self.x("INSERT INTO recommendations VALUES (?,?,?,?,?,?,?,NULL)",
               (rid, from_user, to_user, item_type, item_id, message, time.time()))
        return rid

    def recommendations(self, user_id: str, limit: int = 100) -> list[dict]:
        return [dict(r) for r in self.q(
            """SELECT r.*, u.name from_name FROM recommendations r JOIN users u ON u.id=r.from_user
               WHERE r.to_user=? ORDER BY r.created DESC LIMIT ?""", (user_id, limit))]

    def unread_recommendations(self, user_id: str) -> int:
        return self.q("SELECT COUNT(*) c FROM recommendations WHERE to_user=? AND read_at IS NULL",
                      (user_id,))[0]["c"]

    def mark_recommendations_read(self, user_id: str, ids: list[str] | None = None):
        if ids is None:
            self.x("UPDATE recommendations SET read_at=? WHERE to_user=? AND read_at IS NULL", (time.time(), user_id))
        else:
            for rid in ids:
                self.x("UPDATE recommendations SET read_at=? WHERE to_user=? AND id=? AND read_at IS NULL",
                       (time.time(), user_id, rid))

    def delete_recommendation(self, user_id: str, rid: str) -> bool:
        return self.x("DELETE FROM recommendations WHERE to_user=? AND id=?", (user_id, rid)) > 0

    # ---- scan cache

    def scan_cache(self) -> dict[str, tuple]:
        return {r["rel_path"]: (r["mtime_ns"], r["size"], json.loads(r["meta"]), r["first_seen"])
                for r in self.q("SELECT * FROM scan_cache")}

    def cached_meta(self, rel_path: str) -> dict | None:
        rows = self.q("SELECT meta FROM scan_cache WHERE rel_path=?", (rel_path,))
        return json.loads(rows[0]["meta"]) if rows else None

    def save_scan(self, rows: list[tuple], keep: set[str]):
        with self.lock:
            self.conn.execute("BEGIN")
            try:
                self.conn.executemany("INSERT OR REPLACE INTO scan_cache VALUES (?,?,?,?,?)", rows)
                existing = [r[0] for r in self.conn.execute("SELECT rel_path FROM scan_cache")]
                gone = [(p,) for p in existing if p not in keep]
                self.conn.executemany("DELETE FROM scan_cache WHERE rel_path=?", gone)
                self.conn.execute("COMMIT")
            except Exception:
                self.conn.execute("ROLLBACK")
                raise
