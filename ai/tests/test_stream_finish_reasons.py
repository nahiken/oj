"""The stream's end state, exercised through the real /ai/chat endpoint.

Ticket 05 wants two things that unit tests only half-proved:

* a leaked answer fingerprint really cuts a stream at runtime (`guarded`);
* a provider that ran out of budget really surfaces `length` in the SSE frame
  and in the session record, not just in a mapping function.

The provider and the two outbound HTTP clients are the seams here: everything
between them (guard, ladder, SSE framing, audit reason, session payload) is the
production code path.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# app.py boots settings at import time and refuses to guess a provider.
os.environ.setdefault("P6_PROVIDER", "fake")
os.environ.setdefault("P6_INTERNAL_KEY", "test-internal-key")

from fastapi.testclient import TestClient  # noqa: E402

import app as app_module  # noqa: E402
import context_client  # noqa: E402
import session_client  # noqa: E402
from guard import fingerprint  # noqa: E402

PROBLEM_ID = 1


class ScriptedProvider:
    """A provider whose text and end reason are decided by the test."""

    name = "scripted"

    def __init__(self, chunks: list[str], finish: str = "stop") -> None:
        self.chunks = chunks
        self.finish = finish

    def stream(self, messages, **opts):
        state = opts.get("state")
        for chunk in self.chunks:
            if state is not None:
                state.chunks += 1
            yield chunk
        if state is not None:
            state.finish_reason = self.finish


def context() -> dict:
    return {
        "problem": {"title": "A+B", "difficulty": "简单", "tags": ["数组"]},
        "episodes": [],
        "patterns": [],
        "skills": [],
        "relatedProblems": [],
        "summarySource": "RULE",
    }


@pytest.fixture
def wired(monkeypatch):
    records: list[dict] = []

    async def fake_fetch(settings, cookie, problem_id):
        return context()

    async def fake_mark(settings, cookie, problem_id, ids):
        return None

    def fake_record(settings, cookie, payload):
        records.append(payload)

    monkeypatch.setattr(context_client, "fetch", fake_fetch)
    monkeypatch.setattr(context_client, "mark_skills_used", fake_mark)
    monkeypatch.setattr(session_client, "record", fake_record)
    return records


def post_chat(provider, monkeypatch) -> str:
    monkeypatch.setattr(app_module, "provider", provider)
    client = TestClient(app_module.app)
    response = client.post(
        "/ai/chat",
        json={"problemId": PROBLEM_ID, "message": "帮我看下", "level": 1},
        headers={"x-uid": "9900000001"},
    )
    assert response.status_code == 200, response.text
    return response.text


def sse_payloads(raw: str, event: str) -> list[dict]:
    found = []
    for block in raw.split("\n\n"):
        lines = [line for line in block.splitlines() if line]
        if not lines or lines[0] != f"event: {event}":
            continue
        data = [line for line in lines if line.startswith("data: ")]
        if data:
            found.append(json.loads(data[0][len("data: ") :]))
    return found


def test_leaked_fingerprint_cuts_the_stream_and_is_counted(wired, monkeypatch):
    seed = "LEAKGRAM"  # exactly one 8-gram, so the fixture cannot be ambiguous
    monkeypatch.setitem(app_module.FINGERPRINTS, str(PROBLEM_ID), set(fingerprint.fingerprint(seed)))
    before = app_module.metrics.snapshot()["guard"]["leakBlocked"]

    # Split across chunks: the guard must judge the accumulated text, not one chunk.
    raw = post_chat(ScriptedProvider(["the answer is LEAK", "GRAM exactly"]), monkeypatch)

    done = sse_payloads(raw, "done")
    assert done and done[-1]["finishReason"] == "guarded"
    assert app_module.metrics.snapshot()["guard"]["leakBlocked"] == before + 1
    assert wired and wired[-1]["finishReason"] == "guarded"


def test_length_from_the_provider_reaches_the_sse_frame_and_the_record(wired, monkeypatch):
    raw = post_chat(ScriptedProvider(["partial answer, cut off"], finish="length"), monkeypatch)

    done = sse_payloads(raw, "done")
    assert done and done[-1]["finishReason"] == "length"
    assert wired and wired[-1]["finishReason"] == "length"


def test_normal_stream_still_records_stop(wired, monkeypatch):
    raw = post_chat(ScriptedProvider(["a", "b"], finish="stop"), monkeypatch)

    assert sse_payloads(raw, "done")[-1]["finishReason"] == "stop"
    assert wired and wired[-1]["finishReason"] == "stop"
    assert wired[-1]["completionTokens"] > 0
