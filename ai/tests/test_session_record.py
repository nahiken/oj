"""What a tutoring session leaves behind.

The record is what makes 二次 AC 率 computable later: a denominator needs a
session with a start, an end and a reason. The interesting behaviour is that an
aborted stream must not be recorded as a normal finish — otherwise "was the user
convinced" silently includes "the user closed the tab".

Pure functions only: the HTTP call that sends the payload is best effort and
lives in session_client.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import session_record  # noqa: E402


def build(**overrides):
    kwargs = dict(
        problem_id=7,
        level=2,
        session_id="s-1",
        started="2026-10-21T10:00:00.000+08:00",
        finished="2026-10-21T10:00:05.500+08:00",
        reason="stop",
        prompt_tokens=812,
        completion_tokens=140,
        search_used=False,
    )
    kwargs.update(overrides)
    return session_record.payload(session_record.SessionFacts(**kwargs))


def test_a_completed_stream_is_recorded_as_stop():
    body = build(reason=session_record.finalize_reason("stop", True))
    assert body["finishReason"] == "stop"
    assert body["problemId"] == 7
    assert body["level"] == 2
    assert body["sessionId"] == "s-1"
    assert body["promptTokens"] == 812
    assert body["completionTokens"] == 140
    assert body["searchUsed"] is False


def test_a_stream_that_never_reached_a_terminal_frame_is_aborted():
    # The generator was closed early (tab closed / stop button): the default
    # "stop" must not survive into the record.
    reason = session_record.finalize_reason("stop", False)
    assert reason == "aborted"
    assert build(reason=reason)["finishReason"] == "aborted"


def test_a_guarded_stream_keeps_its_own_reason_even_when_incomplete():
    assert session_record.finalize_reason("guarded", False) == "guarded"


def test_a_provider_failure_keeps_the_error_reason():
    assert session_record.finalize_reason("error", True) == "error"


def test_a_server_fault_is_never_blamed_on_the_user():
    # An exception escaping the stream is ours, even though it also means the
    # stream never reached a terminal frame.
    assert session_record.finalize_reason("error", False) == "error"


def test_a_length_stop_is_not_turned_into_an_abort():
    assert session_record.finalize_reason("length", True) == "length"


def test_an_unknown_reason_is_refused_instead_of_guessed():
    with pytest.raises(ValueError):
        build(reason="finished")


def test_timestamps_are_kept_as_given_and_must_not_run_backwards():
    body = build()
    assert body["startedAt"] == "2026-10-21T10:00:00.000+08:00"
    assert body["finishedAt"] == "2026-10-21T10:00:05.500+08:00"
    with pytest.raises(ValueError):
        build(
            started="2026-10-21T10:00:05.500+08:00",
            finished="2026-10-21T10:00:00.000+08:00",
        )


def test_an_empty_session_id_becomes_null_rather_than_an_empty_string():
    assert build(session_id="")["sessionId"] is None


def test_search_usage_is_recorded_as_a_boolean():
    assert build(search_used=True)["searchUsed"] is True
