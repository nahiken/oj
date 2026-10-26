"""Token estimation and budget trimming.

There is no tokenizer on this machine, so the count is a documented heuristic
(same one P4 uses) and every consumer must label it estimated.

Trimming order is part of the contract (0006 §6): oldest Episode first, then the
least confident Pattern, then the least relevant Skill; the problem itself is
never dropped. Costs are estimated from the *actual* text, not a flat per-item
constant, so a long Episode really does push the next one out.
"""

from __future__ import annotations

# System prompt + ladder rule + the user's own message.
BASE_COST = 220
EPISODE_OVERHEAD = 12
PATTERN_OVERHEAD = 8
SKILL_OVERHEAD = 10
RELATED_OVERHEAD = 12

CONTRACT_BUDGET = 1200


def estimate_chars(chars: int) -> int:
    """The one place the 2.5 chars/token heuristic lives."""
    return max(1, int(chars / 2.5))


def estimate(text: str | None) -> int:
    if not text:
        return 0
    return estimate_chars(len(text))


def _episode_cost(episode: dict) -> int:
    return EPISODE_OVERHEAD + estimate(str(episode.get("summary") or ""))


def _pattern_cost(pattern: dict) -> int:
    return PATTERN_OVERHEAD + estimate(str(pattern.get("description") or ""))


def _skill_cost(skill: dict) -> int:
    return SKILL_OVERHEAD + estimate(str(skill.get("name") or "")) + estimate(
        str(skill.get("content") or "")
    )


def _related_cost(problem: dict) -> int:
    tags = ", ".join(problem.get("tags") or [])
    return RELATED_OVERHEAD + estimate(str(problem.get("title") or "")) + estimate(tags)


def context_cost(context: dict) -> int:
    """Estimated cost of the Context block only (not the whole prompt)."""
    problem = context.get("problem") or {}
    total = 40
    total += estimate(str(problem.get("title") or ""))
    total += estimate(str(problem.get("difficulty") or ""))
    for tag in problem.get("tags") or []:
        total += estimate(str(tag)) + 2
    for episode in context.get("episodes") or []:
        total += _episode_cost(episode)
    for pattern in context.get("patterns") or []:
        total += _pattern_cost(pattern)
    for skill in context.get("skills") or []:
        total += _skill_cost(skill)
    for related in context.get("relatedProblems") or []:
        total += _related_cost(related)
    return total


def trim(context: dict, budget: int = CONTRACT_BUDGET) -> tuple[dict, dict]:
    """Return (trimmed context, meta) without mutating the input."""
    episodes = list(context.get("episodes") or [])
    patterns = sorted(
        context.get("patterns") or [],
        key=lambda item: float(item.get("confidence") or 0.0),
        reverse=True,
    )
    skills = list(context.get("skills") or [])
    related = list(context.get("relatedProblems") or [])

    episodes_dropped = 0
    patterns_dropped = 0
    skills_dropped = 0
    related_dropped = 0

    def cost() -> int:
        return context_cost(
            {
                "problem": context.get("problem"),
                "episodes": episodes,
                "patterns": patterns,
                "skills": skills,
                "relatedProblems": related,
            }
        )

    while episodes and cost() > budget:
        episodes.pop(0)
        episodes_dropped += 1
    while patterns and cost() > budget:
        patterns.pop()
        patterns_dropped += 1
    while skills and cost() > budget:
        # Skills arrive best-match-first; the tail is the least relevant.
        skills.pop()
        skills_dropped += 1
    # [相关题] is all-or-nothing: half a fixed-size section would make both the
    # "fixed count" promise and searchUsed's meaning untrue.
    if related and cost() > budget:
        related_dropped = len(related)
        related = []

    trimmed = dict(context)
    trimmed["episodes"] = episodes
    trimmed["patterns"] = patterns
    trimmed["skills"] = skills
    trimmed["relatedProblems"] = related
    return trimmed, {
        "contextTokens": cost(),
        "episodesUsed": len(episodes),
        "patternsUsed": len(patterns),
        "skillsDropped": skills_dropped,
        "episodesDropped": episodes_dropped,
        "patternsDropped": patterns_dropped,
        "relatedDropped": related_dropped,
        "budget": budget,
        "estimated": True,
    }
