"""Turn a raw P5 eval run into the metric table.

Input: JSON produced by `run-p5.ps1 -Scenario eval`:
    {"mode": "bm25", "runs": [
        {"id": "q001", "lang": "en", "band": "high", "problemId": 910003,
         "hits": [910003, ...], "relevant": [910003, ...], "latencyMs": 41}, ...]}

Output: JSON metrics on stdout (and, with --markdown, a markdown table).

Metric names follow docs/design-docs/0005-search.md §5.2:
    HitRate@k  -- single-label queries: did the one relevant doc appear?
    Recall@k   -- extended relevant set (same tags or difficulty +-1)
    MRR, NDCG@10 with binary relevance.
"""

import argparse
import json
import math


def hit_at(hits, target, k):
    return 1.0 if target in hits[:k] else 0.0


def reciprocal_rank(hits, target):
    for index, doc in enumerate(hits):
        if doc == target:
            return 1.0 / (index + 1)
    return 0.0


def ndcg_at(hits, target, k):
    for index, doc in enumerate(hits[:k]):
        if doc == target:
            return 1.0 / math.log2(index + 2)
    return 0.0


def recall_at(hits, relevant, k):
    if not relevant:
        return 0.0
    return len(set(hits[:k]) & set(relevant)) / len(set(relevant))


def summarize(runs):
    n = len(runs)
    if n == 0:
        return {"n": 0}
    out = {
        "n": n,
        "HitRate@5": sum(hit_at(r["hits"], r["problemId"], 5) for r in runs) / n,
        "HitRate@10": sum(hit_at(r["hits"], r["problemId"], 10) for r in runs) / n,
        "MRR": sum(reciprocal_rank(r["hits"], r["problemId"]) for r in runs) / n,
        "NDCG@10": sum(ndcg_at(r["hits"], r["problemId"], 10) for r in runs) / n,
        "Recall@10": sum(recall_at(r["hits"], r.get("relevant") or [], 10) for r in runs) / n,
        "avgRelevant": sum(len(r.get("relevant") or []) for r in runs) / n,
        "avgLatencyMs": sum(r.get("latencyMs", 0) for r in runs) / n,
    }
    return {k: (round(v, 4) if isinstance(v, float) else v) for k, v in out.items()}


def group(runs, key):
    buckets = {}
    for run in runs:
        buckets.setdefault(run.get(key), []).append(run)
    return {name: summarize(items) for name, items in sorted(buckets.items())}


def row(name, stats, method=None):
    """One markdown row. `method` adds the extra column used by compare_p5.py."""
    cells = [name]
    if method is not None:
        cells.append(method)
    cells.extend(
        [
            "%d" % stats["n"],
            "%.3f" % stats["HitRate@5"],
            "%.3f" % stats["HitRate@10"],
            "%.3f" % stats["MRR"],
            "%.3f" % stats["NDCG@10"],
            "%.3f" % stats["Recall@10"],
            "%.1f" % stats["avgLatencyMs"],
        ]
    )
    return "| " + " | ".join(cells) + " |"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()

    with open(args.input, encoding="utf-8") as fh:
        payload = json.load(fh)
    runs = payload["runs"]

    result = {
        "mode": payload.get("mode", "bm25"),
        "overall": summarize(runs),
        "byLang": group(runs, "lang"),
        "byBand": group(runs, "band"),
    }

    if args.markdown:
        header = (
            "| 组 | n | Hit@5 | Hit@10 | MRR | NDCG@10 | Recall@10 | 平均延迟(ms) |\n"
            "|---|---|---|---|---|---|---|---|"
        )
        lines = [header, row("全部", result["overall"])]
        for lang, stats in result["byLang"].items():
            lines.append(row("lang=%s" % lang, stats))
        for band, stats in result["byBand"].items():
            lines.append(row("band=%s" % band, stats))
        print("\n".join(lines))

    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
