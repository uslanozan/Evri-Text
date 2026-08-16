#!/usr/bin/env python3
"""Phase 2 groundwork — measure Gemini's audio transcription against a known truth.

    python 06_stt_probe.py                      # three windows, timestamp drift
    python 06_stt_probe.py --window 2700 120    # one window, 120s from 45:00

R6 says Gemini's audio timestamps drift, but nobody has measured by how much on a
real file. We can, because this video *does* have YouTube ASR captions: we treat it
as if it had none, transcribe the audio, then align the result back onto the real
captions and read off the error.

The last window matters most — if drift accumulates, the end of a 98-minute film is
where it shows.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import statistics
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

from evri.cues import Cue, load_vtt

HERE = Path(__file__).parent
AUDIO = HERE / "out" / "audio" / "sx8pViXxZQg.m4a"
REFERENCE = HERE / "out" / "raw" / "sx8pViXxZQg.en.vtt"

# Start, middle, end. Drift that accumulates will be obvious across these.
DEFAULT_WINDOWS = [(60, 120), (2700, 120), (5700, 120)]

PROMPT = """\
Transcribe the speech in this audio for the time ranges listed below.

For each range, return every utterance you hear with its start and end time,
measured from the beginning of the audio file.

Ranges (seconds from start):
{ranges}

Rules:
- Timestamps are absolute positions in the file, in seconds, as numbers.
- Transcribe what is said, in the original language. Do not translate.
- Ignore music and sound effects; speech only.
- Reply with nothing but a JSON array of
  {{"start": <seconds>, "end": <seconds>, "text": "<utterance>"}}
"""


ITEM_RE = re.compile(r"\{[^{}]*\}")


def parse_items(raw: str) -> list[dict]:
    """Read the reply, salvaging whatever survived if it was cut off.

    A long transcript can hit the output token ceiling mid-array. The lines before
    the cut are still perfectly good measurements, and throwing them away would mean
    paying for the audio again to learn the same thing.
    """
    text = (raw or "").strip()
    try:
        data = json.loads(text)
        if isinstance(data, list):
            return [i for i in data if isinstance(i, dict)]
    except json.JSONDecodeError:
        pass

    items = []
    for match in ITEM_RE.finditer(text):
        try:
            items.append(json.loads(match.group(0)))
        except json.JSONDecodeError:
            continue
    if items:
        print(f"  ! cevap kesilmiş, {len(items)} tam satır kurtarıldı\n")
    return items


def hhmmss(seconds: float) -> str:
    seconds = max(0, int(seconds))
    return f"{seconds // 3600:02d}:{(seconds % 3600) // 60:02d}:{seconds % 60:02d}"


def normalise(text: str) -> str:
    return re.sub(r"[^a-z0-9 ]", "", text.lower()).strip()


def reference_in(cues: list[Cue], start_s: float, length_s: float) -> list[Cue]:
    start_ms, end_ms = start_s * 1000, (start_s + length_s) * 1000
    return [c for c in cues if c.start_ms < end_ms and c.end_ms > start_ms]


def match_offsets(stt: list[dict], reference: list[Cue]) -> list[tuple[float, str, str]]:
    """Pair each transcribed line with the reference line it most resembles.

    Matching on text rather than time is the whole point: if we matched by time we
    would be assuming the answer we are trying to measure.
    """
    pairs = []
    ref_texts = [normalise(c.text) for c in reference]
    for item in stt:
        text = normalise(str(item.get("text", "")))
        # Short lines match almost anything: "It's a time share." scored 0.6 against
        # "to sell you that time share" and produced a 24-second phantom offset.
        if len(text) < 20:
            continue
        best = difflib.get_close_matches(text, ref_texts, n=1, cutoff=0.8)
        if not best:
            continue
        cue = reference[ref_texts.index(best[0])]
        offset = float(item["start"]) - cue.start_ms / 1000
        pairs.append((offset, str(item.get("text", "")), cue.text))
    return pairs


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=os.getenv("STT_MODEL", "gemini-3.5-flash-lite"))
    parser.add_argument(
        "--window",
        nargs=2,
        type=float,
        metavar=("START_S", "LENGTH_S"),
        action="append",
        help="Repeatable. Defaults to three windows across the film.",
    )
    args = parser.parse_args()
    load_dotenv(HERE / ".env")

    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("GEMINI_API_KEY yok.")
        return 2
    if not AUDIO.exists():
        print(f"Ses dosyası yok: {AUDIO}")
        print('  yt-dlp -f 139 -o "out/audio/%(id)s.%(ext)s" "https://youtu.be/sx8pViXxZQg"')
        return 2

    windows = [tuple(w) for w in (args.window or DEFAULT_WINDOWS)]
    reference = load_vtt(REFERENCE)
    print(f"referans: {len(reference)} cue, {REFERENCE.name}")
    print(f"ses:      {AUDIO.stat().st_size / 1e6:.1f} MB, model {args.model}\n")

    from google import genai
    from google.genai import types

    client = genai.Client(api_key=api_key)

    print("ses yükleniyor…")
    started = time.monotonic()
    uploaded = client.files.upload(file=AUDIO)
    while uploaded.state.name == "PROCESSING":
        time.sleep(2)
        uploaded = client.files.get(name=uploaded.name)
    print(f"  yüklendi ({time.monotonic() - started:.1f}s), durum {uploaded.state.name}\n")

    ranges = "\n".join(
        f"- {hhmmss(s)} to {hhmmss(s + n)}  ({s:.0f}s to {s + n:.0f}s)" for s, n in windows
    )
    started = time.monotonic()
    response = client.models.generate_content(
        model=args.model,
        contents=[uploaded, PROMPT.format(ranges=ranges)],
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            # A two-minute window is ~40 utterances; three of them overrun the
            # default ceiling and the array gets cut mid-object.
            max_output_tokens=32768,
        ),
    )
    elapsed = time.monotonic() - started
    print(f"transkript geldi ({elapsed:.1f}s)\n")

    items = parse_items(response.text)
    if not items:
        print("Hiçbir satır ayrıştırılamadı.")
        print(response.text[:800])
        return 1

    out_path = HERE / "out" / "stt-probe.json"
    out_path.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")

    print("== pencere başına zaman damgası hatası ==========================")
    all_offsets: list[float] = []
    for start_s, length_s in windows:
        window_items = [
            i for i in items
            if isinstance(i.get("start"), (int, float))
            and start_s - 30 <= float(i["start"]) <= start_s + length_s + 30
        ]
        pairs = match_offsets(window_items, reference_in(reference, start_s, length_s))
        print(f"\n  {hhmmss(start_s)}  —  {len(window_items)} satır, {len(pairs)} eşleşti")
        if not pairs:
            print("    eşleşme yok (transkript bu pencereyi kaçırmış olabilir)")
            continue
        offsets = [p[0] for p in pairs]
        all_offsets.extend(offsets)
        print(
            f"    medyan {statistics.median(offsets):+.2f}s   "
            f"ort {statistics.fmean(offsets):+.2f}s   "
            f"min {min(offsets):+.2f}s   max {max(offsets):+.2f}s"
        )
        for offset, said, ref in pairs[:2]:
            print(f"    {offset:+6.2f}s  STT: {said[:60]}")
            print(f"             REF: {ref[:60]}")

    if all_offsets:
        ordered = sorted(abs(o) for o in all_offsets)
        p95 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]
        print("\n== R6 KARARI ===================================================")
        print(f"  n={len(ordered)}  |hata| medyan={statistics.median(ordered):.2f}s  p95={p95:.2f}s")
        if p95 < 0.6:
            print("  YEŞİL — altyazı için doğrudan kullanılabilir.")
        elif p95 < 2.0:
            print("  SARI — kısa parçalara bölüp her parçanın offset'ini bilmek şart.")
        else:
            print("  KIRMIZI — damgalar güvenilmez, parçalama zorunlu ve parça kısa olmalı.")

    print(f"\nHam çıktı: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
