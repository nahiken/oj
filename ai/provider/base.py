"""Provider contract.

`stream()` yields text chunks; the caller turns them into SSE `delta` frames.
The contract is deliberately tiny: it is the seam every test and every future
provider goes through.

Why `StreamState` exists: providers used to keep `finish_reason` on the instance.
A provider instance is shared by the whole process, so two concurrent streams
overwrote each other's reason and a `length` could land on someone else's
session record. The reason now travels in a per-call object the caller owns.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterator, Protocol


@dataclass(frozen=True)
class Message:
    role: str  # "system" | "user" | "assistant"
    content: str


@dataclass
class StreamState:
    """Per-call mutable state. One instance per request, never shared."""

    finish_reason: str = "stop"
    chunks: int = field(default=0)


class Provider(Protocol):
    name: str
    model_id: str

    def stream(self, messages: list[Message], **opts: object) -> Iterator[str]:
        """Yield text. `opts["state"]`, when given, receives the finish reason."""
        ...


class ProviderUnavailable(RuntimeError):
    """The provider cannot serve this request (no key, upstream refused)."""
