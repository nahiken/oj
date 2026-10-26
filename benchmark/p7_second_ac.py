"""Compute 二次 AC 率 for a uid range (P7 ticket 11).

The metric is defined in GLOSSARY.md and implemented once, in SQL:
`benchmark/sql/p7-second-ac-rate.sql`. This script only parameterises it and
prints a report that always carries numerator, denominator, sample size and the
driving mode -- a rate without those is not a measurement.

    D:\\python\\python.exe benchmark/p7_second_ac.py --uid-from 9900000101 --uid-to 9900000112 \
        --driver single-person --out benchmark/results/p7-second-ac.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from runtime_config import run_scalar, run_sql  # noqa: E402

SQL_FILE = ROOT / "benchmark/sql/p7-second-ac-rate.sql"


def run_rows(sql: str) -> list[list[str]]:
    rows = []
    for line in run_sql(sql).splitlines():
        if not line.strip():
            continue
        rows.append(line.split("\t"))
    return rows


def build_sql(uid_from: int, uid_to: int, window_seconds: int) -> tuple[str, str]:
    text = SQL_FILE.read_text(encoding="utf-8")
    aggregate, _, detail = text.partition("-- 2) 逐会话明细")
    if not detail:
        raise SystemExit("p7-second-ac-rate.sql is missing the detail section marker")
    # subn with an assertion: if someone edits the SET lines the bounds would
    # otherwise silently stay at the SQL file's defaults and the report would be
    # about the wrong uid range.
    head, n1 = re.subn(r"SET @uid_from = \d+;", f"SET @uid_from = {uid_from};", aggregate)
    head, n2 = re.subn(r"SET @uid_to = \d+;", f"SET @uid_to = {uid_to};", head)
    head, n3 = re.subn(r"SET @window_seconds = \d+;", f"SET @window_seconds = {window_seconds};", head)
    if (n1, n2, n3) != (1, 1, 1):
        raise SystemExit(f"could not parameterise the SQL (uid_from={n1} uid_to={n2} window={n3})")
    settings = "\n".join(line for line in head.splitlines() if line.startswith("SET "))
    return head, settings + "\n-- 2) 逐会话明细" + detail


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--uid-from", type=int, required=True)
    parser.add_argument("--uid-to", type=int, required=True)
    parser.add_argument("--window-seconds", type=int, default=1800)
    parser.add_argument(
        "--driver",
        choices=["single-person", "synthetic", "mixed"],
        help="required unless --preflight",
    )
    parser.add_argument("--out")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--no-detail", action="store_true")
    parser.add_argument("--preflight", action="store_true",
                        help="assert the uid range is empty and print the problem_submit baseline")
    args = parser.parse_args()

    if args.preflight:
        sessions = int(run_scalar(
            f"SELECT COUNT(*) FROM ai_tutoring_session WHERE uid BETWEEN {args.uid_from} AND {args.uid_to}"))
        submits = int(run_scalar(
            f"SELECT COUNT(*) FROM problem_submit WHERE user_id BETWEEN {args.uid_from} AND {args.uid_to}"))
        baseline = int(run_scalar("SELECT COUNT(*) FROM problem_submit"))
        print(f"range {args.uid_from}-{args.uid_to}: sessions={sessions} submits={submits}")
        print(f"problem_submit baseline: {baseline}")
        return 0 if sessions == 0 and submits == 0 else 1

    if not args.driver:
        parser.error("--driver is required unless --preflight")

    aggregate_sql, detail_sql = build_sql(args.uid_from, args.uid_to, args.window_seconds)
    rows = run_rows(aggregate_sql)
    if not rows or len(rows[0]) < 3:
        raise SystemExit(f"unexpected result: {rows!r}")
    denominator = int(rows[0][0])
    numerator = int(rows[0][1] or 0)
    rate = (numerator / denominator) if denominator else 0.0

    detail = [] if args.no_detail else run_rows(detail_sql)
    report = {
        "metric": "second-ac-rate",
        "definition": "sessions whose user already had a non-AC verdict on the same problem before the session; "
                      "numerator = same-problem AC within the window after the session ended",
        "acTruth": "problem_submit.judge_info->>'$.status' = '0'",
        "windowMinutes": args.window_seconds // 60,
        "uidFrom": args.uid_from,
        "uidTo": args.uid_to,
        "denominator": denominator,
        "numerator": numerator,
        "rate": round(rate, 4),
        "sampleSize": denominator,
        "driver": args.driver,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "valid": denominator > 0,
        "sessions": [
            {
                "uid": int(row[0]),
                "problemId": int(row[1]),
                "finishReason": row[2],
                "startedAt": row[3],
                "finishedAt": row[4],
                "priorNonAc": int(row[5]),
                "acWithinWindow": int(row[6]),
            }
            for row in detail
        ],
    }

    if denominator == 0:
        print("INVALID: denominator is 0 (no session had a prior non-AC submit)", file=sys.stderr)

    if args.out:
        path = pathlib.Path(args.out)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.json:
        print(json.dumps(report, ensure_ascii=False))
    else:
        print(f"denominator={denominator} numerator={numerator} rate={rate:.4f} driver={args.driver}")
        for session in report["sessions"]:
            print(
                f"  uid={session['uid']} problem={session['problemId']} "
                f"reason={session['finishReason']} priorNonAc={session['priorNonAc']} "
                f"acWithinWindow={session['acWithinWindow']}"
            )
    return 0 if denominator > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
