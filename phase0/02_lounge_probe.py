#!/usr/bin/env python3
"""Phase 0, step 2 — automated Lounge API measurement (risk R1).

Everything here is driven by the script: pyytlounge can send play/pause/seek and
getNowPlaying, so the only manual act is typing the TV pairing code ONCE. The
pairing is then saved to lounge_auth.json and reused forever.

Runs ~11 minutes unattended and writes out/probe-<timestamp>.jsonl.

    # first time — get the code from the TV:
    #   YouTube > Ayarlar > "TV kodu ile bağla"
    python 02_lounge_probe.py --pair 123456789012 --video dQw4w9WgXcQ

    # later runs reuse the saved pairing:
    python 02_lounge_probe.py
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

from evri.lounge import (
    JsonlLog,
    PositionTracker,
    ProbeListener,
    make_api,
    subscribe_forever,
)

HERE = Path(__file__).parent
OUT = HERE / "out"
AUTH_FILE = HERE / "lounge_auth.json"

# Stage H needs ~5 minutes of uninterrupted playback after the last seek. If the
# video ends mid-probe, autoplay starts a different one and the drift samples are
# meaningless — so we need runway, and we warn loudly when there isn't any.
DRIFT_RUNWAY_S = 340.0
MIN_USEFUL_DURATION_S = 900.0


def seek_targets(duration: float | None) -> list[float]:
    """Seek destinations that still leave room for the drift stage."""
    if not duration or duration < 120:
        return [120.0, 300.0, 60.0]
    ceiling = max(30.0, duration - DRIFT_RUNWAY_S)
    return [min(target, ceiling) for target in (0.2 * duration, 0.5 * duration, 0.1 * duration)]


class Probe:
    def __init__(self, api, logger: JsonlLog, tracker: PositionTracker) -> None:
        self.api = api
        self.log = logger
        self.tracker = tracker

    def stage(self, name: str, detail: str = "") -> None:
        self.log.write("stage", name=name, detail=detail)
        elapsed = time.monotonic() - self.log.t0
        print(f"[{elapsed:6.1f}s] {name}" + (f" — {detail}" if detail else ""))

    async def refresh(self, tag: str = "") -> bool:
        """getNowPlaying: the only tool we have to re-anchor on demand.

        Logged with the send timestamp so the analyser can pair each request with
        the event it produced and measure both latency and rate limiting.
        """
        ok = await self.api.get_now_playing()
        prediction = self.tracker.predict()
        self.log.write(
            "cmd_getNowPlaying",
            ok=bool(ok),
            tag=tag,
            predicted_s=round(prediction.position_s, 3) if prediction else None,
            anchor_age_s=round(prediction.anchor_age_s, 3) if prediction else None,
        )
        return bool(ok)

    async def poll_burst(self, tag: str, interval: float, count: int) -> None:
        """Hammer getNowPlaying at a fixed rate to find the rate limit."""
        self.stage(f"poll:{tag}", f"{count} istek, {interval}s arayla")
        for _ in range(count):
            await self.refresh(tag)
            await asyncio.sleep(interval)

    async def passive(self, tag: str, seconds: float, refresh_every: float | None = None) -> None:
        """Observe without commanding — measures natural event cadence and, when
        refresh_every is set, interpolation drift over that gap."""
        self.stage(f"passive:{tag}", f"{seconds:.0f}s" + (f", her {refresh_every}s çapa" if refresh_every else ""))
        deadline = time.monotonic() + seconds
        next_refresh = time.monotonic() + refresh_every if refresh_every else None
        while time.monotonic() < deadline:
            await asyncio.sleep(0.5)
            if next_refresh and time.monotonic() >= next_refresh:
                await self.refresh(f"drift:{tag}")
                next_refresh = time.monotonic() + refresh_every

    async def run(self, video_id: str | None) -> None:
        if video_id:
            self.stage("play_video", video_id)
            await self.api.play_video(video_id)
            await asyncio.sleep(8)
        else:
            self.stage("using_current_video", "TV'de zaten oynayan video kullanılıyor")
            await self.refresh("initial")
            await asyncio.sleep(3)

        duration = self.tracker.duration
        self.log.write("video_info", video_id=self.tracker.video_id, duration_s=duration)
        if duration:
            print(f"         video süresi: {duration / 60:.1f} dk")
            if duration < MIN_USEFUL_DURATION_S:
                print()
                print("  !!  UYARI: bu video 15 dakikadan kısa.")
                print("  !!  Drift ölçümü (aşama H) 5 dakika kesintisiz oynatma istiyor.")
                print("  !!  Video biterse autoplay başka videoya geçer ve ölçüm çöp olur.")
                print("  !!  Ctrl-C ile durdur, 20+ dakikalık bir videoyla tekrar başlat.")
                print()
                await asyncio.sleep(10)
        else:
            print("         video süresi bilinmiyor — 20+ dakikalık bir video kullandığından emin ol")

        # A — natural cadence. How often does the TV push position unprompted?
        await self.passive("cadence", 90)

        # B/C/D — rate-limit ladder. Where does getNowPlaying start failing?
        await self.poll_burst("5s", 5.0, 6)
        await self.poll_burst("1s", 1.0, 20)
        await self.poll_burst("0.4s", 0.4, 25)
        await asyncio.sleep(5)

        # E — pause/resume event latency, and whether position freezes correctly.
        self.stage("pause")
        await self.api.pause()
        await asyncio.sleep(6)
        await self.refresh("while_paused")
        await asyncio.sleep(2)
        self.stage("play")
        await self.api.play()
        await asyncio.sleep(6)

        # F — seeks. Does the pushed position match what we asked for?
        for target in seek_targets(self.tracker.duration):
            self.stage("seek_to", f"{target:.0f}s")
            await self.api.seek_to(target)
            await asyncio.sleep(6)
            await self.refresh(f"after_seek_{target:.0f}")
            await asyncio.sleep(2)

        # G — playback speed. Known weak spot in iSponsorBlockTV (issue #268).
        self.stage("speed_1.5")
        await self.api.set_playback_speed(1.5)
        await asyncio.sleep(20)
        await self.refresh("speed_1.5")
        await asyncio.sleep(2)
        self.stage("speed_1.0")
        await self.api.set_playback_speed(1.0)
        await asyncio.sleep(8)

        # H — THE measurement. Long uninterrupted run, re-anchoring every 20s.
        # Each anchor yields one drift sample over a realistic gap.
        await self.passive("long_drift", 300, refresh_every=20)

        self.stage("done")


async def main() -> int:
    load_dotenv()
    parser = argparse.ArgumentParser()
    parser.add_argument("--pair", help="TV eşleştirme kodu (sadece ilk kez)")
    parser.add_argument(
        "--video",
        help="Test videosu ID'si. Verilmezse TV'de oynayan video kullanılır. "
        "İngilizce altyazısı olan, 15+ dakikalık bir video seç.",
    )
    parser.add_argument("--name", default="Evri-Text probe")
    args = parser.parse_args()

    if not args.pair and not AUTH_FILE.exists():
        print("Kayıtlı eşleştirme yok.")
        print("TV'de: YouTube > Ayarlar > 'TV kodu ile bağla' -> 12 haneli kodu al")
        print("Sonra: python 02_lounge_probe.py --pair <KOD> --video <VIDEO_ID>")
        return 2

    log_path = OUT / f"probe-{time.strftime('%Y%m%d-%H%M%S')}.jsonl"
    logger = JsonlLog(log_path)
    tracker = PositionTracker()
    listener = ProbeListener(logger, tracker)

    print(f"Log: {log_path}")
    api = None
    subscription = None
    try:
        api = await make_api(
            listener, auth_file=AUTH_FILE, pairing_code=args.pair, device_name=args.name
        )
        logger.write("connected", device_name=args.name)
        print("Bağlandı. Test başlıyor — ~11 dakika, müdahale gerekmiyor.\n")

        subscription = asyncio.create_task(subscribe_forever(api, logger=logger))
        await asyncio.sleep(2)

        await Probe(api, logger, tracker).run(args.video)

    except Exception as exc:  # noqa: BLE001
        logger.write("fatal", error=str(exc), type=type(exc).__name__)
        print(f"\nHATA: {exc}")
        return 1
    finally:
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

    print(f"\nBitti. Şimdi: python 03_analyze.py {log_path.name}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(130)
