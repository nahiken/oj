"""Read-only OJ tools exposed to the Hermes worker.

Why a plugin and not a patch: Hermes documents `~/.hermes/plugins/<name>/` as
the supported extension point, so the checkout stays unmodified and this code
is reviewed like any other file in the repo.

Why the tool calls back into `ai` instead of judge: the worker never
holds the user's Cookie. `P6_TURN_ID` is set by the worker for the duration of
one turn (one turn at a time per process), and `ai` resolves it to the
Cookie it is already holding.

Handlers must return a JSON string — that is the Hermes tool contract.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request

AI_URL = os.environ.get("P6_AI_URL", "http://127.0.0.1:8026").rstrip("/")
INTERNAL_KEY = os.environ.get("P6_INTERNAL_KEY", "").strip()


def _post(path: str, payload: dict, timeout: float = 3.0) -> dict:
    turn_id = os.environ.get("P6_TURN_ID", "")
    if not turn_id:
        return {"error": "no_turn", "retryable": False}
    body = json.dumps({**payload, "turnId": turn_id}).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if INTERNAL_KEY:
        headers["X-Internal-Key"] = INTERNAL_KEY
    request = urllib.request.Request(f"{AI_URL}{path}", data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read() or b"{}")
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError) as exc:
        # A dead tool must not kill the turn: the model gets a typed error and
        # decides whether to answer from what it already has.
        return {"error": "tool_unavailable", "retryable": True, "detail": type(exc).__name__}


def get_learning_context(args: dict, **kwargs) -> str:
    """What this student has done on this problem: episodes, patterns, skills."""
    return json.dumps(_post("/internal/tools/context", {}), ensure_ascii=False)


def search_skill(args: dict, **kwargs) -> str:
    """Find reusable solution recipes (platform ones plus this student's own)."""
    query = str((args or {}).get("query") or "").strip()
    if not query:
        # Fail here rather than at the bridge: a missing argument is the model's
        # mistake and it can fix it if the message says so.
        return json.dumps({"error": "query is required", "retryable": False}, ensure_ascii=False)
    return json.dumps(_post("/internal/tools/skill", {"query": query}), ensure_ascii=False)


_SCHEMA = {
    "name": "get_learning_context",
    "description": (
        "Read this student's learning context for the current problem: recent "
        "episodes, weak patterns and platform skills. Call it before giving "
        "advice that claims to be personalised. It returns JSON with error and "
        "retryable fields on failure."
    ),
    "parameters": {
        "type": "object",
        "properties": {},
        "required": [],
    },
}

_SKILL_SCHEMA = {
    "name": "search_skill",
    "description": (
        "Search reusable solution recipes for a topic: platform skills plus this "
        "student's own. Returns at most three skills with a short body each. Use "
        "it when the student asks 'how should I approach X', not to look up an "
        "answer. It returns JSON with error and retryable fields on failure."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Topic or technique, e.g. '二分答案 单调性' or 'sliding window'.",
            }
        },
        "required": ["query"],
    },
}


# Bridge tools are how a plugin tool is reached at all; our tools are the only
# thing they may reach. Everything else is denied with a message the model can
# read, which is better than a silent "tool not found".
BRIDGE_TOOLS = {"tool_search", "tool_describe", "tool_call"}
ALLOWED_TOOLS = {"get_learning_context", "search_skill"}

# Per-turn budgets live here because this is the only place that sees every
# tool call before it runs. Env vars keep the numbers configurable without a
# second channel; the turn id tells us when to reset.
MAX_TOOL_CALLS = int(os.environ.get("P6_MAX_TOOL_CALLS", "12") or 12)
TURN_WALL_MS = int(os.environ.get("P6_TURN_WALL_MS", "45000") or 45000)
_state: dict = {"turn": None, "calls": 0, "started": 0.0}


def _reset_if_new_turn() -> None:
    turn = os.environ.get("P6_TURN_ID", "")
    if _state["turn"] != turn:
        _state.update(turn=turn, calls=0, started=time.monotonic())


def _names_in(args: dict) -> list[str]:
    """`tool_call` takes either {name} or {calls: [{name}]}; read both."""
    raw = args.get("calls")
    if isinstance(raw, list):
        return [str((c or {}).get("name") or "") for c in raw if isinstance(c, dict)]
    if raw is None:
        return [str(args.get("name") or "")]
    return []


def pre_tool_call(tool_name: str, args: dict | None = None, **kwargs):
    """Second allowlist layer, on top of the toolset.

    The toolset decides what is *visible*; this decides what is *executable*.
    `tool_call` can name any tool in the catalog, so the toolsets alone would
    let the model reach terminal/browser/file tools that we never exposed.
    """
    args = args or {}
    _reset_if_new_turn()
    if _state["calls"] >= MAX_TOOL_CALLS:
        return {"action": "block", "message": "tool budget for this turn is exhausted; answer with what you have"}
    if (time.monotonic() - _state["started"]) * 1000 > TURN_WALL_MS:
        return {"action": "block", "message": "time budget for this turn is exhausted; answer with what you have"}
    if tool_name == "tool_call":
        denied = [name for name in _names_in(args) if name not in ALLOWED_TOOLS]
        if denied:
            return {"action": "block", "message": f"tool '{denied[0]}' is not available in this tutor"}
        _state["calls"] += 1
        return None
    if tool_name in BRIDGE_TOOLS or tool_name in ALLOWED_TOOLS:
        _state["calls"] += 1
        return None
    return {"action": "block", "message": f"tool '{tool_name}' is not available in this tutor"}


def register(ctx) -> None:
    ctx.register_hook("pre_tool_call", pre_tool_call)
    ctx.register_tool(
        name="get_learning_context",
        toolset="oj",
        schema=_SCHEMA,
        handler=get_learning_context,
        emoji="📚",
    )
    ctx.register_tool(
        name="search_skill",
        toolset="oj",
        schema=_SKILL_SCHEMA,
        handler=search_skill,
        emoji="🧭",
    )
