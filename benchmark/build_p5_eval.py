"""Derive benchmark/fixtures/p5-eval.json from the authored query set + corpus text.

The authored file (p5-queries.json) carries only what a human wrote: query text,
ground-truth problem id and language.  `lexical_overlap` is *computed* here from
the corpus so the two can never drift apart.

Usage:
    python benchmark/build_p5_eval.py --corpus .runtime/tmp/p5-corpus.tsv \
        --queries benchmark/fixtures/p5-queries.json \
        --out benchmark/fixtures/p5-eval.json

Corpus TSV format (produced by mysql.exe, one row per line):
    <id>\t<title>\t<content with literal \\n replaced by space>

Overlap rule (written down because the numbers depend on it):
    en: share of query words (lowercased, length>1, stopwords dropped) that
        appear anywhere in "title + content".
    zh: share of the query's character bigrams that appear in the text.
Bands: high >= 0.6, mid >= 0.3, low < 0.3.
"""

import argparse
import json
import re

STOP = set(
    "a an the of to in on for with and or is are be by at as from that this it "
    "its into over under using use used given you your we i need want find how "
    "problem like such which what".split()
)

ZH_CHAR = re.compile(r"[\u4e00-\u9fff]")


def en_words(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def zh_bigrams(text):
    chars = ZH_CHAR.findall(text)
    return {chars[i] + chars[i + 1] for i in range(len(chars) - 1)}


def overlap(query, lang, text):
    if lang == "zh":
        q = zh_bigrams(query)
        if not q:
            return 0.0
        return len(q & zh_bigrams(text)) / len(q)
    q = [w for w in en_words(query) if len(w) > 1 and w not in STOP]
    if not q:
        return 0.0
    corpus = set(en_words(text))
    return sum(1 for w in q if w in corpus) / len(q)


def band(value):
    if value >= 0.6:
        return "high"
    if value >= 0.3:
        return "mid"
    return "low"


def load_corpus(path):
    corpus = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            corpus[parts[0].strip()] = parts[1] + " " + parts[2]
    return corpus


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--queries", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    corpus = load_corpus(args.corpus)
    with open(args.queries, encoding="utf-8") as fh:
        authored = json.load(fh)

    rows = []
    missing = []
    for item in authored["queries"]:
        text = corpus.get(str(item["problemId"]))
        if text is None:
            missing.append(item["id"])
            continue
        value = round(overlap(item["query"], item["lang"], text), 3)
        rows.append(
            {
                "id": item["id"],
                "query": item["query"],
                "problemId": item["problemId"],
                "lang": item["lang"],
                "lexicalOverlap": value,
                "band": band(value),
            }
        )

    if missing:
        raise SystemExit("ground-truth problem missing from corpus: %s" % ",".join(missing))

    payload = {
        "note": authored["note"],
        "seed": authored["seed"],
        "overlapRule": "en: share of query words present in title+content; zh: share of character bigrams present. bands high>=0.6 mid>=0.3 low<0.3",
        "queries": rows,
    }
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
        fh.write("\n")

    counts = {}
    for row in rows:
        counts[row["band"]] = counts.get(row["band"], 0) + 1
    print("wrote %s: %d queries, bands %s" % (args.out, len(rows), counts))


if __name__ == "__main__":
    main()
