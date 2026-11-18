//! Apply an Android A/B payload.bin (full or incremental) to base partition images.
//! usage: ota-apply PAYLOAD_FILE PAYLOAD_OFFSET OLD_DIR NEW_DIR PART [PART...]
//! Fails (non-zero) when a base image does not match old_partition_info (wrong base: STOP, never forced),
//! when an operation blob or source range does not match its SHA-256, or on an unsupported operation.
//! Prints one JSON line per partition: base and result hashes, and whether the result equals new_partition_info.
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};

type R<T> = Result<T, String>;

fn vint(b: &[u8], i: &mut usize) -> R<u64> {
    let (mut v, mut s) = (0u64, 0);
    loop {
        let c = *b.get(*i).ok_or("truncated varint")?; *i += 1;
        v |= ((c & 127) as u64) << s; s += 7;
        if c < 128 { return Ok(v); }
    }
}
enum V<'a> { I(u64), B(&'a [u8]) }
fn fields(b: &[u8]) -> R<Vec<(u64, V<'_>)>> {
    let (mut i, mut out) = (0usize, vec![]);
    while i < b.len() {
        let k = vint(b, &mut i)?;
        let v = match k & 7 {
            0 => V::I(vint(b, &mut i)?),
            2 => { let l = vint(b, &mut i)? as usize; let s = &b[i..i + l]; i += l; V::B(s) }
            1 => { i += 8; continue }
            5 => { i += 4; continue }
            t => return Err(format!("wire type {t}")),
        };
        out.push((k >> 3, v));
    }
    Ok(out)
}
fn int(f: &[(u64, V)], n: u64) -> u64 { f.iter().find_map(|(k, v)| if *k == n { if let V::I(x) = v { Some(*x) } else { None } } else { None }).unwrap_or(0) }
fn bytes<'a>(f: &'a [(u64, V<'a>)], n: u64) -> Option<&'a [u8]> { f.iter().find_map(|(k, v)| if *k == n { if let V::B(x) = v { Some(*x) } else { None } } else { None }) }
fn all<'a>(f: &'a [(u64, V<'a>)], n: u64) -> Vec<&'a [u8]> { f.iter().filter_map(|(k, v)| if *k == n { if let V::B(x) = v { Some(*x) } else { None } } else { None }).collect() }
fn extents(f: &[(u64, V)], n: u64) -> R<Vec<(u64, u64)>> {
    all(f, n).into_iter().map(|e| { let e = fields(e)?; Ok((int(&e, 1), int(&e, 2))) }).collect()
}
fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
fn sha_file(f: &mut File, len: u64) -> R<Vec<u8>> {
    f.seek(SeekFrom::Start(0)).map_err(|e| e.to_string())?;
    let mut h = Sha256::new(); let mut left = len; let mut buf = vec![0u8; 1 << 20];
    while left > 0 { let n = left.min(buf.len() as u64) as usize; f.read_exact(&mut buf[..n]).map_err(|e| e.to_string())?; h.update(&buf[..n]); left -= n as u64; }
    Ok(h.finalize().to_vec())
}
fn read_ext(f: &mut File, ex: &[(u64, u64)], bs: u64) -> R<Vec<u8>> {
    let mut out = Vec::new();
    for &(s, n) in ex { let mut b = vec![0u8; (n * bs) as usize]; f.seek(SeekFrom::Start(s * bs)).map_err(|e| e.to_string())?; f.read_exact(&mut b).map_err(|e| e.to_string())?; out.extend(b); }
    Ok(out)
}
fn write_ext(f: &mut File, ex: &[(u64, u64)], bs: u64, data: &[u8]) -> R<()> {
    let total: u64 = ex.iter().map(|e| e.1 * bs).sum();
    if (data.len() as u64) > total { return Err(format!("output {} larger than dst extents {total}", data.len())); }
    let mut pos = 0usize;
    for &(s, n) in ex {
        let len = ((n * bs) as usize).min(data.len() - pos.min(data.len()));
        f.seek(SeekFrom::Start(s * bs)).map_err(|e| e.to_string())?;
        f.write_all(&data[pos..pos + len]).map_err(|e| e.to_string())?;
        if len < (n * bs) as usize { f.write_all(&vec![0u8; (n * bs) as usize - len]).map_err(|e| e.to_string())?; }
        pos += len;
    }
    Ok(())
}

fn main() {
    if let Err(e) = run() { eprintln!("ERROR: {e}"); std::process::exit(1); }
}

fn run() -> R<()> {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 6 { return Err("usage: ota-apply PAYLOAD_FILE PAYLOAD_OFFSET OLD_DIR NEW_DIR PART...".into()); }
    let mut p = File::open(&a[1]).map_err(|e| e.to_string())?; let off: u64 = a[2].parse().map_err(|_| "offset")?;
    let mut hdr = [0u8; 24]; p.seek(SeekFrom::Start(off)).map_err(|e| e.to_string())?; p.read_exact(&mut hdr).map_err(|e| e.to_string())?;
    if &hdr[..4] != b"CrAU" { return Err("not a payload (magic)".into()); }
    let ver = u64::from_be_bytes(hdr[4..12].try_into().unwrap());
    let ms = u64::from_be_bytes(hdr[12..20].try_into().unwrap());
    let ss = u32::from_be_bytes(hdr[20..24].try_into().unwrap()) as u64;
    if ver != 2 { return Err(format!("payload version {ver}")); }
    let mut m = vec![0u8; ms as usize]; p.read_exact(&mut m).map_err(|e| e.to_string())?;
    let data0 = off + 24 + ms + ss;
    let mf = fields(&m)?;
    let bs = match int(&mf, 3) { 0 => 4096, x => x };
    let want: Vec<&String> = a[5..].iter().collect();
    for pu in all(&mf, 13) {
        let pf = fields(pu)?;
        let name = String::from_utf8_lossy(bytes(&pf, 1).unwrap_or(b"")).to_string();
        if !want.iter().any(|w| **w == name) { continue; }
        let oldi = bytes(&pf, 6).map(fields).transpose()?; let newi = fields(bytes(&pf, 7).ok_or("no new_partition_info")?)?;
        let ops = all(&pf, 8);
        let mut base_hash = String::new();
        let mut old = match &oldi {
            Some(o) => {
                let (osz, oh) = (int(o, 1), bytes(o, 2).unwrap_or(b""));
                let mut f = File::open(format!("{}/{}.img", a[3], name)).map_err(|e| format!("{name}: base image: {e}"))?;
                let have = f.metadata().map_err(|e| e.to_string())?.len();
                if have < osz { return Err(format!("{name}: base is {have} bytes, payload expects {osz}: WRONG BASE, stop")); }
                let h = sha_file(&mut f, osz)?; base_hash = hex(&h);
                if h != oh { return Err(format!("{name}: base sha256 {} != expected {}: WRONG BASE, stop", hex(&h), hex(oh))); }
                Some(f)
            }
            None => None,
        };
        let nsz = int(&newi, 1);
        let np = format!("{}/{}.img", a[4], name);
        let mut out = OpenOptions::new().read(true).write(true).create(true).truncate(true).open(&np).map_err(|e| e.to_string())?;
        out.set_len(nsz).map_err(|e| e.to_string())?;
        let mut counts = std::collections::BTreeMap::new();
        for (idx, op) in ops.iter().enumerate() {
            let of = fields(op)?; let t = int(&of, 1);
            *counts.entry(t).or_insert(0u64) += 1;
            let dst = extents(&of, 6)?; let src = extents(&of, 4)?;
            let (doff, dlen) = (int(&of, 2), int(&of, 3));
            let blob = if dlen > 0 {
                let mut b = vec![0u8; dlen as usize]; p.seek(SeekFrom::Start(data0 + doff)).map_err(|e| e.to_string())?; p.read_exact(&mut b).map_err(|e| e.to_string())?;
                if let Some(h) = bytes(&of, 8) { if Sha256::digest(&b).as_slice() != h { return Err(format!("{name} op {idx}: data sha256 mismatch")); } }
                b
            } else { vec![] };
            let srcdata = if !src.is_empty() {
                let f = old.as_mut().ok_or(format!("{name} op {idx}: source op without base image"))?;
                let s = read_ext(f, &src, bs)?;
                if let Some(h) = bytes(&of, 9) { if Sha256::digest(&s).as_slice() != h { return Err(format!("{name} op {idx}: source sha256 mismatch")); } }
                s
            } else { vec![] };
            let res: Vec<u8> = match t {
                0 => blob,
                1 => { let mut v = vec![]; bzip2::read::BzDecoder::new(&blob[..]).read_to_end(&mut v).map_err(|e| e.to_string())?; v }
                8 => { let mut v = vec![]; xz2::read::XzDecoder::new(&blob[..]).read_to_end(&mut v).map_err(|e| e.to_string())?; v }
                6 | 7 => vec![],
                4 => srcdata,
                5 | 10 => {
                    let mut v = Vec::new();
                    // AOSP BROTLI_BSDIFF blobs are BSDF2; legacy BSDIFF40 is not seen in this payload and is refused rather than guessed.
                    if !blob.starts_with(b"BSDF2") { return Err(format!("{name} op {idx}: unsupported bsdiff container")); }
                    bsdiff_android::patch_bsdf2(&srcdata, &blob, &mut v).map_err(|e| format!("{name} op {idx}: bsdf2 {e}"))?;
                    v
                }
                9 => puffdiff::puffpatch(&srcdata, &blob).map_err(|e| format!("{name} op {idx}: puffpatch {e}"))?,
                _ => return Err(format!("{name} op {idx}: unsupported operation type {t}")),
            };
            write_ext(&mut out, &dst, bs, &res)?;
        }
        out.flush().map_err(|e| e.to_string())?;
        let h = sha_file(&mut out, nsz)?; let expect = bytes(&newi, 2).unwrap_or(b"");
        let has_tree = bytes(&pf, 12).is_some() || bytes(&pf, 13).is_some();
        println!("{{\"partition\":\"{name}\",\"base_sha256\":\"{base_hash}\",\"new_bytes\":{nsz},\"new_sha256\":\"{}\",\"expected_sha256\":\"{}\",\"match\":{},\"verity_hash_tree_in_manifest\":{has_tree},\"ops\":\"{:?}\"}}",
                 hex(&h), hex(expect), h == expect, counts);
    }
    Ok(())
}
