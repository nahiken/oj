"""Process settings for ai.

Every value comes from the environment; nothing is persisted. The service must
fail loudly instead of falling back to a provider the operator did not choose.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

VERSION = "0.1.0"
# The service is started from ai/, so a bare relative path would look
# for ai/.runtime/... Resolve against the repo root, then the cwd.
REPO_ROOT = Path(__file__).resolve().parents[1]
KEY_FILE = REPO_ROOT / ".runtime/secrets/deepseek.key"
PROVIDERS = ("fake", "deepseek", "hermes")


class ConfigError(RuntimeError):
    """Raised at startup when the environment cannot produce a valid config."""


@dataclass(frozen=True)
class Settings:
    enabled: bool
    provider: str
    api_key: str | None
    judge_base_url: str
    context_budget: int = 1200
    max_tokens: int = 600
    rate_uid_per_min: int = 6
    rate_global_per_min: int = 60
    internal_key: str | None = None
    # Hermes worker (Python 3.14, separate process). Only read when
    # P6_PROVIDER=hermes; a loopback address on purpose — it holds no auth of
    # its own and must never be reachable from the network.
    worker_url: str = "http://127.0.0.1:8027"
    agent_max_steps: int = 6
    agent_model: str = "deepseek-chat"
    # How many Skill hits judge may return before the tool-side truncation
    # (top-3 / 120 chars / 600 tokens). Kept above 3 on purpose: the truncation
    # is a budget decision, not a "the index only had 3" decision.
    skill_search_top_k: int = 5
    # Fake provider only: a per-chunk pause so the browser test can observe the
    # stop button. It has no effect on a real provider.
    fake_delay_ms: int = 0

    @property
    def key_state(self) -> str:
        return "present" if self.api_key else "absent"


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    value = raw.strip().lower()
    if value in ("1", "true", "yes", "on"):
        return True
    if value in ("0", "false", "no", "off"):
        return False
    raise ConfigError(f"{name} must be a boolean, got {raw!r}")


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw.strip())
    except ValueError as exc:
        raise ConfigError(f"{name} must be an integer, got {raw!r}") from exc
    if value <= 0:
        raise ConfigError(f"{name} must be positive, got {value}")
    return value


def _env_non_negative_int(name: str, default: int) -> int:
    """Like _env_int but 0 is meaningful: it means "no delay"."""
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw.strip())
    except ValueError as exc:
        raise ConfigError(f"{name} must be an integer, got {raw!r}") from exc
    if value < 0:
        raise ConfigError(f"{name} must not be negative, got {value}")
    return value


def _read_key() -> str | None:
    """Environment first, then the single-line key file. Never logged."""
    env_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if env_key:
        return env_key
    try:
        text = KEY_FILE.read_text(encoding="utf-8-sig").strip()
    except OSError:
        return None
    return text or None


def load() -> Settings:
    provider = os.environ.get("P6_PROVIDER", "").strip().lower()
    if provider not in PROVIDERS:
        raise ConfigError(
            "P6_PROVIDER must be set to 'fake', 'deepseek' or 'hermes'.\n"
            "Set it explicitly; the service never guesses and never switches on its own."
        )
    api_key = _read_key()
    if provider == "deepseek" and not api_key:
        raise ConfigError(
            "P6_PROVIDER=deepseek but no key was found.\n"
            "Set DEEPSEEK_API_KEY or write the key to .runtime/secrets/deepseek.key."
        )
    return Settings(
        enabled=_env_bool("P6_ENABLED", True),
        provider=provider,
        api_key=api_key,
        # judge serves under /judge; the gateway rewrites /api/judge/** to it.
        judge_base_url=os.environ.get("JUDGE_BASE_URL", "http://127.0.0.1:8012/judge").rstrip("/"),
        context_budget=_env_int("P6_CONTEXT_BUDGET", 1200),
        # Every token count is labelled estimated; 600 is the contract default.
        # A tiny value is how the `length` path is exercised end to end.
        max_tokens=_env_int("P6_MAX_TOKENS", 600),
        # Spec D14 defaults. The benchmark raises these (it hammers on purpose);
        # production keeps 6/min per uid and 60/min per process.
        rate_uid_per_min=_env_int("P6_RATE_UID_PER_MIN", 6),
        rate_global_per_min=_env_int("P6_RATE_GLOBAL_PER_MIN", 60),
        # Shared with judge's oj.ai.internal-key. Without it no session
        # record is delivered at all (logged once), because a forgeable
        # denominator is worse than a missing one.
        internal_key=os.environ.get("P6_INTERNAL_KEY", "").strip() or None,
        worker_url=os.environ.get("P6_WORKER_URL", "http://127.0.0.1:8027").rstrip("/"),
        agent_max_steps=_env_int("P6_AGENT_MAX_STEPS", 6),
        # Fixed on purpose: deepseek-reasoner's tool-calling is not reliable
        # enough to expose to students, and a silent switch would change answers.
        agent_model=os.environ.get("P6_AGENT_MODEL", "deepseek-chat").strip() or "deepseek-chat",
        skill_search_top_k=_env_int("P6_SKILL_SEARCH_TOPK", 5),
        fake_delay_ms=_env_non_negative_int("P6_FAKE_DELAY_MS", 0),
    )
