"""Print the three-method comparison table (BM25 / Dense / RRF) from three eval runs.

Usage:
    python benchmark/compare_p5.py --bm25 <file> --dense <file> --hybrid <file>

The three result files are produced by `run-p5.ps1 -Scenario eval -Mode <mode>`;
this script only aggregates, so the numbers cannot drift between tables.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eval_p5 import group, row, summarize  # noqa: E402

METHODS = ("bm25", "dense", "hybrid")


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)["runs"]


def main():
    parser = argparse.ArgumentParser()
    for method in METHODS:
        parser.add_argument("--" + method, required=True)
    args = parser.parse_args()

    runs = {method: load(getattr(args, method)) for method in METHODS}

    header = (
        "| 组 | 方法 | n | Hit@5 | Hit@10 | MRR | NDCG@10 | Recall@10 | 平均延迟(ms) |\n"
        "|---|---|---|---|---|---|---|---|---|"
    )
    lines = [header]

    for method in METHODS:
        lines.append(row("全部", summarize(runs[method]), method))
    for lang in ("en", "zh"):
        for method in METHODS:
            stats = group(runs[method], "lang").get(lang)
            if stats:
                lines.append(row("lang=" + lang, stats, method))
    for band in ("high", "mid", "low"):
        for method in METHODS:
            stats = group(runs[method], "band").get(band)
            if stats:
                lines.append(row("band=" + band, stats, method))

    print("\n".join(lines))

    payload = {
        method: {
            "overall": summarize(runs[method]),
            "byLang": group(runs[method], "lang"),
            "byBand": group(runs[method], "band"),
        }
        for method in METHODS
    }
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
