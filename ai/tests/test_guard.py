"""Pure-function tests for the guard rails."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from guard import fingerprint, ladder  # noqa: E402

COMPLETE = """public class Main {
    public static void main(String[] args) {
        int sum = 0;
        for (int i = 0; i < 3; i++) {
            sum += i;
        }
        return;
    }
}
"""

FRAGMENT = """if (nums == null || nums.length == 0) {
    return 0;
}
"""

PSEUDO = """```text
for each item in input:
    update the answer
return answer
```"""


def test_level_one_rejects_a_fence():
    assert ladder.classify("text\n```java\nint a = 1;\n```", 1)[0] is True


def test_level_one_accepts_prose():
    assert ladder.classify("先想边界，再想复杂度。", 1) == (False, None)


def test_level_two_rejects_a_complete_function():
    assert ladder.classify(COMPLETE, 2) == (True, "level2_complete_function")


def test_level_two_accepts_pseudocode():
    assert ladder.classify(PSEUDO, 2)[0] is False


def test_level_three_accepts_a_fragment():
    assert ladder.classify(FRAGMENT, 3)[0] is False


def test_level_three_rejects_a_full_solution():
    assert ladder.classify(COMPLETE, 3) == (True, "level3_full_solution")


def test_fingerprint_splits_into_eight_grams():
    grams = fingerprint.ngrams("abcdefghij")
    assert grams == ["abcdefgh", "bcdefghi", "cdefghij"]


def test_short_answer_has_no_fingerprint():
    assert fingerprint.fingerprint("short") == []


def test_leak_is_detected_only_on_a_real_hit():
    hashes = set(fingerprint.fingerprint("Read two integers and print their sum."))
    assert fingerprint.leaks("the answer is: Read two integers and print their sum.", hashes)
    assert not fingerprint.leaks("think about the boundary first", hashes)


def test_empty_hash_set_never_leaks():
    assert fingerprint.leaks("anything", set()) is False
