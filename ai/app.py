"""ai: the tutoring service. See docs/design-docs/0006-ai-tutor.md.

Boundary rules that must stay true:
- this process has no MySQL/Redis/ES client: user state comes from judge over
  HTTP, and /internal/* is only reachable from loopback;
- the gateway proves "we signed this token", judge proves "it is still alive";
  this service only trusts X-Uid, which the gateway strips and re-injects.
"""

from __future__ import annotations

import json
import logging
import time
from datetime import datetime
from pathlib import Path
from typing import Iterator

from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse, StreamingResponse

import agent_relay
import context_client
import internal_api
import ratelimit
import session_client
import session_record
import skill_client
import summary_api
import tool_bridge
from guard import fingerprint as guard_fingerprint
from guard import ladder
from metrics import Metrics
from prompt import builder as prompt_builder
from provider.base import Message, ProviderUnavailable, StreamState
from provider.fake import FakeProvider
from settings import VERSION, ConfigError, Settings, load
from token_budget import estimate_chars
from token_budget import trim as trim_budget
from tool_result import trim_skill_hits

FINGERPRINTS = guard_fingerprint.load(
    Path(__file__).resolve().parents[1] / "benchmark/fixtures/p6-answer-fingerprints.json"
)

MODES = ("chat", "diagnose", "plan", "explain")
LEVELS = (1, 2, 3)
HISTORY_MAX_ITEMS = 8
HISTORY_MAX_CHARS = 2000
MESSAGE_MAX_CHARS = 2000

# SECURITY.md §AI 安全: every AI call leaves an audit line (uid, scene, tokens,
# duration). Bodies are never logged — they contain problem and user text.
audit = logging.getLogger("oj.ai.audit")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

app = FastAPI(title="ai", version=VERSION)


class _HermesProvider:
    """Name carrier for `P6_PROVIDER=hermes`.

    The turn itself is relayed to the 3.14 worker; this object exists so the
    audit line, the meta frame and `_source()` can keep one shape. Calling
    `stream()` here would mean the relay was bypassed — fail loudly.
    """

    name = "hermes"

    def stream(self, *args, **kwargs):  # pragma: no cover - defensive
        raise RuntimeError("hermes turns are relayed, not streamed in-process")


def _boot() -> tuple[Settings, object, Metrics]:
    try:
        settings = load()
    except ConfigError as exc:  # fail fast, with the reason on stderr
        raise SystemExit(f"ai cannot start:\n{exc}") from exc
    if settings.provider == "fake":
        provider = FakeProvider(settings.fake_delay_ms)
    elif settings.provider == "hermes":
        # The agent loop lives in the worker; this object only carries the name
        # that `_source()` branches on and the audit line that records it.
        provider = _HermesProvider()
    else:
        from provider.deepseek import DeepSeekProvider

        provider = DeepSeekProvider(settings.api_key or "")
    return settings, provider, Metrics(settings.provider, settings.enabled, settings.key_state)


settings, provider, metrics = _boot()
limiter = ratelimit.Limiter(
    uid_capacity=settings.rate_uid_per_min, global_capacity=settings.rate_global_per_min
)


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def fail(status: int, code: str, message: str, headers: dict | None = None) -> JSONResponse:
    return JSONResponse({"code": code, "message": message}, status_code=status, headers=headers)


def uid_of(x_uid: str | None) -> str:
    return (x_uid or "").strip()


def _now_iso() -> str:
    """Local time with the offset, so judge can store it without guessing a zone."""
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _record_session(facts: session_record.SessionFacts, uid: str, cookie: str | None) -> None:
    """Hand one session to judge. A rejected or lost record must not raise."""
    try:
        payload = session_record.payload(facts)
    except ValueError as exc:
        audit.warning("evt=ai.session.rejected uid=%s reason=%s err=%s", uid, facts.reason, exc)
        return
    session_client.record(settings, cookie, payload)


def trim_history(history: list[dict]) -> tuple[list[dict], bool]:
    trimmed = False
    items = [h for h in history if isinstance(h, dict) and h.get("content")]
    if len(items) > HISTORY_MAX_ITEMS:
        items = items[-HISTORY_MAX_ITEMS:]
        trimmed = True
    total = sum(len(str(h["content"])) for h in items)
    while items and total > HISTORY_MAX_CHARS:
        removed = items.pop(0)
        total -= len(str(removed["content"]))
        trimmed = True
    return items, trimmed


def done_reason(state: StreamState) -> str:
    """Map this call's stop reason onto the contract enum (0006 §3)."""
    return "length" if state.finish_reason == "length" else "stop"


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "provider": provider.name, "version": VERSION}


@app.get("/ai/metrics")
def ai_metrics(x_uid: str | None = Header(default=None)):
    if not settings.enabled:
        return fail(503, "DISABLED", "ai tutor is disabled")
    if not uid_of(x_uid):
        return fail(401, "UNAUTHORIZED", "missing X-Uid")
    snapshot = metrics.snapshot()
    # The benchmark reads this to know whether it may assert a 429.
    snapshot["limits"] = {
        "uidPerMin": settings.rate_uid_per_min,
        "globalPerMin": settings.rate_global_per_min,
    }
    # "Armed" evidence: how many problems actually have answer fingerprints.
    snapshot["guard"]["fingerprints"] = sum(len(hashes) for hashes in FINGERPRINTS.values())
    snapshot["guard"]["fingerprintProblems"] = len(FINGERPRINTS)
    # Only the fake provider honours this; None for a real provider. The browser
    # test needs it to be visible so verify.ps1 can fail early instead of flaking.
    snapshot["fakeDelayMs"] = settings.fake_delay_ms if provider.name == "fake" else None
    return snapshot


@app.post("/internal/extract-skill")
async def internal_extract_skill(request: Request, x_uid: str | None = Header(default=None)):
    client_host = request.client.host if request.client else None
    if not internal_api.is_internal(x_uid, client_host):
        return fail(403, "FORBIDDEN", "internal endpoint")
    payload = await internal_api.extract(request, x_uid, provider)
    metrics.skill_extracted()
    return payload


@app.post("/internal/summarize")
async def internal_summarize(request: Request, x_uid: str | None = Header(default=None)):
    client_host = request.client.host if request.client else None
    if not internal_api.is_internal(x_uid, client_host):
        return fail(403, "FORBIDDEN", "internal endpoint")
    return await summary_api.summarize(request, provider)


@app.post("/internal/tools/context")
async def internal_tool_context(request: Request, x_uid: str | None = Header(default=None)):
    """Agent-tool hop: turn id in, learning context out.

    Loopback + shared key only. The Cookie never leaves this process — it is
    looked up by the turn the worker is currently serving.
    """
    client_host = request.client.host if request.client else None
    if not internal_api.is_internal(x_uid, client_host):
        return fail(403, "FORBIDDEN", "internal endpoint")
    try:
        body = await request.json()
    except Exception:
        return fail(400, "INVALID_ARGUMENT", "body must be json")
    turn = tool_bridge.get(str((body or {}).get("turnId") or ""))
    if not turn:
        # The turn already ended (client closed, or budget cut it). A tool that
        # keeps calling after that must fail fast, not fetch someone's data.
        return JSONResponse({"error": "turn_closed", "retryable": False}, status_code=409)
    try:
        context = await context_client.fetch(settings, turn["cookie"], turn["problemId"])
    except context_client.Unauthorized:
        return JSONResponse({"error": "unauthorized", "retryable": False}, status_code=401)
    except Exception as exc:  # noqa: BLE001 - any judge failure is "try later"
        metrics.error("judge")
        return JSONResponse(
            {"error": "context_unavailable", "retryable": True, "detail": type(exc).__name__},
            status_code=503,
        )
    trimmed, _budget = trim_budget(context, settings.context_budget)
    return {
        "ok": True,
        "data": {
            "problemId": turn["problemId"],
            "episodes": trimmed.get("episodes") or [],
            "patterns": trimmed.get("patterns") or [],
            "skills": trimmed.get("skills") or [],
        },
    }


@app.post("/internal/tools/skill")
async def internal_tool_skill(request: Request, x_uid: str | None = Header(default=None)):
    """Agent-tool hop for `search_skill`: turn id + query in, top-3 Skills out.

    Same trust boundary as `/internal/tools/context`: loopback + shared key, and
    the Cookie is resolved from the open turn rather than passed by the worker.
    """
    client_host = request.client.host if request.client else None
    if not internal_api.is_internal(x_uid, client_host):
        return fail(403, "FORBIDDEN", "internal endpoint")
    try:
        body = await request.json()
    except Exception:
        return fail(400, "INVALID_ARGUMENT", "body must be json")
    turn = tool_bridge.get(str((body or {}).get("turnId") or ""))
    if not turn:
        return JSONResponse({"error": "turn_closed", "retryable": False}, status_code=409)
    query = str((body or {}).get("query") or "").strip()
    if not query:
        return JSONResponse({"error": "query_required", "retryable": False}, status_code=400)
    if len(query) > 200:
        return JSONResponse({"error": "query_too_long", "retryable": False}, status_code=400)
    try:
        hits = await skill_client.search(settings, turn["cookie"], query, settings.skill_search_top_k)
    except skill_client.Unauthorized:
        return JSONResponse({"error": "unauthorized", "retryable": False}, status_code=401)
    except Exception as exc:  # noqa: BLE001 - any judge failure is "try later"
        metrics.error("judge")
        return JSONResponse(
            {"error": "skill_search_unavailable", "retryable": True, "detail": type(exc).__name__},
            status_code=503,
        )
    skills, meta = trim_skill_hits(hits)
    return {"ok": True, "data": {"query": query, "skills": skills, "meta": meta}}


@app.post("/ai/chat")
async def chat(request: Request, x_uid: str | None = Header(default=None)):
    if not settings.enabled:
        return fail(503, "DISABLED", "ai tutor is disabled")
    uid = uid_of(x_uid)
    if not uid:
        return fail(401, "UNAUTHORIZED", "missing X-Uid")

    allowed, retry_after = limiter.allow(uid)
    if not allowed:
        metrics.error("ratelimit")
        return fail(
            429,
            "RATE_LIMITED",
            "too many tutoring requests; retry later",
            headers={"Retry-After": str(retry_after)},
        )

    try:
        body = await request.json()
    except Exception:
        return fail(400, "INVALID_ARGUMENT", "body must be json")
    if not isinstance(body, dict):
        return fail(400, "INVALID_ARGUMENT", "body must be an object")

    problem_id = body.get("problemId")
    if not isinstance(problem_id, int) or isinstance(problem_id, bool) or problem_id <= 0:
        return fail(400, "INVALID_ARGUMENT", "problemId must be a positive integer")
    mode = body.get("mode", "chat")
    if mode not in MODES:
        return fail(400, "INVALID_ARGUMENT", f"mode must be one of {MODES}")
    level = body.get("level", 1)
    if level not in LEVELS:
        return fail(400, "INVALID_ARGUMENT", "level must be 1, 2 or 3")
    message = body.get("message")
    if not isinstance(message, str) or not message.strip():
        return fail(400, "INVALID_ARGUMENT", "message must be a non-empty string")
    if len(message) > MESSAGE_MAX_CHARS:
        return fail(400, "INVALID_ARGUMENT", f"message must be <= {MESSAGE_MAX_CHARS} chars")
    raw_history = body.get("history") or []
    if not isinstance(raw_history, list):
        return fail(400, "INVALID_ARGUMENT", "history must be a list")
    history, history_trimmed = trim_history(raw_history)
    session_id = body.get("sessionId")
    if session_id is not None and (not isinstance(session_id, str) or len(session_id) > 64):
        return fail(400, "INVALID_ARGUMENT", "sessionId must be a string of at most 64 chars")

    # Fail before the first byte: once StreamingResponse starts we can only emit
    # an error event, and "503 + no delta" is a contract we can still keep here.
    if provider.name == "hermes" and not agent_relay.ping(settings.worker_url):
        metrics.error("worker")
        return fail(503, "WORKER_UNAVAILABLE", "hermes worker is not running")

    # Context comes from judge, authenticated by the browser's own Cookie: the
    # gateway only proved the token is ours, judge proves it is still alive.
    try:
        context = await context_client.fetch(settings, request.headers.get("cookie"), problem_id)
    except context_client.Unauthorized:
        return fail(401, "UNAUTHORIZED", "login expired or token revoked")
    except context_client.ProblemNotFound:
        return fail(400, "PROBLEM_NOT_FOUND", f"problem {problem_id} does not exist")
    except context_client.ContextUnavailable as exc:
        metrics.error("judge")
        return fail(503, "CONTEXT_UNAVAILABLE", f"learning context is unavailable: {exc}")

    trimmed, budget_meta = trim_budget(context, settings.context_budget)
    # The worker never sees the Cookie: tools trade this id for it instead.
    turn_id = tool_bridge.open_turn(
        uid=uid, cookie=request.headers.get("cookie"), problem_id=problem_id
    )
    messages, _context_text = prompt_builder.build(trimmed, history, message, level)
    prompt_tokens = prompt_builder.token_estimate(messages)
    skills = [
        {"id": skill.get("id"), "name": skill.get("name"), "content": skill.get("content")}
        for skill in (trimmed.get("skills") or [])
    ]
    if skills:
        # Counting usage here is what makes the number mean "injected", not
        # "listed": this is the point where the Skill enters the prompt.
        await context_client.mark_skills_used(
            settings,
            request.headers.get("cookie"),
            problem_id,
            [skill["id"] for skill in skills if skill.get("id")],
        )
        metrics.skills_used(len(skills))
    metrics.request()
    # True only when judge really returned related problems AND the budget kept
    # them: a record with a hardcoded False would make the metric unreadable.
    search_used = bool(trimmed.get("relatedProblems"))

    def generate() -> Iterator[str]:
        started = time.perf_counter()
        started_at = _now_iso()
        ttft_ms: int | None = None
        completion_chars = 0
        finish = "stop"
        reached_terminal = False
        stream_state = StreamState()
        metrics.stream_started()
        try:
            yield sse(
                "meta",
                {
                    "mode": mode,
                    "level": level,
                    "provider": provider.name,
                    "agent": (
                        {
                            "provider": "hermes",
                            "model": settings.agent_model,
                            "maxSteps": settings.agent_max_steps,
                        }
                        if provider.name == "hermes"
                        else None
                    ),
                    "contextTokens": budget_meta["contextTokens"],
                    "promptTokens": prompt_tokens,
                    "skillsUsed": [skill["name"] for skill in skills],
                    "historyTrimmed": history_trimmed,
                    "summarySource": context.get("summarySource") or "RULE",
                    "searchUsed": search_used,
                    "budget": budget_meta,
                },
            )
            guard_hashes = FINGERPRINTS.get(str(problem_id), set())
            emitted = ""
            steps_used: list[str] = []
            step_errors: list[str] = []
            degraded = False

            def _source():
                """One event stream, two producers. The loop below only knows
                deltas/steps/done/error, so the guard runs exactly once."""
                if provider.name == "hermes":
                    payload = agent_relay.build_payload(
                        uid=uid,
                        problem_id=problem_id,
                        message=message,
                        level=level,
                        mode=mode,
                        history=history,
                        messages=messages,
                        max_steps=settings.agent_max_steps,
                        turn_id=turn_id,
                    )
                    yield from agent_relay.stream_turn(
                        settings.worker_url,
                        payload,
                        internal_key=settings.internal_key,
                    )
                    return
                for piece in provider.stream(
                    messages,
                    level=level,
                    mode=mode,
                    max_tokens=settings.max_tokens,
                    state=stream_state,
                ):
                    yield ("delta", piece)

            try:
                for kind, item in _source():
                    if kind == "step":
                        # started + completed are the same call; only the
                        # terminal event may count, or done.steps doubles.
                        if item.status != "started":
                            steps_used.append(item.tool)
                            if item.status == "error":
                                step_errors.append(item.tool)
                        yield sse("step", item.as_dict())
                        continue
                    if kind == "done":
                        # A worker that aborted mid-turn must say so; we never
                        # guess "stop" in its place.
                        finish = item.get("finishReason") or finish
                        degraded = degraded or bool(item.get("degraded"))
                        continue
                    if kind == "error":
                        finish = "error"
                        metrics.stream_failed()
                        metrics.error("worker")
                        reached_terminal = True
                        yield sse(
                            "error",
                            {
                                "code": item.get("code") or "WORKER_ERROR",
                                "message": item.get("message") or "agent 暂时不可用",
                            },
                        )
                        return
                    chunk = item
                    if ttft_ms is None:
                        ttft_ms = int((time.perf_counter() - started) * 1000)
                        if provider.name == "fake":
                            ttft_ms = None  # no real first token; never fake the number
                        metrics.record_ttft(ttft_ms)
                    emitted += chunk
                    # Judge the text we are about to have said, not the whole answer:
                    # buffering would destroy TTFT, so a violation cuts the stream.
                    violated, _reason = ladder.classify(emitted, level)
                    leaked = guard_fingerprint.leaks(emitted, guard_hashes)
                    if violated or leaked:
                        finish = "guarded"
                        if leaked:
                            metrics.guard_leak()
                        else:
                            metrics.guard_ladder()
                        metrics.error("guard")
                        # assigned, not +=: `emitted` already holds every chunk so
                        # far, and adding it to the running total double counted.
                        completion_chars = len(emitted)
                        completion_tokens = estimate_chars(completion_chars)
                        metrics.tokens(prompt_tokens, completion_tokens)
                        metrics.stream_completed()
                        yield sse("delta", {"text": "\n\n" + ladder.SAFE_TAIL})
                        reached_terminal = True
                        yield sse(
                            "done",
                            {
                                "finishReason": "guarded",
                                "tokens": {
                                    "prompt": prompt_tokens,
                                    "completion": completion_tokens,
                                    "estimated": True,
                                },
                                "ttftMs": ttft_ms,
                            },
                        )
                        return
                    completion_chars += len(chunk)
                    yield sse("delta", {"text": chunk})
            except ProviderUnavailable:
                finish = "error"
                metrics.stream_failed()
                metrics.error("provider")
                reached_terminal = True
                yield sse("error", {"code": "PROVIDER_UNAVAILABLE", "message": "上游模型不可用"})
                return
            except agent_relay.WorkerUnavailable:
                # The ping passed but the turn died: still a worker fault, still
                # reported as one, never as a user abort.
                finish = "error"
                metrics.stream_failed()
                metrics.error("worker")
                reached_terminal = True
                yield sse("error", {"code": "WORKER_UNAVAILABLE", "message": "agent 暂时不可用"})
                return
            if provider.name == "hermes" and not emitted and step_errors and len(step_errors) == len(steps_used):
                # Every tool failed and the model produced no text: this is an
                # outage, not a terse answer.
                finish = "error"
                metrics.stream_failed()
                reached_terminal = True
                yield sse("error", {"code": "worker_error", "message": "agent 工具全部不可用"})
                return
            finish = done_reason(stream_state) if provider.name != "hermes" else finish
            completion_tokens = estimate_chars(completion_chars)
            metrics.tokens(prompt_tokens, completion_tokens)
            metrics.stream_completed()
            reached_terminal = True
            yield sse(
                "done",
                {
                    "finishReason": finish,
                    "tokens": {
                        "prompt": prompt_tokens,
                        "completion": completion_tokens,
                        "estimated": True,
                    },
                    "ttftMs": ttft_ms,
                    "steps": len(steps_used),
                    "degraded": degraded or bool(step_errors),
                },
            )
        except GeneratorExit:
            # The client went away: recorded as "aborted", never as a server fault.
            raise
        except Exception:
            # Anything escaping the loop is ours to blame, not the user's. Without
            # this, a server fault would be recorded as "the user closed the tab".
            finish = "error"
            metrics.stream_failed()
            metrics.error("stream")
            raise
        finally:
            # One audit line per call, success or not. No bodies, never the key.
            recorded_reason = session_record.finalize_reason(finish, reached_terminal)
            audit.info(
                "evt=ai.call uid=%s scene=chat provider=%s finish=%s prompt=%d completion=%d "
                "ttftMs=%s totalMs=%d key=%s",
                uid,
                provider.name,
                recorded_reason,
                prompt_tokens,
                estimate_chars(completion_chars),
                ttft_ms if ttft_ms is not None else "-",
                int((time.perf_counter() - started) * 1000),
                settings.key_state,
            )
            tool_bridge.close_turn(turn_id)
            _record_session(
                session_record.SessionFacts(
                    problem_id=problem_id,
                    level=level,
                    session_id=session_id,
                    started=started_at,
                    finished=_now_iso(),
                    reason=recorded_reason,
                    prompt_tokens=prompt_tokens,
                    completion_tokens=estimate_chars(completion_chars),
                    search_used=search_used,
                ),
                uid,
                request.headers.get("cookie"),
            )

    return StreamingResponse(
        generate(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
