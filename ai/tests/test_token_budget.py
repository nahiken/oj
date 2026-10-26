"""Budget is enforced on real text, not on a flat per-item constant."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import token_budget  # noqa: E402

PROBLEM = {"title": "两数之和", "difficulty": "简单", "tags": ["数组"]}


def context(episodes: int = 0, patterns: int = 0, skills: int = 0, summary_len: int = 100):
    return {
        "problem": PROBLEM,
        "episodes": [
            {"startedAt": "2026-10-20 10:00", "outcome": "WA", "attempts": 2,
             "summary": "x" * summary_len}
            for _ in range(episodes)
        ],
        "patterns": [{"type": "WEAK_TAG", "description": "y" * 50, "confidence": 0.5}
                     for _ in range(patterns)],
        "skills": [{"id": i, "name": "经验", "content": "z" * 100, "tags": ["数组"]}
                   for i in range(skills)],
    }


def test_context_cost_tracks_actual_text():
    small = token_budget.context_cost(context(episodes=1, summary_len=10))
    large = token_budget.context_cost(context(episodes=1, summary_len=2000))
    assert large > small


def test_base_cost_fits_a_small_context():
    trimmed, meta = token_budget.trim(context(episodes=1, patterns=1, skills=1))
    assert meta["estimated"] is True
    assert meta["contextTokens"] <= 1200
    assert len(trimmed["episodes"]) == 1
    assert len(trimmed["skills"]) == 1


def test_a_huge_episode_is_dropped_oldest_first():
    ctx = context(episodes=3, summary_len=1500)
    trimmed, meta = token_budget.trim(ctx)
    assert meta["episodesDropped"] >= 1
    # The newest episode survives; the oldest ones go.
    assert trimmed["episodes"] == ctx["episodes"][-len(trimmed["episodes"]):]


def test_skills_are_trimmed_from_the_tail():
    ctx = context(skills=4)
    for skill in ctx["skills"]:
        skill["content"] = "z" * 800
    trimmed, meta = token_budget.trim(ctx)
    assert meta["skillsDropped"] == 1
    assert [skill["id"] for skill in trimmed["skills"]] == [0, 1, 2]


def test_meta_reports_the_trimmed_cost():
    _, meta = token_budget.trim(context(episodes=2))
    assert meta["contextTokens"] <= meta["budget"]
    assert meta["episodesUsed"] + meta["episodesDropped"] == 2


def test_estimate_chars_matches_the_single_heuristic():
    assert token_budget.estimate_chars(250) == 100
    assert token_budget.estimate("a" * 250) == 100
    assert token_budget.estimate("") == 0
