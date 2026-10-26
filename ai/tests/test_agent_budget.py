"""Per-turn budgets: the tool loop must be bounded, not merely discouraged.

These run without Hermes: the policy is a pure function of (env, call count).
"""

from __future__ import annotations

import itertools
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import hermes_plugin as plugin  # noqa: E402

# Deterministic, not time-based: `time.monotonic_ns()` on Windows is backed by a
# ~15.6ms tick, so two tests starting in the same tick produced the *same* turn
# id, and the per-turn state (call count) leaked from one test into the next.
_TURNS = itertools.count()


@pytest.fixture(autouse=True)
def fresh_turn(monkeypatch):
    monkeypatch.setenv("P6_TURN_ID", f"test-{next(_TURNS)}")
    monkeypatch.setattr(plugin, "MAX_TOOL_CALLS", 12)
    monkeypatch.setattr(plugin, "TURN_WALL_MS", 45_000)
    yield


def call(name: str = "get_learning_context") -> dict | None:
    return plugin.pre_tool_call("tool_call", {"calls": [{"name": name}]})


def test_after_the_tool_budget_tools_are_blocked_not_silently_dropped() -> None:
    plugin.MAX_TOOL_CALLS = 2

    assert call() is None
    assert call() is None
    verdict = call()

    assert verdict["action"] == "block"
    assert "budget" in verdict["message"]


def test_wall_clock_exhaustion_blocks_further_tools(monkeypatch) -> None:
    # The clock starts with the turn's first tool call, so exhaustion is
    # observed on a later call in the same turn.
    plugin.TURN_WALL_MS = 1
    assert call() is None
    # Move the turn's start back instead of sleeping: on Windows `time.monotonic`
    # is backed by a ~15.6ms tick, so `sleep(0.01)` frequently reports 0ms
    # elapsed and this test flaked. The policy under test is "elapsed > budget",
    # not "sleep really advanced the clock".
    plugin._state["started"] -= 1.0

    verdict = call()

    assert verdict["action"] == "block"
    assert "time budget" in verdict["message"]


def test_a_new_turn_resets_the_budget(monkeypatch) -> None:
    plugin.MAX_TOOL_CALLS = 1
    assert call() is None
    assert call()["action"] == "block"

    monkeypatch.setenv("P6_TURN_ID", "another-turn")

    assert call() is None


def test_denied_calls_do_not_consume_the_budget() -> None:
    plugin.MAX_TOOL_CALLS = 1
    assert call("terminal")["action"] == "block"
    assert call("get_learning_context") is None
