"""Drive synthetic tutoring sessions through the real stack (P7 ticket 11).

Each session has the fixed shape from the spec: submit WA -> ask the tutor ->
submit AC within the window. Both submissions are real judge runs and the
tutoring call is a real `/api/ai/chat` through the gateway.

This is **synthetic** (script-driven). It validates the metric SQL and the
timestamps end to end; it is NOT the 单人自测 sample, and the report it feeds is
labelled `driver: synthetic`. The two samples must never be added together.

    D:\\python\\python.exe benchmark/p7_synthetic_sessions.py --count 12 \
        --uid-base 9900000701 --problem 1
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import time

import httpx

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from runtime_config import browser_password, run_scalar  # noqa: E402

GATEWAY = "http://localhost:8024"
EMAIL_DOMAIN = "example.invalid"
PASSWORD = browser_password()

WA_CODE = """import java.util.*;
public class Main {
  public static void main(String[] a) { Scanner s = new Scanner(System.in); System.out.println(0); }
}
"""

AC_CODE = """import java.util.*;
public class Main {
  public static void main(String[] a) { Scanner s = new Scanner(System.in); System.out.println(s.nextInt() + s.nextInt()); }
}
"""


def seed_user(uid: int) -> None:
    email = f"p7-syn-{uid}@{EMAIL_DOMAIN}"
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts/seed_p7_browser_user.py"),
         "--uid", str(uid), "--email", email],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"seed failed for {uid}: {result.stderr.strip()}")


def login(uid: int) -> httpx.Client:
    client = httpx.Client(base_url=GATEWAY, timeout=90.0)
    response = client.post(
        "/api/user/login",
        json={"email": f"p7-syn-{uid}@{EMAIL_DOMAIN}", "password": PASSWORD},
    )
    body = response.json()
    if body.get("code") != 200:
        raise SystemExit(f"login failed for {uid}: {response.status_code} {body}")
    # Python's cookie jar drops Domain=localhost (no dot), so carry the token as
    # an explicit header instead of relying on jar semantics.
    for name, value in response.headers.multi_items():
        if name.lower() == "set-cookie" and value.startswith("token="):
            client.headers["Cookie"] = value.split(";", 1)[0]
            break
    else:
        raise SystemExit(f"login for {uid} did not set a token cookie")
    return client


def submit_and_wait(client: httpx.Client, problem_id: int, code: str) -> dict:
    response = client.post(
        "/api/judge/submit",
        json={"code": code, "language": "Java", "problemId": problem_id},
    )
    body = response.json()
    if body.get("code") != 200:
        raise SystemExit(f"submit refused: {response.status_code} {body}")
    data = body.get("data") or {}
    submit_id = data.get("id") if isinstance(data, dict) else data
    if not submit_id:
        raise SystemExit(f"no submit id in {body}")

    started = time.time()
    while time.time() - started < 180:
        status = client.get(f"/api/judge/submit/{submit_id}/status").json().get("data") or {}
        if int(status.get("status", 0)) >= 2:
            return status
        time.sleep(0.5)
    raise SystemExit(f"submit {submit_id} never reached a terminal state")


def judge_status(status: dict) -> str:
    info = status.get("judgeInfo") or {}
    return str(info.get("status"))


def query_scalar(sql: str) -> int:
    return int(run_scalar(sql))


def session_count(uid_from: int, uid_to: int) -> int:
    return query_scalar(
        f"SELECT COUNT(*) FROM ai_tutoring_session WHERE uid BETWEEN {uid_from} AND {uid_to}"
    )


def total_submits() -> int:
    return query_scalar("SELECT COUNT(*) FROM problem_submit")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=12)
    parser.add_argument("--uid-base", type=int, default=9900000701)
    parser.add_argument("--problem", type=int, default=1)
    args = parser.parse_args()

    uid_from = args.uid_base
    uid_to = args.uid_base + args.count - 1
    # The spec's sample discipline: a fresh uid segment, asserted empty before and
    # after. Without this, a rerun silently doubles the denominator.
    before = session_count(uid_from, uid_to)
    if before != 0:
        raise SystemExit(
            f"uid range {uid_from}-{uid_to} already has {before} session rows; "
            "pick an unused --uid-base instead of rerunning the same one"
        )
    submits_before = total_submits()

    results = []
    for index in range(args.count):
        uid = args.uid_base + index
        seed_user(uid)
        client = login(uid)
        try:
            wa = submit_and_wait(client, args.problem, WA_CODE)
            wa_verdict = judge_status(wa)
            if wa_verdict == "0":
                raise SystemExit(f"uid {uid}: the WA submission was accepted; fix the fixture")

            chat = client.post(
                "/api/ai/chat",
                json={
                    "problemId": args.problem,
                    "level": 1,
                    "message": "I am stuck on the boundary case, what should I check?",
                    "history": [],
                    "sessionId": f"p7-syn-{uid}",
                },
            )
            streamed = "event: done" in chat.text

            ac = submit_and_wait(client, args.problem, AC_CODE)
            ac_verdict = judge_status(ac)
            results.append(
                {
                    "uid": uid,
                    "waVerdict": wa_verdict,
                    "acVerdict": ac_verdict,
                    "streamed": streamed,
                }
            )
            print(f"uid={uid} wa={wa_verdict} ac={ac_verdict} streamed={streamed}", flush=True)
        finally:
            client.close()

    bad = [r for r in results if r["acVerdict"] != "0" or not r["streamed"]]
    after = session_count(uid_from, uid_to)
    print(f"sessions={len(results)} rows={after} ac={len(results) - len(bad)} failed={len(bad)}")
    print(f"problem_submit rows: before={submits_before} after={total_submits()} (expect +{2 * args.count})")
    if after != args.count:
        print(f"EXPECTED {args.count} session rows for {uid_from}-{uid_to}, found {after}", file=sys.stderr)
        return 1
    return 0 if not bad else 1


if __name__ == "__main__":
    raise SystemExit(main())
