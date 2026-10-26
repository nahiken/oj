"""Skill 检索评估：20 条人工 query → Hit@10 / MRR + 可见性断言（票 04）。

用法（由 `run-p5.ps1 -Scenario skill-eval` 调用）：token 与密码都从环境变量读，
不写命令行——argv 对同机进程可见（docs/SECURITY.md）：

    set OJ_ADMIN_TOKEN=<run-p5.ps1 签好的 token>
    set OJ_MYSQL_PASSWORD=<本机库密码>
    python benchmark/skill_eval.py --base-url http://127.0.0.1:8012/judge

它做四件事，顺序不能换：
1. 把语料**幂等**写进 MySQL（平台 / 个人 / 待审核三种状态）；
2. 调全量重建，让索引与刚写的行一致；
3. 逐条 query 打 `GET /search/skill?q=`，算 Hit@10 与 MRR；
4. 断言可见性：自己的个人 Skill 搜得到，别人的个人 Skill 与 DRAFT 搜不到。

**结论边界**：n=20，语料 45 条，随机基线约 10/42≈0.24。这份报告只证明
「管道能出可算的指标、可见性规则成立」，不证明检索质量达到某个水平。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

import httpx

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CORPUS = REPO_ROOT / "benchmark/fixtures/p8-skill-corpus.json"
DEFAULT_QUERIES = REPO_ROOT / "benchmark/fixtures/p8-skill-queries.json"


def sql_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def insert_row(skill_id: int, name: str, scope: str, owner: int | None, status: str,
               content: str, tags: list[str]) -> str:
    owner_sql = "NULL" if owner is None else str(owner)
    tags_sql = json.dumps(tags, ensure_ascii=False)
    return (
        "INSERT INTO learning_skill (id, name, scope, owner_uid, status, content, steps_json, tags, "
        "source_episode_id, model, prompt_version, reject_reason, usage_count, success_count, version, "
        "created_at, updated_at) VALUES ("
        f"{skill_id}, {sql_quote(name)}, {sql_quote(scope)}, {owner_sql}, {sql_quote(status)}, "
        f"{sql_quote(content)}, NULL, {sql_quote(tags_sql)}, NULL, NULL, NULL, NULL, 0, 0, 1, "
        "NOW(3), NOW(3)) ON DUPLICATE KEY UPDATE name = VALUES(name), scope = VALUES(scope), "
        "owner_uid = VALUES(owner_uid), status = VALUES(status), content = VALUES(content), "
        "tags = VALUES(tags), updated_at = NOW(3)"
    )


def seed(corpus: dict, mysql_password: str) -> int:
    rows: list[str] = []
    for skill in corpus.get("platform", []):
        rows.append(insert_row(skill["id"], skill["name"], "PLATFORM", None, "ACTIVE",
                               skill["content"], skill.get("tags") or []))
    for skill in corpus.get("personal", []):
        rows.append(insert_row(skill["id"], skill["name"], "PERSONAL", skill["ownerUid"], "ACTIVE",
                               skill["content"], skill.get("tags") or []))
    for skill in corpus.get("draft", []):
        rows.append(insert_row(skill["id"], skill["name"], "PLATFORM", None, "DRAFT",
                               skill["content"], skill.get("tags") or []))
    env = dict(os.environ, MYSQL_PWD=mysql_password)
    # Reset promoted copies first: a previous `-Scenario skill-promote` leaves a
    # PLATFORM row behind, and it would be counted as a 45th document, making the
    # baseline and the doc count depend on scenario order.
    personal_ids = ", ".join(str(skill["id"]) for skill in corpus.get("personal", [])) or "0"
    sql = (
        f"DELETE FROM learning_skill WHERE origin_skill_id IN ({personal_ids});\n"
        + ";\n".join(rows) + ";"
    )
    result = subprocess.run(
        ["mysql", "--host=127.0.0.1", "--user=root", "--database=oj", "--batch"],
        input=sql, text=True, capture_output=True, env=env,
    )
    if result.returncode != 0:
        raise SystemExit(f"seed failed: {result.stderr.strip()}")
    return len(rows)


def reindex(client: httpx.Client, base_url: str, token: str) -> dict:
    response = client.post(f"{base_url}/search/skill/admin/reindex", headers={"Cookie": f"token={token}"})
    if response.status_code != 200:
        raise SystemExit(f"reindex failed: HTTP {response.status_code} {response.text[:300]}")
    return response.json()["data"]


def search(client: httpx.Client, base_url: str, token: str, query: str, top_k: int) -> list[int]:
    response = client.get(
        f"{base_url}/search/skill",
        params={"q": query, "topK": top_k},
        headers={"Cookie": f"token={token}"},
    )
    if response.status_code != 200:
        raise SystemExit(f"search failed: HTTP {response.status_code} {response.text[:300]}")
    return [int(hit["skillId"]) for hit in response.json()["data"]["hits"]]


def first_rank(hits: list[int], relevant: list[int]) -> int | None:
    for rank, skill_id in enumerate(hits, start=1):
        if skill_id in relevant:
            return rank
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8012/judge")
    # Token and password come from the environment: argv is visible to every
    # process on the box (docs/SECURITY.md), and run-p5.ps1 already exports both.
    parser.add_argument("--token", default=os.environ.get("OJ_ADMIN_TOKEN", ""))
    parser.add_argument("--mysql-password", default=os.environ.get("OJ_MYSQL_PASSWORD", ""))
    parser.add_argument("--corpus", default=str(DEFAULT_CORPUS))
    parser.add_argument("--queries", default=str(DEFAULT_QUERIES))
    parser.add_argument("--out", default="benchmark/results/skill-eval.json")
    parser.add_argument("--topk", type=int, default=10)
    parser.add_argument("--uid", type=int, default=1900000000000000001,
                        help="the uid the token belongs to; decides what is visible")
    args = parser.parse_args()
    if not args.token:
        raise SystemExit("OJ_ADMIN_TOKEN is not set (do not pass the token on the command line)")
    if not args.mysql_password:
        raise SystemExit("OJ_MYSQL_PASSWORD is not set (do not pass the password on the command line)")

    corpus = json.loads(Path(args.corpus).read_text(encoding="utf-8"))
    fixture = json.loads(Path(args.queries).read_text(encoding="utf-8"))
    seeded = seed(corpus, args.mysql_password)

    with httpx.Client(timeout=30.0) as client:
        rebuild = reindex(client, args.base_url, args.token)
        rows = []
        for case in fixture["queries"]:
            hits = search(client, args.base_url, args.token, case["query"], args.topk)
            rank = first_rank(hits, case["relevant"])
            rows.append({
                "id": case["id"],
                "query": case["query"],
                "relevant": case["relevant"],
                "hits": hits,
                "rank": rank,
            })

        visibility = []
        for case in fixture.get("visibility", []):
            hits = search(client, args.base_url, args.token, case["query"], args.topk)
            entry = {"id": case["id"], "query": case["query"], "hits": hits, "why": case.get("why")}
            if case.get("expectVisible"):
                entry["ok"] = all(skill_id in hits for skill_id in case["relevant"])
            else:
                entry["ok"] = not any(skill_id in hits for skill_id in case.get("forbidden", []))
            visibility.append(entry)

    hit = sum(1 for row in rows if row["rank"] is not None)
    mrr = sum(1.0 / row["rank"] for row in rows if row["rank"] is not None) / len(rows)
    # What *this* uid can see: every ACTIVE row minus other people's personal ones.
    # Naming it visibleDocuments matters — "44 docs" would overstate the candidate
    # pool and make the random baseline look better than it is.
    active = rebuild["documents"]
    foreign_personal = sum(1 for skill in corpus.get("personal", []) if skill["ownerUid"] != args.uid)
    visible = max(1, active - foreign_personal)
    summary = {
        "queries": len(rows),
        "topk": args.topk,
        "hitRateAt10": round(hit / len(rows), 3),
        "mrr": round(mrr, 3),
        "indexed": rebuild["indexed"],
        "indexedFailed": rebuild["failed"],
        "activeDocuments": active,
        "visibleDocuments": visible,
        "randomBaseline": round(min(1.0, args.topk / visible), 3),
        "visibilityOk": all(entry["ok"] for entry in visibility),
        "note": "n=20，仅证明管道与可见性；随机基线见 randomBaseline",
    }

    out_path = REPO_ROOT / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps({
        "summary": summary,
        "seededRows": seeded,
        "rebuild": rebuild,
        "runs": rows,
        "visibility": visibility,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print("| id | query | 首个相关命中名次 | 命中 ids（前 3） |")
    print("| --- | --- | --- | --- |")
    for row in rows:
        rank = row["rank"] if row["rank"] is not None else "—"
        print(f"| {row['id']} | {row['query']} | {rank} | {row['hits'][:3]} |")
    for entry in visibility:
        mark = "ok" if entry["ok"] else "FAIL"
        print(f"visibility {mark}: {entry['id']} {entry['query']} -> {entry['hits'][:3]} ({entry['why']})")
    print(f"indexed={rebuild['indexed']} seededRows={seeded} topk={args.topk}")
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["visibilityOk"] else 1


if __name__ == "__main__":
    sys.exit(main())
