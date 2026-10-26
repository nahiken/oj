"""Skill 晋升端到端演示（票 05）：门槛 → 晋升 → 幂等 → 自批拒绝 → 立刻可搜。

它做的是脚本做不到的「跨用户」验证：一个**别人**的个人 Skill，在晋升前对另一个人
不可见，晋升成 PLATFORM 行之后同一秒就能被搜到。所有断言都落在 HTTP 与 DB 上。

用法（`run-p5.ps1 -Scenario skill-promote` 会导出这两个环境变量）：

    set OJ_JWT_SECRET=<本机密钥>
    set OJ_MYSQL_PASSWORD=<本机库密码>
    python benchmark/skill_promotion_demo.py

密钥与密码只从环境变量读：argv 对同机进程可见（docs/SECURITY.md）。
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import httpx
import jwt

BASE_URL = "http://127.0.0.1:8012/judge"
OWNER_UID = 1900000000000000001
OTHER_UID = 1810000000000000002
EXTRA_UID = 1810000000000000003
ADMIN_UID = 1900000000000000099
PERSONAL_SKILL = 9200000000000000001  # 「我的错题复盘三步」，owner = OWNER_UID
QUERY = "错题复盘 检查清单"


def redis_set(key: str, value: str, db: int = 12) -> None:
    """One RESP connection: SELECT db then SET, otherwise the key lands in db 0."""
    def encode(*parts: str) -> bytes:
        out = [f"*{len(parts)}\r\n".encode()]
        for part in parts:
            raw = part.encode()
            out.append(f"${len(raw)}\r\n".encode() + raw + b"\r\n")
        return b"".join(out)

    with socket.create_connection(("127.0.0.1", 6379), timeout=5) as sock:
        sock.sendall(encode("SELECT", str(db)) + encode("SET", key, value))
        time.sleep(0.1)
        reply = sock.recv(256)
    if b"+OK" not in reply:
        raise SystemExit(f"redis SET failed: {reply!r}")


def token_for(uid: int, secret: str, role: str = "admin") -> str:
    token = jwt.encode({"uid": uid, "userRole": role}, secret, algorithm="HS256")
    redis_set(f"user:token:{uid}", token)
    return token


def mysql(sql: str, password: str) -> str:
    env = dict(os.environ, MYSQL_PWD=password)
    result = subprocess.run(
        ["mysql", "--host=127.0.0.1", "--user=root", "--database=oj",
         "--batch", "--skip-column-names", "-e", sql],
        text=True, capture_output=True, env=env,
    )
    if result.returncode != 0:
        raise SystemExit(f"mysql failed: {result.stderr.strip()}")
    return result.stdout.strip()


def show(label: str, password: str) -> None:
    rows = mysql(
        "SELECT id, scope, IFNULL(owner_uid,'NULL'), IFNULL(origin_skill_id,'NULL'), status, "
        f"usage_count, success_count, name FROM learning_skill WHERE id = {PERSONAL_SKILL} "
        f"OR origin_skill_id = {PERSONAL_SKILL} ORDER BY id;",
        password,
    )
    print(f"-- {label}")
    print("id | scope | owner | origin | status | usage | success | name")
    for line in rows.splitlines():
        print(line.replace("\t", " | "))


def seed_evidence(password: str) -> None:
    """3 个不同 uid、12 次注入、8 次归因成功（成功率 0.667），全部落在 30 天窗口内。"""
    uids = [OWNER_UID, OTHER_UID, EXTRA_UID]
    values = []
    row_id = 9110000000000000000  # < BIGINT max (9.22e18); 9.4e18 would overflow
    rewarded = 0
    for user in uids:
        for slot in range(4):
            row_id += 1
            flag = 1 if rewarded < 8 else 0
            rewarded += 1
            values.append(f"({row_id}, {user}, 1, {PERSONAL_SKILL}, NOW(3) - INTERVAL {slot + 1} DAY, {flag})")
    mysql(
        f"DELETE FROM learning_skill_usage WHERE skill_id = {PERSONAL_SKILL};"
        "INSERT INTO learning_skill_usage (id, uid, problem_id, skill_id, used_at, rewarded) VALUES "
        + ", ".join(values)
        + ";"
        + f"UPDATE learning_skill SET usage_count = 12, success_count = 8, status = 'ACTIVE', "
          f"scope = 'PERSONAL', owner_uid = {OWNER_UID}, origin_skill_id = NULL WHERE id = {PERSONAL_SKILL};"
        + f"DELETE FROM learning_skill WHERE origin_skill_id = {PERSONAL_SKILL} AND scope = 'PLATFORM';",
        password,
    )


def search(token: str, query: str) -> list[int]:
    response = httpx.get(f"{BASE_URL}/search/skill", params={"q": query, "topK": 10},
                         headers={"Cookie": f"token={token}"}, timeout=20)
    response.raise_for_status()
    return [int(hit["skillId"]) for hit in response.json()["data"]["hits"]]


def main() -> int:
    parser = argparse.ArgumentParser()
    # Secrets from the environment, not argv: any process on the box can read a
    # command line, and this one mints admin tokens (docs/SECURITY.md).
    parser.add_argument("--jwt-secret", default=os.environ.get("OJ_JWT_SECRET", ""))
    parser.add_argument("--mysql-password", default=os.environ.get("OJ_MYSQL_PASSWORD", ""))
    parser.add_argument("--out", default="benchmark/results/skill-promotion.json")
    args = parser.parse_args()
    if not args.jwt_secret or not args.mysql_password:
        raise SystemExit("OJ_JWT_SECRET and OJ_MYSQL_PASSWORD must be set")

    admin = token_for(ADMIN_UID, args.jwt_secret)
    owner = token_for(OWNER_UID, args.jwt_secret)
    other = token_for(OTHER_UID, args.jwt_secret)

    seed_evidence(args.mysql_password)
    httpx.post(f"{BASE_URL}/search/skill/admin/reindex",
               headers={"Cookie": f"token={admin}"}, timeout=120).raise_for_status()
    show("晋升前：个人行在，公共池里没有它", args.mysql_password)

    before = search(other, QUERY)
    print(f"other uid 搜「{QUERY}」晋升前 -> {before}")

    candidates = httpx.get(f"{BASE_URL}/learning/skill/promotion/candidates",
                           headers={"Cookie": f"token={admin}"}, timeout=20).json()["data"]
    mine = [c for c in candidates if c["skill"]["id"] == PERSONAL_SKILL]
    print("candidates ->", json.dumps(mine[0], ensure_ascii=False) if mine else "empty")

    self_approval = httpx.post(f"{BASE_URL}/learning/skill/{PERSONAL_SKILL}/promote",
                               headers={"Cookie": f"token={owner}"}, timeout=20)
    print(f"self-approval -> HTTP {self_approval.status_code} {self_approval.text[:120]}")

    first = httpx.post(f"{BASE_URL}/learning/skill/{PERSONAL_SKILL}/promote",
                       headers={"Cookie": f"token={admin}"}, timeout=60)
    first.raise_for_status()
    promoted_id = first.json()["data"]["id"]
    second = httpx.post(f"{BASE_URL}/learning/skill/{PERSONAL_SKILL}/promote",
                        headers={"Cookie": f"token={admin}"}, timeout=60)
    second.raise_for_status()
    show("晋升后：两行都在，个人行不变", args.mysql_password)

    after = search(other, QUERY)
    print(f"other uid 搜「{QUERY}」晋升后 -> {after}")

    platform_rows = int(mysql(
        f"SELECT COUNT(*) FROM learning_skill WHERE origin_skill_id = {PERSONAL_SKILL} AND scope = 'PLATFORM';",
        args.mysql_password))
    personal_row = mysql(
        f"SELECT CONCAT(scope, '|', IFNULL(owner_uid,'NULL')) FROM learning_skill WHERE id = {PERSONAL_SKILL};",
        args.mysql_password)
    checks = {
        "platformRowCreated": platform_rows == 1,
        "personalRowUnchanged": personal_row == f"PERSONAL|{OWNER_UID}",
        "repeatedPromoteIsIdempotent": int(second.json()["data"]["id"]) == int(promoted_id),
        "authorCannotApproveOwnSkill": self_approval.status_code in (200,) and
        self_approval.json().get("code") == 403,
        "visibleToOtherUserOnlyAfterPromotion": PERSONAL_SKILL not in before and promoted_id in after,
        "promotedRowIndexed": int(promoted_id) in after,
        "candidateExplainsEvidence": bool(mine) and mine[0]["windowUsers"] >= 3 and mine[0]["eligible"] is True,
    }

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "personalSkill": PERSONAL_SKILL,
        "promotedSkill": promoted_id,
        "before": before,
        "after": after,
        "candidates": mine,
        "checks": checks,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps(checks, ensure_ascii=False))
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
