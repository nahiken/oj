"""Context client classification: a revoked login is not "context is down"."""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import context_client  # noqa: E402
from settings import Settings  # noqa: E402

SETTINGS = Settings(
    enabled=True,
    provider="fake",
    api_key=None,
    judge_base_url="http://judge.test/judge",
)


class FakeResponse:
    def __init__(self, status_code: int, payload: dict) -> None:
        self.status_code = status_code
        self._payload = payload

    def json(self) -> dict:
        return self._payload


class FakeClient:
    response = FakeResponse(200, {})

    def __init__(self, *args, **kwargs) -> None:
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def get(self, *args, **kwargs):
        return FakeClient.response


def fetch_with(monkeypatch, status_code: int, payload: dict):
    FakeClient.response = FakeResponse(status_code, payload)
    monkeypatch.setattr(context_client.httpx, "AsyncClient", FakeClient)
    return asyncio.run(context_client.fetch(SETTINGS, "token", 1))


def test_http_403_is_an_auth_failure(monkeypatch):
    with pytest.raises(context_client.Unauthorized):
        fetch_with(monkeypatch, 403, {"code": 403, "msg": "无效的TOKEN"})


def test_envelope_code_403_is_an_auth_failure(monkeypatch):
    # judge answers HTTP 200 with code=403 for a revoked token.
    with pytest.raises(context_client.Unauthorized):
        fetch_with(monkeypatch, 200, {"code": 403, "msg": "无效的TOKEN", "data": None})


def test_other_refusals_stay_context_unavailable(monkeypatch):
    with pytest.raises(context_client.ContextUnavailable):
        fetch_with(monkeypatch, 200, {"code": 500, "msg": "boom", "data": None})


def test_missing_problem_is_not_auth(monkeypatch):
    with pytest.raises(context_client.ProblemNotFound):
        fetch_with(monkeypatch, 200, {"code": 200, "data": {"problem": None}})
