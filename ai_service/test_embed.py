"""Contract tests for the embedding sidecar.

Run with `python ai_service/test_embed.py` - no pytest needed.
The unit part passes offline; the smoke part needs the sidecar on 127.0.0.1:8025.
The prefix lives in the sidecar and nowhere else: callers pass a raw text plus a
`kind`, and the sidecar refuses text that already carries a prefix.
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8025"
DIMS = 384
failures = []
checks = 0


def check(name, cond, detail=""):
    global checks
    checks += 1
    if cond:
        print("PASS %s" % name)
    else:
        failures.append(name)
        print("FAIL %s %s" % (name, detail))


def test_prefix():
    sys.path.insert(0, "ai_service")
    from embed_service import build_input

    check("prefix.query", build_input("query", "abc") == "query: abc")
    check("prefix.passage", build_input("passage", "abc") == "passage: abc")
    try:
        build_input("bogus", "abc")
        check("prefix.unknown_kind_rejected", False, "no exception")
    except ValueError:
        check("prefix.unknown_kind_rejected", True)
    try:
        build_input("query", "query: already prefixed")
        check("prefix.double_prefix_rejected", False, "no exception")
    except ValueError:
        check("prefix.double_prefix_rejected", True)


def post(path, body, timeout=30):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_status(path, body, timeout=30):
    try:
        post(path, body, timeout)
        return 200
    except urllib.error.HTTPError as exc:
        return exc.code


def get(path, timeout=10):
    with urllib.request.urlopen(BASE + path, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def test_smoke():
    try:
        health = get("/health")
    except (urllib.error.URLError, ConnectionError) as exc:
        for name in (
            "health.dims",
            "dims",
            "deterministic",
            "query_differs_from_passage",
            "empty_text_still_returns_vector",
            "batch_of_unequal_lengths",
            "kind_is_required",
            "double_prefix_is_rejected",
        ):
            check(name, False, "service down: %s" % exc)
        return

    check("health.dims", health.get("dims") == DIMS, str(health))
    a = post("/embed", {"texts": ["two numbers sum to target"], "kind": "passage"})
    b = post("/embed", {"texts": ["two numbers sum to target"], "kind": "passage"})
    check("dims", len(a["vectors"][0]) == DIMS, "got %d" % len(a["vectors"][0]))
    check("deterministic", a["vectors"][0] == b["vectors"][0])
    q = post("/embed", {"texts": ["two numbers sum to target"], "kind": "query"})
    check("query_differs_from_passage", q["vectors"][0] != a["vectors"][0])
    zero = post("/embed", {"texts": [""], "kind": "query"})["vectors"][0]
    check("empty_text_still_returns_vector", len(zero) == DIMS)
    batch = post(
        "/embed",
        {
            "texts": ["short one", "a much longer second text that tokenizes differently"],
            "kind": "query",
        },
    )["vectors"]
    check(
        "batch_of_unequal_lengths",
        len(batch) == 2 and all(len(v) == DIMS for v in batch),
        "shape=%s" % [len(v) for v in batch],
    )
    check("kind_is_required", post_status("/embed", {"texts": ["x"]}) == 422)
    check(
        "double_prefix_is_rejected",
        post_status("/embed", {"texts": ["query: already"], "kind": "query"}) == 400,
    )


if __name__ == "__main__":
    test_prefix()
    test_smoke()
    print("assertions=%d fail=%d" % (checks, len(failures)))
    sys.exit(1 if failures else 0)
