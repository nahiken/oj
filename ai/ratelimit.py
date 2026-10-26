"""In-process token buckets (spec D14).

Two buckets guard `/ai/chat`: one per uid (6 requests / minute) and one global
(60 requests / minute). Deliberately in-process: the contract forbids
distributed state here, and a single-instance deployment is the current shape
(0006 §10). The clock is injectable so tests do not sleep.

A denied request must not burn a token in the other bucket, so `allow` checks
both first and only consumes when both say yes.
"""

from __future__ import annotations

import threading
import time
from typing import Callable

UID_CAPACITY = 6
GLOBAL_CAPACITY = 60
WINDOW_SECONDS = 60.0


class _Bucket:
    """Classic token bucket: `capacity` tokens, refilled over `window` seconds."""

    def __init__(self, capacity: int, window: float, clock: Callable[[], float]) -> None:
        self._capacity = float(capacity)
        self._rate = capacity / window
        self._clock = clock
        self._tokens = float(capacity)
        self._updated = clock()

    def _refill(self) -> None:
        now = self._clock()
        self._tokens = min(self._capacity, self._tokens + (now - self._updated) * self._rate)
        self._updated = now

    def ready(self) -> bool:
        self._refill()
        return self._tokens >= 1.0

    def take(self) -> None:
        self._refill()
        self._tokens -= 1.0

    def retry_after(self) -> int:
        self._refill()
        return max(1, int((1.0 - self._tokens) / self._rate + 0.999))


class Limiter:
    def __init__(
        self,
        clock: Callable[[], float] = time.monotonic,
        uid_capacity: int = UID_CAPACITY,
        global_capacity: int = GLOBAL_CAPACITY,
    ) -> None:
        self.uid_capacity = uid_capacity
        self.global_capacity = global_capacity
        self._lock = threading.Lock()
        self._clock = clock
        self._global = _Bucket(global_capacity, WINDOW_SECONDS, clock)
        self._per_uid: dict[str, _Bucket] = {}

    def allow(self, uid: str) -> tuple[bool, int]:
        """Return (allowed, retry_after_seconds). Never raises."""
        with self._lock:
            bucket = self._bucket_for(uid)
            if not bucket.ready():
                return False, bucket.retry_after()
            if not self._global.ready():
                return False, self._global.retry_after()
            bucket.take()
            self._global.take()
            return True, 0

    def _bucket_for(self, uid: str) -> _Bucket:
        bucket = self._per_uid.get(uid)
        if bucket is None:
            # Bound the map: a long-running process must not grow per-uid state forever.
            if len(self._per_uid) >= 10_000:
                self._per_uid.clear()
            bucket = _Bucket(self.uid_capacity, WINDOW_SECONDS, self._clock)
            self._per_uid[uid] = bucket
        return bucket
