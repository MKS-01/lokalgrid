#!/usr/bin/env bash
# Build the firmware's record codec for the host and check it against the golden
# vectors — the same file the Kotlin suite checks itself against. No ESP-IDF, no
# board, no toolchain beyond cc and python3 (§5: pure logic builds for the host).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
golden="$here/../../mock-node/golden/vectors.json"
bin="$(mktemp -d)/record_vectors"

cc -std=c11 -Wall -Wextra -Werror -O1 -o "$bin" "$here/record_vectors.c" "$here/../main/record.c"

python3 - "$golden" "$bin" <<'PY'
import json, subprocess, sys

golden, binary = sys.argv[1], sys.argv[2]
vectors = json.load(open(golden))["vectors"]

fields = ["epoch", "lat_e7", "lon_e7", "alt", "baro", "spd", "hdg",
          "sv", "hd", "bat", "tmp", "flags"]

bad = 0
for v in vectors:
    args = [str(v["fields"][f]) for f in fields]
    got = subprocess.run([binary, *args], capture_output=True, text=True,
                         check=True).stdout.strip()
    want = v["hex"]
    if got == want:
        print(f"  ok    {v['name']}")
    else:
        bad += 1
        print(f"  FAIL  {v['name']}\n        want {want}\n        got  {got}")

print(f"\n{len(vectors) - bad} of {len(vectors)} golden vectors reproduced by the C codec")
sys.exit(1 if bad else 0)
PY
