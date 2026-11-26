"""Every /api path the Android client builds must exist on the server."""
import re
from pathlib import Path

from spinus_server.app import Config, SpinUs

CLIENT = Path(__file__).resolve().parents[2] / "app/src/main/java/com/oniel/music/data/SpinUsClient.kt"


def test_client_paths_exist(tmp_path):
    app = SpinUs(Config(music=tmp_path, data=tmp_path / "d", rescan_minutes=0)).make_app()
    routes = [(r.method, r.resource.canonical) for r in app.router.routes()]
    patterns = [re.compile("^" + re.sub(r"\\\{[^}]+\\\}", "[^/]+", re.escape(c)) + "$") for _, c in routes]
    src = CLIENT.read_text()
    paths = set(re.findall(r'"[^"]*?(/api/[^"?]*)', src))
    assert len(paths) > 20
    missing = []
    for p in paths:
        concrete = re.sub(r"\$\{[^}]+\}|\$\w+", "X", p)
        if not any(pat.match(concrete) for pat in patterns):
            missing.append(p)
    assert not missing, missing
