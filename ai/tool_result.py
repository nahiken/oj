"""Truncation for the `search_skill` tool result (spec D20).

The tool is the only place the model sees Skill bodies, so the limit lives here
rather than in the prompt: top-3, 120 characters each, 600 tokens in total. The
token count is the same 2.5 chars/token heuristic as `token_budget` and is
labelled `estimated` for the same reason — this machine has no tokenizer.

Order is the retrieval order (best match first): once the budget is exhausted we
stop instead of skipping ahead, because "the 4th hit is short" is not a reason
to drop the 2nd.
"""

from __future__ import annotations

from token_budget import estimate

TOP_N = 3
MAX_CHARS = 120
MAX_TOKENS = 600
HIT_OVERHEAD = 8
ELLIPSIS = "…"


def _clip(text: str | None, max_chars: int) -> str:
    if not text:
        return ""
    if len(text) <= max_chars:
        return text
    # The ellipsis is part of the budget: "120 字以内" is what the contract says,
    # so the visible string must not be 121 characters long.
    return text[: max(0, max_chars - 1)] + ELLIPSIS


def _cost(hit: dict) -> int:
    tags = ", ".join(hit.get("tags") or [])
    return (
        HIT_OVERHEAD
        + estimate(str(hit.get("name") or ""))
        + estimate(str(hit.get("content") or ""))
        + estimate(tags)
    )


def trim_skill_hits(
    hits: list[dict] | None,
    top: int = TOP_N,
    max_chars: int = MAX_CHARS,
    max_tokens: int = MAX_TOKENS,
) -> tuple[list[dict], dict]:
    """Return (skills, meta) without mutating the input."""
    considered = list(hits or [])
    kept: list[dict] = []
    tokens = 0
    dropped = 0
    for hit in considered[:top]:
        trimmed = {
            "skillId": hit.get("skillId"),
            "name": hit.get("name"),
            "content": _clip(str(hit.get("content") or ""), max_chars),
            "scope": hit.get("scope"),
            "tags": list(hit.get("tags") or []),
        }
        cost = _cost(trimmed)
        if kept and tokens + cost > max_tokens:
            dropped += 1
            break
        kept.append(trimmed)
        tokens += cost
    return kept, {
        "considered": len(considered),
        "returned": len(kept),
        "dropped": dropped,
        "topN": top,
        "maxChars": max_chars,
        "maxTokens": max_tokens,
        "tokens": tokens,
        "estimated": True,
    }
