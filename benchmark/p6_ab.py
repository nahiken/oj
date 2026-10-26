"""Synthetic-student A/B for P6 (spec D13) — through the real pipeline.

What it does: 8 problems x 3 personas x 2 arms = 48 sessions, each one a real
`POST /api/ai/chat` (gateway -> ai -> provider -> SSE). It records the
answer text, whether the answer names the persona's weakness, and which provider
answered.

What it does NOT do: no human students, no real second submission, no judge run.
The "second attempt" is judged by a rule ("did the hint name the weakness?"), so
every report carries `synthetic: true` and `judgedBy: "rule"`. That is weaker
than an efficacy study and is labelled as such; it is stronger than simulating
the arms, because a broken prompt/context/provider chain makes the numbers move.

Both arms use their own uid: arm A has no Episodes/Skills (empty Context), arm B
has a WEAK_TAG Pattern per persona (inserted by run-p6.ps1), so the only
difference between the arms is the Context the service assembles.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

PROBLEMS = 8
PERSONAS = ["OFF_BY_ONE", "TLE", "SYNTAX"]

# A hint names the persona's weakness if it contains one of these markers.
# Both languages are accepted: the tutor is instructed to answer in Chinese, so
# an English-only keyword rule scores a correct Chinese hint as a miss (measured
# on 2026-10-21: both arms 0.0 until this was fixed).
WEAKNESS = {
    "OFF_BY_ONE": ("boundary", "边界", "越界"),
    "TLE": ("complexity", "复杂度", "时间限制", "超时"),
    "SYNTAX": ("compile", "编译", "语法"),
}


def names_weakness(persona: str, answer: str) -> bool:
    lowered = answer.lower()
    return any(marker in lowered for marker in WEAKNESS[persona])


def chat(gateway: str, token: str, problem_id: int, message: str, timeout: float = 30.0) -> dict:
    """One tutoring session. Returns {answer, events, finishReason, provider}."""
    payload = json.dumps(
        {"problemId": problem_id, "mode": "chat", "level": 1, "message": message}
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{gateway.rstrip('/')}/api/ai/chat",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/json", "Cookie": f"token={token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"chat failed with HTTP {exc.code}: {exc.read()[:200]!r}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"gateway unreachable: {exc}") from exc

    answer = []
    events = []
    finish = None
    provider = None
    ttft_ms = None
    tokens = None
    for block in raw.split("\n\n"):
        event = None
        data = None
        for line in block.splitlines():
            if line.startswith("event:"):
                event = line[len("event:") :].strip()
            elif line.startswith("data:"):
                data = line[len("data:") :].strip()
        if not event:
            continue
        events.append(event)
        if not data:
            continue
        body = json.loads(data)
        if event == "delta":
            answer.append(str(body.get("text") or ""))
        elif event == "meta":
            provider = body.get("provider")
        elif event == "done":
            finish = body.get("finishReason")
            ttft_ms = body.get("ttftMs")
            tokens = body.get("tokens")
        elif event == "error":
            raise RuntimeError(f"stream error: {body}")
    return {
        "answer": "".join(answer),
        "events": events,
        "finishReason": finish,
        "provider": provider,
        "ttftMs": ttft_ms,
        "tokens": tokens,
    }


def run(gateway: str, tokens_a: list[str], tokens_b: list[str], problem_ids: list[int]) -> dict:
    if len(tokens_a) != len(PERSONAS) or len(tokens_b) != len(PERSONAS):
        raise SystemExit("need one token per persona per arm")
    if len(problem_ids) != PROBLEMS:
        raise SystemExit(f"need exactly {PROBLEMS} problem ids")

    sessions = {"A_no_context": [], "B_with_context": []}
    for arm, tokens in (("A_no_context", tokens_a), ("B_with_context", tokens_b)):
        for index, persona in enumerate(PERSONAS):
            for problem_id in problem_ids:
                result = chat(
                    gateway,
                    tokens[index],
                    problem_id,
                    f"persona={persona}; I am stuck on this problem, what should I check?",
                )
                named = names_weakness(persona, result["answer"])
                sessions[arm].append(
                    {
                        "problemId": problem_id,
                        "persona": persona,
                        "chars": len(result["answer"]),
                        "namedWeakness": named,
                        "provider": result["provider"],
                        "finishReason": result["finishReason"],
                        "ttftMs": result["ttftMs"],
                        "tokens": result["tokens"],
                        "syntheticPass": named,
                    }
                )

    def rate(rows: list[dict]) -> float:
        if not rows:
            return 0.0
        return round(sum(1 for row in rows if row["syntheticPass"]) / len(rows), 4)

    a, b = sessions["A_no_context"], sessions["B_with_context"]
    providers = sorted({row["provider"] for row in a + b if row["provider"]})
    # Cost/TTFT only mean something when a real model answered. The fake provider
    # reports no TTFT on purpose; writing 0 there would look like a measurement.
    # Any "fake" in the set means at least part of the run has no real TTFT or
    # token cost; mixing them would produce the merged table ticket 02 forbids.
    real = bool(providers) and all(provider != "fake" for provider in providers)
    ttft_values = sorted(
        row["ttftMs"] for row in a + b if isinstance(row["ttftMs"], int)
    )
    prompt_tokens = sum(row["tokens"]["prompt"] for row in a + b if row["tokens"])
    completion_tokens = sum(row["tokens"]["completion"] for row in a + b if row["tokens"])
    return {
        "synthetic": True,
        "judgedBy": "rule",
        "label": "synthetic (not a human A/B); second attempt judged by keyword rule",
        "judging": {
            "rule": "answer contains one marker for the persona's weakness",
            "markers": {persona: list(markers) for persona, markers in WEAKNESS.items()},
            "languages": ["en", "zh"],
        },
        "pipeline": "POST /api/ai/chat (SSE)",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "problems": len(problem_ids),
        "personas": PERSONAS,
        "sessionsPerArm": len(a),
        "calls": len(a) + len(b),
        "providers": providers,
        "realKeyProvider": real,
        "ttft": (
            {
                "samples": len(ttft_values),
                "avgMs": round(sum(ttft_values) / len(ttft_values), 1),
                "p50Ms": (
                    ttft_values[len(ttft_values) // 2]
                    if len(ttft_values) % 2
                    else round((ttft_values[len(ttft_values) // 2 - 1]
                                + ttft_values[len(ttft_values) // 2]) / 2, 1)
                ),
                "minMs": ttft_values[0],
                "maxMs": ttft_values[-1],
            }
            if real and ttft_values
            else None
        ),
        "tokens": (
            {
                "prompt": prompt_tokens,
                "completion": completion_tokens,
                "total": prompt_tokens + completion_tokens,
                "estimated": True,
            }
            if real
            else None
        ),
        "costBasis": "chars/2.5 heuristic, not provider billing" if real else None,
        "arms": {
            "A_no_context": {"sessions": len(a), "secondAttemptAcRate": rate(a)},
            "B_with_context": {"sessions": len(b), "secondAttemptAcRate": rate(b)},
        },
        "delta": round(rate(b) - rate(a), 4),
        "sample": {
            "A": {"chars": a[0]["chars"], "namedWeakness": a[0]["namedWeakness"]},
            "B": {"chars": b[0]["chars"], "namedWeakness": b[0]["namedWeakness"]},
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://127.0.0.1:8024")
    parser.add_argument("--tokens-a", required=True, help="comma-separated, one per persona")
    parser.add_argument("--tokens-b", required=True, help="comma-separated, one per persona")
    parser.add_argument("--problems", required=True, help="comma-separated problem ids")
    parser.add_argument("--out", help="write the report to this path")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args()

    try:
        report = run(
            args.gateway,
            [tok for tok in args.tokens_a.split(",") if tok],
            [tok for tok in args.tokens_b.split(",") if tok],
            [int(pid) for pid in args.problems.split(",") if pid],
        )
    except RuntimeError as exc:
        print(f"ab harness failed: {exc}", file=sys.stderr)
        return 1

    if args.out:
        path = Path(args.out)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.json:
        print(json.dumps(report, ensure_ascii=False))
    else:
        print(f"sessions per arm: {report['sessionsPerArm']}  (synthetic, judgedBy=rule)")
        for name, arm in report["arms"].items():
            print(f"  {name}: second-attempt AC {arm['secondAttemptAcRate']:.3f}")
        print(f"  delta: {report['delta']:+.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
