#!/usr/bin/env python3
"""Phase 0, step 3 — turn a probe log into an R1 verdict.

    python 03_analyze.py                    # newest log in out/
    python 03_analyze.py probe-....jsonl
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

OUT = Path(__file__).parent / "out"

# What "good enough for subtitles" means. A cue is on screen for seconds, so a
# steady sub-300ms error is invisible; beyond ~600ms it reads as out of sync.
GOOD_MS = 300
ACCEPTABLE_MS = 600

POSITION_EVENTS = {"nowPlaying", "onStateChange"}


def load(path: Path) -> list[dict]:
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return records


def stage_of(records: list[dict], index: int) -> str:
    for record in reversed(records[:index]):
        if record["kind"] == "stage":
            return record.get("name", "?")
    return "start"


def describe(values: list[float], unit: str = "s") -> str:
    if not values:
        return "veri yok"
    ordered = sorted(values)
    p95 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))]
    return (
        f"n={len(values)}  medyan={statistics.median(ordered):.3f}{unit}  "
        f"ort={statistics.fmean(ordered):.3f}{unit}  p95={p95:.3f}{unit}  "
        f"max={ordered[-1]:.3f}{unit}"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("log", nargs="?", help="out/ içindeki jsonl dosyası")
    args = parser.parse_args()

    if args.log:
        path = Path(args.log)
        if not path.exists():
            path = OUT / args.log
    else:
        candidates = sorted(OUT.glob("probe-*.jsonl"))
        if not candidates:
            print("out/ içinde probe-*.jsonl yok. Önce 02_lounge_probe.py çalıştır.")
            return 2
        path = candidates[-1]

    records = load(path)
    print(f"Log: {path.name}  ({len(records)} kayıt)\n")

    # ------------------------------------------------------- event inventory
    kinds = Counter(record["kind"] for record in records)
    print("== olay sayıları " + "=" * 48)
    for kind, count in kinds.most_common():
        print(f"  {kind:26s} {count}")

    fatal = [record for record in records if record["kind"] == "fatal"]
    if fatal:
        print("\n  ! ÖLÜMCÜL HATA: " + fatal[0].get("error", ""))

    # -------------------------------------------------- natural push cadence
    print("\n== doğal olay sıklığı (komut göndermeden) " + "=" * 23)
    cadence_gaps: list[float] = []
    previous = None
    for index, record in enumerate(records):
        if record["kind"] not in POSITION_EVENTS:
            continue
        if stage_of(records, index) != "passive:cadence":
            continue
        if previous is not None:
            cadence_gaps.append(record["mono"] - previous)
        previous = record["mono"]
    print(f"  pozisyon olayları arası: {describe(cadence_gaps)}")
    if not cadence_gaps:
        print("  -> Hiç kendiliğinden olay gelmedi. Nabız atışı yok demek;")
        print("     tamamen getNowPlaying ile çapa atmaya bağımlıyız.")

    # ------------------------------------------------ getNowPlaying success
    print("\n== getNowPlaying sorgu limiti " + "=" * 35)
    by_tag: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        if record["kind"] == "cmd_getNowPlaying":
            by_tag[record.get("tag") or "?"].append(record)

    for tag in sorted(by_tag):
        commands = by_tag[tag]
        ok_count = sum(1 for command in commands if command.get("ok"))
        # An answered request is one followed by a position event within 2s.
        answered = 0
        for command in commands:
            window_end = command["mono"] + 2.0
            if any(
                other["kind"] in POSITION_EVENTS
                and command["mono"] < other["mono"] <= window_end
                for other in records
            ):
                answered += 1
        rate = 100.0 * answered / len(commands) if commands else 0.0
        print(
            f"  {tag:16s} gönderilen={len(commands):3d}  ok={ok_count:3d}  "
            f"cevaplanan={answered:3d} ({rate:5.1f}%)"
        )
    print("  ('cevaplanan' = 2 saniye içinde pozisyon olayı geldi)")

    # --------------------------------------------------------------- drift
    print("\n== interpolasyon kayması (R1 — ANA ÖLÇÜM) " + "=" * 23)
    samples: list[tuple[float, float, str]] = []  # (anchor_age, abs_error, stage)
    for index, record in enumerate(records):
        if record["kind"] not in POSITION_EVENTS:
            continue
        error = record.get("predicted_error_s")
        age = record.get("anchor_age_s")
        if error is None or age is None or age <= 0.5:
            continue
        # Only trust samples where playback was advancing normally.
        if record.get("tracker_state") != 1:
            continue
        samples.append((float(age), abs(float(error)), stage_of(records, index)))

    long_run = [s for s in samples if s[2] == "passive:long_drift"]
    pool = long_run or samples

    if not pool:
        print("  Kullanılabilir örnek yok — video oynatılmıyor olabilir.")
    else:
        errors = [error for _, error, _ in pool]
        ages = [age for age, _, _ in pool]
        print(f"  kaynak: {'uzun koşu (H)' if long_run else 'tüm aşamalar'}")
        print(f"  çapa yaşı:      {describe(ages)}")
        print(f"  |hata|:         {describe(errors)}")

        # Drift rate: error growth per second of anchor age. If this is ~0 the
        # clocks agree and only latency remains; if it is linear we must
        # re-anchor at 1/rate * tolerance.
        if len(pool) >= 3:
            rate = statistics.fmean(error / age for age, error, _ in pool if age > 0)
            print(f"  kayma hızı:     ~{rate * 1000:.1f} ms / saniye")
            if rate > 0.0005:
                budget = (ACCEPTABLE_MS / 1000.0) / rate
                print(f"  -> {ACCEPTABLE_MS}ms tolerans için en fazla {budget:.0f}s'de bir çapa gerekiyor")

    # ------------------------------------------------ pause / seek reaction
    print("\n== komut tepki gecikmesi " + "=" * 40)
    for command_stage in ("pause", "play", "seek_to", "speed_1.5", "speed_1.0"):
        latencies = []
        for index, record in enumerate(records):
            if record["kind"] != "stage" or record.get("name") != command_stage:
                continue
            for other in records[index + 1 :]:
                if other["kind"] in POSITION_EVENTS:
                    latencies.append(other["mono"] - record["mono"])
                    break
        if latencies:
            print(f"  {command_stage:12s} {describe(latencies)}")

    seeks = [
        record
        for index, record in enumerate(records)
        if record["kind"] in POSITION_EVENTS and stage_of(records, index) == "seek_to"
    ]
    if seeks:
        print(f"  seek sonrası bildirilen pozisyonlar: "
              f"{[round(r.get('event', {}).get('current_time') or -1, 1) for r in seeks][:8]}")

    # -------------------------------------------------------------- verdict
    print("\n== R1 KARARI " + "=" * 52)
    reanchor_ok = any(
        tag in ("drift:long_drift", "1s", "5s")
        and sum(1 for command in commands if command.get("ok")) >= len(commands) * 0.9
        for tag, commands in by_tag.items()
    )
    if pool:
        errors = [error for _, error, _ in pool]
        p95 = sorted(errors)[min(len(errors) - 1, int(len(errors) * 0.95))]
        p95_ms = p95 * 1000
        if p95_ms <= GOOD_MS:
            print(f"  YEŞİL — p95 kayma {p95_ms:.0f}ms. Altyazı senkronu sorunsuz.")
        elif p95_ms <= ACCEPTABLE_MS:
            print(f"  SARI — p95 kayma {p95_ms:.0f}ms. İzlenebilir; kullanıcı ayarında")
            print("         elle offset düzeltmesi koymak yeterli olur.")
        else:
            print(f"  KIRMIZI — p95 kayma {p95_ms:.0f}ms. Çıplak interpolasyon yetmiyor.")
            if reanchor_ok:
                print("         AMA getNowPlaying çalışıyor — sık çapa atarak kurtarılabilir.")
            else:
                print("         getNowPlaying da güvenilir değil. SmartTube fork'unu")
                print("         yeniden değerlendir (DESIGN.md bölüm 8).")
    print(f"  getNowPlaying ile sık çapa atılabiliyor: {'EVET' if reanchor_ok else 'HAYIR/belirsiz'}")

    print("\nSıradaki: python 04_make_subs.py --url <YouTube linki>")
    return 0


if __name__ == "__main__":
    sys.exit(main())
