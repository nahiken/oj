"""Window-boundary tests for the 二次 AC 率 SQL (P7 ticket 11).

The metric is one SQL query; the rules that are easy to get wrong are all
boundary rules, so they get fixtures rather than prose:

* exactly 1800 s counts, 1801 s does not;
* same problem only;
* same uid only;
* a CE (judge status 1) is not an AC, even though the row's lifecycle `status`
  is 3 for every terminal submission;
* a session with no prior non-AC verdict is not in the denominator.

Run with the local lab up: `pytest benchmark/test_second_ac.py -q`.
"""

from __future__ import annotations

import pathlib
import sys

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "benchmark"))

from p7_second_ac import build_sql  # noqa: E402
from runtime_config import run_sql  # noqa: E402

PROBLEM = 1
OTHER_PROBLEM = 910001
UID_A = 9900000801          # AC at exactly +1800 s
UID_B = 9900000802          # AC at +1801 s
UID_C = 9900000803          # AC on another problem
UID_D = 9900000804          # AC by another user
UID_E = 9900000805          # CE only
UID_F = 9900000806          # no prior non-AC
UID_OTHER = 9900000899      # submits for UID_D's cross-user case
SUBMIT_BASE = 9910000001
SESSION_BASE = 9920000001

# Fixed timestamps, not NOW(3): the 1800 s boundary is exact only if both rows
# are computed from the same instant. With NOW(3) each INSERT adds a few ms and
# the "exactly 1800" case silently becomes 1800.004 and fails.
SESSION_START = "'2026-10-01 08:00:00.000'"
SESSION_FINISH = "'2026-10-01 09:30:00.000'"
PRIOR_WA = "'2026-10-01 07:00:00.000'"


def _session(session_id: int, uid: int, problem_id: int = PROBLEM) -> str:
    return (
        "INSERT INTO ai_tutoring_session "
        "(id, uid, problem_id, level, session_id, started_at, finished_at, finish_reason, "
        " prompt_tokens, completion_tokens, search_used) VALUES "
        f"({session_id}, {uid}, {problem_id}, 1, 'p7-boundary-{uid}', "
        f"{SESSION_START}, {SESSION_FINISH}, 'stop', 10, 5, 0);"
    )


def _submit(submit_id: int, uid: int, problem_id: int, verdict: int,
            after_seconds: int | None, before_minutes: int | None = None) -> str:
    """verdict: 0=AC, 1=CE (the value inside judge_info, not the row status)."""
    info = '{"status":' + str(verdict) + ',"msg":"fixture","pass":1,"total":1}'
    if before_minutes is not None:
        when = PRIOR_WA
    else:
        when = f"DATE_ADD({SESSION_FINISH}, INTERVAL {after_seconds} SECOND)"
    # lifecycle status is 3 (SUCCEED) for every terminal row on purpose: the
    # metric must read judge_info, not this column.
    return (
        "INSERT INTO problem_submit "
        "(id, language, code, judge_info, status, priority, problem_id, user_id, "
        " create_time, update_time, finished_at) VALUES "
        f"({submit_id}, 'Java', 'fixture', '{info}', 3, 'NORMAL', {problem_id}, {uid}, "
        f"{when}, {when}, {when});"
    )


@pytest.fixture(scope="module", autouse=True)
def fixtures():
    cleanup = (
        f"DELETE FROM ai_tutoring_session WHERE uid BETWEEN {UID_A} AND {UID_OTHER}; "
        f"DELETE FROM problem_submit WHERE id BETWEEN {SUBMIT_BASE} AND {SUBMIT_BASE + 99};"
    )
    run_sql(cleanup)
    rows = [
        # Prior non-AC (WA) before each session, so each is in the denominator.
        _submit(SUBMIT_BASE + 1, UID_A, PROBLEM, 4, None, before_minutes=180),
        _session(SESSION_BASE + 1, UID_A),
        _submit(SUBMIT_BASE + 2, UID_A, PROBLEM, 0, 1800),

        _submit(SUBMIT_BASE + 3, UID_B, PROBLEM, 4, None, before_minutes=180),
        _session(SESSION_BASE + 2, UID_B),
        _submit(SUBMIT_BASE + 4, UID_B, PROBLEM, 0, 1801),

        _submit(SUBMIT_BASE + 5, UID_C, PROBLEM, 4, None, before_minutes=180),
        _session(SESSION_BASE + 3, UID_C),
        _submit(SUBMIT_BASE + 6, UID_C, OTHER_PROBLEM, 0, 100),

        _submit(SUBMIT_BASE + 7, UID_D, PROBLEM, 4, None, before_minutes=180),
        _session(SESSION_BASE + 4, UID_D),
        _submit(SUBMIT_BASE + 8, UID_OTHER, PROBLEM, 0, 100),

        _submit(SUBMIT_BASE + 9, UID_E, PROBLEM, 4, None, before_minutes=180),
        _session(SESSION_BASE + 5, UID_E),
        _submit(SUBMIT_BASE + 10, UID_E, PROBLEM, 1, 100),

        # F: no prior non-AC -> not in the denominator at all.
        _session(SESSION_BASE + 6, UID_F),
        _submit(SUBMIT_BASE + 11, UID_F, PROBLEM, 0, 100),
    ]
    run_sql("".join(rows))
    try:
        yield
    finally:
        run_sql(cleanup)


def metric() -> tuple[int, int]:
    aggregate, _ = build_sql(UID_A, UID_OTHER, 1800)
    out = run_sql(aggregate).strip().split("\t")
    return int(out[0]), int(out[1] or 0)


def test_denominator_counts_only_sessions_with_a_prior_non_ac():
    denominator, _ = metric()
    # A, B, C, D, E; F has no prior non-AC.
    assert denominator == 5


def test_exactly_1800_seconds_counts():
    _, numerator = metric()
    assert numerator == 1


def test_1801_seconds_does_not_count():
    # If B were counted the numerator would be 2.
    _, numerator = metric()
    assert numerator == 1


def test_other_problem_does_not_count():
    aggregate, detail = build_sql(UID_A, UID_OTHER, 1800)
    rows = [line.split("\t") for line in run_sql(detail).splitlines() if line.strip()]
    by_uid = {int(row[0]): int(row[6]) for row in rows}
    assert by_uid[UID_C] == 0
    assert by_uid[UID_D] == 0
    assert by_uid[UID_E] == 0


def test_ce_is_not_an_ac_even_though_lifecycle_status_is_three():
    aggregate, detail = build_sql(UID_A, UID_OTHER, 1800)
    rows = [line.split("\t") for line in run_sql(detail).splitlines() if line.strip()]
    by_uid = {int(row[0]): int(row[6]) for row in rows}
    assert by_uid[UID_E] == 0
    # ... and the CE row really is terminal (status 3) in the lifecycle column.
    lifecycle = run_sql(
        f"SELECT status FROM problem_submit WHERE id={SUBMIT_BASE + 10}"
    ).strip()
    assert lifecycle == "3"
