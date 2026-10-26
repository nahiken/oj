"""Best-effort delivery of a tutoring-session record to judge.

The AI service still holds no database connection: judge owns
`ai_tutoring_session`, and this module only POSTs to its internal endpoint.

Two boundaries make the endpoint trustworthy, and both are needed:

* the gateway answers 404 for `/api/judge/learning/ai/**`, so a browser cannot
  reach it through the normal entry point;
* judge also demands a shared `X-Internal-Key`, so reaching port 8012 directly
  does not help either. This table is the denominator of 二次 AC 率; a row a user
  can forge is worse than no metric at all.

Delivery is fire-and-forget on a daemon thread. A slow or dead judge must never
delay the end of a stream, and a lost record must never be lost *silently* —
every drop leaves an audit line, because a quietly missing denominator is how a
metric starts lying.
"""

from __future__ import annotations

import atexit
import logging
import threading

import httpx

from settings import Settings

TIMEOUT_SECONDS = 2.0
HEADER = "X-Internal-Key"

# Same logger as the request audit trail: one place to look for "what did the
# tutor do", and it never carries bodies or the key.
audit = logging.getLogger("oj.ai.audit")

_threads: set[threading.Thread] = set()
_lock = threading.Lock()
_warned_missing_key = False


def record(settings: Settings, cookie: str | None, payload: dict) -> None:
    """Deliver one session record. Never raises, never blocks the caller."""
    global _warned_missing_key
    if not payload:
        return
    if not settings.internal_key:
        if not _warned_missing_key:
            _warned_missing_key = True
            audit.warning("evt=ai.session.disabled reason=no-internal-key")
        return
    thread = threading.Thread(
        target=_run,
        args=(settings.judge_base_url, settings.internal_key, cookie, payload),
        name="ai-session-record",
        daemon=True,
    )
    with _lock:
        _threads.add(thread)
    thread.start()


@atexit.register
def _drain() -> None:
    """Give in-flight records a moment at shutdown.

    Without this an ordinary uvicorn restart drops whatever was posted in the
    last few milliseconds, and the drop would be invisible: the thread simply
    never runs. One second is enough for a loopback POST.
    """
    with _lock:
        pending = list(_threads)
    for thread in pending:
        thread.join(timeout=1.0)


def _run(base_url: str, internal_key: str, cookie: str | None, payload: dict) -> None:
    """Deliver one record, then drop this thread from the in-flight set.

    Without the discard the set keeps one dead Thread per chat for the lifetime
    of the process; `_drain` would also keep joining threads that already ran.
    """
    try:
        _post(base_url, internal_key, cookie, payload)
    finally:
        with _lock:
            _threads.discard(threading.current_thread())


def _post(base_url: str, internal_key: str, cookie: str | None, payload: dict) -> None:
    try:
        headers = {HEADER: internal_key}
        if cookie:
            headers["Cookie"] = cookie
        response = httpx.post(
            f"{base_url}/learning/ai/session",
            json=payload,
            headers=headers,
            timeout=TIMEOUT_SECONDS,
        )
        _record_outcome(base_url, payload, response)
    except Exception as exc:  # noqa: BLE001 - a lost record must never surface
        audit.warning("evt=ai.session.lost problemId=%s reason=%s err=%s",
                      payload.get("problemId"), payload.get("finishReason"),
                      exc.__class__.__name__)


def _record_outcome(base_url: str, payload: dict, response: httpx.Response) -> None:
    where = f"{base_url} problemId={payload.get('problemId')} reason={payload.get('finishReason')}"
    if response.status_code != 200:
        audit.warning("evt=ai.session.lost %s status=%s", where, response.status_code)
        return
    try:
        body = response.json()
    except ValueError:
        audit.warning("evt=ai.session.lost %s err=non-json-body", where)
        return
    data = body.get("data") if isinstance(body, dict) else None
    if data is not True:
        # judge answers R.ok(false) when it drops a row it considers invalid.
        audit.warning("evt=ai.session.rejected %s judgeData=%s", where, data)
