#!/usr/bin/env python3
"""Phase 0, step 5 — the whole product, running from the PC.

Watches the TV over the Lounge API, generates Turkish subtitles for whatever
starts playing, and renders them on top of the official YouTube app through
TvOverlay. If this feels in sync while your parents watch, Phase 1 is just the
same logic moved on-device.

    python 05_live_demo.py

Ctrl-C to stop. Start/stop/seek videos with the normal remote — it follows along.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

from evri.cues import cue_at
from evri.lounge import (
    JsonlLog,
    PositionTracker,
    ProbeListener,
    make_api,
    subscribe_forever,
)
from evri.overlay import TvOverlay
from evri.pipeline import build_subtitles
from evri.translate import GeminiTranslationProvider

HERE = Path(__file__).parent
OUT = HERE / "out"
AUTH_FILE = HERE / "lounge_auth.json"

log = logging.getLogger("live")

TICK_S = 0.15  # how often we recompute the on-screen cue


class LiveSubtitles:
    def __init__(self, overlay: TvOverlay, tracker: PositionTracker, provider, args) -> None:
        self.overlay = overlay
        self.tracker = tracker
        self.provider = provider
        self.args = args
        self.cues: list = []
        self.current_video: str | None = None
        self.shown_text: str | None = None
        self.building = False

    async def on_video(self, video_id: str) -> None:
        """New video started — build subtitles for it without blocking the loop."""
        if video_id == self.current_video or self.building:
            return
        self.current_video = video_id
        self.cues = []
        await self.clear()
        self.building = True

        self.overlay.notify("Altyazı hazırlanıyor…", title="Evri-Text", seconds=6)
        started = time.monotonic()
        try:
            result = await asyncio.to_thread(
                build_subtitles,
                video_id,
                self.provider,
                target_lang=self.args.target,
                out_dir=OUT,
                chunk_size=self.args.chunk_size,
                max_workers=self.args.workers,
                skip_if_manual_target=not self.args.force,
            )
        except Exception as exc:  # noqa: BLE001
            log.error("subtitle build failed for %s: %s", video_id, exc)
            self.overlay.notify(f"Altyazı üretilemedi: {exc}", title="Evri-Text", seconds=8)
            self.building = False
            return

        self.building = False
        elapsed = time.monotonic() - started

        if result.skipped_reason:
            log.info("skipped %s: %s", video_id, result.skipped_reason)
            self.overlay.notify(result.skipped_reason, title="Evri-Text", seconds=6)
            return

        self.cues = result.cues
        log.info(
            "%s: %d cues ready in %.1fs (%s)",
            video_id,
            len(self.cues),
            elapsed,
            "cache" if result.from_cache else "fresh",
        )
        self.overlay.notify(
            f"Hazır: {len(self.cues)} satır, {elapsed:.1f}s"
            + (" (önbellek)" if result.from_cache else ""),
            title="Evri-Text",
            seconds=4,
        )

    async def clear(self) -> None:
        if self.shown_text is not None:
            self.overlay.hide_fixed()
            self.shown_text = None

    async def tick(self) -> None:
        if not self.cues:
            return

        # Hide during ads and while paused: position means nothing then (R5).
        if self.tracker.in_ad:
            await self.clear()
            return
        if not self.tracker.advancing:
            return

        prediction = self.tracker.predict()
        if prediction is None:
            return

        position_ms = int((prediction.position_s + self.args.offset) * 1000)
        cue = cue_at(self.cues, position_ms)
        text = cue.text if cue else None

        if text != self.shown_text:
            if text:
                self.overlay.show_fixed(text)
            else:
                self.overlay.hide_fixed()
            self.shown_text = text


async def main() -> int:
    load_dotenv()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    parser = argparse.ArgumentParser()
    parser.add_argument("--pair", help="TV eşleştirme kodu (sadece ilk kez)")
    parser.add_argument("--ip", default=os.getenv("TV_IP", ""), help="Mi Box S IP")
    parser.add_argument("--target", default=os.getenv("TARGET_LANG", "tr"))
    parser.add_argument("--model", default=os.getenv("GEMINI_MODEL", "gemini-2.5-flash-lite"))
    parser.add_argument("--chunk-size", type=int, default=60)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--force", action="store_true")
    parser.add_argument(
        "--reanchor", type=float, default=15.0,
        help="Kaç saniyede bir getNowPlaying ile pozisyonu tazele (0 = kapalı)",
    )
    parser.add_argument(
        "--offset", type=float, default=0.0,
        help="Altyazıyı kaydır (saniye). Pozitif = altyazı ileri gider.",
    )
    args = parser.parse_args()

    if not args.ip:
        print("TV_IP gerekli (.env veya --ip). TvOverlay'in HTTP portu için lazım.")
        return 2
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("GEMINI_API_KEY yok. .env dosyasını doldur.")
        return 2

    overlay = TvOverlay(args.ip)
    if not overlay.reachable():
        print(f"TvOverlay http://{args.ip}:5001 adresinde cevap vermiyor.")
        print("01_preflight.py --install-tvoverlay ile kur ve TV'de bir kez aç.")
        return 2

    logger = JsonlLog(OUT / f"live-{time.strftime('%Y%m%d-%H%M%S')}.jsonl")
    tracker = PositionTracker()
    listener = ProbeListener(logger, tracker)
    provider = GeminiTranslationProvider(api_key, model=args.model)
    live = LiveSubtitles(overlay, tracker, provider, args)
    listener.on_now_playing = live.on_video

    api = None
    subscription = None
    try:
        api = await make_api(
            listener, auth_file=AUTH_FILE, pairing_code=args.pair, device_name="Evri-Text"
        )
        subscription = asyncio.create_task(subscribe_forever(api, logger=logger))
        await asyncio.sleep(1)
        await api.get_now_playing()

        print("Çalışıyor. TV'de normal kumandayla video aç. Durdurmak için Ctrl-C.\n")
        next_reanchor = time.monotonic() + args.reanchor if args.reanchor > 0 else None

        while True:
            await live.tick()
            if next_reanchor and time.monotonic() >= next_reanchor:
                await api.get_now_playing()
                next_reanchor = time.monotonic() + args.reanchor
            await asyncio.sleep(TICK_S)

    except (KeyboardInterrupt, asyncio.CancelledError):
        print("\nKapatılıyor…")
        return 0
    except Exception as exc:  # noqa: BLE001
        logger.write("fatal", error=str(exc), type=type(exc).__name__)
        print(f"HATA: {exc}")
        return 1
    finally:
        overlay.hide_fixed()
        if subscription is not None:
            subscription.cancel()
            try:
                await subscription
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        if api is not None:
            try:
                await api.close()
            except Exception:  # noqa: BLE001
                pass
        logger.close()


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(130)
