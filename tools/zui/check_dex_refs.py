#!/usr/bin/env python3
"""List types, methods and fields an APK references but neither defines nor finds in given framework jars/APKs.

usage: check_dex_refs.py APP.apk [--lib APP_LIB.jar ...] FRAMEWORK.jar|DIR ...
Prints "type L...;" and "member L...;->name(desc)" lines, then a count on stderr. Exit 1 if any are missing.
Members are resolved through superclasses and interfaces of the framework classes. A member reached
only by reflection, or one whose access flags changed, is not caught.
"""
import os, struct, sys, zipfile


def uleb(b, i):
    r = s = 0
    while True:
        c = b[i]; i += 1
        r |= (c & 0x7F) << s; s += 7
        if c < 0x80:
            return r, i


def sections(d):
    """Yield one parsed view per dex section. Dex 041 (Android 16+) packs several sections in one
    container; each header sits at the previous section's end and offsets count from the container start."""
    if d[:4] != b'dex\n':
        raise ValueError('not a dex')
    o = 0
    while o < len(d) and d[o:o + 4] == b'dex\n':
        size = struct.unpack_from('<I', d, o + 32)[0]
        yield Section(d, o)
        if d[o + 4:o + 7] != b'041':
            break
        o += size


class Section:
    def __init__(s, d, o):
        s.d = d
        (s.str_n, s.str_off, s.type_n, s.type_off, s.proto_n, s.proto_off,
         s.field_n, s.field_off, s.meth_n, s.meth_off, s.cls_n, s.cls_off) = struct.unpack_from('<12I', d, o + 56)
        s._str = {}

    def string(s, i):
        if i not in s._str:
            off = struct.unpack_from('<I', s.d, s.str_off + 4 * i)[0]
            _, j = uleb(s.d, off)
            s._str[i] = s.d[j:s.d.index(b'\0', j)].decode('utf-8', 'replace')
        return s._str[i]

    def type(s, i):
        return s.string(struct.unpack_from('<I', s.d, s.type_off + 4 * i)[0])

    def types(s):
        return [s.type(t) for t in range(s.type_n)]

    def type_list(s, off):
        if not off:
            return []
        n = struct.unpack_from('<I', s.d, off)[0]
        return [s.type(struct.unpack_from('<H', s.d, off + 4 + 2 * k)[0]) for k in range(n)]

    def proto(s, i):
        _, ret, params = struct.unpack_from('<III', s.d, s.proto_off + 12 * i)
        return '(' + ''.join(s.type_list(params)) + ')' + s.type(ret)

    def method(s, i):
        c, p, n = struct.unpack_from('<HHI', s.d, s.meth_off + 8 * i)
        return s.type(c), s.string(n) + s.proto(p)

    def field(s, i):
        c, _, n = struct.unpack_from('<HHI', s.d, s.field_off + 8 * i)
        return s.type(c), s.string(n)

    def classes(s):
        """(class, superclass or None, interfaces, member keys defined in the class body)."""
        for k in range(s.cls_n):
            c, _, sup, ifs, _, _, data, _ = struct.unpack_from('<8I', s.d, s.cls_off + 32 * k)
            members = set()
            if data:
                counts = []
                j = data
                for _ in range(4):
                    v, j = uleb(s.d, j); counts.append(v)
                for kind, n in enumerate(counts):
                    idx = 0
                    for _ in range(n):
                        diff, j = uleb(s.d, j); idx += diff
                        _, j = uleb(s.d, j)
                        if kind >= 2:
                            _, j = uleb(s.d, j)
                            members.add(s.method(idx)[1])
                        else:
                            members.add(s.field(idx)[1])
            yield s.type(c), (s.type(sup) if sup != 0xFFFFFFFF else None), s.type_list(ifs), members


def dexes(path):
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.startswith('classes') and n.endswith('.dex'):
                yield z.read(n)


def dex_types(d):
    """(referenced object types, defined classes) of one dex file."""
    refs, defined = set(), set()
    for s in sections(d):
        refs |= {t.lstrip('[') for t in s.types()}
        defined |= {c for c, *_ in s.classes()}
    return {t for t in refs if t.startswith('L')}, defined


def jar_files(paths):
    for p in paths:
        files = [p] if os.path.isfile(p) else [os.path.join(r, f) for r, _, fs in os.walk(p) for f in fs]
        yield from (f for f in files if f.endswith(('.jar', '.apk')))


def hierarchy(paths):
    """class -> (super, interfaces, members) over every dex in the given jars/APKs."""
    h = {}
    for f in jar_files(paths):
        try:
            for d in dexes(f):
                for s in sections(d):
                    for c, sup, ifs, mem in s.classes():
                        h.setdefault(c, (sup, ifs, mem))
        except (zipfile.BadZipFile, ValueError):
            pass
    return h


def resolves(h, cls, key, seen=None):
    seen = seen if seen is not None else set()
    if cls in seen or cls not in h:
        return False
    seen.add(cls)
    sup, ifs, mem = h[cls]
    return key in mem or any(resolves(h, c, key, seen) for c in ([sup] if sup else []) + ifs)


def missing(apk, libs, framework):
    app = hierarchy([apk] + libs)
    fw = hierarchy(framework)
    out = set()
    for d in dexes(apk):
        for s in sections(d):
            for t in s.types():
                t = t.lstrip('[')
                if t.startswith('L') and t not in app and t not in fw:
                    out.add(f'type {t}')
            refs = [s.method(i) for i in range(s.meth_n)] + [s.field(i) for i in range(s.field_n)]
            for cls, key in refs:
                if cls.startswith('[') or cls in app or cls not in fw:
                    continue  # array clone(), app-local, or already reported as a missing type
                if not resolves(fw, cls, key):
                    out.add(f'member {cls}->{key}')
    return sorted(out)


def main(argv):
    libs, fw, it = [], [], iter(argv[1:])
    for a in it:
        (libs.append(next(it)) if a == '--lib' else fw.append(a))
    if len(argv) < 3:
        sys.exit(__doc__)
    apk, fw = fw[0], fw[1:]
    m = missing(apk, libs, fw)
    for t in m:
        print(t)
    print(f'# missing: {sum(x.startswith("type") for x in m)} types, {sum(x.startswith("member") for x in m)} members', file=sys.stderr)
    return 1 if m else 0


def demo():
    # minimal dex: strings LA; LB; V a ; types LA; LB; V ; proto ()V ; method LB;->a()V ; class LA; extends LB;
    strs = [b'LA;', b'LB;', b'V', b'a']
    hdr = 0x70
    str_off = hdr; type_off = str_off + 16; proto_off = type_off + 12; meth_off = proto_off + 12
    cls_off = meth_off + 8; data = cls_off + 32
    body, offs = b'', []
    for x in strs:
        offs.append(data + len(body)); body += bytes([len(x)]) + x + b'\0'
    d = bytearray(b'dex\n035\0' + b'\0' * (hdr - 8))
    struct.pack_into('<12I', d, 56, 4, str_off, 3, type_off, 1, proto_off, 0, 0, 1, meth_off, 1, cls_off)
    d += struct.pack('<4I', *offs) + struct.pack('<3I', 0, 1, 2) + struct.pack('<III', 2, 2, 0)
    d += struct.pack('<HHI', 1, 0, 3) + struct.pack('<8I', 0, 1, 1, 0, 0xFFFFFFFF, 0, 0, 0) + body
    s = next(sections(bytes(d)))
    assert s.method(0) == ('LB;', 'a()V'), s.method(0)
    assert list(s.classes()) == [('LA;', 'LB;', [], set())]
    h = {'LA;': ('LB;', [], set()), 'LB;': (None, [], {'a()V'})}
    assert resolves(h, 'LA;', 'a()V') and not resolves(h, 'LA;', 'b()V')
    print('demo ok')


if __name__ == '__main__':
    if sys.argv[1:] == ['--demo']:
        demo()
    else:
        sys.exit(main(sys.argv))
