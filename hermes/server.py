"""Hermes worker: the only process that runs agent code.

Why a separate process: Hermes needs Python 3.14 while `ai` runs 3.11,
and Hermes keeps process-global state (`model_tools._last_resolved_tool_names`
is unsynchronised, the tool registry caches availability per process). One turn
at a time per process keeps both hazards out of reach.

Wire contract: NDJSON, one event per line. The caller (`ai`) owns the
guard, the audit line and the SSE encoding; this process owns the agent loop.
Bound to loopback and gated by `X-Internal-Key` because it holds a model key.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = REPO_ROOT / ".runtime/secrets/deepseek.key"
DEFAULT_MODEL = os.environ.get("P6_AGENT_MODEL", "deepseek-chat")
BASE_URL = os.environ.get("P6_AGENT_BASE_URL", "https://api.deepseek.com")
# 0 means "no key configured"; the worker then refuses every turn loudly rather
# than serving a model it cannot reach.
TURN_LOCK = threading.Lock()
INTERNAL_KEY = os.environ.get("P6_INTERNAL_KEY", "").strip() or None


def read_key() -> str | None:
    env_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if env_key:
        return env_key
    try:
        return KEY_FILE.read_text(encoding="utf-8-sig").strip() or None
    except OSError:
        return None


class ChunkedWriter:
    """HTTP/1.1 chunked body, because a turn must stream before it ends."""

    def __init__(self, handler: BaseHTTPRequestHandler) -> None:
        self._handler = handler
        self._lock = threading.Lock()
        self.closed = False

    def line(self, event: dict) -> None:
        if self.closed:
            return
        payload = (json.dumps(event, ensure_ascii=False) + "\n").encode("utf-8")
        try:
            with self._lock:
                self._handler.wfile.write(f"{len(payload):X}\r\n".encode("ascii"))
                self._handler.wfile.write(payload)
                self._handler.wfile.write(b"\r\n")
                self._handler.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            # The client went away. We stop writing; the turn itself is allowed
            # to finish because Hermes cannot be interrupted mid-call safely.
            self.closed = True

    def finish(self) -> None:
        if self.closed:
            return
        try:
            with self._lock:
                self._handler.wfile.write(b"0\r\n\r\n")
                self._handler.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


def run_turn(body: dict, writer: ChunkedWriter) -> None:
    """Blocking: build the agent, stream deltas, emit exactly one terminal event."""
    from run_agent import AIAgent

    key = read_key()
    if not key:
        writer.line({"type": "error", "code": "worker_error", "message": "no model key configured"})
        return

    max_steps = int(body.get("maxSteps") or 6)
    history = body.get("history") or []
    # One turn at a time per process, so an env var is a safe channel for the
    # id the tools trade back for the Cookie inside ai.
    turn_id = str(body.get("turnId") or "")
    if turn_id:
        os.environ["P6_TURN_ID"] = turn_id
    # The caller assembles the prompt; rebuild the three pieces Hermes needs so
    # the context banner and the user wrapper survive the hop.
    wire = [m for m in (body.get("messages") or []) if isinstance(m, dict)]
    if wire:
        system_prompt = next((m.get("content") for m in wire if m.get("role") == "system"), None)
        user_message = next(
            (m.get("content") for m in reversed(wire) if m.get("role") == "user"), body.get("message") or ""
        )
        history = [m for m in wire if m.get("role") in ("user", "assistant")][:-1]
    else:
        system_prompt = body.get("system") or None
        user_message = body.get("message") or ""
    steps: list[dict] = []
    degraded = {"value": False}
    started: dict[str, tuple[float, str]] = {}

    def args_hash(args) -> str:
        raw = json.dumps(args, sort_keys=True, ensure_ascii=False, default=str)
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]

    def on_progress(event_type, name, preview, args=None, **kwargs):
        # Started carries the arguments; completed carries duration and error.
        # `tool_call` is a bridge: the model names the real tool inside its
        # arguments. Reporting the bridge name would make the step evidence
        # meaningless ("it called tool_call" says nothing).
        real = name
        if name == "tool_call" and isinstance(args, dict):
            calls = args.get("calls")
            if isinstance(calls, list) and calls and isinstance(calls[0], dict):
                real = str(calls[0].get("name") or name)
            elif args.get("name"):
                real = str(args["name"])
        if event_type == "tool.started":
            started[name] = (time.time(), args_hash(args), len(started) + 1, real)
            steps.append({"type": "step", "index": started[name][2], "tool": real,
                          "status": "started", "ms": None, "argsHash": started[name][1]})
        elif event_type == "tool.completed":
            began, digest, index, real = started.get(name, (time.time(), None, len(started) + 1, real))
            elapsed = int((time.time() - began) * 1000)
            failed = bool(kwargs.get("is_error"))
            degraded["value"] = degraded["value"] or failed
            steps.append({"type": "step", "index": index, "tool": real,
                          "status": "error" if failed else "ok",
                          "ms": elapsed, "argsHash": digest})
        else:
            return
        writer.line(steps[-1])

    agent = AIAgent(
        model=DEFAULT_MODEL,
        api_key=key,
        base_url=BASE_URL,
        quiet_mode=True,
        # Stateless by construction: no MEMORY.md, no AGENTS.md from cwd, no
        # end-of-turn review fork (that one costs ~30K tokens per turn), no
        # session rows in Hermes' own database.
        skip_memory=True,
        skip_context_files=True,
        skip_background_review=True,
        session_db=None,
        enabled_toolsets=["oj"],
        ephemeral_system_prompt=system_prompt,
        max_iterations=max(1, max_steps),
    )
    terminal = {"written": False}

    def emit_delta(text: str) -> None:
        terminal["written"] = terminal["written"] or bool(text)
        writer.line({"type": "delta", "text": text})

    agent.stream_delta_callback = emit_delta
    agent.tool_progress_callback = on_progress
    # Belt and braces with the plugin hook: the thread whitelist blocks any
    # tool outside the bridge + our tool, even if a plugin fails to load.
    from hermes_cli.plugins import clear_thread_tool_whitelist, set_thread_tool_whitelist

    set_thread_tool_whitelist(
        {"tool_search", "tool_describe", "tool_call", "get_learning_context"},
        deny_msg_fmt="Tool '{tool_name}' is not available in this tutor",
    )
    try:
        agent.run_conversation(
            user_message=user_message,
            conversation_history=history,
        )
    except Exception as exc:  # noqa: BLE001 - a turn must never kill the worker
        traceback.print_exc()
        writer.line({"type": "error", "code": "worker_error", "message": type(exc).__name__})
        return
    finally:
        clear_thread_tool_whitelist()
    # A turn that never produced text was cut short somewhere upstream; saying
    # "stop" would make truncation indistinguishable from a complete answer.
    tool_names = sorted({s["tool"] for s in steps})
    writer.line({
        "type": "done",
        "finishReason": "stop" if terminal["written"] else "aborted",
        "steps": len(tool_names),
        "toolsUsed": tool_names,
        "degraded": degraded["value"],
    })


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):  # noqa: N802 - stdlib naming
        if self.path == "/health":
            body = b'{"status":"ok","provider":"hermes"}'
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):  # noqa: N802 - stdlib naming
        if self.path != "/turn":
            self.send_error(404)
            return
        # Fail closed: an unset key must not turn into "anyone on loopback".
        if not INTERNAL_KEY or self.headers.get("x-internal-key") != INTERNAL_KEY:
            self.send_error(403)
            return
        length = int(self.headers.get("content-length") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self.send_error(400)
            return
        if not isinstance(body, dict) or not body.get("message"):
            self.send_error(400)
            return

        # One turn at a time is a hard guarantee, not a comment: the turn id
        # travels through os.environ, so concurrency here would let one request
        # resolve another user's Cookie. Busy => 429, same as a full queue.
        if not TURN_LOCK.acquire(blocking=False):
            self.send_error(429)
            return
        try:
            self._stream_turn(body)
        finally:
            TURN_LOCK.release()

    def _stream_turn(self, body: dict) -> None:
        self.send_response(200)
        self.send_header("content-type", "application/x-ndjson")
        self.send_header("transfer-encoding", "chunked")
        self.end_headers()
        writer = ChunkedWriter(self)
        try:
            run_turn(body, writer)
        finally:
            writer.finish()

    def log_message(self, fmt, *args):  # keep the console for turn summaries
        sys.stderr.write("[worker] " + (fmt % args) + "\n")


def prewarm() -> None:
    """Pay the import/plugin-discovery cost before the first student arrives.

    A cold worker took ~20s to first token; a warm one takes ~1s. The cost is
    Hermes' own import graph plus plugin discovery, all one-shot per process.
    """
    try:
        from run_agent import AIAgent

        AIAgent(model=DEFAULT_MODEL, quiet_mode=True, enabled_toolsets=[], max_iterations=1)
        print("[worker] prewarmed", flush=True)
    except Exception:  # noqa: BLE001 - prewarm must never block serving
        traceback.print_exc()


def main() -> None:
    host = os.environ.get("P6_WORKER_HOST", "127.0.0.1")
    port = int(os.environ.get("P6_WORKER_PORT", "8027"))
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"hermes worker listening on http://{host}:{port} (model={DEFAULT_MODEL})", flush=True)
    threading.Thread(target=prewarm, daemon=True).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
