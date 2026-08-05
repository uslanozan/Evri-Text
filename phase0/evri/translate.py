"""Translation providers.

Mirrors ``interface TranslationProvider`` in DESIGN.md section 5 so the Phase 1
Kotlin port is a direct translation.
"""

from __future__ import annotations

import json
import logging
import re
import time
from abc import ABC, abstractmethod
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

log = logging.getLogger(__name__)

PROMPT_VERSION = "v2"


@dataclass
class TranslationContext:
    """Extra signal that measurably improves quality on ASR input."""

    video_title: str = ""
    before: list[str] = field(default_factory=list)
    after: list[str] = field(default_factory=list)


class TranslationProvider(ABC):
    id: str = "abstract"
    display_name: str = "Abstract"

    @abstractmethod
    def translate(
        self,
        sentences: list[str],
        source_lang: str | None,
        target_lang: str,
        context: TranslationContext,
    ) -> list[str]:
        """Return one translation per input sentence, same order, 1:1."""


SYSTEM_INSTRUCTION = """\
You translate video subtitles from {source} into {target}.

The input is automatic speech recognition (ASR) output. It usually has misheard
words, wrong or entirely missing punctuation, and no capitalisation. Use the
surrounding context to work out what was actually said, silently fix obvious
recognition errors, then translate.

Rules:
- Translate naturally and idiomatically. Never translate word by word.
- Preserve register and tone: casual speech stays casual, slang stays slang.
- Keep proper nouns, brand names and established technical terms in their
  original form whenever that is what a native {target} speaker would actually
  say.
- Keep each translation short enough to read comfortably as a TV subtitle.
- Return EXACTLY one translation per input item, in the same order, preserving
  each item's "i" index. Never merge, split, add or drop items.
- Reply with nothing but a JSON array of {{"i": <int>, "tr": "<translation>"}}.
"""


class GeminiTranslationProvider(TranslationProvider):
    id = "gemini"
    display_name = "Google Gemini"

    def __init__(
        self,
        api_key: str,
        model: str = "gemini-2.5-flash-lite",
        *,
        max_attempts: int = 3,
    ) -> None:
        from google import genai

        self._client = genai.Client(api_key=api_key)
        self.model = model
        self.max_attempts = max_attempts

    def translate(
        self,
        sentences: list[str],
        source_lang: str | None,
        target_lang: str,
        context: TranslationContext,
    ) -> list[str]:
        from google.genai import types

        if not sentences:
            return []

        system = SYSTEM_INSTRUCTION.format(
            source=source_lang or "the source language (detect it)",
            target=target_lang,
        )
        payload = {
            "video_title": context.video_title,
            "preceding_context": context.before,
            "following_context": context.after,
            # Deliberately NOT "text": models mirror the input key name back and
            # answer with {"i", "text"} instead of {"i", "tr"}.
            "items": [{"i": i, "source": s} for i, s in enumerate(sentences)],
        }
        config = types.GenerateContentConfig(
            system_instruction=system,
            response_mime_type="application/json",
            temperature=0.3,
        )

        last_error: Exception | None = None
        best: list[str | None] = [None] * len(sentences)

        for attempt in range(1, self.max_attempts + 1):
            try:
                response = self._client.models.generate_content(
                    model=self.model,
                    contents=json.dumps(payload, ensure_ascii=False),
                    config=config,
                )
                parsed = _parse_indexed_json(response.text, len(sentences))
                for i, value in enumerate(parsed):
                    if best[i] is None and value is not None:
                        best[i] = value
                if all(value is not None for value in best):
                    return [value for value in best if value is not None]
                log.info(
                    "attempt %d returned %d/%d items, retrying",
                    attempt,
                    sum(value is not None for value in best),
                    len(sentences),
                )
            except Exception as exc:  # noqa: BLE001 - provider errors vary widely
                last_error = exc
                log.warning(
                    "gemini translate attempt %d/%d failed: %s",
                    attempt,
                    self.max_attempts,
                    str(exc)[:160],
                )

            if attempt < self.max_attempts:
                time.sleep(_retry_delay_s(last_error, attempt))

        translated = sum(value is not None for value in best)
        if translated == 0:
            raise RuntimeError(
                f"translation failed after {self.max_attempts} attempts"
            ) from last_error

        # Partial success beats discarding sixty good lines over one bad index.
        log.warning("keeping partial chunk: %d/%d translated", translated, len(sentences))
        return [
            value if value is not None else sentences[i] for i, value in enumerate(best)
        ]


def _retry_delay_s(error: Exception | None, attempt: int) -> float:
    """Honour the server's own retryDelay on 429, else back off exponentially.

    The Gemini free tier allows 15 requests/minute; a burst of chunks trips it
    instantly and the reply carries the exact wait, so use it.
    """
    text = str(error) if error else ""
    if "RESOURCE_EXHAUSTED" in text or "429" in text:
        match = re.search(r"[Rr]etry in ([\d.]+)s|'retryDelay': '(\d+)s'", text)
        if match:
            seconds = float(match.group(1) or match.group(2))
            return min(seconds + 1.0, 65.0)
        return 30.0
    return min(2.0 * attempt, 10.0)


def _parse_indexed_json(raw: str | None, expected: int) -> list[str | None]:
    """Parse the model's reply into ``expected`` slots.

    Slots the model failed to return come back as ``None`` so the caller can fill
    just those from the source text — dropping one item out of sixty should not
    cost the whole chunk its translation.
    """
    if not raw:
        raise ValueError("empty response")

    text = raw.strip()
    # Tolerate a stray markdown fence even though we asked for raw JSON.
    fence = re.match(r"^```(?:json)?\s*(.*?)\s*```$", text, re.DOTALL)
    if fence:
        text = fence.group(1)

    # raw_decode instead of loads: models sometimes append a second array or a
    # trailing note after the JSON, which makes loads raise "Extra data" and
    # throws away a reply that was otherwise complete.
    data, end = json.JSONDecoder().raw_decode(text)
    trailing = text[end:].strip()
    if trailing:
        log.debug("ignoring %d trailing chars after JSON", len(trailing))
    if isinstance(data, dict):
        for key in ("items", "translations", "result"):
            if key in data:
                data = data[key]
                break
    if not isinstance(data, list):
        raise ValueError(f"expected a JSON array, got {type(data).__name__}")

    out: list[str | None] = [None] * expected
    for entry in data:
        if not isinstance(entry, dict):
            raise ValueError("array items must be objects")
        index = int(entry["i"])
        if not 0 <= index < expected:
            raise ValueError(f"index {index} out of range")
        # "tr" is what the prompt asks for; the rest are what models actually
        # produce often enough to be worth accepting rather than failing on.
        for field_name in ("tr", "translation", "text", "t"):
            value = entry.get(field_name)
            if isinstance(value, str) and value.strip():
                out[index] = value
                break
        else:
            log.debug("item %d has no translation field: %s", index, sorted(entry))

    if all(value is None for value in out):
        raise ValueError("no usable translations in response")

    return out


def translate_in_parallel(
    provider: TranslationProvider,
    sentences: list[str],
    *,
    source_lang: str | None,
    target_lang: str,
    video_title: str = "",
    chunk_size: int = 60,
    context_size: int = 3,
    max_workers: int = 8,
) -> tuple[list[str], list[int]]:
    """Translate in concurrent chunks.

    This is the latency trick from DESIGN.md section 6: wall-clock time is one
    chunk's latency rather than the sum of all of them. Each chunk also gets a
    few neighbouring sentences as context so chunk seams don't read as breaks.

    Returns ``(translations, failed_chunk_indices)``. A failed chunk falls back
    to its source text so one bad chunk cannot sink the whole video.
    """
    if not sentences:
        return [], []

    chunks = [
        (start, sentences[start : start + chunk_size])
        for start in range(0, len(sentences), chunk_size)
    ]

    def run(job: tuple[int, list[str]]) -> tuple[int, list[str], bool]:
        start, items = job
        context = TranslationContext(
            video_title=video_title,
            before=sentences[max(0, start - context_size) : start],
            after=sentences[start + len(items) : start + len(items) + context_size],
        )
        try:
            return start, provider.translate(items, source_lang, target_lang, context), True
        except Exception as exc:  # noqa: BLE001
            log.error("chunk at %d failed, falling back to source text: %s", start, exc)
            return start, list(items), False

    results: list[str | None] = [None] * len(sentences)
    failed: list[int] = []

    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        for start, translated, ok in pool.map(run, chunks):
            if not ok:
                failed.append(start)
            for offset, text in enumerate(translated):
                results[start + offset] = text

    return [value or "" for value in results], failed
