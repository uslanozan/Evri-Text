#!/usr/bin/env python3
"""Phase 0, step 4 — generate Turkish subtitles and survey caption availability.

    # one video -> out/subs/<id>.tr.gemini.v1.srt
    python 04_make_subs.py --url "https://www.youtube.com/watch?v=..."

    # cheap prompt iteration (first 20 sentences only)
    python 04_make_subs.py --url ... --limit 20

    # availability survey: which of your real videos actually need STT?
    python 04_make_subs.py --survey videos.txt
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

from evri import captions as captions_mod
from evri.pipeline import build_subtitles, cache_key
from evri.translate import GeminiTranslationProvider

OUT = Path(__file__).parent / "out"


def survey(paths_file: Path) -> int:
    """Report the caption situation across many videos.

    This is the number that decides how much the STT path matters
    (DESIGN.md section 2.3).
    """
    urls = [
        line.strip()
        for line in paths_file.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    print(f"{len(urls)} video inceleniyor...\n")

    buckets = {"manual_tr": 0, "manual_en": 0, "auto_only": 0, "none": 0, "error": 0}
    for url in urls:
        try:
            info = captions_mod.probe(url)
        except Exception as exc:  # noqa: BLE001
            buckets["error"] += 1
            print(f"  HATA   {url}  ({exc})")
            continue

        if info.manual_track("tr"):
            bucket = "manual_tr"
        elif info.manual_track("en"):
            bucket = "manual_en"
        elif info.automatic:
            bucket = "auto_only"
        else:
            bucket = "none"
        buckets[bucket] += 1
        print(f"  {bucket:10s} {info.title[:60]}")

    total = max(1, len(urls))
    print("\n== özet " + "=" * 55)
    labels = {
        "manual_tr": "elle yazılmış Türkçe var -> hiçbir şey yapmaya gerek yok",
        "manual_en": "elle yazılmış İngilizce -> ÇEVİRİ YOLU (en iyi kalite)",
        "auto_only": "sadece otomatik (ASR)  -> ÇEVİRİ YOLU",
        "none": "hiç altyazı yok        -> STT YOLU gerekiyor",
        "error": "incelenemedi",
    }
    for key, count in buckets.items():
        print(f"  {count:3d} ({100 * count / total:5.1f}%)  {labels[key]}")

    needs_stt = buckets["none"]
    print()
    if needs_stt == 0:
        print("  -> STT yolu ACİL DEĞİL. Phase 1'i sadece çeviri yoluyla yapabiliriz.")
    elif needs_stt / total < 0.2:
        print(f"  -> Videoların %{100 * needs_stt / total:.0f}'i STT gerektiriyor. Phase 2'de ele alınır.")
    else:
        print(f"  -> Videoların %{100 * needs_stt / total:.0f}'i STT gerektiriyor. STT'yi öne almalıyız.")
    return 0


def main() -> int:
    load_dotenv()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    parser = argparse.ArgumentParser()
    parser.add_argument("--url", help="YouTube linki veya video ID'si")
    parser.add_argument("--survey", help="Her satırda bir link olan dosya")
    parser.add_argument("--target", default=os.getenv("TARGET_LANG", "tr"))
    parser.add_argument("--source", default=None, help="Varsayılan: otomatik")
    parser.add_argument("--model", default=os.getenv("GEMINI_MODEL", "gemini-2.5-flash-lite"))
    parser.add_argument("--limit", type=int, help="Sadece ilk N cümle (ucuz test)")
    parser.add_argument("--chunk-size", type=int, default=60, help="Cümle / chunk")
    # 3, not 8: the Gemini free tier caps at 15 requests/minute and a wider fan-out
    # spends the whole budget on 429s and retries. Raise it on a paid key.
    parser.add_argument("--workers", type=int, default=3, help="Paralel chunk sayısı")
    parser.add_argument("--no-cache", action="store_true")
    parser.add_argument("--force", action="store_true",
                        help="Türkçe altyazısı olsa bile yine üret")
    args = parser.parse_args()

    if args.survey:
        return survey(Path(args.survey))

    if not args.url:
        parser.error("--url veya --survey gerekli")

    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("GEMINI_API_KEY yok. .env.example'ı .env olarak kopyala ve doldur.")
        print("Key: https://aistudio.google.com/apikey")
        return 2

    provider = GeminiTranslationProvider(api_key, model=args.model)
    result = build_subtitles(
        args.url,
        provider,
        target_lang=args.target,
        source_lang=args.source,
        out_dir=OUT,
        chunk_size=args.chunk_size,
        max_workers=args.workers,
        limit=args.limit,
        use_cache=not args.no_cache,
        skip_if_manual_target=not args.force,
    )

    print()
    print(f"  video      {result.video_id} — {result.title[:60]}")
    print(f"  süre       {result.duration_s / 60:.1f} dk")

    if result.skipped_reason:
        print(f"  ATLANDI    {result.skipped_reason}")
        return 0

    print(f"  kaynak     {result.source_lang} ({'otomatik/ASR' if result.source_was_auto else 'elle yazılmış'})")
    print(f"  cue        {result.raw_cue_count} parça -> {result.sentence_count} cümle")
    print(f"  çeviri     {len(result.cues)} altyazı satırı")
    if result.from_cache:
        print("  önbellek   HIT — sıfır gecikme, sıfır maliyet")
    else:
        print(f"  süre       çekme {result.elapsed_fetch_s:.1f}s + çeviri {result.elapsed_translate_s:.1f}s "
              f"= {result.elapsed_total_s:.1f}s")
    if result.failed_chunks:
        print(f"  ! {len(result.failed_chunks)} chunk çevrilemedi, kaynak metin bırakıldı")

    key = cache_key(result.video_id, args.target, provider.id)
    print(f"\n  SRT: out/subs/{key}.srt")
    print("\nÖnce sen bak, sonra anneye göster:")
    print(f'  1. VLC ile videoyu aç, Altyazı > Altyazı dosyası ekle -> yukarıdaki .srt')
    print(f"  2. Rastgele 3-4 yerden kontrol et: cümleler tam mı, Türkçe doğal mı?")
    print(f"  3. Sonra: python 05_live_demo.py  (TV'de canlı test)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
