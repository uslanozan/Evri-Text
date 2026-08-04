"""TvOverlay HTTP client.

TvOverlay (``com.tabdeveloper.tvoverlay``) draws a SYSTEM_ALERT_WINDOW overlay on
Android TV and is driven entirely over HTTP. That lets Phase 0 prove risk R4 —
"can anything draw on top of fullscreen YouTube on API 28" — and even render real
subtitles, without writing a single line of Android code.

Requires, once, over adb:
    adb shell appops set com.tabdeveloper.tvoverlay SYSTEM_ALERT_WINDOW allow
    adb shell dumpsys deviceidle whitelist +com.tabdeveloper.tvoverlay
"""

from __future__ import annotations

import logging

import requests

log = logging.getLogger(__name__)

PACKAGE = "com.tabdeveloper.tvoverlay"
DEFAULT_PORT = 5001
SUBTITLE_ID = "evri-subtitle"


class TvOverlay:
    def __init__(self, host: str, port: int = DEFAULT_PORT, timeout: float = 2.0) -> None:
        self.base = f"http://{host}:{port}"
        self.timeout = timeout
        self._session = requests.Session()

    def _post(self, path: str, payload: dict) -> bool:
        try:
            response = self._session.post(
                f"{self.base}{path}", json=payload, timeout=self.timeout
            )
            if response.status_code >= 400:
                log.warning("%s -> HTTP %d: %s", path, response.status_code, response.text[:200])
                return False
            return True
        except requests.RequestException as exc:
            log.warning("%s failed: %s", path, exc)
            return False

    def reachable(self) -> bool:
        """Cheap liveness check — a short-lived notification that proves the port."""
        return self.notify("Evri-Text bağlandı", title="Evri-Text", seconds=3)

    def notify(
        self,
        message: str,
        *,
        title: str = "",
        seconds: int = 10,
        corner: str = "bottom_end",
        notification_id: int = 0,
    ) -> bool:
        """Transient toast-style notification."""
        return self._post(
            "/notify",
            {
                "message": message,
                "title": title,
                "id": notification_id,
                "appTitle": "Evri-Text",
                "corner": corner,
                "seconds": seconds,
            },
        )

    def show_fixed(self, text: str, *, fixed_id: str = SUBTITLE_ID) -> bool:
        """Persistent text, replaced in place on each call. Used for subtitles."""
        return self._post("/notify_fixed", {"text": text, "id": fixed_id})

    def hide_fixed(self, *, fixed_id: str = SUBTITLE_ID) -> bool:
        return self._post("/notify_fixed", {"visible": False, "id": fixed_id})
