"""Counters exposed by GET /ai/metrics.

Field names are part of the contract in docs/design-docs/0006-ai-tutor.md and
must not be renamed without updating it.
"""

from __future__ import annotations

import threading


class Metrics:
    def __init__(self, provider: str, enabled: bool, key_state: str = "absent") -> None:
        self._lock = threading.Lock()
        self._provider = provider
        self._enabled = enabled
        self._key_state = key_state
        self._requests = 0
        self._started = 0
        self._completed = 0
        self._failed = 0
        self._errors = {"judge": 0, "provider": 0, "guard": 0, "ratelimit": 0}
        self._prompt_tokens = 0
        self._completion_tokens = 0
        self._leak_blocked = 0
        self._ladder_violations = 0
        self._skills_used = 0
        self._skills_extracted = 0
        self._last_ttft_ms: int | None = None

    def _bump(self, name: str) -> None:
        with self._lock:
            setattr(self, name, getattr(self, name) + 1)

    def request(self) -> None:
        self._bump("_requests")

    def stream_started(self) -> None:
        self._bump("_started")

    def stream_completed(self) -> None:
        self._bump("_completed")

    def stream_failed(self) -> None:
        self._bump("_failed")

    def error(self, kind: str) -> None:
        with self._lock:
            self._errors[kind] = self._errors.get(kind, 0) + 1

    def guard_leak(self) -> None:
        self._bump("_leak_blocked")

    def guard_ladder(self) -> None:
        self._bump("_ladder_violations")

    def skills_used(self, count: int) -> None:
        with self._lock:
            self._skills_used += count

    def skill_extracted(self) -> None:
        self._bump("_skills_extracted")

    def tokens(self, prompt: int, completion: int) -> None:
        with self._lock:
            self._prompt_tokens += prompt
            self._completion_tokens += completion

    def record_ttft(self, ttft_ms: int | None) -> None:
        if ttft_ms is not None:
            with self._lock:
                self._last_ttft_ms = ttft_ms

    def snapshot(self, ttft_ms: int | None = None) -> dict:
        if ttft_ms is None:
            ttft_ms = self._last_ttft_ms
        with self._lock:
            return {
                "enabled": self._enabled,
                "provider": self._provider,
                # 0006 §5: the key itself never leaves the process, only present|absent.
                "key": self._key_state,
                "requests": self._requests,
                "streams": {
                    "started": self._started,
                    "completed": self._completed,
                    "failed": self._failed,
                },
                "errors": dict(self._errors),
                "ttftMs": {"value": ttft_ms, "available": ttft_ms is not None},
                "tokens": {
                    "prompt": self._prompt_tokens,
                    "completion": self._completion_tokens,
                    "estimated": True,
                },
                "guard": {
                    "leakBlocked": self._leak_blocked,
                    "ladderViolations": self._ladder_violations,
                },
                "skills": {
                    "used": self._skills_used,
                    "extracted": self._skills_extracted,
                },
            }
