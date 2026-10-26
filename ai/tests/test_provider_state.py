"""The finish reason belongs to one call, not to the provider instance.

Providers are shared by the whole process. When `finish_reason` lived on the
instance, two concurrent streams overwrote each other and a `length` could be
recorded against someone else's tutoring session. These tests pin the new shape:
the reason travels in a per-call `StreamState`.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from provider.base import Message, StreamState  # noqa: E402
from provider.fake import FakeProvider  # noqa: E402


def test_the_fake_provider_has_no_shared_finish_reason():
    provider = FakeProvider()
    assert not hasattr(provider, "finish_reason"), "a shared reason would cross-contaminate sessions"


def test_each_call_receives_its_own_state():
    provider = FakeProvider()
    messages = [Message("user", "how do I approach two sum")]

    first = StreamState()
    second = StreamState()
    list(provider.stream(messages, level=1, mode="chat", state=first))
    list(provider.stream(messages, level=1, mode="chat", state=second))

    assert first.finish_reason == "stop"
    assert second.finish_reason == "stop"
    assert first is not second


def test_a_provider_can_report_a_different_reason_per_stream():
    """Interleaved streams must not see each other's reason."""

    class CutOff:
        name = "cut-off"

        def stream(self, messages, **opts):
            state = opts.get("state")
            for index in range(3):
                yield f"chunk-{index}"
            if state is not None:
                state.finish_reason = "length"

    provider = CutOff()
    truncated = StreamState()
    normal = StreamState()

    list(provider.stream([], state=truncated))
    list(FakeProvider().stream([Message("user", "hi")], level=1, mode="chat", state=normal))

    assert truncated.finish_reason == "length"
    assert normal.finish_reason == "stop"
