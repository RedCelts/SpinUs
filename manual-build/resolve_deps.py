#!/usr/bin/env python3
"""Resolve the Oniel Music dependency graph from Maven POMs (Google Maven +
Maven Central) with curl, matching app/build.gradle exactly.

Usage: resolve_deps.py <outdir>
Writes <outdir>/resolved.txt (group:artifact:version:packaging  path)
and downloads artifacts into <outdir>/files/.
"""
import os, re, subprocess, sys, xml.etree.ElementTree as ET
from collections import defaultdict

OUT = sys.argv[1]
os.makedirs(OUT + "/files", exist_ok=True)
os.makedirs(OUT + "/poms", exist_ok=True)

REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
]

def curl(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    for attempt in range(3):
        r = subprocess.run(["curl", "-fsSL", "--max-time", "60", "-o", dest, url],
                           capture_output=True)
        if r.returncode == 0 and os.path.exists(dest) and os.path.getsize(dest) > 0:
            return True
        if os.path.exists(dest):
            os.remove(dest)
    return False

def gav_path(g, a, v, ext, classifier=None):
    base = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}"
    if classifier:
        base += f"-{classifier}"
    return base + "." + ext

def fetch_pom(g, a, v):
    rel = gav_path(g, a, v, "pom")
    dest = f"{OUT}/poms/{g}__{a}__{v}.pom"
    if os.path.exists(dest):
        return dest
    for repo in REPOS:
        if curl(f"{repo}/{rel}", dest):
            head = open(dest, "rb").read(64)
            if head.lstrip().startswith(b"<"):
                return dest
            os.remove(dest)
        if os.path.exists(dest):
            os.remove(dest)
    print(f"WARN: no POM for {g}:{a}:{v}", flush=True)
    return None

NS = "{http://maven.apache.org/POM/4.0.0}"

def txt(el):
    return (el.text or "").strip() if el is not None else ""

def subst(s, props):
    if s is None:
        return None
    for _ in range(10):
        m = re.search(r"\$\{([^}]+)\}", s)
        if not m:
            break
        key = m.group(1)
        val = props.get(key)
        if val is None:
            break
        s = s[:m.start()] + val + s[m.end():]
    return s

def parse_pom(path):
    tree = ET.parse(path)
    root = tree.getroot()
    def find(p):
        return root.find(p if p.startswith(NS) else NS + p.replace("/", "/" + NS))
    def findall(p):
        return root.findall(p if p.startswith(NS) else NS + p.replace("/", "/" + NS))
    return root, find, findall

def load_pom_chain(g, a, v):
    """Return (pom_dict, props) following parents. pom_dict has keys:
    groupId, artifactId, version, packaging, deps[], depMgmt{}, properties."""
    chain = []
    cg, ca, cv = g, a, v
    seen = set()
    while True:
        key = (cg, ca, cv)
        if key in seen:
            break
        seen.add(key)
        path = fetch_pom(cg, ca, cv)
        if not path:
            return None
        root, find, findall = parse_pom(path)
        parent = find("parent")
        p = {
            "groupId": txt(find("groupId")),
            "artifactId": txt(find("artifactId")),
            "version": txt(find("version")),
            "packaging": txt(find("packaging")) or "jar",
            "parent": parent,
            "deps": [],
            "depMgmt": {},
            "props": {},
            "bom_imports": [],
        }
        for prop in findall("properties/*"):
            p["props"][prop.tag.replace(NS, "")] = txt(prop)
        if parent is not None:
            pg = txt(parent.find(NS + "groupId"))
            pa = txt(parent.find(NS + "artifactId"))
            pv = txt(parent.find(NS + "version"))
            p["_parent_gav"] = (pg, pa, pv)
        # dependencyManagement
        dm = find("dependencyManagement/dependencies")
        if dm is not None:
            for d in dm.findall(NS + "dependency"):
                dg = txt(d.find(NS + "groupId"))
                da = txt(d.find(NS + "artifactId"))
                dv = txt(d.find(NS + "version"))
                dtype = txt(d.find(NS + "type")) or "jar"
                dscope = txt(d.find(NS + "scope")) or "compile"
                if dscope == "import" and dtype == "pom":
                    p["bom_imports"].append((dg, da, dv))
                else:
                    p["depMgmt"][(dg, da)] = dv
        for d in findall("dependencies/dependency"):
            dep = {
                "groupId": txt(d.find(NS + "groupId")),
                "artifactId": txt(d.find(NS + "artifactId")),
                "version": txt(d.find(NS + "version")),
                "type": txt(d.find(NS + "type")) or "jar",
                "scope": txt(d.find(NS + "scope")) or "compile",
                "optional": txt(d.find(NS + "optional")) == "true",
                "classifier": txt(d.find(NS + "classifier")),
                "exclusions": set(),
            }
            ex = d.find(NS + "exclusions")
            if ex is not None:
                for e in ex.findall(NS + "exclusion"):
                    dep["exclusions"].add(
                        (txt(e.find(NS + "groupId")), txt(e.find(NS + "artifactId"))))
            p["deps"].append(dep)
        chain.append(p)
        if "_parent_gav" in p:
            cg, ca, cv = p["_parent_gav"]
        else:
            break
    # merge: child wins; props accumulate child-first
    merged = {"groupId": "", "artifactId": "", "version": "", "packaging": "jar",
              "deps": [], "depMgmt": {}, "props": {}, "bom_imports": []}
    for p in reversed(chain):  # parent first, child overrides
        merged["props"].update(p["props"])
        merged["depMgmt"].update(p["depMgmt"])
        merged["bom_imports"].extend(p["bom_imports"])
    top = chain[0]
    merged["groupId"] = top["groupId"] or chain[-1]["groupId"]
    merged["artifactId"] = top["artifactId"]
    merged["version"] = top["version"] or chain[-1]["version"]
    merged["packaging"] = top["packaging"]
    merged["deps"] = top["deps"]  # only the artifact's own deps, not parents'
    # built-in props
    merged["props"].setdefault("project.groupId", merged["groupId"])
    merged["props"].setdefault("project.artifactId", merged["artifactId"])
    merged["props"].setdefault("project.version", merged["version"])
    merged["props"].setdefault("groupId", merged["groupId"])
    merged["props"].setdefault("artifactId", merged["artifactId"])
    merged["props"].setdefault("version", merged["version"])
    return merged

def resolve_range(g, a, v):
    """Resolve a Maven version range like [1.6.1] or [1.0,2.0) to a concrete version."""
    m = re.match(r"^[\[\(]([^,\]\)]+)?,?([^,\]\)]*)?[\]\)]$", v)
    if not m:
        return v
    lo, hi = m.group(1), m.group(2)
    lo_inc = v.startswith("[")
    hi_inc = v.endswith("]")
    if lo and not hi and lo_inc:
        return lo  # exact [x]
    # need metadata listing
    rel = f"{g.replace('.', '/')}/{a}/maven-metadata.xml"
    dest = f"{OUT}/poms/{g}__{a}__metadata.xml"
    for repo in REPOS:
        if curl(f"{repo}/{rel}", dest):
            break
    if not os.path.exists(dest):
        return None
    try:
        root = ET.parse(dest).getroot()
        versions = [txt(e) for e in root.iter("version")]
    except Exception:
        return None
    cands = []
    for cand in versions:
        if lo and (ver_key(cand) < ver_key(lo) or (ver_key(cand) == ver_key(lo) and not lo_inc)):
            continue
        if hi and (ver_key(cand) > ver_key(hi) or (ver_key(cand) == ver_key(hi) and not hi_inc)):
            continue
        cands.append(cand)
    if not cands:
        return None
    cands.sort(key=ver_key)
    return cands[-1]

_pom_cache = {}
def get_pom(g, a, v):
    if v and (v.startswith("[") or v.startswith("(")):
        v = resolve_range(g, a, v)
        if v is None:
            return None
    key = (g, a, v)
    if key not in _pom_cache:
        _pom_cache[key] = load_pom_chain(g, a, v)
    return _pom_cache[key]

def ver_key(v):
    parts = []
    for tok in re.split(r"[.\-+]", v or ""):
        if tok.isdigit():
            parts.append((0, int(tok), ""))
        else:
            parts.append((1, 0, tok))
    return parts

def pick_higher(v1, v2):
    return v1 if ver_key(v1) >= ver_key(v2) else v2

# ---- root dependencies (from app/build.gradle) ----
BOM = ("androidx.compose", "compose-bom", "2024.02.00")
ROOT = [
    ("androidx.compose.ui", "ui", None),
    ("androidx.compose.ui", "ui-graphics", None),
    ("androidx.compose.ui", "ui-tooling-preview", None),
    ("androidx.compose.material3", "material3", None),
    ("androidx.activity", "activity-compose", "1.8.2"),
    ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.7.0"),
    ("androidx.lifecycle", "lifecycle-runtime-compose", "2.7.0"),
    ("androidx.navigation", "navigation-compose", "2.7.7"),
    ("androidx.media3", "media3-exoplayer", "1.3.1"),
    ("androidx.media3", "media3-ui", "1.3.1"),
    ("androidx.media3", "media3-session", "1.3.1"),
    ("com.squareup.okhttp3", "okhttp", "4.12.0"),
    ("io.coil-kt", "coil-compose", "2.6.0"),
    ("androidx.datastore", "datastore-preferences", "1.1.1"),
    ("androidx.room", "room-runtime", "2.6.1"),
    ("androidx.room", "room-ktx", "2.6.1"),
    # extra: compose compiler plugin jar (kotlinc -Xplugin), not a runtime dep
    ("androidx.compose.compiler", "compiler", "1.5.14"),
]

# BOM versions
bom_pom = get_pom(*BOM)
assert bom_pom, "could not fetch compose BOM"
bom_versions = {}
for (dg, da), dv in bom_pom["depMgmt"].items():
    bom_versions[(dg, da)] = subst(dv, bom_pom["props"])
print(f"BOM {BOM} -> {len(bom_versions)} managed versions", flush=True)

resolved = {}   # (g,a) -> version
packaging = {}  # (g,a) -> packaging
queue = []      # (g, a, version_or_None, depMgmt_context, exclusions)

def enqueue(g, a, v, ctx_mgmt, exclusions):
    queue.append((g, a, v, ctx_mgmt, frozenset(exclusions)))

for g, a, v in ROOT:
    enqueue(g, a, v, bom_versions, set())

processed = set()
while queue:
    g, a, v, ctx_mgmt, exclusions = queue.pop(0)
    if (g, a) in exclusions:
        continue
    if v is None:
        v = ctx_mgmt.get((g, a))
    if v is None:
        print(f"ERROR: no version for {g}:{a}", flush=True)
        sys.exit(1)
    if v.startswith("[") or v.startswith("("):
        v = resolve_range(g, a, v)
        if v is None:
            print(f"ERROR: could not resolve range for {g}:{a}", flush=True)
            sys.exit(1)
    key = (g, a)
    if key in resolved:
        if ver_key(v) > ver_key(resolved[key]):
            resolved[key] = v
        else:
            continue
    else:
        resolved[key] = v
    if key in processed and resolved[key] == v:
        continue
    processed.add(key)
    pom = get_pom(g, a, v)
    if pom is None:
        print(f"ERROR: no POM for {g}:{a}:{v}", flush=True)
        sys.exit(1)
    packaging[key] = pom["packaging"]
    # effective depMgmt: pom's own + inherited BOM imports + ctx
    eff = dict(ctx_mgmt)
    for (bg, ba, bv) in pom["bom_imports"]:
        bp = get_pom(bg, ba, subst(bv, pom["props"]))
        if bp:
            for k, dv in bp["depMgmt"].items():
                eff.setdefault(k, subst(dv, bp["props"]))
    for k, dv in pom["depMgmt"].items():
        eff[k] = subst(dv, pom["props"])
    props = pom["props"]
    for d in pom["deps"]:
        if d["scope"] not in ("compile", "runtime"):
            continue
        if d["optional"]:
            continue
        dg = subst(d["groupId"], props)
        da = subst(d["artifactId"], props)
        if (dg, da) in exclusions or ("*", "*") in exclusions:
            continue
        if (dg, "*") in exclusions:
            continue
        dv = subst(d["version"], props) if d["version"] else None
        if dv is None:
            dv = eff.get((dg, da))
        if dv is None:
            print(f"WARN: no version for transitive {dg}:{da} (from {g}:{a}:{v})", flush=True)
            continue
        if d["type"] not in ("jar", "aar"):
            continue
        enqueue(dg, da, dv, eff, exclusions | d["exclusions"])

print(f"resolved {len(resolved)} artifacts", flush=True)

# download artifacts
failed = []
lines = []
for (g, a), v in sorted(resolved.items()):
    pkg = packaging.get((g, a), "jar")
    ext = "aar" if pkg == "aar" else "jar"
    rel = gav_path(g, a, v, ext)
    dest = f"{OUT}/files/{g}__{a}__{v}.{ext}"
    ok = False
    for repo in REPOS:
        if curl(f"{repo}/{rel}", dest):
            ok = True
            break
        if os.path.exists(dest):
            os.remove(dest)
    if not ok:
        # try jar fallback for aar packaging
        if ext == "aar":
            rel = gav_path(g, a, v, "jar")
            dest = f"{OUT}/files/{g}__{a}__{v}.jar"
            for repo in REPOS:
                if curl(f"{repo}/{rel}", dest):
                    ok = True
                    ext = "jar"
                    break
                if os.path.exists(dest):
                    os.remove(dest)
    if not ok:
        failed.append(f"{g}:{a}:{v}")
        print(f"FAILED download {g}:{a}:{v}", flush=True)
    else:
        lines.append(f"{g}:{a}:{v}:{ext} {dest}")

with open(f"{OUT}/resolved.txt", "w") as f:
    f.write("\n".join(lines) + "\n")
print(f"downloaded {len(lines)} artifacts, {len(failed)} failed", flush=True)
if failed:
    print("FAILED:", failed, flush=True)
    sys.exit(1)
