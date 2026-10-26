"""Token buckets: the 7th request in a minute is refused, without sleeping."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import ratelimit  # noqa: E402


class Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


def test_six_requests_pass_and_the_seventh_is_refused():
    clock = Clock()
    limiter = ratelimit.Limiter(clock)
    results = [limiter.allow("u1")[0] for _ in range(6)]
    assert results == [True] * 6
    allowed, retry_after = limiter.allow("u1")
    assert allowed is False
    assert 1 <= retry_after <= 60


def test_the_bucket_refills_after_the_window():
    clock = Clock()
    limiter = ratelimit.Limiter(clock)
    for _ in range(6):
        limiter.allow("u1")
    assert limiter.allow("u1")[0] is False
    clock.now += 60.0
    assert limiter.allow("u1")[0] is True


def test_uids_are_counted_separately():
    clock = Clock()
    limiter = ratelimit.Limiter(clock)
    for _ in range(6):
        limiter.allow("u1")
    assert limiter.allow("u1")[0] is False
    assert limiter.allow("u2")[0] is True


def test_a_denied_request_does_not_burn_the_other_bucket():
    clock = Clock()
    limiter = ratelimit.Limiter(clock)
    for _ in range(6):
        limiter.allow("u1")
    # u1 is now empty; u2 must still get its full six.
    limiter.allow("u1")
    assert [limiter.allow("u2")[0] for _ in range(6)] == [True] * 6


def test_global_bucket_caps_the_process():
    clock = Clock()
    limiter = ratelimit.Limiter(clock)
    allowed = 0
    for index in range(61):
        if limiter.allow(f"u{index}")[0]:
            allowed += 1
    assert allowed == 60
