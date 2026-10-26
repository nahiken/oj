"""Tool layer for `search_skill` (spec D7 / D20).

Two seams, both without Hermes and without judge:

* the pure truncation function (top-3 / 120 chars / 600 tokens);
* `/internal/tools/skill`, driven through the real FastAPI app with a patched
  judge client, so the turn-id check, the error envelopes and the loopback rule
  are exercised as they ship.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# app.py boots settings at import time and refuses to guess a provider.
os.environ.setdefault("P6_PROVIDER", "fake")
os.environ.setdefault("P6_INTERNAL_KEY", "test-internal-key")

from fastapi.testclient import TestClient  # noqa: E402

import app as app_module  # noqa: E402
import skill_client  # noqa: E402
import tool_bridge  # noqa: E402
from hermes_plugin import ALLOWED_TOOLS, register, search_skill  # noqa: E402
from tool_result import trim_skill_hits  # noqa: E402

LOOPBACK = ("127.0.0.1", 50000)


def hit(skill_id: int, content: str = "先确定单调性", name: str = "二分答案") -> dict:
    return {
        "skillId": skill_id,
        "name": name,
        "content": content,
        "scope": "PLATFORM",
        "tags": ["二分"],
        "score": 0.03,
        "bm25Rank": 1,
        "denseRank": 2,
    }


# --- truncation -------------------------------------------------------------


def test_content_is_clipped_to_the_contract_length():
    skills, meta = trim_skill_hits([hit(1, "x" * 400)])

    assert len(skills[0]["content"]) <= 120
    assert skills[0]["content"].endswith("…")
    assert meta["maxChars"] == 120


def test_only_the_top_three_survive():
    skills, meta = trim_skill_hits([hit(index) for index in range(1, 6)])

    assert [skill["skillId"] for skill in skills] == [1, 2, 3]
    assert meta["considered"] == 5
    assert meta["returned"] == 3


def test_the_budget_stops_the_tail_instead_of_skipping_ahead():
    # Lowered budget so the limit actually binds: each clipped hit costs ~48
    # tokens, so with 100 only two survive. (The contract's own 600 is a guard
    # rail: 3 × clipped hits never reach it, which is the point.)
    wide = "标" * 330
    skills, meta = trim_skill_hits([hit(index, wide) for index in range(1, 6)], max_tokens=150)

    assert [skill["skillId"] for skill in skills] == [1, 2]
    assert meta["dropped"] == 1
    assert meta["tokens"] <= 150


def test_the_contract_budget_is_never_exceeded():
    _, meta = trim_skill_hits([hit(index, "标" * 500) for index in range(1, 6)])

    assert meta["tokens"] <= 600
    assert meta["maxTokens"] == 600


def test_counts_are_labelled_estimated():
    _, meta = trim_skill_hits([hit(1)])

    assert meta["estimated"] is True


def test_empty_result_is_not_an_error():
    skills, meta = trim_skill_hits([])

    assert skills == []
    assert meta["returned"] == 0


# --- plugin tool ------------------------------------------------------------


def test_missing_query_is_an_error_the_model_can_fix():
    payload = __import__("json").loads(search_skill({}))

    assert payload["error"]
    assert payload["retryable"] is False


def test_query_is_forwarded_to_the_bridge(monkeypatch):
    calls = []

    def fake_post(path, payload, timeout=3.0):
        calls.append((path, payload))
        return {"ok": True, "data": {"skills": [], "meta": {}}}

    monkeypatch.setattr("hermes_plugin._post", fake_post)
    __import__("json").loads(search_skill({"query": " 滑动窗口 "}))

    assert calls == [("/internal/tools/skill", {"query": "滑动窗口"})]


class RecordingCtx:
    def __init__(self) -> None:
        self.tools = []
        self.hooks = []

    def register_tool(self, **kwargs) -> None:
        self.tools.append(kwargs)

    def register_hook(self, name, handler) -> None:
        self.hooks.append((name, handler))


def test_register_exposes_both_tools_in_the_oj_toolset():
    ctx = RecordingCtx()
    register(ctx)

    names = {tool["name"] for tool in ctx.tools}
    assert names == {"get_learning_context", "search_skill"}
    assert {tool["toolset"] for tool in ctx.tools} == {"oj"}
    # Every registered tool must be reachable through the bridge allowlist,
    # otherwise `tool_call` would be blocked for a tool we just advertised.
    assert names <= ALLOWED_TOOLS


# --- HTTP endpoint ----------------------------------------------------------


def open_turn() -> str:
    return tool_bridge.open_turn(uid="7", cookie="token=abc", problem_id=1)


def post_skill(turn_id: str, query: str = "二分"):
    client = TestClient(app_module.app, client=LOOPBACK)
    return client.post("/internal/tools/skill", json={"turnId": turn_id, "query": query})


def test_endpoint_returns_trimmed_skills(monkeypatch):
    async def fake_search(settings, cookie, query, top_k):
        assert cookie == "token=abc"
        assert top_k == 5
        return [hit(index) for index in range(1, 6)]

    monkeypatch.setattr(skill_client, "search", fake_search)
    turn_id = open_turn()
    try:
        response = post_skill(turn_id)
    finally:
        tool_bridge.close_turn(turn_id)

    assert response.status_code == 200
    body = response.json()
    assert body["ok"] is True
    assert len(body["data"]["skills"]) == 3
    assert body["data"]["meta"]["returned"] == 3


def test_a_closed_turn_is_rejected(monkeypatch):
    async def fake_search(*args, **kwargs):  # pragma: no cover - must not run
        raise AssertionError("a closed turn must not reach judge")

    monkeypatch.setattr(skill_client, "search", fake_search)

    response = post_skill("not-a-turn")

    assert response.status_code == 409
    assert response.json()["retryable"] is False


def test_judge_down_is_a_retryable_503(monkeypatch):
    async def exploding(settings, cookie, query, top_k):
        raise skill_client.SkillSearchUnavailable("boom")

    monkeypatch.setattr(skill_client, "search", exploding)
    turn_id = open_turn()
    try:
        response = post_skill(turn_id)
    finally:
        tool_bridge.close_turn(turn_id)

    assert response.status_code == 503
    assert response.json()["retryable"] is True


def test_an_expired_login_is_not_retryable(monkeypatch):
    async def unauthorized(settings, cookie, query, top_k):
        raise skill_client.Unauthorized("token expired")

    monkeypatch.setattr(skill_client, "search", unauthorized)
    turn_id = open_turn()
    try:
        response = post_skill(turn_id)
    finally:
        tool_bridge.close_turn(turn_id)

    assert response.status_code == 401
    assert response.json()["retryable"] is False


def test_a_non_loopback_caller_is_forbidden():
    client = TestClient(app_module.app)
    response = client.post("/internal/tools/skill", json={"turnId": "x", "query": "二分"})

    assert response.status_code == 403
