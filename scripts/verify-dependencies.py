#!/usr/bin/env python3
"""Deterministic source/artifact checksums for v2 native and security dependencies."""
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "app/src/main/cpp/third_party/libwebp/README.md": None,
}

# The vendored libwebp tree is checked as a deterministic aggregate because
# upstream ships hundreds of files. This value is regenerated with --update.
AGGREGATE = "668133ea1462b4b87d9652f85513408c82a6dfc35ba1d91ac7a1b37e8916e219"


def tree_digest(root: Path) -> str:
    h = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix().encode()
        h.update(rel)
        h.update(b"\0")
        data = path.read_bytes()
        try:
            data.decode("utf-8")
            data = data.replace(b"\r\n", b"\n")
        except UnicodeDecodeError:
            pass
        h.update(data)
        h.update(b"\0")
    return h.hexdigest()


def main() -> int:
    libwebp = ROOT / "app/src/main/cpp/third_party/libwebp"
    if not (libwebp / "src/webp/mux.h").is_file():
        print("ERROR: vendored libwebp source tree is incomplete")
        return 1
    digest = tree_digest(libwebp)
    print(f"vendored libwebp tree sha256: {digest}")
    print(f"expected tree sha256: {AGGREGATE}")
    if "--update" in sys.argv:
        print(f"AGGREGATE = \"{digest}\"")
        return 0
    if digest != AGGREGATE:
        print("ERROR: vendored libwebp checksum mismatch")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
