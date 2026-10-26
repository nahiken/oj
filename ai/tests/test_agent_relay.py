"""The worker wire contract: what we accept, what we refuse.

These tests never touch Hermes. They pin the only thing `ai` may assume
about a process we do not own: NDJSON in, four event names, nothing else.
"""

from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import agent_relay  # noqa: E402


class _Worker:
    """A fake worker that replays a fixed script and records the request."""

    def __init__(self, status: int, body: bytes, content_type: str = "application/x-ndjson") -> None:
        self.status = status
        self.body = body
        self.content_type = content_type
        self.headers_seen: dict = {}
        self.path_seen: str = ""
        worker = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - stdlib naming
                worker.path_seen = self.path
                worker.headers_seen = {k.lower(): v for k, v in self.headers.items()}
                length = int(self.headers.get("content-length", "0"))
                self.rfile.read(length)
                self.send_response(worker.status)
                self.send_header("content-type", worker.content_type)
                self.send_header("content-length", str(len(worker.body)))
                self.end_headers()
                self.wfile.write(worker.body)

            def log_message(self, *args):  # silence the test output
                return

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *args):
        self._server.shutdown()
        self._server.server_close()
        return False

    @property
    def url(self) -> str:
        host, port = self._server.server_address[:2]
        return f"http://{host}:{port}"


def ndjson(*events: dict) -> bytes:
    return b"".join(json.dumps(e, ensure_ascii=False).encode("utf-8") + b"\n" for e in events)


def collect(url: str, **kwargs) -> list:
    return list(agent_relay.stream_turn(url, {"uid": "1"}, **kwargs))


def test_deltas_and_done_are_decoded_in_order() -> None:
    body = ndjson(
        {"type": "delta", "text": "你"},
        {"type": "delta", "text": "好"},
        {"type": "done", "finishReason": "stop"},
    )
    with _Worker(200, body) as worker:
        events = collect(worker.url)

    assert [kind for kind, _ in events] == ["delta", "delta", "done"]
    assert events[0][1] == "你"
    assert events[2][1]["finishReason"] == "stop"


def test_internal_key_and_path_are_sent() -> None:
    with _Worker(200, ndjson({"type": "done", "finishReason": "stop"})) as worker:
        collect(worker.url, internal_key="secret")

    assert worker.path_seen == "/turn"
    assert worker.headers_seen["x-internal-key"] == "secret"


def test_non_200_means_unavailable_not_an_empty_answer() -> None:
    with _Worker(503, b"busy") as worker:
        with pytest.raises(agent_relay.WorkerUnavailable):
            collect(worker.url)


def test_wrong_content_type_is_refused() -> None:
    with _Worker(200, b'{"type":"delta","text":"x"}', content_type="application/json") as worker:
        with pytest.raises(agent_relay.WorkerUnavailable):
            collect(worker.url)


def test_unknown_event_is_ignored_for_forward_compatibility() -> None:
    body = ndjson({"type": "heartbeat"}, {"type": "done", "finishReason": "stop"})
    with _Worker(200, body) as worker:
        events = collect(worker.url)

    assert [kind for kind, _ in events] == ["done"]


def test_malformed_line_is_refused() -> None:
    with _Worker(200, b"not json\n") as worker:
        with pytest.raises(agent_relay.WorkerUnavailable):
            collect(worker.url)


def test_step_carries_no_raw_arguments() -> None:
    body = ndjson(
        {"type": "step", "index": 1, "tool": "search_problem", "status": "ok", "ms": 12, "argsHash": "abc"},
        {"type": "done", "finishReason": "stop", "steps": 1},
    )
    with _Worker(200, body) as worker:
        events = collect(worker.url)

    kind, step = events[0]
    assert kind == "step"
    assert step.as_dict() == {
        "index": 1,
        "tool": "search_problem",
        "status": "ok",
        "ms": 12,
        "argsHash": "abc",
    }
