"""DeepSeek provider: the only implementation that needs a key."""

from __future__ import annotations

from typing import Iterator

from .base import Message, ProviderUnavailable

BASE_URL = "https://api.deepseek.com"
MODEL = "deepseek-chat"


class DeepSeekProvider:
    name = "deepseek"
    model_id = MODEL

    def __init__(self, api_key: str, max_tokens: int = 600) -> None:
        if not api_key:
            raise ProviderUnavailable("deepseek provider requires an api key")
        # Imported lazily so a fake-only deployment never needs the SDK.
        from openai import OpenAI

        self._client = OpenAI(api_key=api_key, base_url=BASE_URL)
        self._max_tokens = max_tokens

    def stream(self, messages: list[Message], **opts: object) -> Iterator[str]:
        state = opts.get("state")
        try:
            response = self._client.chat.completions.create(
                model=MODEL,
                messages=[{"role": m.role, "content": m.content} for m in messages],
                max_tokens=int(opts.get("max_tokens", self._max_tokens)),
                stream=True,
                temperature=0.3,
            )
            for event in response:
                choice = event.choices[0] if event.choices else None
                if choice is not None and choice.finish_reason:
                    # "length" means the model was cut off by max_tokens. The
                    # reason belongs to this call, not to the shared provider.
                    if state is not None:
                        state.finish_reason = choice.finish_reason
                delta = choice.delta.content if choice and choice.delta else None
                if delta:
                    yield delta
        except Exception as exc:  # noqa: BLE001 - mapped to a contract error
            raise ProviderUnavailable(str(exc)) from exc
