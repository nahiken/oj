"""Build the P5 evaluation corpus fixtures from public sources.

    python benchmark/fetch_p5_corpus.py

Outputs (committed, deterministic):
    benchmark/fixtures/p5-corpus.en.json   500 English problems, apache-2.0
    benchmark/fixtures/p5-corpus.zh.json   20 hand-written Chinese problems

Sources (downloaded once into .runtime/tmp/):
    walk-alone/codeforces-problems  problems.csv  (apache-2.0 statements)
    codeforces.com API problemset.problems       (rating, used for difficulty)

The sample is fixed: rows are sorted, then `random.Random(SEED).sample` picks 500.
Re-running produces byte-identical output as long as the cached sources do not
change. `--refresh` re-downloads the sources.
"""
import argparse
import csv
import json
import os
import random
import re
import sys
import urllib.request

SEED = 20261020
SAMPLE = 500
FIRST_ID = 910001

CSV_URL = "https://hf-mirror.com/datasets/walk-alone/codeforces-problems/resolve/main/problems.csv"
API_URL = "https://codeforces.com/api/problemset.problems"

CSV_PATH = os.path.join(".runtime", "tmp", "cf-problems.csv")
API_PATH = os.path.join(".runtime", "tmp", "cf-api.json")
OUT_EN = os.path.join("benchmark", "fixtures", "p5-corpus.en.json")
OUT_ZH = os.path.join("benchmark", "fixtures", "p5-corpus.zh.json")

TITLE_RE = re.compile(r"^([A-Z]\d{0,2}\.)\s*(.+?)\s*time limit per test", re.S)


def download(url, path, refresh):
    if os.path.exists(path) and not refresh:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    print("downloading %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": "oj-p5/1.0"})
    with urllib.request.urlopen(req, timeout=600) as resp, open(path, "wb") as fh:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)


def parse_tags(raw):
    if not raw:
        return []
    try:
        value = json.loads(raw.replace("'", '"'))
        if isinstance(value, list):
            return [str(v).strip() for v in value if str(v).strip()]
    except ValueError:
        pass
    return [t.strip() for t in re.findall(r"'([^']+)'", raw)]


def difficulty_of(rating):
    if rating is None:
        return "MEDIUM"
    if rating < 1200:
        return "EASY"
    if rating < 1900:
        return "MEDIUM"
    return "HARD"


def build_en(refresh):
    download(CSV_URL, CSV_PATH, refresh)
    download(API_URL, API_PATH, refresh)

    with open(API_PATH, encoding="utf-8") as fh:
        api = json.load(fh)
    ratings = {}
    for problem in api["result"]["problems"]:
        name = (problem.get("name") or "").strip()
        if name and problem.get("rating"):
            ratings.setdefault(name, problem["rating"])

    csv.field_size_limit(10 ** 9)
    rows = []
    with open(CSV_PATH, encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            match = TITLE_RE.match(row["problem_statement"] or "")
            if not match:
                continue
            title = match.group(2).strip()
            if title not in ratings:
                continue
            rows.append(
                {
                    "title": title,
                    "content": (row["problem_statement"] or "").strip(),
                    "tags": parse_tags(row.get("tags")),
                    "difficulty": difficulty_of(ratings[title]),
                    "lang": "en",
                    "source": "imported",
                }
            )

    # Deterministic order, then a fixed seed sample.
    rows.sort(key=lambda r: (r["title"], len(r["content"])))
    picked = random.Random(SEED).sample(rows, SAMPLE)
    picked.sort(key=lambda r: (r["title"], len(r["content"])))
    for index, item in enumerate(picked):
        item["id"] = FIRST_ID + index

    os.makedirs(os.path.dirname(OUT_EN), exist_ok=True)
    with open(OUT_EN, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(picked, fh, ensure_ascii=False, indent=1, sort_keys=True)
        fh.write("\n")

    sizes = [len(r["content"]) for r in picked]
    print(
        "en: candidates=%d sampled=%d body median=%d p90=%d"
        % (len(rows), len(picked), sorted(sizes)[len(sizes) // 2], sorted(sizes)[int(len(sizes) * 0.9)])
    )
    return picked


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", action="store_true", help="re-download the cached sources")
    args = parser.parse_args()

    build_en(args.refresh)
    if not os.path.exists(OUT_ZH):
        print("zh: %s missing - the 20 hand-written problems must exist first" % OUT_ZH)
        return 1
    with open(OUT_ZH, encoding="utf-8") as fh:
        zh = json.load(fh)
    print("zh: %d problems" % len(zh))
    return 0


if __name__ == "__main__":
    sys.exit(main())
