"""The record a tutoring session leaves in judge.

`ai_tutoring_session` is the denominator of 二次 AC 率: without a session row
there is nothing to attribute a later AC to. judge owns the table (the AI
service still never touches a database), so this module only builds the payload.

Strict on purpose: an unknown reason raises instead of quietly becoming "stop",
because a wrong reason silently poisons the metric. The caller decides whether to
drop the record and keep streaming.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

FINISH_REASONS = ("stop", "guarded", "length", "error", "aborted")


@dataclass(frozen=True)
class SessionFacts:
    """Everything one finished stream knows about itself.

    A single object rather than nine loose arguments: the payload and its caller
    used to repeat the same field list, so adding a field meant editing two
    signatures that could drift apart.
    """

    problem_id: int
    level: int
    session_id: str | None
    started: str
    finished: str
    reason: str
    prompt_tokens: int
    completion_tokens: int
    search_used: bool


def finalize_reason(finish: str, reached_terminal: bool) -> str:
    """A stream that never reached a terminal frame did not finish normally.

    Closing the tab aborts the generator; `finish` is still the "stop" default at
    that point, so the two pieces of information are needed together.
    """
    if not reached_terminal and finish == "stop":
        return "aborted"
    return finish


def payload(facts: SessionFacts) -> dict:
    """Turn the facts into the one object judge stores."""
    if facts.reason not in FINISH_REASONS:
        raise ValueError(f"unknown finish reason: {facts.reason!r}")
    if facts.level not in (1, 2, 3):
        raise ValueError(f"level must be 1, 2 or 3, got {facts.level!r}")
    if facts.problem_id <= 0:
        raise ValueError(f"problemId must be positive, got {facts.problem_id!r}")
    if datetime.fromisoformat(facts.finished) < datetime.fromisoformat(facts.started):
        raise ValueError("finishedAt must not be earlier than startedAt")
    return {
        "problemId": facts.problem_id,
        "level": facts.level,
        "sessionId": facts.session_id or None,
        "startedAt": facts.started,
        "finishedAt": facts.finished,
        "finishReason": facts.reason,
        "promptTokens": int(facts.prompt_tokens),
        "completionTokens": int(facts.completion_tokens),
        "searchUsed": bool(facts.search_used),
    }
