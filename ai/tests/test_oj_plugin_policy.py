"""The second allowlist layer, tested without Hermes.

`pretool_call` is the only thing standing between `tool_call` (which can name
any tool in the catalog) and the tools we never exposed. It has to be a pure
function of (tool_name, args), so it can be tested like one.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hermes_plugin import pre_tool_call  # noqa: E402


def test_bridge_calls_to_our_tool_are_allowed() -> None:
    assert pre_tool_call("tool_call", {"calls": [{"name": "get_learning_context"}]}) is None
    assert pre_tool_call("tool_call", {"name": "get_learning_context"}) is None


def test_bridge_call_to_an_unexposed_tool_is_blocked() -> None:
    verdict = pre_tool_call("tool_call", {"calls": [{"name": "terminal"}]})

    assert verdict["action"] == "block"
    assert "terminal" in verdict["message"]


def test_search_and_describe_stay_available_so_the_bridge_works() -> None:
    assert pre_tool_call("tool_search", {"query": "context"}) is None
    assert pre_tool_call("tool_describe", {"name": "get_learning_context"}) is None


def test_a_non_bridge_tool_is_blocked_outright() -> None:
    verdict = pre_tool_call("write_file", {"path": "/tmp/x"})

    assert verdict["action"] == "block"


def test_our_tool_called_directly_is_allowed() -> None:
    assert pre_tool_call("get_learning_context", {}) is None
