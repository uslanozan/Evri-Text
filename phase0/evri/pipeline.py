"""End-to-end subtitle generation, shared by the CLI and the live demo.

captions -> cues -> sentences -> parallel LLM translation -> cues
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import captions as captions_mod
from .cues import Cue, load_vtt, write_srt, write_vtt
from .sentences import merge_into_sentences, sentences_to_cues
from .translate import PROMPT_VERSION, TranslationProvider, translate_in_parallel

log = logging.getLogger(__name__)


@dataclass
class SubtitleResult:
    video_id: str
    title: str
    duration_s: float
    cues: list[Cue]
    source_lang: str
    source_was_auto: bool
    sentence_count: int
    raw_cue_count: int
    elapsed_fetch_s: float
    elapsed_translate_s: float
    failed_chunks: list[int] = field(default_factory=list)
    from_cache: bool = False
    skipped_reason: str | None = None

    @property
    def elapsed_total_s(self) -> float:
        return self.elapsed_fetch_s + self.elapsed_translate_s


def cache_key(video_id: str, target_lang: str, provider_id: str) -> str:
    """Matches the Phase 1 cache key in DESIGN.md section 6."""
    return f"{video_id}.{target_lang}.{provider_id}.{PROMPT_VERSION}"


def build_subtitles(
    url_or_id: str,
    provider: TranslationProvider,
    *,
    target_lang: str = "tr",
    source_lang: str | None = None,
    out_dir: Path,
    chunk_size: int = 60,
    max_workers: int = 8,
    limit: int | None = None,
    use_cache: bool = True,
    skip_if_manual_target: bool = True,
) -> SubtitleResult:
    """Fetch the best caption track for a video and translate it.

    ``skip_if_manual_target`` implements priority 1 of DESIGN.md section 2.3: if a
    human-authored track already exists in the target language, do nothing.
    """
    url = url_or_id if url_or_id.startswith("http") else captions_mod.watch_url(url_or_id)

    fetch_started = time.monotonic()
    info = captions_mod.probe(url)

    subs_dir = out_dir / "subs"
    raw_dir = out_dir / "raw"
    subs_dir.mkdir(parents=True, exist_ok=True)

    key = cache_key(info.video_id, target_lang, provider.id)
    srt_path = subs_dir / f"{key}.srt"
    meta_path = subs_dir / f"{key}.json"

    if use_cache and srt_path.exists() and meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        log.info("cache hit: %s", srt_path.name)
        return SubtitleResult(
            video_id=info.video_id,
            title=info.title,
            duration_s=info.duration_s,
            cues=load_vtt(subs_dir / f"{key}.vtt"),
            source_lang=meta.get("source_lang", "?"),
            source_was_auto=meta.get("source_was_auto", False),
            sentence_count=meta.get("sentence_count", 0),
            raw_cue_count=meta.get("raw_cue_count", 0),
            elapsed_fetch_s=0.0,
            elapsed_translate_s=0.0,
            from_cache=True,
        )

    if skip_if_manual_target and info.manual_track(target_lang) is not None:
        return SubtitleResult(
            video_id=info.video_id,
            title=info.title,
            duration_s=info.duration_s,
            cues=[],
            source_lang=target_lang,
            source_was_auto=False,
            sentence_count=0,
            raw_cue_count=0,
            elapsed_fetch_s=time.monotonic() - fetch_started,
            elapsed_translate_s=0.0,
            skipped_reason=f"video already has a human-authored '{target_lang}' track",
        )

    track = info.best_track()
    if track is None:
        return SubtitleResult(
            video_id=info.video_id,
            title=info.title,
            duration_s=info.duration_s,
            cues=[],
            source_lang="?",
            source_was_auto=False,
            sentence_count=0,
            raw_cue_count=0,
            elapsed_fetch_s=time.monotonic() - fetch_started,
            elapsed_translate_s=0.0,
            skipped_reason="no usable caption track — this video needs the STT path",
        )

    vtt_path = captions_mod.download_vtt(
        url, track.lang, auto_generated=track.auto_generated, out_dir=raw_dir
    )
    raw_cues = load_vtt(vtt_path)
    sentences = merge_into_sentences(raw_cues)
    if limit is not None:
        sentences = sentences[:limit]
    elapsed_fetch = time.monotonic() - fetch_started

    log.info(
        "%s: %d raw cues -> %d sentences (track=%s, auto=%s)",
        info.video_id,
        len(raw_cues),
        len(sentences),
        track.lang,
        track.auto_generated,
    )

    translate_started = time.monotonic()
    translations, failed = translate_in_parallel(
        provider,
        [sentence.text for sentence in sentences],
        source_lang=source_lang or track.lang.split("-")[0],
        target_lang=target_lang,
        video_title=info.title,
        chunk_size=chunk_size,
        max_workers=max_workers,
    )
    elapsed_translate = time.monotonic() - translate_started

    cues = sentences_to_cues(sentences, translations)
    write_srt(cues, srt_path)
    write_vtt(cues, subs_dir / f"{key}.vtt")
    meta_path.write_text(
        json.dumps(
            {
                "video_id": info.video_id,
                "title": info.title,
                "duration_s": info.duration_s,
                "source_lang": track.lang,
                "source_was_auto": track.auto_generated,
                "target_lang": target_lang,
                "provider": provider.id,
                "prompt_version": PROMPT_VERSION,
                "raw_cue_count": len(raw_cues),
                "sentence_count": len(sentences),
                "failed_chunks": failed,
                "elapsed_fetch_s": round(elapsed_fetch, 2),
                "elapsed_translate_s": round(elapsed_translate, 2),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    return SubtitleResult(
        video_id=info.video_id,
        title=info.title,
        duration_s=info.duration_s,
        cues=cues,
        source_lang=track.lang,
        source_was_auto=track.auto_generated,
        sentence_count=len(sentences),
        raw_cue_count=len(raw_cues),
        elapsed_fetch_s=elapsed_fetch,
        elapsed_translate_s=elapsed_translate,
        failed_chunks=failed,
    )
