"""Wire format between `ai` (3.11) and the Hermes worker (3.14).

The worker speaks NDJSON, not SSE, because the answer has to pass the guard and
the ladder classifier *inside this process* before the browser sees a byte. This
module owns the decoding; `app.py` owns the policy (guard, budget, audit).

The relay is synchronous on purpose: `app.py` already streams from a sync
generator, and the worker is a loopback hop. Nothing is buffered — the first
delta must reach the browser at the latency it would have had without the hop.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Iterator

import httpx

# Event names the worker may emit. Anything else is ignored on purpose: the
# worker is a separate deployable and will grow new events before we ship them.
KNOWN_EVENTS = ("delta", "step", "done", "error")


class WorkerUnavailable(RuntimeError):
    """The worker is down, overloaded, or answered with a non-NDJSON body."""


@dataclass(frozen=True)
class Step:
    index: int
    tool: str
    status: str
    ms: int | None
    args_hash: str | None

    def as_dict(self) -> dict:
        return {
            "index": self.index,
            "tool": self.tool,
            "status": self.status,
            "ms": self.ms,
            "argsHash": self.args_hash,
        }


def build_payload(
    *,
    uid: str,
    problem_id: int,
    message: str,
    level: int,
    mode: str,
    history: list[dict],
    messages: list[dict],
    max_steps: int,
    turn_id: str,
) -> dict:
    """The whole turn request.

    `messages` is the prompt this process already assembled, so the prompt
    builder stays in one place; the worker only adds its agent loop.
    """
    # `messages` are provider.base.Message dataclasses, not dicts: attribute
    # access here is what keeps the assembled prompt (banner + user wrapper)
    # intact instead of guessing it back from `message`.
    def _part(message):
        if isinstance(message, dict):
            return {"role": message.get("role"), "content": message.get("content")}
        return {"role": getattr(message, "role", None), "content": getattr(message, "content", None)}

    parts = [_part(m) for m in messages]
    system = next((p["content"] or "" for p in parts if p["role"] == "system"), "")
    return {
        "messages": parts,
        "uid": uid,
        "problemId": problem_id,
        "message": message,
        "level": level,
        "mode": mode,
        "history": history,
        "system": system,
        "maxSteps": max_steps,
        "turnId": turn_id,
    }


def ping(base_url: str, *, timeout: float = 1.0) -> bool:
    """Cheap liveness probe, called before the stream starts so we can still
    answer 503. A mid-stream failure can only become an `error` event."""
    try:
        with httpx.Client(timeout=timeout) as http:
            return http.get(f"{base_url.rstrip('/')}/health").status_code == 200
    except httpx.HTTPError:
        return False


def stream_turn(
    base_url: str,
    payload: dict,
    *,
    internal_key: str | None = None,
    timeout: float = 45.0,
    client: httpx.Client | None = None,
) -> Iterator[tuple[str, object]]:
    """Yield `(event, data)` tuples; raise WorkerUnavailable before any event."""
    headers = {"Content-Type": "application/json", "Accept": "application/x-ndjson"}
    if internal_key:
        headers["X-Internal-Key"] = internal_key

    owns_client = client is None
    http = client or httpx.Client(timeout=timeout)
    try:
        try:
            with http.stream("POST", f"{base_url.rstrip('/')}/turn", json=payload, headers=headers) as response:
                if response.status_code != 200:
                    raise WorkerUnavailable(f"worker answered {response.status_code}")
                content_type = response.headers.get("content-type", "")
                if "ndjson" not in content_type:
                    raise WorkerUnavailable(f"worker content-type is {content_type!r}")
                for line in response.iter_lines():
                    parsed = _parse_line(line)
                    if parsed is not None:
                        yield parsed
        except httpx.HTTPError as exc:
            raise WorkerUnavailable(str(exc)) from exc
    finally:
        if owns_client:
            http.close()


def _parse_line(line: str) -> tuple[str, object] | None:
    line = line.strip()
    if not line:
        return None
    try:
        event = json.loads(line)
    except json.JSONDecodeError as exc:
        raise WorkerUnavailable(f"worker sent a non-JSON line: {line[:80]!r}") from exc
    if not isinstance(event, dict):
        raise WorkerUnavailable("worker event must be an object")
    kind = event.get("type")
    if kind not in KNOWN_EVENTS:
        return None
    if kind == "delta":
        text = event.get("text")
        # A None/empty delta is the worker's flush sentinel, not content.
        if not isinstance(text, str) or not text:
            return None
        return ("delta", text)
    if kind == "step":
        try:
            step = Step(
                index=int(event.get("index", 0)),
                tool=str(event.get("tool", "")),
                status=str(event.get("status", "ok")),
                ms=int(event["ms"]) if event.get("ms") is not None else None,
                args_hash=event.get("argsHash"),
            )
        except (TypeError, ValueError) as exc:
            raise WorkerUnavailable("worker step event is malformed") from exc
        return ("step", step)
    if kind == "done":
        return ("done", event)
    return ("error", event)
