"""YouTube Lounge API plumbing: pairing persistence and event dispatch.

The position maths lives in :mod:`evri.tracking`, which has no dependency on
pyytlounge so it stays testable on its own. This module is only the adapter
between pyytlounge's callback interface and that tracker.

Phase 1 replaces this file with a Kotlin/OkHttp port of the protocol (R3); the
tracker ports across unchanged.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from pyytlounge import YtLoungeApi

try:  # exported from the package root in recent versions
    from pyytlounge import EventListener
except ImportError:  # pragma: no cover - older layouts
    from pyytlounge.event_listener import EventListener

from .tracking import (
    ADVANCING_STATES,
    STATE_ADVERTISEMENT,
    JsonlLog,
    PositionTracker,
    Prediction,
    event_to_dict,
)

__all__ = [
    "ADVANCING_STATES",
    "STATE_ADVERTISEMENT",
    "JsonlLog",
    "PositionTracker",
    "Prediction",
    "ProbeListener",
    "event_to_dict",
    "make_api",
]

log = logging.getLogger(__name__)


class ProbeListener(EventListener):
    """Logs every event and keeps a ``PositionTracker`` in step.

    All ten hooks are implemented because ``EventListener`` is an ABC. The ones we
    do not reason about are still logged — that is how we learn the protocol for
    the Kotlin port (R3).
    """

    def __init__(self, logger: JsonlLog, tracker: PositionTracker) -> None:
        self.log = logger
        self.tracker = tracker
        self.on_now_playing = None  # optional async callback(video_id)

    def _record(self, kind: str, event: Any, *, position_key: str = "current_time") -> dict:
        fields = event_to_dict(event)
        reported = fields.get(position_key)
        state = fields.get("state")

        duration = fields.get("duration")
        if isinstance(duration, (int, float)) and not isinstance(duration, bool) and duration > 0:
            self.tracker.duration = float(duration)

        # Capture the prediction BEFORE re-anchoring — that is the measurement.
        prediction = self.tracker.predict()
        error = None
        if isinstance(reported, (int, float)) and prediction is not None:
            error = float(reported) - prediction.position_s

        record = self.log.write(
            kind,
            event=fields,
            predicted_error_s=round(error, 4) if error is not None else None,
            anchor_age_s=round(prediction.anchor_age_s, 4) if prediction else None,
            tracker_state=self.tracker.state,
            tracker_speed=self.tracker.speed,
        )

        self.tracker.anchor(
            reported if isinstance(reported, (int, float)) else None,
            state if isinstance(state, (int, float)) else None,
        )
        return record

    async def playback_state_changed(self, event) -> None:
        self._record("onStateChange", event)

    async def now_playing_changed(self, event) -> None:
        record = self._record("nowPlaying", event)
        video_id = record["event"].get("video_id")
        if video_id and video_id != self.tracker.video_id:
            # New content started, so any ad that was running is over.
            self.tracker.ad_active = False
            self.tracker.video_id = video_id
            if self.on_now_playing is not None:
                await self.on_now_playing(video_id)

    async def playback_speed_changed(self, event) -> None:
        fields = event_to_dict(event)
        self.log.write("onPlaybackSpeedChanged", event=fields)
        for key in ("speed", "playback_speed", "playbackSpeed"):
            if isinstance(fields.get(key), (int, float)) and not isinstance(fields[key], bool):
                self.tracker.set_speed(float(fields[key]))
                break

    async def volume_changed(self, event) -> None:
        self.log.write("onVolumeChanged", event=event_to_dict(event))

    async def autoplay_changed(self, event) -> None:
        self.log.write("onAutoplayModeChanged", event=event_to_dict(event))

    async def autoplay_up_next_changed(self, event) -> None:
        self.log.write("autoplayUpNext", event=event_to_dict(event))

    async def ad_state_changed(self, event) -> None:
        fields = event_to_dict(event)
        self.log.write("onAdStateChange", event=fields)
        # Note: `current_time` on ad events is the AD's clock. Never anchor from it.
        self.tracker.set_ad_state(fields.get("ad_state"))

    async def ad_playing_changed(self, event) -> None:
        fields = event_to_dict(event)
        self.log.write("adPlaying", event=fields)
        self.tracker.set_ad_state(fields.get("ad_state"))

    async def subtitles_track_changed(self, event) -> None:
        self.log.write("onSubtitlesTrackChanged", event=event_to_dict(event))

    async def disconnected(self, event) -> None:
        self.log.write("disconnected", event=event_to_dict(event))


async def make_api(
    listener: EventListener,
    *,
    auth_file: Path,
    pairing_code: str | None,
    device_name: str = "Evri-Text",
) -> YtLoungeApi:
    """Connect, reusing saved pairing when possible.

    ``store_auth_state``/``load_auth_state`` mean the TV code has to be typed once,
    ever — which is the whole point of doing this in one trip to the TV.
    """
    api = YtLoungeApi(device_name, listener)

    if auth_file.exists() and not pairing_code:
        api.load_auth_state(json.loads(auth_file.read_text(encoding="utf-8")))
        log.info("loaded saved pairing from %s", auth_file)
        if not await api.refresh_auth():
            raise RuntimeError(
                "saved pairing is no longer valid — re-run with --pair <TV CODE>"
            )
    else:
        if not pairing_code:
            raise RuntimeError("no saved pairing; pass --pair <TV CODE>")
        code = pairing_code.replace(" ", "").replace("-", "")
        if not await api.pair(code):
            raise RuntimeError(f"pairing failed for code {code!r}")
        auth_file.parent.mkdir(parents=True, exist_ok=True)
        auth_file.write_text(
            json.dumps(api.store_auth_state(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        log.info("paired and saved to %s", auth_file)

    if not await api.connect():
        raise RuntimeError("connect() failed — is the TV awake with YouTube open?")

    return api
