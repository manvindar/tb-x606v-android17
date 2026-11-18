#!/usr/bin/env python3
"""Check an Android partition image's data against the dm-verity root digest in its own AVB footer.

usage: verity_check.py IMAGE
Prints JSON: the hashtree descriptor, computed vs stored root digest, the vbmeta self-hash check,
and the byte ranges this check does NOT cover (hash tree, FEC, gaps). Exit 0 only if the root matches and
the vbmeta self-hash, when the vbmeta is signed, matches. A match shows every data block up to image_size is the signed content;
the tree and FEC bytes themselves are not compared (this tool recomputes the root, not FEC parity).
"""
import hashlib, json, struct, sys


def footer(f, size):
    f.seek(size - 64)
    b = f.read(64)
    if b[:4] != b'AVBf':
        raise SystemExit('no AVB footer')
    orig, off, vsz = struct.unpack_from('>QQQ', b, 12)
    return orig, off, vsz


def descriptors(vb):
    if vb[:4] != b'AVB0':
        raise SystemExit('bad vbmeta magic')
    auth, aux = struct.unpack_from('>QQ', vb, 12)
    alg = struct.unpack_from('>I', vb, 28)[0]
    h_off, h_sz = struct.unpack_from('>QQ', vb, 32)
    d_off, d_sz = struct.unpack_from('>QQ', vb, 96)
    auxb = vb[256 + auth:256 + auth + aux]
    # algorithms 1-3 are SHA256_RSA*, 4-6 SHA512_RSA*; the hash covers header + aux block
    hf = hashlib.sha256 if alg in (1, 2, 3) else hashlib.sha512 if alg in (4, 5, 6) else None
    vb_ok = None
    if hf:
        vb_ok = hf(vb[:256] + auxb).digest() == vb[256 + h_off:256 + h_off + h_sz]
    d, i, out = auxb[d_off:d_off + d_sz], 0, []
    while i + 16 <= len(d):
        tag, n = struct.unpack_from('>QQ', d, i)
        out.append((tag, d[i + 16:i + 16 + n]))
        i += 16 + n
    return out, vb_ok


def hashtree(b):
    (ver, img, t_off, t_sz, dbs, hbs, roots, fec_off, fec_sz) = struct.unpack_from('>IQQQIIIQQ', b, 0)
    alg = b[56:88].rstrip(b'\0').decode()
    nl, sl, rl, flags = struct.unpack_from('>IIII', b, 88)
    p = 88 + 16 + 60
    name = b[p:p + nl].decode(); p += nl
    salt = b[p:p + sl]; p += sl
    root = b[p:p + rl]
    return dict(name=name, image_size=img, tree_offset=t_off, tree_size=t_sz, data_block_size=dbs,
                hash_block_size=hbs, fec_num_roots=roots, fec_offset=fec_off, fec_size=fec_sz,
                hash_algorithm=alg, salt=salt.hex(), root_digest=root.hex())


def verity_root(f, size, dbs, hbs, alg, salt):
    h = lambda x: hashlib.new(alg, salt + x).digest()
    f.seek(0)
    level = bytearray()
    for _ in range(size // dbs):
        level += h(f.read(dbs))
    while True:
        if len(level) % hbs:
            level += b'\0' * (hbs - len(level) % hbs)
        if len(level) == hbs:
            return h(bytes(level)).hex()
        level = bytearray(b''.join(h(bytes(level[i:i + hbs])) for i in range(0, len(level), hbs)))


def main(path):
    with open(path, 'rb') as f:
        f.seek(0, 2); size = f.tell()
        orig, off, vsz = footer(f, size)
        f.seek(off); vb = f.read(vsz)
        ds, vb_ok = descriptors(vb)
        hts = [hashtree(b) for t, b in ds if t == 1]
        if len(hts) != 1:
            raise SystemExit(f'expected one hashtree descriptor, found {len(hts)}')
        ht = hts[0]
        got = verity_root(f, ht['image_size'], ht['data_block_size'], ht['hash_block_size'],
                          ht['hash_algorithm'], bytes.fromhex(ht['salt']))
    ok = got == ht['root_digest'] and vb_ok is not False  # None: algorithm NONE, vbmeta signed by its chain parent
    not_covered = [['hash_tree', ht['tree_offset'], ht['tree_offset'] + ht['tree_size']],
                   ['fec', ht['fec_offset'], ht['fec_offset'] + ht['fec_size']]]
    print(json.dumps(dict(image=path, image_bytes=size, footer_original_size=orig, vbmeta_offset=off,
                          vbmeta_size=vsz, vbmeta_hash_ok=vb_ok, hashtree=ht, computed_root=got,
                          root_match=got == ht['root_digest'], not_covered=not_covered,
                          also_not_covered='gaps between data end, tree, fec, vbmeta and footer')))
    return 0 if ok else 1


def demo():
    # verity of 2 zero blocks, 4096/4096, empty salt: leaf level is one block, root = H(leaf block)
    import io
    z = hashlib.sha256(b'\0' * 4096).digest()
    leaf = (z + z).ljust(4096, b'\0')
    assert verity_root(io.BytesIO(b'\0' * 8192), 8192, 4096, 4096, 'sha256', b'') == hashlib.sha256(leaf).hexdigest()
    print('demo ok')


if __name__ == '__main__':
    if sys.argv[1:] == ['--demo']:
        demo()
    elif len(sys.argv) == 2:
        sys.exit(main(sys.argv[1]))
    else:
        sys.exit(__doc__)
