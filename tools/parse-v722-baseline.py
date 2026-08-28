#!/usr/bin/env python3
"""Create a text-only structural/bytecode baseline from a local SimpleReader APK.

The APK is read locally and is never copied into the repository. Output includes
all ZIP-entry hashes, all com.simplereader.app classes, all app method code-item
hashes, and Merkle roots matching baseline/v722/baseline.json.
"""
import argparse
import hashlib
import json
import struct
import tempfile
import zipfile
from pathlib import Path

APP_PREFIX = "Lcom/simplereader/app/"


def uleb(data, off):
    res = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        res |= (b & 0x7F) << shift
        if not (b & 0x80):
            return res, off
        shift += 7
        if shift > 35:
            raise ValueError("ULEB128 too large")


def sleb(data, off):
    res = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        res |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            if shift < 32 and (b & 0x40):
                res |= -(1 << shift)
            return res, off
        if shift > 35:
            raise ValueError("SLEB128 too large")


def read_mutf8(data, off):
    _, off = uleb(data, off)
    end = data.index(0, off)
    return data[off:end].decode("utf-8", errors="replace")


def parse_code_item(data, off):
    if not off:
        return None
    registers, ins, outs, tries = struct.unpack_from("<HHHH", data, off)
    debug_info_off, insns_size = struct.unpack_from("<II", data, off + 8)
    insns_off = off + 16
    insns_end = insns_off + insns_size * 2
    p = insns_end
    if tries and (insns_size & 1):
        p += 2
    p += tries * 8
    if tries:
        count, p = uleb(data, p)
        for _ in range(count):
            size, p = sleb(data, p)
            for __ in range(abs(size)):
                _, p = uleb(data, p)
                _, p = uleb(data, p)
            if size <= 0:
                _, p = uleb(data, p)
    block = data[off:p]
    insns = data[insns_off:insns_end]
    return {
        "registers": registers,
        "ins": ins,
        "outs": outs,
        "tries": tries,
        "debugInfoOff": debug_info_off,
        "insnsSize": insns_size,
        "codeSizeBytes": len(block),
        "codeSha256": hashlib.sha256(block).hexdigest(),
        "insnsSha256": hashlib.sha256(insns).hexdigest(),
    }


def parse_dex(path):
    data = path.read_bytes()
    if not data.startswith(b"dex\n"):
        raise ValueError(f"Not a DEX file: {path}")
    vals = struct.unpack_from("<20I", data, 0x20)
    (file_size, header_size, endian_tag, link_size, link_off, map_off,
     ssz, soff, tsz, toff, psz, poff, fsz, foff, msz, moff,
     csz, coff, dsz, doff) = vals

    strings = []
    for i in range(ssz):
        off = struct.unpack_from("<I", data, soff + i * 4)[0]
        strings.append(read_mutf8(data, off))

    types = []
    for i in range(tsz):
        idx = struct.unpack_from("<I", data, toff + i * 4)[0]
        types.append(strings[idx])

    protos = []
    for i in range(psz):
        shorty_idx, ret_idx, params_off = struct.unpack_from("<III", data, poff + i * 12)
        params = []
        if params_off:
            n = struct.unpack_from("<I", data, params_off)[0]
            for j in range(n):
                tidx = struct.unpack_from("<H", data, params_off + 4 + j * 2)[0]
                params.append(types[tidx])
        protos.append({"return": types[ret_idx], "params": params, "shorty": strings[shorty_idx]})

    methods = []
    for i in range(msz):
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, moff + i * 8)
        methods.append({"class": types[class_idx], "name": strings[name_idx], "proto": proto_idx})

    classes = []
    app_methods = []
    for i in range(csz):
        (class_idx, access_flags, super_idx, interfaces_off, source_idx,
         annotations_off, class_data_off, static_values_off) = struct.unpack_from("<8I", data, coff + i * 32)
        desc = types[class_idx]
        src = None if source_idx == 0xFFFFFFFF else strings[source_idx]
        classes.append({"descriptor": desc, "sourceFile": src, "accessFlags": access_flags, "classDataOff": class_data_off})
        if not class_data_off:
            continue
        p = class_data_off
        sf, p = uleb(data, p)
        inf, p = uleb(data, p)
        dm, p = uleb(data, p)
        vm, p = uleb(data, p)
        idx = 0
        for _ in range(sf):
            diff, p = uleb(data, p)
            _, p = uleb(data, p)
            idx += diff
        idx = 0
        for _ in range(inf):
            diff, p = uleb(data, p)
            _, p = uleb(data, p)
            idx += diff
        for kind, count in (("direct", dm), ("virtual", vm)):
            midx = 0
            for _ in range(count):
                diff, p = uleb(data, p)
                flags, p = uleb(data, p)
                code_off, p = uleb(data, p)
                midx += diff
                m = methods[midx]
                proto = protos[m["proto"]]
                sig = f"{m['class']}->{m['name']}({''.join(proto['params'])}){proto['return']}"
                if desc.startswith(APP_PREFIX):
                    app_methods.append({
                        "class": m["class"],
                        "name": m["name"],
                        "params": proto["params"],
                        "return": proto["return"],
                        "signature": sig,
                        "kind": kind,
                        "accessFlags": flags,
                        "codeOff": code_off,
                        "code": parse_code_item(data, code_off) if code_off else None,
                    })

    return {
        "file": path.name,
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
        "header": {
            "fileSize": file_size,
            "stringCount": ssz,
            "typeCount": tsz,
            "protoCount": psz,
            "fieldCount": fsz,
            "methodCount": msz,
            "classCount": csz,
        },
        "classes": classes,
        "appMethods": app_methods,
    }


def merkle_root(lines):
    hashes = [hashlib.sha256(line.encode("utf-8")).digest() for line in sorted(lines)]
    if not hashes:
        return hashlib.sha256(b"").hexdigest()
    while len(hashes) > 1:
        if len(hashes) & 1:
            hashes.append(hashes[-1])
        hashes = [hashlib.sha256(hashes[i] + hashes[i + 1]).digest() for i in range(0, len(hashes), 2)]
    return hashes[0].hex()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", type=Path, help="local canonical v722 APK; never copied to output")
    ap.add_argument("--out", type=Path, default=Path("v722-parsed-baseline"))
    args = ap.parse_args()
    apk = args.apk.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    entries = []
    dex_parsed = []
    with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as td:
        td = Path(td)
        dex_names = []
        for zi in sorted(z.infolist(), key=lambda x: x.filename):
            if zi.is_dir():
                continue
            b = z.read(zi.filename)
            entries.append({
                "path": zi.filename,
                "size": zi.file_size,
                "compressedSize": zi.compress_size,
                "crc32": f"{zi.CRC:08x}",
                "sha256": hashlib.sha256(b).hexdigest(),
            })
            if zi.filename.startswith("classes") and zi.filename.endswith(".dex") and "/" not in zi.filename:
                p = td / zi.filename
                p.write_bytes(b)
                dex_names.append(p)
        for p in sorted(dex_names):
            dex_parsed.append(parse_dex(p))

    app_classes = []
    app_methods = []
    for d in dex_parsed:
        app_classes.extend({"dex": d["file"], **c} for c in d["classes"] if c["descriptor"].startswith(APP_PREFIX))
        app_methods.extend({"dex": d["file"], **m} for m in d["appMethods"])

    (out / "apk-entry-sha256.json").write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "app-classes.json").write_text(json.dumps(app_classes, ensure_ascii=False, indent=2), encoding="utf-8")
    with (out / "app-method-bytecode-hashes.jsonl").open("w", encoding="utf-8") as f:
        for m in sorted(app_methods, key=lambda x: x["signature"]):
            f.write(json.dumps(m, ensure_ascii=False, separators=(",", ":")) + "\n")

    entry_lines = [f"{e['path']}\t{e['size']}\t{e['sha256']}" for e in entries]
    class_lines = [f"{c['dex']}\t{c['descriptor']}\t{c.get('sourceFile')}\t{c['accessFlags']}" for c in app_classes]
    method_lines = []
    for m in app_methods:
        code = m.get("code") or {}
        method_lines.append(f"{m['signature']}\t{code.get('codeSha256', 'NO_CODE')}")

    summary = {
        "apkSha256": apk_sha,
        "apkEntryCount": len(entries),
        "applicationClassCount": len(app_classes),
        "applicationMethodCount": len(app_methods),
        "apkEntryMerkleRootSha256": merkle_root(entry_lines),
        "applicationClassMerkleRootSha256": merkle_root(class_lines),
        "applicationMethodCodeMerkleRootSha256": merkle_root(method_lines),
        "dexFiles": [
            {
                "file": d["file"],
                "sha256": d["sha256"],
                "size": d["size"],
                "applicationClassCount": sum(1 for c in d["classes"] if c["descriptor"].startswith(APP_PREFIX)),
                "applicationMethodCount": len(d["appMethods"]),
            }
            for d in dex_parsed
        ],
    }
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
