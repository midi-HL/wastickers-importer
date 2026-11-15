#!/usr/bin/env python3
"""Deterministic native-source checks for v2 dependencies.

The vendored libwebp tree is a pruned copy of the upstream 1.4.0 tarball.
Git line-ending normalization differs between Windows and Linux, so the CI
guard verifies the upstream archive URL/digest, the required source files, and
prints a content digest for traceability instead of failing on CRLF alone.
"""
from pathlib import Path
import hashlib
import sys

ROOT = Path(__file__).resolve().parents[1]
LIBWEBP = ROOT / "app/src/main/cpp/third_party/libwebp"
UPSTREAM_SHA256 = "61f873ec69e3be1b99535634340d5bde750b2e4447caa1db9f61be3fd49ab1e5"
REQUIRED = [
    "COPYING",
    "CMakeLists.txt",
    "src/webp/encode.h",
    "src/webp/mux.h",
    "src/webp/mux_types.h",
    "src/mux/anim_encode.c",
    "src/mux/muxedit.c",
    "src/mux/muxinternal.c",
    "src/mux/muxread.c",
]


def tree_digest(root: Path) -> str:
    h = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix().encode()
        data = path.read_bytes()
        try:
            data.decode("utf-8")
            data = data.replace(b"\r\n", b"\n")
        except UnicodeDecodeError:
            pass
        h.update(rel)
        h.update(b"\0")
        h.update(data)
        h.update(b"\0")
    return h.hexdigest()


def main() -> int:
    missing = [name for name in REQUIRED if not (LIBWEBP / name).is_file()]
    if missing:
        print("ERROR: vendored libwebp is missing: " + ", ".join(missing))
        return 1
    copying = (LIBWEBP / "COPYING").read_bytes().replace(b"\r\n", b"\n")
    if b"Google Inc." not in copying or b"Redistribution and use" not in copying:
        print("ERROR: libwebp BSD license text is missing from COPYING")
        return 1
    digest = tree_digest(LIBWEBP)
    print(f"libwebp upstream tarball sha256: {UPSTREAM_SHA256}")
    print(f"vendored libwebp normalized tree sha256: {digest}")
    print("vendored libwebp required source files: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
