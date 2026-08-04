"""Position tracking and event logging — deliberately dependency-free.

``PositionTracker`` is the heart of risk R1 and the single most important thing
that gets ported to Kotlin in Phase 1, so it is kept free of any Lounge/network
imports: it can be unit-tested and reasoned about on its own.

The Lounge API pushes position only when something changes — there is no
heartbeat — so between events we advance a local clock from the last known
anchor. Phase 0 measures how far that prediction drifts from reality.
"""

from __future__ import annotations

import dataclasses
import enum
import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# State values that mean the playhead is advancing in real time.
# Mirrors pyytlounge's State enum: Playing == 1.
STATE_PLAYING = 1
STATE_PAUSED = 2
STATE_ADVERTISEMENT = 1081
ADVANCING_STATES = {STATE_PLAYING}


def event_to_dict(event: Any) -> dict:
    """Flatten an arbitrary event object into JSON-safe fields."""
    if event is None:
        return {}
    if dataclasses.is_dataclass(event):
        raw = dataclasses.asdict(event)
    elif hasattr(event, "__dict__"):
        raw = dict(vars(event))
    else:
        return {"repr": repr(event)}
    return {key: _plain(value) for key, value in raw.items()}


def _plain(value: Any) -> Any:
    if isinstance(value, bool) or value is None:
        return value
    if isinstance(value, enum.Enum):
        return _plain(value.value)
    if isinstance(value, (str, int, float)):
        return value
    if isinstance(value, (list, tuple, set)):
        return [_plain(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _plain(item) for key, item in value.items()}
    return repr(value)


class JsonlLog:
    """Append-only event log.

    Every record carries both clocks: ``mono`` for interval maths (immune to NTP
    steps) and ``unix`` for correlating with anything outside this process.
    """

    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self._handle = path.open("a", encoding="utf-8")
        self.t0 = time.monotonic()

    def write(self, kind: str, **fields: Any) -> dict:
        record = {
            "mono": round(time.monotonic() - self.t0, 4),
            "unix": round(time.time(), 3),
            "kind": kind,
            **fields,
        }
        self._handle.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")
        self._handle.flush()
        return record

    def close(self) -> None:
        if not self._handle.closed:
            self._handle.close()


@dataclass
class Prediction:
    position_s: float
    anchor_age_s: float


class PositionTracker:
    """Anchor + interpolate, exactly as the shipped Kotlin client will have to."""

    def __init__(self) -> None:
        self.video_id: str | None = None
        self.state: int | None = None
        self.speed: float = 1.0
        self.duration: float | None = None
        # Ads arrive on their own events carrying their own `ad_state` and
        # `current_time`; the content `state` field does not reliably flip to
        # Advertisement. So ad-ness is tracked separately, and ad positions are
        # never used as anchors — they are the ad's clock, not the video's.
        self.ad_active: bool = False
        self._anchor_position: float | None = None
        self._anchor_mono: float | None = None

    @property
    def advancing(self) -> bool:
        return self.state in ADVANCING_STATES and not self.ad_active

    @property
    def in_ad(self) -> bool:
        return self.ad_active or self.state == STATE_ADVERTISEMENT

    def set_ad_state(self, ad_state: int | None) -> None:
        """Called from ad events only. ``None`` leaves the flag untouched."""
        if ad_state is not None:
            self.ad_active = int(ad_state) == STATE_ADVERTISEMENT

    def anchor(self, position_s: float | None, state: int | None) -> None:
        """Record a known-good position. Either argument may be None."""
        if state is not None:
            self.state = int(state)
        if position_s is not None:
            self._anchor_position = float(position_s)
            self._anchor_mono = time.monotonic()

    def set_speed(self, speed: float) -> None:
        """Change playback speed without losing the elapsed time at the old speed."""
        prediction = self.predict()
        if prediction is not None:
            self.anchor(prediction.position_s, None)
        self.speed = float(speed)

    def predict(self, at_mono: float | None = None) -> Prediction | None:
        if self._anchor_position is None or self._anchor_mono is None:
            return None
        now = at_mono if at_mono is not None else time.monotonic()
        elapsed = now - self._anchor_mono
        position = self._anchor_position
        if self.advancing:
            position += elapsed * self.speed
        return Prediction(position_s=position, anchor_age_s=elapsed)

    def error_against(self, reported_s: float, at_mono: float | None = None) -> float | None:
        """``reported - predicted``. Positive means we were running behind.

        Must be called BEFORE re-anchoring, otherwise the error is always zero.
        """
        prediction = self.predict(at_mono)
        if prediction is None:
            return None
        return reported_s - prediction.position_s
