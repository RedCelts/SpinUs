#!/usr/bin/env python3
"""Find method/field references in dex files with no definition in any dex.
Framework classes (android.*, java.*, javax.*, dalvik.*, sun.*, etc.) are
excluded: they live in the OS, not in the dex. Anything else that is
referenced but never defined is a runtime NoSuchMethodError /
NoClassDefFoundError / NoSuchFieldError waiting to happen."""
import struct, sys
from collections import defaultdict

def uleb(data, off):
    r = 0; s = 0
    while True:
        b = data[off]; off += 1
        r |= (b & 0x7f) << s
        if not (b & 0x80): return r, off
        s += 7

def parse_dex(path):
    d = open(path, 'rb').read()
    (magic, cksum, sig, fsize, hsize, endian, link_size, link_off, map_off,
     str_n, str_off, typ_n, typ_off, pro_n, pro_off, fld_n, fld_off,
     mtd_n, mtd_off, cls_n, cls_off, data_n, data_off) = struct.unpack('<8sI20s20I', d[:112])
    strings = []
    for i in range(str_n):
        o = struct.unpack_from('<I', d, str_off + 4*i)[0]
        ln, p = uleb(d, o)
        strings.append(d[p:p+ln].decode('utf-8', 'replace'))
    types = []
    for i in range(typ_n):
        si = struct.unpack_from('<I', d, typ_off + 4*i)[0]
        types.append(strings[si])
    def proto_str(pi):
        shorty, ret, params = struct.unpack_from('<III', d, pro_off + 12*pi)
        ps = []
        if params:
            n = struct.unpack_from('<I', d, params)[0]
            for j in range(n):
                ti = struct.unpack_from('<H', d, params+4+2*j)[0]
                ps.append(types[ti])
        return '(%s)%s' % (''.join(ps), types[ret])
    fields = []
    for i in range(fld_n):
        c, t, n = struct.unpack_from('<HHI', d, fld_off + 8*i)
        fields.append((types[c], types[t], strings[n]))
    methods = []
    for i in range(mtd_n):
        c, p, n = struct.unpack_from('<HHI', d, mtd_off + 8*i)
        methods.append((types[c], proto_str(p), strings[n]))
    # class defs + defined members + code refs
    defined_methods = set()
    defined_fields = set()
    defined_classes = set()
    superclass = {}   # class descriptor -> superclass descriptor (or None)
    interfaces = {}   # class descriptor -> [interface descriptors]
    # code item -> (defining method, invoked method_ids)
    refs = defaultdict(set)  # method_id index -> set of defining (class,name,proto)
    for i in range(cls_n):
        (cidx, acc, sup, ifaces, src, ann, cdata, stat) = struct.unpack_from('<8I', d, cls_off + 32*i)
        cls = types[cidx]
        defined_classes.add(cls)
        superclass[cls] = types[sup] if sup != 0xffffffff else None
        ifs = []
        if ifaces:
            n = struct.unpack_from('<I', d, ifaces)[0]
            for j in range(n):
                ti = struct.unpack_from('<H', d, ifaces+4+2*j)[0]
                ifs.append(types[ti])
        interfaces[cls] = ifs
        if not cdata: continue
        p = cdata
        sf, p = uleb(d, p); inf, p = uleb(d, p); dm, p = uleb(d, p); vm, p = uleb(d, p)
        fidx = 0
        for _ in range(sf):
            diff, p = uleb(d, p); af, p = uleb(d, p)
            fidx += diff
            defined_fields.add(fields[fidx])
        fidx = 0
        for _ in range(inf):
            diff, p = uleb(d, p); af, p = uleb(d, p)
            fidx += diff
            defined_fields.add(fields[fidx])
        def process_methods(count):
            nonlocal p
            midx = 0
            for _ in range(count):
                diff, p = uleb(d, p); af, p = uleb(d, p); code_off, p = uleb(d, p)
                midx += diff
                m = methods[midx]
                defined_methods.add(m)
                if not code_off: continue
                (regs, ins, outs, tries, dbg, insns_n) = struct.unpack_from('<HHHHII', d, code_off)
                base = code_off + 16
                # scan 16-bit units for invoke opcodes (35c: 0x6e-0x72, 3rc: 0x74-0x78)
                for u in range(insns_n):
                    op = struct.unpack_from('<H', d, base + 2*u)[0] & 0xff
                    if 0x6e <= op <= 0x72:  # 35c: method ref at +1 unit
                        ref = struct.unpack_from('<H', d, base + 2*u + 2)[0]
                        refs[ref].add(m)
                    elif 0x74 <= op <= 0x78:  # 3rc: method ref at +1 unit
                        ref = struct.unpack_from('<H', d, base + 2*u + 2)[0]
                        refs[ref].add(m)
        process_methods(dm)
        process_methods(vm)
    return types, methods, fields, defined_methods, defined_fields, defined_classes, refs, superclass, interfaces

FRAMEWORK = ('Landroid/', 'Ljava/', 'Ljavax/', 'Ldalvik/', 'Lsun/', 'Lorg/xml/', 'Lorg/w3c/', 'Lorg/json/')

def is_framework(t):
    return t.startswith(FRAMEWORK)

all_defined_m = set(); all_defined_f = set(); all_defined_c = set()
all_super = {}; all_ifaces = {}
per_dex = []
for path in sys.argv[1:]:
    t, m, f, dm, df, dc, refs, sup, ifs = parse_dex(path)
    per_dex.append((path, m, f, refs))
    all_defined_m |= dm; all_defined_f |= df; all_defined_c |= dc
    all_super.update(sup); all_ifaces.update(ifs)

def ancestors(cls):
    """class + all superclasses and interfaces, transitively."""
    seen = set(); stack = [cls]
    while stack:
        c = stack.pop()
        if c in seen: continue
        seen.add(c)
        s = all_super.get(c)
        if s: stack.append(s)
        for i in all_ifaces.get(c, []): stack.append(i)
    return seen

def method_defined(cls, proto, name):
    return any((a, proto, name) in all_defined_m for a in ancestors(cls))

def field_defined(cls, typ, name):
    return any((a, typ, name) in all_defined_f for a in ancestors(cls))

print("=== missing method definitions (non-framework) ===")
missing = []
for path, methods, fields, refs in per_dex:
    for idx, (cls, proto, name) in enumerate(methods):
        if is_framework(cls): continue
        if not method_defined(cls, proto, name):
            missing.append((cls, proto, name, refs.get(idx, set())))
missing.sort()
for cls, proto, name, callers in missing:
    callers_s = ', '.join('%s.%s' % (c, n) for (c, p, n) in sorted(callers)[:4])
    print(f"{cls} -> {name}{proto}   [called by: {callers_s or '?'}]")
print(f"total missing methods: {len(missing)}")

print("=== missing field definitions (non-framework) ===")
fmiss = []
for path, methods, fields, refs in per_dex:
    for (cls, typ, name) in fields:
        if is_framework(cls): continue
        if not field_defined(cls, typ, name):
            fmiss.append((cls, typ, name))
fmiss = sorted(set(fmiss))
for cls, typ, name in fmiss:
    print(f"{cls} . {name} : {typ}")
print(f"total missing fields: {len(fmiss)}")

print("=== missing classes referenced by method ids (non-framework) ===")
cmiss = set()
for path, methods, fields, refs in per_dex:
    for (cls, proto, name) in methods:
        if is_framework(cls): continue
        if cls.startswith('['): continue
        if cls not in all_defined_c:
            cmiss.add(cls)
for c in sorted(cmiss):
    print(c)
print(f"total missing classes: {len(cmiss)}")
