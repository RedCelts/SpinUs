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

    def create_user(self, name: str, password: str, is_admin: bool = False) -> dict:
        name = name.strip()
        if not name or not password:
            raise ValueError("name and password are required")
        salt = secrets.token_hex(16)
        uid = secrets.token_hex(16)
        try:
            self.x("INSERT INTO users VALUES (?,?,?,?,?,?)",
                   (uid, name, hash_password(password, salt), salt, int(is_admin), time.time()))
        except sqlite3.IntegrityError:
            raise ValueError(f"user {name!r} already exists")
        return {"id": uid, "name": name, "isAdmin": is_admin}

    def set_password(self, name: str, password: str) -> bool:
        salt = secrets.token_hex(16)
        return self.x("UPDATE users SET pw_hash=?, salt=? WHERE name=?",
                      (hash_password(password, salt), salt, name)) > 0

    def delete_user(self, name: str) -> bool:
        return self.x("DELETE FROM users WHERE name=?", (name,)) > 0

    def list_users(self) -> list[dict]:
        return [{"id": r["id"], "name": r["name"], "isAdmin": bool(r["is_admin"])}
                for r in self.q("SELECT * FROM users ORDER BY name")]

    def check_login(self, name: str, password: str) -> dict | None:
        rows = self.q("SELECT * FROM users WHERE name=?", (name.strip(),))
        if not rows:
            hash_password(password, "00" * 16)  # same work either way
            return None
        r = rows[0]
        if not hmac.compare_digest(r["pw_hash"], hash_password(password, r["salt"])):
            return None
        return {"id": r["id"], "name": r["name"], "isAdmin": bool(r["is_admin"])}

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
        rows = self.q("""SELECT t.*, u.name user_name, u.is_admin FROM tokens t
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

    def mark_played(self, user_id: str, track_id: str, when_iso: str):
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
