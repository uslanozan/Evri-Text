"""Thin adb wrappers for the Phase 0 preflight."""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass

TIMEOUT = 20


@dataclass
class Result:
    ok: bool
    out: str
    err: str

    @property
    def text(self) -> str:
        return (self.out or self.err).strip()


def available() -> bool:
    return shutil.which("adb") is not None


def run(*args: str, timeout: int = TIMEOUT) -> Result:
    try:
        proc = subprocess.run(
            ["adb", *args],
            capture_output=True,
            text=True,
            timeout=timeout,
            encoding="utf-8",
            errors="replace",
        )
        return Result(proc.returncode == 0, proc.stdout or "", proc.stderr or "")
    except FileNotFoundError:
        return Result(False, "", "adb not found in PATH")
    except subprocess.TimeoutExpired:
        return Result(False, "", f"adb {' '.join(args)} timed out after {timeout}s")


def shell(command: str, timeout: int = TIMEOUT) -> Result:
    return run("shell", command, timeout=timeout)


def connect(ip: str, port: int = 5555) -> Result:
    return run("connect", f"{ip}:{port}", timeout=15)


def devices() -> list[tuple[str, str]]:
    result = run("devices")
    entries = []
    for line in result.out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            entries.append((parts[0], parts[1]))
    return entries


def getprop(name: str) -> str:
    return shell(f"getprop {name}").text


def install(apk_path: str) -> Result:
    return run("install", "-r", apk_path, timeout=180)


def package_installed(package: str) -> bool:
    return package in shell(f"pm list packages {package}").text


def grant_overlay(package: str) -> Result:
    return shell(f"appops set {package} SYSTEM_ALERT_WINDOW allow")


def whitelist_doze(package: str) -> Result:
    return shell(f"dumpsys deviceidle whitelist +{package}")


def overlay_settings_resolves() -> bool:
    """Whether the overlay-permission Settings screen exists at all.

    On most Android TV builds it does not — which is exactly why the appops route
    matters (DESIGN.md section 2.2).
    """
    result = shell(
        "cmd package resolve-activity --brief "
        "-a android.settings.action.MANAGE_OVERLAY_PERMISSION"
    )
    text = result.text.lower()
    return result.ok and "no activity found" not in text and "/" in text


def wifi_ip() -> str:
    """Best-effort device IP, needed to reach TvOverlay's HTTP port."""
    for command in ("ip route", "ip -f inet addr show wlan0", "ifconfig wlan0"):
        text = shell(command).text
        for token in text.replace("/", " ").split():
            if token.count(".") == 3 and not token.startswith("127."):
                parts = token.split(".")
                if all(part.isdigit() and 0 <= int(part) <= 255 for part in parts):
                    return token
    return ""
