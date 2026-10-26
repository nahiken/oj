"""One place that knows how to talk to the local MySQL (P7 helper scripts).

The password lives in `.runtime/run/judge/application.yml`; three scripts used to
carry their own regex + subprocess copy of this. Import it instead.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def mysql_password() -> str:
    yml = (ROOT / ".runtime/run/judge/application.yml").read_text(encoding="utf-8", errors="replace")
    datasource = re.search(r"(?ms)datasource:(.*?)(?:\n\S|\Z)", yml).group(1)
    return re.search(r"password:\s*(\S+)", datasource).group(1).strip("\"'")


def browser_password() -> str:
    """The seeded-account password lives in one JSON file, not in four scripts."""
    data = json.loads((ROOT / "scripts/p7_browser_account.json").read_text(encoding="utf-8"))
    return str(data["password"])


def run_sql(sql: str) -> str:
    env = dict(os.environ)
    env["MYSQL_PWD"] = mysql_password()
    result = subprocess.run(
        [
            "mysql",
            "--host=127.0.0.1",
            "--user=root",
            "--database=oj",
            "--batch",
            "--skip-column-names",
            "-e",
            sql,
        ],
        env=env,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"mysql failed: {result.stderr.strip()}")
    return result.stdout


def run_scalar(sql: str) -> str:
    rows = [line for line in run_sql(sql).splitlines() if line.strip()]
    if not rows:
        raise SystemExit(f"query returned no rows: {sql}")
    return rows[0].split("\t")[0]


if __name__ == "__main__":
    # Small CLI so PowerShell can ask for a count without knowing the password.
    print(run_scalar(sys.argv[1]))
