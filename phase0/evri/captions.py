"""Fetch YouTube caption tracks via yt-dlp.

Phase 0 only. In Phase 1 this becomes NewPipeExtractor on-device (DESIGN.md R2).
yt-dlp is used here because it already solves poToken/nsig, which lets us focus
on measuring translation quality instead of fighting the extractor.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

# Order matters: manual tracks beat auto-generated ones (DESIGN.md section 2.3).
DEFAULT_LANG_PREFS = ("en", "en-orig", "en-US", "en-GB")


@dataclass
class CaptionTrack:
    lang: str
    name: str
    ext: str
    url: str
    auto_generated: bool


@dataclass
class VideoInfo:
    video_id: str
    title: str
    duration_s: float
    manual: list[CaptionTrack]
    automatic: list[CaptionTrack]

    @property
    def has_manual_target(self) -> bool:
        return bool(self.manual)

    def best_track(self, langs: tuple[str, ...] = DEFAULT_LANG_PREFS) -> CaptionTrack | None:
        """Manual track in a preferred language, else auto-generated, else None."""
        for pool in (self.manual, self.automatic):
            for lang in langs:
                for track in pool:
                    if track.lang == lang:
                        return track
            # Fall back to a prefix match, e.g. "en-CA" for pref "en".
            for lang in langs:
                base = lang.split("-")[0]
                for track in pool:
                    if track.lang.split("-")[0] == base:
                        return track
        return None

    def manual_track(self, lang: str) -> CaptionTrack | None:
        base = lang.split("-")[0]
        for track in self.manual:
            if track.lang.split("-")[0] == base:
                return track
        return None


def _collect(raw: dict | None, auto: bool, ext: str = "vtt") -> list[CaptionTrack]:
    tracks: list[CaptionTrack] = []
    for lang, variants in (raw or {}).items():
        for variant in variants:
            if variant.get("ext") != ext:
                continue
            tracks.append(
                CaptionTrack(
                    lang=lang,
                    name=variant.get("name") or lang,
                    ext=variant["ext"],
                    url=variant.get("url", ""),
                    auto_generated=auto,
                )
            )
    return tracks


def probe(url: str) -> VideoInfo:
    """Inspect a video's available caption tracks without downloading anything."""
    import yt_dlp

    # noplaylist matters: a normal watch URL often carries &list=..., and without
    # this yt-dlp would walk the whole playlist instead of the one video.
    options = {
        "skip_download": True,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)

    return VideoInfo(
        video_id=info.get("id", ""),
        title=info.get("title", ""),
        duration_s=float(info.get("duration") or 0.0),
        manual=_collect(info.get("subtitles"), auto=False),
        automatic=_collect(info.get("automatic_captions"), auto=True),
    )


def download_vtt(
    url: str,
    lang: str,
    *,
    auto_generated: bool,
    out_dir: Path,
) -> Path:
    """Download one caption track as VTT and return the file path.

    We let yt-dlp write the file rather than fetching the URL ourselves — it
    handles the request headers, retries and token juggling that a bare GET
    against ``timedtext`` tends to trip over.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    import yt_dlp

    options = {
        "skip_download": True,
        "writesubtitles": not auto_generated,
        "writeautomaticsub": auto_generated,
        "subtitleslangs": [lang],
        "subtitlesformat": "vtt",
        "outtmpl": str(out_dir / "%(id)s.%(ext)s"),
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)

    video_id = info.get("id", "")
    candidates = sorted(out_dir.glob(f"{video_id}*.vtt"))
    if not candidates:
        raise FileNotFoundError(
            f"yt-dlp wrote no .vtt for {video_id} (lang={lang}, auto={auto_generated})"
        )

    exact = [path for path in candidates if f".{lang}." in path.name]
    return exact[0] if exact else candidates[0]


def watch_url(video_id: str) -> str:
    return f"https://www.youtube.com/watch?v={video_id}"
