"""[相关题] is a data section fed by P5 search, and it degrades to nothing."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from prompt import builder  # noqa: E402
from token_budget import context_cost, trim  # noqa: E402

RELATED = [
    {"id": 2, "title": "三数之和", "difficulty": "中等", "tags": ["数组", "双指针"]},
    {"id": 3, "title": "两数相加", "difficulty": "中等", "tags": ["链表"]},
    {"id": 4, "title": "四数之和", "difficulty": "困难", "tags": ["数组"]},
]


def make_context(related: list | None = None) -> dict:
    context = {
        "problem": {"title": "两数之和", "difficulty": "简单", "tags": ["数组"]},
        "episodes": [],
        "patterns": [],
        "skills": [],
    }
    if related is not None:
        context["relatedProblems"] = related
    return context


def test_section_is_rendered_between_skills_and_the_ladder():
    _, text = builder.build(make_context(RELATED), [], "帮我看下", 1)
    assert "[相关题]" in text
    assert text.index("[经验]") < text.index("[相关题]") < text.index("[阶梯]")
    for item in RELATED:
        assert item["title"] in text


def test_section_stays_in_the_data_message_not_the_system_prompt():
    messages, _ = builder.build(make_context(RELATED), [], "帮我看下", 1)
    assert "[相关题]" not in messages[0].content
    data = [m for m in messages if m.content.startswith(builder.DATA_BANNER)]
    assert len(data) == 1
    assert "[相关题]" in data[0].content


def test_no_section_when_judge_returned_nothing():
    _, text = builder.build(make_context(), [], "帮我看下", 1)
    assert "[相关题]" not in text


def test_trim_keeps_the_section_when_budget_allows():
    trimmed, meta = trim(make_context(RELATED), budget=5000)
    assert trimmed["relatedProblems"] == RELATED
    assert meta["relatedDropped"] == 0


def test_trim_drops_the_section_as_a_whole_when_budget_is_tight():
    trimmed, meta = trim(make_context(RELATED), budget=60)
    assert trimmed["relatedProblems"] == []
    assert meta["relatedDropped"] == len(RELATED)


def test_cost_counts_the_section():
    without = context_cost(make_context())
    with_related = context_cost(make_context(RELATED))
    assert with_related > without
