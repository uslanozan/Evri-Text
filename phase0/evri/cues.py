"""Subtitle cue model, WebVTT parsing, SRT/VTT writing.

YouTube's auto-generated (ASR) VTT is messy in three specific ways that this
module exists to clean up:

1. Inline word-level timing tags:  ``hello<00:00:00.539> and<00:00:00.900>``
2. Tiny "bridge" cues of ~10ms that carry no new text
3. A rolling window, where each cue repeats the tail of the previous one

Leaving any of these in place wrecks the sentence merging downstream.
"""

from __future__ import annotations

import html
import re
from dataclasses import dataclass

TIMING_RE = re.compile(
    r"(?P<start>(?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})"
    r"\s*-->\s*"
    r"(?P<end>(?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})"
)
TAG_RE = re.compile(r"<[^>]*>")
BLOCK_KEYWORDS = ("NOTE", "STYLE", "REGION")


@dataclass
class Cue:
    """One subtitle cue. Mirrors ``data class Cue`` in DESIGN.md."""

    start_ms: int
    end_ms: int
    text: str

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms


def parse_timestamp(value: str) -> int:
    """``HH:MM:SS.mmm`` or ``MM:SS.mmm`` -> milliseconds."""
    value = value.strip().replace(",", ".")
    parts = value.split(":")
    if len(parts) == 3:
        hours, minutes, seconds = parts
    elif len(parts) == 2:
        hours, minutes, seconds = "0", parts[0], parts[1]
    else:
        raise ValueError(f"unrecognised timestamp: {value!r}")
    total = int(hours) * 3600 + int(minutes) * 60 + float(seconds)
    return int(round(total * 1000))


def format_timestamp(ms: int, *, sep: str = ",") -> str:
    ms = max(0, ms)
    hours, rem = divmod(ms, 3_600_000)
    minutes, rem = divmod(rem, 60_000)
    seconds, millis = divmod(rem, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}{sep}{millis:03d}"


def _clean_text(raw: str) -> list[str]:
    """Strip tags/entities, return non-empty lines."""
    text = TAG_RE.sub("", raw)
    text = html.unescape(text).replace(" ", " ")
    lines = []
    for line in text.split("\n"):
        line = re.sub(r"\s+", " ", line).strip()
        if line:
            lines.append(line)
    return lines


def parse_vtt(content: str) -> list[Cue]:
    """Parse WebVTT (or SRT — the timing line is close enough) into raw cues."""
    cues: list[Cue] = []
    lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    i = 0
    while i < len(lines):
        line = lines[i].strip()

        # Skip NOTE / STYLE / REGION blocks entirely.
        if any(line.startswith(kw) for kw in BLOCK_KEYWORDS):
            i += 1
            while i < len(lines) and lines[i].strip():
                i += 1
            continue

        match = TIMING_RE.search(line)
        if not match:
            i += 1
            continue

        start_ms = parse_timestamp(match.group("start"))
        end_ms = parse_timestamp(match.group("end"))

        i += 1
        body: list[str] = []
        while i < len(lines) and lines[i].strip():
            # A new timing line means the previous cue had no blank separator.
            if TIMING_RE.search(lines[i]):
                break
            body.append(lines[i])
            i += 1

        text_lines = _clean_text("\n".join(body))
        if text_lines:
            cues.append(Cue(start_ms, end_ms, "\n".join(text_lines)))

    return cues


def dedupe_rolling(cues: list[Cue], *, min_duration_ms: int = 50) -> list[Cue]:
    """Undo YouTube's rolling-window ASR format.

    Drops the ~10ms bridge cues, then removes each cue's leading lines when they
    were already emitted by the cue before it.
    """
    result: list[Cue] = []
    previous_lines: list[str] = []

    for cue in cues:
        if cue.duration_ms < min_duration_ms:
            continue

        lines = [line for line in cue.text.split("\n") if line]

        index = 0
        while index < len(lines) and lines[index] in previous_lines:
            index += 1
        fresh = lines[index:]

        previous_lines = lines
        if not fresh:
            continue

        result.append(Cue(cue.start_ms, cue.end_ms, " ".join(fresh)))

    return result


def load_vtt(path) -> list[Cue]:
    """Read a VTT file and return cleaned, de-duplicated cues."""
    from pathlib import Path

    content = Path(path).read_text(encoding="utf-8", errors="replace")
    return dedupe_rolling(parse_vtt(content))


def write_srt(cues: list[Cue], path) -> None:
    from pathlib import Path

    blocks = []
    for index, cue in enumerate(cues, start=1):
        start = format_timestamp(cue.start_ms, sep=",")
        end = format_timestamp(cue.end_ms, sep=",")
        blocks.append(f"{index}\n{start} --> {end}\n{cue.text}\n")
    Path(path).write_text("\n".join(blocks), encoding="utf-8")


def write_vtt(cues: list[Cue], path) -> None:
    from pathlib import Path

    blocks = ["WEBVTT\n"]
    for cue in cues:
        start = format_timestamp(cue.start_ms, sep=".")
        end = format_timestamp(cue.end_ms, sep=".")
        blocks.append(f"{start} --> {end}\n{cue.text}\n")
    Path(path).write_text("\n".join(blocks), encoding="utf-8")


def cue_at(cues: list[Cue], position_ms: int) -> Cue | None:
    """Cue active at ``position_ms``, or None. Assumes cues are sorted."""
    low, high = 0, len(cues) - 1
    while low <= high:
        mid = (low + high) // 2
        cue = cues[mid]
        if position_ms < cue.start_ms:
            high = mid - 1
        elif position_ms >= cue.end_ms:
            low = mid + 1
        else:
            return cue
    return None
