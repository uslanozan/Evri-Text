"""Merge fragmented ASR cues into whole sentences.

This is the step that makes the difference between our output and YouTube's own
Turkish auto-translation. See DESIGN.md section 4.

ASR captions frequently have NO punctuation at all, so punctuation alone is not
a sufficient boundary signal — the gap, duration and length limits carry most of
the weight in practice.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .cues import Cue

SENTENCE_END_RE = re.compile(r"[.!?…]['\")\]]*$")


@dataclass
class Sentence:
    start_ms: int
    end_ms: int
    text: str
    cue_count: int


def merge_into_sentences(
    cues: list[Cue],
    *,
    max_gap_ms: int = 1200,
    max_duration_ms: int = 6000,
    # ASR input often has no punctuation at all, so this cap — not the full stop —
    # is what actually ends most groups. At 200 a Turkish line renders as 3-4 rows
    # of subtitle; 120 keeps it to the two rows a TV viewer can read in time.
    max_chars: int = 120,
) -> list[Sentence]:
    """Group cues into sentence-ish units.

    A group is closed when any of these is true:
      * the accumulated text ends with sentence-final punctuation
      * the next cue starts more than ``max_gap_ms`` after this one ends
      * the group has reached ``max_duration_ms`` or ``max_chars``
    """
    sentences: list[Sentence] = []
    buffer: list[Cue] = []

    def flush() -> None:
        if not buffer:
            return
        text = " ".join(cue.text for cue in buffer)
        text = re.sub(r"\s+", " ", text).strip()
        if text:
            sentences.append(
                Sentence(buffer[0].start_ms, buffer[-1].end_ms, text, len(buffer))
            )
        buffer.clear()

    for index, cue in enumerate(cues):
        buffer.append(cue)

        text = " ".join(item.text for item in buffer).strip()
        duration = buffer[-1].end_ms - buffer[0].start_ms

        gap_to_next = None
        if index + 1 < len(cues):
            gap_to_next = cues[index + 1].start_ms - cue.end_ms

        if SENTENCE_END_RE.search(text):
            flush()
        elif duration >= max_duration_ms or len(text) >= max_chars:
            flush()
        elif gap_to_next is not None and gap_to_next > max_gap_ms:
            flush()

    flush()
    return sentences


def sentences_to_cues(sentences: list[Sentence], translations: list[str]) -> list[Cue]:
    """Pair translated text back onto the source sentences' time ranges.

    We deliberately do NOT try to redistribute a translation across the original
    fragment cues: Turkish word order differs from English, so any such split
    would put words under the wrong timestamps.
    """
    if len(sentences) != len(translations):
        raise ValueError(
            f"sentence/translation count mismatch: "
            f"{len(sentences)} vs {len(translations)}"
        )
    return [
        Cue(sentence.start_ms, sentence.end_ms, text.strip())
        for sentence, text in zip(sentences, translations)
        if text.strip()
    ]
