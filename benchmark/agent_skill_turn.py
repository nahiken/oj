"""One agent turn through the gateway, to prove Hermes really calls `search_skill`.

Cheap and disposable: it prints the SSE frames it cares about (meta / step / done)
and asserts that a step named `search_skill` finished with status `ok`.

The JWT secret comes from `OJ_JWT_SECRET` (docs/SECURITY.md forbids baking
secrets into source, and `docs/SECURITY.md` also forbids secrets on argv).
The token it mints is deleted from Redis on the way out, even on failure.
"""

from __future__ import annotations

import json
import os
import socket
import sys
import time

import httpx
import jwt

UID = 1900000000000000001
PROBLEM = 1
MESSAGE = "我想用二分答案，但不确定第一步该做什么。请先调用 search_skill 找一条可复用的步骤，再按它给我提示。"


def redis_command(*parts: str) -> bytes:
    """Minimal RESP client: enough for SELECT / SET / DEL on the local lab."""

    def encode(*items: str) -> bytes:
        out = [f"*{len(items)}\r\n".encode()]
        for item in items:
            raw = item.encode()
            out.append(f"${len(raw)}\r\n".encode() + raw + b"\r\n")
        return b"".join(out)

    with socket.create_connection(("127.0.0.1", 6379), timeout=5) as sock:
        sock.sendall(encode("SELECT", "12") + encode(*parts))
        time.sleep(0.1)
        return sock.recv(256)


def require_secret() -> str:
    value = os.environ.get("OJ_JWT_SECRET")
    if not value:
        print(
            "OJ_JWT_SECRET is not set. This script must not hardcode the JWT "
            "secret (docs/SECURITY.md); export it from .runtime/run/judge/application.yml.",
            file=sys.stderr,
        )
        raise SystemExit(2)
    return value


def main() -> int:
    token = jwt.encode({"uid": UID, "userRole": "admin"}, require_secret(), algorithm="HS256")
    key = f"user:token:{UID}"
    redis_command("SET", key, token)
    try:
        return run_turn(token)
    finally:
        redis_command("DEL", key)


def run_turn(token: str) -> int:
    body = {"problemId": PROBLEM, "message": MESSAGE, "mode": "chat", "level": 1}
    steps: list[dict] = []
    meta: dict = {}
    done: dict = {}
    deltas = 0
    with httpx.stream("POST", "http://localhost:8024/api/ai/chat", json=body,
                      headers={"Cookie": f"token={token}"}, timeout=180) as response:
        print("HTTP", response.status_code)
        event = None
        for line in response.iter_lines():
            if line.startswith("event:"):
                event = line.split(":", 1)[1].strip()
            elif line.startswith("data:") and event:
                payload = json.loads(line.split(":", 1)[1].strip())
                if event == "meta":
                    meta = payload
                    print("meta", json.dumps(payload, ensure_ascii=False)[:300])
                elif event == "step":
                    steps.append(payload)
                    print("step", json.dumps(payload, ensure_ascii=False))
                elif event == "delta":
                    deltas += 1
                elif event == "done":
                    done = payload
                    print("done", json.dumps(payload, ensure_ascii=False))
                elif event == "error":
                    print("error", payload)
    tools = [step.get("tool") for step in steps]
    ok = {step.get("tool") for step in steps if step.get("status") == "ok"}
    result = {
        "deltaFrames": deltas,
        "tools": tools,
        "toolOk": sorted(ok),
        "steps": done.get("steps"),
        "agent": (meta or {}).get("agent"),
    }
    print(json.dumps(result, ensure_ascii=False))
    return 0 if "search_skill" in ok else 1


if __name__ == "__main__":
    sys.exit(main())
