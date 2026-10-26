"""Judge Context client.

The service holds no database connection: everything about the user comes from
judge over HTTP, authenticated by the original Cookie the browser sent.
"""

from __future__ import annotations

import httpx

from settings import Settings


class ContextUnavailable(RuntimeError):
    """judge is down, returned non-200, or the payload is not a context."""


class Unauthorized(RuntimeError):
    """judge rejected the browser's token: expired, revoked, or never valid.

    Separate from ContextUnavailable on purpose. "Your login expired" and "the
    learning context is down" are different user-facing states, and folding them
    together makes the tutor tell people to retry when they need to log in.
    """


class ProblemNotFound(RuntimeError):
    """judge answered, but the problem does not exist (problem == null)."""


async def fetch(settings: Settings, cookie: str | None, problem_id: int) -> dict:
    url = f"{settings.judge_base_url}/learning/context"
    headers = {"Cookie": cookie} if cookie else {}
    try:
        # 5s, not 3s: the first call after judge restarts is cold (JIT, DB pool,
        # ES and the embedding sidecar all cold) and measured 3.1s, which turned
        # every post-deploy first question into a 503. Warm calls are ~170-200ms,
        # so this raises the ceiling without hiding a slow path.
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(url, params={"problemId": problem_id}, headers=headers)
    except httpx.HTTPError as exc:
        raise ContextUnavailable(str(exc)) from exc
    if response.status_code in (401, 403):
        raise Unauthorized(f"judge rejected the token (HTTP {response.status_code})")
    if response.status_code != 200:
        raise ContextUnavailable(f"judge returned {response.status_code}")
    body = response.json()
    # judge answers the project-wide envelope, so a rejected token is often
    # HTTP 200 carrying code=401/403.
    if isinstance(body, dict) and body.get("code") in (401, 403):
        raise Unauthorized(f"judge rejected the token (code={body.get('code')})")
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, dict):
        code = body.get("code") if isinstance(body, dict) else None
        raise ContextUnavailable(f"judge refused the request (code={code})")
    if not data.get("problem"):
        raise ProblemNotFound(str(problem_id))
    return data


async def mark_skills_used(
    settings: Settings, cookie: str | None, problem_id: int, ids: list[int]
) -> None:
    """Tell judge that these Skills really went into the prompt.

    Judge stores the (uid, problemId, skill) triple so a later AC on the same
    problem can be attributed back to the Skill (success_count).

    Best effort on purpose: usage accounting must never break a tutoring stream.
    """
    if not ids:
        return
    headers = {"Cookie": cookie} if cookie else {}
    payload = {"problemId": problem_id, "ids": ids}
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            await client.post(
                f"{settings.judge_base_url}/learning/skill/used", json=payload, headers=headers
            )
    except httpx.HTTPError:
        return
