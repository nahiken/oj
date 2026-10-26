"""Behavioural tests for the deterministic provider."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from provider.base import Message  # noqa: E402
from provider.fake import FakeProvider  # noqa: E402


def render(level: int, extra: str = "", mode: str = "chat") -> str:
    provider = FakeProvider()
    messages = [
        Message("system", "level hard constraints"),
        Message("user", f"题目 1：two sum\n{extra}"),
    ]
    return "".join(provider.stream(messages, level=level, mode=mode))


def test_same_input_same_bytes():
    assert render(1) == render(1)


def test_level_one_has_no_code_fence():
    assert "```" not in render(1)


def test_level_two_has_pseudocode_only():
    text = render(2)
    assert "```text" in text
    assert "return" not in text.split("```text")[0]


def test_level_three_has_partial_code():
    text = render(3)
    assert "```java" in text
    # A partial snippet must not be a complete, submittable method.
    assert "public " not in text


def test_context_markers_change_the_answer():
    plain = render(1)
    tagged = render(1, "WEAK_TAG: dynamic programming\nSKILL: draw the state table")
    assert tagged != plain
    assert "dynamic programming" in tagged
    assert "draw the state table" in tagged


def test_related_section_changes_the_answer():
    plain = render(1)
    tagged = render(1, "[相关题]\n- 三数之和 | 难度 中等 | 标签 数组\n[阶梯] level=1")
    assert tagged != plain
    assert "三数之和" in tagged


def test_mode_changes_the_opening():
    assert render(1, mode="diagnose") != render(1, mode="chat")
