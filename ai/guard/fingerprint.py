"""Answer fingerprints.

Only hashes are stored: the fixture file must never contain a solution. A hit
means the model reproduced a chunk of the reference answer, which is treated as
a leak regardless of how it was phrased around.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

N = 8


def ngrams(text: str, n: int = N) -> list[str]:
    compact = "".join(text.split())
    if len(compact) < n:
        return []
    return [compact[i : i + n] for i in range(len(compact) - n + 1)]


def fingerprint(text: str, n: int = N) -> list[str]:
    return [hashlib.sha256(gram.encode("utf-8")).hexdigest()[:16] for gram in ngrams(text, n)]


def load(path: Path) -> dict[str, set[str]]:
    if not path.exists():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {str(pid): set(hashes) for pid, hashes in raw.get("problems", {}).items()}


def leaks(text: str, hashes: set[str], n: int = N) -> bool:
    if not hashes:
        return False
    return any(gram in hashes for gram in fingerprint(text, n))
