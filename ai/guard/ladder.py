"""Hint-ladder rules as pure functions.

The rules are deliberately mechanical: the same text and level must always
produce the same verdict, on any machine, with no model in the loop. That is
what makes "0 ladder violations" an assertion instead of an opinion.

`level` semantics live in levels.py; this module only owns the predicates.
"""

from __future__ import annotations

import re

FENCE = "```"

# A "complete function" is a signature plus a return plus a loop or branch.
_SIGNATURE = re.compile(r"\b(?:public|private|protected|static|def|function)\b[^\n]*\(")
_RETURN = re.compile(r"\breturn\b")
_CONTROL = re.compile(r"\b(?:for|while|if|switch|foreach)\b")

SAFE_TAIL = "这个层级先不展开代码。你按上面的思路写一版，卡住了再要下一层提示。"


def has_code_fence(text: str) -> bool:
    return FENCE in text


def looks_like_complete_function(text: str) -> bool:
    if not _SIGNATURE.search(text):
        return False
    if not _RETURN.search(text):
        return False
    if not _CONTROL.search(text):
        return False
    # A closing brace after the body is what makes it submittable.
    return text.count("{") >= 2 and text.count("}") >= 2


def is_submittable_solution(text: str) -> bool:
    """Level 3 may show fragments, but never a whole function plus its body."""
    if not looks_like_complete_function(text):
        return False
    # A fragment usually lacks the enclosing class or the method's opening line;
    # require both a signature and a balanced body to call it submittable.
    return text.count("{") >= 3


def _level1_violation(text: str) -> str | None:
    return "level1_code_fence" if has_code_fence(text) else None


def _level2_violation(text: str) -> str | None:
    return "level2_complete_function" if looks_like_complete_function(text) else None


def _level3_violation(text: str) -> str | None:
    return "level3_full_solution" if is_submittable_solution(text) else None


# One row per level: adding a level is one edit, and every level must have a rule.
PREDICATES = {
    1: _level1_violation,
    2: _level2_violation,
    3: _level3_violation,
}


def classify(text: str, level: int) -> tuple[bool, str | None]:
    """Return (violated, reason). Reason is None when the text is compliant."""
    predicate = PREDICATES.get(level)
    if predicate is None:
        raise ValueError(f"unknown level: {level}")
    reason = predicate(text)
    return (reason is not None), reason
