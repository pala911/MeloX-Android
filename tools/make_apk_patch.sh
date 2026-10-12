#!/usr/bin/env bash
# Builds a BSDIFF40 incremental-update patch between two release APKs and prints
# the `updatePatches` entry to publish in the signed remote config.
#
#   tools/make_apk_patch.sh <installed.apk> <new.apk> <out.patch> [toVersion]
#
# Requires the reference `bsdiff4` package (pip install bsdiff4). Point PYTHON at
# an interpreter that has it, e.g. a venv:
#
#   python3 -m venv .venv && .venv/bin/pip install bsdiff4
#   PYTHON=.venv/bin/python tools/make_apk_patch.sh old.apk new.apk 0.6.6.patch 0.6.6
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <installed.apk> <new.apk> <out.patch> [toVersion]" >&2
  exit 2
fi

OLD="$1"; NEW="$2"; OUT="$3"; TO_VERSION="${4:-}"
PYTHON="${PYTHON:-python3}"

"$PYTHON" - "$OLD" "$NEW" "$OUT" "$TO_VERSION" <<'PY'
import hashlib, json, pathlib, sys

old_path, new_path, out_path, to_version = sys.argv[1:5]
try:
    import bsdiff4
except ImportError:
    sys.exit("bsdiff4 is required: python3 -m venv .venv && .venv/bin/pip install bsdiff4")

old = pathlib.Path(old_path).read_bytes()
new = pathlib.Path(new_path).read_bytes()
patch = bsdiff4.diff(old, new)
pathlib.Path(out_path).write_bytes(patch)

def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

entry = {
    "fromApkSha256": sha(old),
    "toVersion": to_version or "TODO",
    "apkSha256": sha(new),
    "patchUrl": "https://github.com/lladlam/MeloX-Android/releases/download/<tag>/" + pathlib.Path(out_path).name,
    "patchSha256": sha(patch),
    "patchBytes": len(patch),
}
print(f"patch: {out_path}  {len(patch)} bytes ({len(patch)/1048576:.1f} MB)", file=sys.stderr)
print(json.dumps({"updatePatches": [entry]}, ensure_ascii=False, indent=2))
PY
