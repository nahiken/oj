"""Deterministic provider used when no key is available.

The point is not to look clever: it is to make "did the Context reach the
prompt?" observable. The answer text is a pure function of the messages, so two
runs with the same fixture produce the same bytes.
"""

from __future__ import annotations

import time
from typing import Iterator

import levels
from .base import Message

WEAK_TAG = "WEAK_TAG:"
SKILL = "SKILL:"
RELATED = "[相关题]"


def _related_titles(text: str) -> list[str]:
    """Titles under `[相关题]`, so search injection is visible in the answer."""
    titles: list[str] = []
    in_section = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == RELATED:
            in_section = True
            continue
        if not in_section:
            continue
        if not stripped.startswith("- "):
            break
        titles.append(stripped[2:].split("|")[0].strip())
    return titles

OPENING = {
    "chat": "思路：先把题目要求拆成「输入、约束、要返回什么」三步。",
    "diagnose": "诊断：先定位你的错误发生在哪一类边界上，再改代码。",
    "plan": "计划：先用最小用例跑通，再补边界，最后看复杂度。",
    "explain": "解释：这个解法之所以成立，是因为每一步都在缩小候选范围。",
}


def _first_after(text: str, marker: str) -> str | None:
    for line in text.splitlines():
        line = line.strip()
        if line.startswith(marker):
            value = line[len(marker) :].strip()
            return value or None
    return None


def render(messages: list[Message], level: int, mode: str) -> str:
    prompt = "\n".join(message.content for message in messages)
    weak_tag = _first_after(prompt, WEAK_TAG)
    skill = _first_after(prompt, SKILL)
    related = _related_titles(prompt)

    lines = [OPENING[mode]]
    if weak_tag:
        lines.append(f"你最近在「{weak_tag}」上反复出错，这道题先检查这一处。")
    if skill:
        lines.append(f"可以参考这条经验：{skill}。它来自同类题的总结，不一定完全适用。")
    if related:
        lines.append(f"同类题也可以练：{related[0]}。")
    # The per-level text lives in levels.py so the prompt rule, this sample and
    # the guard cannot drift apart.
    lines.extend(levels.sample(level))
    lines.append("想清楚后自己写一遍，再看下一个提示。")
    return "\n".join(lines)


class FakeProvider:
    name = "fake"
    model_id = "fake-v1"

    def __init__(self, delay_ms: int = 0) -> None:
        # 0 in production. The browser test sets it so 停止 has something to stop.
        self._delay = max(0, delay_ms) / 1000.0

    def stream(self, messages: list[Message], **opts: object) -> Iterator[str]:
        level = int(opts.get("level", 1))
        mode = str(opts.get("mode", "chat"))
        state = opts.get("state")
        text = render(messages, level, mode)
        step = 48
        for start in range(0, len(text), step):
            if state is not None:
                state.chunks += 1
            if self._delay:
                time.sleep(self._delay)
            yield text[start : start + step]
        # Contract 0006 §3: finishReason comes from the provider, not the caller.
        if state is not None:
            state.finish_reason = "stop"
