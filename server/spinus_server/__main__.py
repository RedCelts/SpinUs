"""SpinUs server command line.

  python -m spinus_server                     run the server
  python -m spinus_server adduser NAME [--admin]
  python -m spinus_server passwd NAME
  python -m spinus_server deluser NAME
  python -m spinus_server users
  python -m spinus_server scan

Settings come from environment variables: SPINUS_MUSIC (music folder,
default /music), SPINUS_DATA (database + caches, default /data),
SPINUS_PORT (default 8484), SPINUS_RESCAN_MINUTES (default 15, 0 = off),
SPINUS_FETCH_ARTWORK (default 1: download missing covers / artist photos
from Deezer), SPINUS_FETCH_LYRICS (default 1: download missing lyrics from LRCLIB).
"""
import argparse
import getpass
import logging
import os
import sys

from aiohttp import web

from .app import Config, SpinUs
from .db import Database


def main(argv=None):
    p = argparse.ArgumentParser(prog="spinus_server")
    sub = p.add_subparsers(dest="cmd")
    a = sub.add_parser("adduser"); a.add_argument("name"); a.add_argument("--admin", action="store_true")
    a.add_argument("--password", help="(default: ask)")
    w = sub.add_parser("passwd"); w.add_argument("name"); w.add_argument("--password")
    d = sub.add_parser("deluser"); d.add_argument("name")
    sub.add_parser("users")
    sub.add_parser("scan")
    args = p.parse_args(argv)

    logging.basicConfig(level=os.environ.get("SPINUS_LOG", "INFO"),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = Config.from_env()
    if args.cmd in ("adduser", "passwd"):
        pw = args.password or getpass.getpass(f"Password for {args.name}: ")
        db = Database(config.data / "spinus.db")
        if args.cmd == "adduser":
            db.create_user(args.name, pw, args.admin)
            print(f"added {args.name}{' (admin)' if args.admin else ''}")
        elif not db.set_password(args.name, pw):
            sys.exit(f"no user {args.name}")
        return
    if args.cmd == "deluser":
        if not Database(config.data / "spinus.db").delete_user(args.name):
            sys.exit(f"no user {args.name}")
        return
    if args.cmd == "users":
        for u in Database(config.data / "spinus.db").list_users():
            print(u["name"], "(admin)" if u["isAdmin"] else "")
        return
    server = SpinUs(config)
    if args.cmd == "scan":
        print(server.library.scan())
        return

    admin_user, admin_pw = os.environ.get("SPINUS_ADMIN_USER"), os.environ.get("SPINUS_ADMIN_PASSWORD")
    if server.db.user_count() == 0 and admin_user and admin_pw:
        server.db.create_user(admin_user, admin_pw, is_admin=True)
        logging.info("created admin account %r from SPINUS_ADMIN_USER", admin_user)
    if not config.music.is_dir():
        logging.error("music folder %s does not exist (set SPINUS_MUSIC)", config.music)
    if not config.ffmpeg:
        logging.warning("ffmpeg not found: ALAC/WMA/AIFF/APE files won't play")
    logging.info("SpinUs server on port %d, music in %s", config.port, config.music)
    web.run_app(server.make_app(), port=config.port, print=None, access_log=None)


if __name__ == "__main__":
    main()
