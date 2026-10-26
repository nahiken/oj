"""Judge Skill search client (spec D20).

Judge owns the index and the visibility rule; this process only forwards the
browser's Cookie. Splitting `Unauthorized` from "search is down" matters here for
the same reason as in `context_client`: an expired login and a broken index are
different things for the user to do something about.
"""

from __future__ import annotations

import httpx

from settings import Settings


class SkillSearchUnavailable(RuntimeError):
    """judge is down, returned non-200, or the envelope carries no data."""


class Unauthorized(RuntimeError):
    """judge rejected the browser's token."""


async def search(settings: Settings, cookie: str | None, query: str, top_k: int) -> list[dict]:
    url = f"{settings.judge_base_url}/search/skill"
    headers = {"Cookie": cookie} if cookie else {}
    params = {"q": query, "topK": top_k}
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            response = await client.get(url, params=params, headers=headers)
    except httpx.HTTPError as exc:
        raise SkillSearchUnavailable(str(exc)) from exc
    if response.status_code in (401, 403):
        raise Unauthorized(f"judge rejected the token (HTTP {response.status_code})")
    if response.status_code != 200:
        raise SkillSearchUnavailable(f"judge returned {response.status_code}")
    body = response.json()
    # judge answers the project-wide envelope: an expired token is often HTTP 200
    # carrying code=401/403, and a disabled index carries code=30002.
    if isinstance(body, dict) and body.get("code") in (401, 403):
        raise Unauthorized(f"judge rejected the token (code={body.get('code')})")
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, dict):
        code = body.get("code") if isinstance(body, dict) else None
        raise SkillSearchUnavailable(f"judge refused the search (code={code})")
    hits = data.get("hits")
    if not isinstance(hits, list):
        raise SkillSearchUnavailable("judge returned no hits array")
    return hits
