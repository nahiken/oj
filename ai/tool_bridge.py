"""Turn-scoped state for agent tools.

The worker runs agent code we do not fully trust, so it never receives the
user's Cookie. Instead, while a turn is open, `ai` keeps the Cookie in
this in-memory map keyed by an unguessable turn id; the worker's tools call back
with that id and the bytes never leave this process.

Scope is one turn: opened when `/ai/chat` starts, closed in `finally`. Nothing
is persisted, and a restart simply invalidates every id.
"""

from __future__ import annotations

import secrets
import threading

_LOCK = threading.Lock()
_TURNS: dict[str, dict] = {}


def open_turn(*, uid: str, cookie: str | None, problem_id: int) -> str:
    turn_id = secrets.token_urlsafe(16)
    with _LOCK:
        _TURNS[turn_id] = {"uid": uid, "cookie": cookie, "problemId": problem_id}
    return turn_id


def get(turn_id: str) -> dict | None:
    with _LOCK:
        return _TURNS.get(turn_id)


def close_turn(turn_id: str) -> None:
    with _LOCK:
        _TURNS.pop(turn_id, None)


def open_count() -> int:
    """Test hook: proves the finally block actually closes turns."""
    with _LOCK:
        return len(_TURNS)
