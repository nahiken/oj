"""Seed the browser-test account (P7 ticket 10).

The account is a normal row in `user` with a bcrypt password, so Playwright can
walk the real login form instead of injecting a token cookie. The account is
reused: the upsert only resets the password.

Test credentials, not secrets: they only exist in the local lab database.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import bcrypt

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from runtime_config import browser_password, run_scalar, run_sql  # noqa: E402
DEFAULT_UID = 9900000201
DEFAULT_EMAIL = "p7-playwright@example.invalid"
PASSWORD = browser_password()


def main() -> int:
    parser = argparse.ArgumentParser()
    # verify.ps1 passes a fresh uid per run: the AI's token bucket is in-process,
    # so reusing one account makes a rerun inside the same minute hit the limit
    # before the browser test has done anything.
    parser.add_argument("--uid", type=int, default=DEFAULT_UID)
    parser.add_argument("--email", default=DEFAULT_EMAIL)
    parser.add_argument("--cleanup", action="store_true", help="delete this uid's rows and exit")
    parser.add_argument("--count-sessions", action="store_true", help="print this uid's session rows and exit")
    args = parser.parse_args()
    uid, email = args.uid, args.email

    if args.count_sessions:
        print(run_scalar(f"SELECT COUNT(*) FROM ai_tutoring_session WHERE uid={uid}"))
        return 0
    if args.cleanup:
        run_sql(
            f"DELETE FROM ai_tutoring_session WHERE uid={uid}; "
            f"DELETE FROM user WHERE uid={uid};"
        )
        return 0

    password_hash = bcrypt.hashpw(PASSWORD.encode("utf-8"), bcrypt.gensalt()).decode("ascii")
    secret = "p7" + f"{uid:038d}"
    sql = (
        "INSERT INTO user (uid, username, email, password, user_role, secret_id, secret_key, sex) VALUES "
        f"({uid}, 'p7playwright{uid}', '{email}', '{password_hash}', 'user', '{secret}', '{secret}', 1) "
        "ON DUPLICATE KEY UPDATE password = VALUES(password), is_delete = 0;"
    )
    run_sql(sql)
    print(f"browser account ready: {email} (uid {uid})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
