"""Internal summary endpoint (ADR 0005): text in, text out."""

from __future__ import annotations

from fastapi import Request

from provider.base import Message

PROMPT_VERSION = "summary-v1"


def fake_summary(body: dict) -> str:
    outcome = body.get("outcome") or "UNKNOWN"
    attempts = body.get("attempts") or 0
    base = body.get("summary") or ""
    return f"这次会话尝试 {attempts} 次，结果 {outcome}；过程：{base[:80]}"


async def summarize(request: Request, provider) -> dict:
    body = await request.json()
    if provider.name == "fake":
        content = fake_summary(body)
    else:
        prompt = (
            "用一句话总结这次做题过程，不超过 80 字，不要写代码，不要评价用户。\n"
            f"结果：{body.get('outcome')}；尝试次数：{body.get('attempts')}；"
            f"过程：{body.get('summary') or ''}"
        )
        content = "".join(provider.stream([Message("user", prompt)], level=1, mode="explain")).strip()
    return {
        "content": content,
        "model": provider.model_id,
        "prompt_version": PROMPT_VERSION,
    }
