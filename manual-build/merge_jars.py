#!/usr/bin/env python3
"""Merge dependency jars + compiled app classes + R classes into one
directory of .class files for d8, skipping duplicate class paths.

Order = priority list first (newest wins), then the rest. Logs every
skipped duplicate to duplicates.log. Only *.class files are copied;
module-info.class is dropped (d8 does not need it).
"""
import os, sys, zipfile, shutil

OUTDIR, LOG = sys.argv[1], sys.argv[2]
inputs = sys.argv[3:]  # jar files or class dirs, in priority order

if os.path.exists(OUTDIR):
    shutil.rmtree(OUTDIR)
os.makedirs(OUTDIR)

seen = {}   # rel path -> source
skipped = []
copied = 0

def add_file(rel, data, src):
    global copied
    if rel in seen:
        skipped.append(f"{rel}  [kept from {seen[rel]}, skipped {src}]")
        return
    if rel.endswith("module-info.class"):
        return
    seen[rel] = src
    dest = os.path.join(OUTDIR, rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as f:
        f.write(data)
    copied += 1

for inp in inputs:
    base = os.path.basename(inp)
    if os.path.isdir(inp):
        for root, _dirs, files in os.walk(inp):
            for fn in files:
                if not fn.endswith(".class"):
                    continue
                p = os.path.join(root, fn)
                rel = os.path.relpath(p, inp)
                with open(p, "rb") as f:
                    add_file(rel, f.read(), base)
    elif inp.endswith(".jar"):
        try:
            z = zipfile.ZipFile(inp)
        except Exception as e:
            print(f"WARN: cannot open {inp}: {e}", file=sys.stderr)
            continue
        for info in z.infolist():
            if info.is_dir() or not info.filename.endswith(".class"):
                continue
            add_file(info.filename, z.read(info.filename), base)
        z.close()
    else:
        print(f"WARN: unknown input {inp}", file=sys.stderr)

with open(LOG, "w") as f:
    f.write(f"copied {copied} class files, skipped {len(skipped)} duplicates\n")
    for s in skipped:
        f.write(s + "\n")
print(f"merged {copied} classes, {len(skipped)} duplicates skipped -> {LOG}")
