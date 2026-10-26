"""Internal endpoints: text in, text out. Never reachable through the gateway.

The gateway only routes /api/ai/**, so /internal/** is not exposed. As a second
lock, a request that carries X-Uid (i.e. came through the gateway) is refused.
"""

from __future__ import annotations

from fastapi import Request

from provider.base import Message

PROMPT_VERSION = "extract-v1"


def is_internal(x_uid: str | None, client_host: str | None) -> bool:
    if x_uid:
        return False
    return client_host in ("127.0.0.1", "::1", "localhost")


def fake_candidate(tags: list[str], summary: str) -> str:
    tag = tags[0] if tags else "this kind of problem"
    return (
        f"1. restate the input and the boundary for {tag}\n"
        f"2. reduce the problem to one pass over the data\n"
        f"3. check the empty case and the single-element case\n"
        f"note: derived from a session where the solver said: {summary[:80]}"
    )


async def extract(request: Request, x_uid: str | None, provider) -> dict:
    body = await request.json()
    tags = [str(tag) for tag in (body.get("tags") or [])]
    summary = str(body.get("summary") or "")
    if provider.name == "fake":
        content = fake_candidate(tags, summary)
    else:
        prompt = (
            "把下面这次做题过程总结成可复用的解题步骤，3 到 5 步，每行以 '1. ' 这样的编号开头，"
            "不要写代码，不要超过 500 字。\n"
            f"题目标签：{', '.join(tags)}\n过程摘要：{summary}"
        )
        chunks = provider.stream([Message("user", prompt)], level=3, mode="chat")
        content = "".join(chunks).strip()
    steps = [
        line.split(".", 1)[1].strip()
        for line in content.splitlines()
        if len(line) > 2 and line[0].isdigit() and "." in line[:3]
    ]
    return {
        "content": content,
        "steps": steps,
        "model": provider.model_id,
        "prompt_version": PROMPT_VERSION,
    }
