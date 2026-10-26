"""Level semantics, in one place.

Three consumers used to switch on `level` independently: the prompt builder
(system rule), the deterministic Fake provider (sample text) and the guard
(predicate). They now all read this table, so adding level 4 means one edit.
"""

from __future__ import annotations

LEVELS = (1, 2, 3)

LEVEL_SPEC = {
    1: {
        "rule": "level=1：只给思路，禁止出现任何代码块（```）。",
        "sample": ["先写出暴力解法，再想哪一步重复计算了。"],
    },
    2: {
        "rule": "level=2：可以给伪代码，禁止给出完整函数体。",
        "sample": [
            "骨架如下，变量名和边界你自己补：",
            "```text",
            "for each item in input:",
            "    if item satisfies the boundary:",
            "        update the answer",
            "return answer",
            "```",
        ],
    },
    3: {
        "rule": "level=3：可以给局部代码片段，禁止给出可直接提交的完整解。",
        "sample": [
            "下面只是局部片段，循环条件和返回值要你自己想清楚：",
            "```java",
            "if (nums == null || nums.length == 0) {",
            "    return 0;",
            "}",
            "int best = nums[0];",
            "```",
        ],
    },
}


def rule(level: int) -> str:
    return LEVEL_SPEC[level]["rule"]


def sample(level: int) -> list[str]:
    return list(LEVEL_SPEC[level]["sample"])
