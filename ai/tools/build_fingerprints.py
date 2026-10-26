"""Build the answer fingerprint fixture.

Run this whenever the target problem's answer changes; it prints the JSON that
`benchmark/fixtures/p6-answer-fingerprints.json` should contain. Only hashes are
written, so the fixture can live in the repo.

    D:\\python\\python.exe tools\\build_fingerprints.py 1 "Read two integers..."
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from guard.fingerprint import N, fingerprint  # noqa: E402


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: build_fingerprints.py <problemId> <answer> [<problemId> <answer> ...]", file=sys.stderr)
        return 2
    problems: dict[str, list[str]] = {}
    pairs = sys.argv[1:]
    for index in range(0, len(pairs) - 1, 2):
        problems[pairs[index]] = fingerprint(pairs[index + 1])
    print(json.dumps({"n": N, "problems": problems}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
