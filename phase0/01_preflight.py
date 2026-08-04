#!/usr/bin/env python3
"""Phase 0, step 1 — device preflight and overlay proof (risk R4).

Checks adb reachability, captures device facts, optionally installs TvOverlay,
grants SYSTEM_ALERT_WINDOW via appops, and then draws real text on top of
whatever the TV is showing. Everything is written to out/preflight.txt so the
whole diagnosis survives one trip to the TV.

    python 01_preflight.py                      # checks only
    python 01_preflight.py --install-tvoverlay  # also install + grant + draw
"""

from __future__ import annotations

import argparse
import os
import sys
import time
import urllib.request
from pathlib import Path

from dotenv import load_dotenv

from evri import adb
from evri.overlay import PACKAGE as TVOVERLAY_PACKAGE
from evri.overlay import TvOverlay

APK_URL = "https://github.com/gugutab/TvOverlay/raw/main/apk/tvoverlay1.0.0.apk"
OUT = Path(__file__).parent / "out"

ENABLE_ADB_HELP = """\
Mi Box S üzerinde ADB'yi açmak için:
  1. Ayarlar > Cihaz Tercihleri > Hakkında
  2. "Yapım" (Build) satırına 7 kez bas -> "Artık geliştiricisiniz"
  3. Ayarlar > Cihaz Tercihleri > Geliştirici seçenekleri
  4. "USB hata ayıklama" (USB debugging) -> AÇIK
     Varsa "Ağ üzerinden hata ayıklama" / "ADB over network" -> AÇIK
  5. TV'nin IP adresi: Ayarlar > Ağ ve İnternet > (Wi-Fi ağın) > IP adresi
  6. Bu script'i --ip <IP> ile veya .env içinde TV_IP ayarlayarak tekrar çalıştır
  7. TV'de "USB hata ayıklamaya izin ver?" çıkarsa İZİN VER (+ "her zaman")

"Ağ üzerinden hata ayıklama" seçeneği yoksa: TV'yi USB ile bilgisayara bağla,
`adb tcpip 5555` çalıştır, sonra kabloyu çıkarıp `adb connect <IP>:5555` yap.
"""


class Report:
    def __init__(self) -> None:
        self.lines: list[str] = []
        self.failures: list[str] = []

    def section(self, title: str) -> None:
        self.emit("")
        self.emit(f"== {title} " + "=" * max(0, 60 - len(title)))

    def emit(self, text: str = "") -> None:
        print(text)
        self.lines.append(text)

    def check(self, label: str, ok: bool, detail: str = "", *, fatal: bool = False) -> bool:
        mark = "OK  " if ok else "FAIL"
        self.emit(f"[{mark}] {label}" + (f" — {detail}" if detail else ""))
        if not ok and fatal:
            self.failures.append(label)
        return ok

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(self.lines) + "\n", encoding="utf-8")
        print(f"\nRapor kaydedildi: {path}")


def download_apk(dest: Path) -> Path | None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 100_000:
        return dest
    try:
        print(f"TvOverlay APK indiriliyor: {APK_URL}")
        urllib.request.urlretrieve(APK_URL, dest)  # noqa: S310 - fixed, known URL
    except Exception as exc:  # noqa: BLE001
        print(f"  indirilemedi: {exc}")
        print("  Elle indir: https://github.com/gugutab/TvOverlay/tree/main/apk")
        return None
    return dest if dest.exists() else None


def main() -> int:
    load_dotenv()
    parser = argparse.ArgumentParser()
    parser.add_argument("--ip", default=os.getenv("TV_IP", ""), help="Mi Box S IP")
    parser.add_argument(
        "--install-tvoverlay",
        action="store_true",
        help="TvOverlay'i TV'ye kur, overlay iznini ver, ekrana yazı bas (R4 testi)",
    )
    args = parser.parse_args()

    report = Report()
    report.emit("Evri-Text Phase 0 — preflight")
    report.emit(time.strftime("%Y-%m-%d %H:%M:%S"))

    # ---------------------------------------------------------------- adb
    report.section("adb")
    if not report.check("adb PATH'te bulundu", adb.available(), fatal=True):
        report.emit("\nAndroid platform-tools kurulu değil.")
        report.emit("https://developer.android.com/tools/releases/platform-tools")
        report.save(OUT / "preflight.txt")
        return 1

    report.emit(adb.run("version").text.splitlines()[0] if adb.run("version").ok else "")

    if args.ip:
        result = adb.connect(args.ip)
        report.check(f"adb connect {args.ip}:5555", result.ok, result.text)

    device_list = adb.devices()
    online = [serial for serial, state in device_list if state == "device"]
    unauthorized = [serial for serial, state in device_list if state == "unauthorized"]

    if unauthorized:
        report.check(
            "cihaz yetkilendirmesi",
            False,
            f"{unauthorized} — TV ekranındaki 'USB hata ayıklamaya izin ver?' "
            "sorusuna İZİN VER",
            fatal=True,
        )
    report.check(f"bağlı cihaz ({len(online)})", bool(online), ", ".join(online), fatal=True)

    if not online:
        report.emit("")
        report.emit(ENABLE_ADB_HELP)
        report.save(OUT / "preflight.txt")
        return 1

    # ------------------------------------------------------------- device
    report.section("cihaz bilgileri")
    props = {
        "model": "ro.product.model",
        "device": "ro.product.device",
        "manufacturer": "ro.product.manufacturer",
        "android": "ro.build.version.release",
        "sdk": "ro.build.version.sdk",
        "abi": "ro.product.cpu.abi",
        "build": "ro.build.display.id",
    }
    facts = {}
    for label, prop in props.items():
        facts[label] = adb.getprop(prop)
        report.emit(f"  {label:14s} {facts[label]}")

    sdk = int(facts["sdk"]) if facts["sdk"].isdigit() else 0
    report.check(
        "API 28+ (overlay engellenemez: HIDE_OVERLAY_WINDOWS API 31+)",
        sdk >= 23,
        f"SDK {sdk}",
    )
    if sdk >= 31:
        report.emit(
            "  ! DİKKAT: SDK 31+ — uygulamalar setHideOverlayWindows() ile "
            "overlay'i gizleyebilir. YouTube bunu kullanıyorsa R4 riski geri gelir."
        )

    memory = adb.shell("cat /proc/meminfo | head -1").text
    report.emit(f"  {'ram':14s} {memory}")

    device_ip = adb.wifi_ip()
    report.emit(f"  {'ip (cihazdan)':14s} {device_ip or '?'}")

    # ------------------------------------------------- overlay permission
    report.section("overlay izni")
    resolves = adb.overlay_settings_resolves()
    report.emit(
        f"  MANAGE_OVERLAY_PERMISSION ekranı: "
        f"{'VAR' if resolves else 'YOK (beklenen — appops yolu kullanılacak)'}"
    )

    # ------------------------------------------------------ media_session
    report.section("media_session dökümü (alternatif pozisyon yolu için veri)")
    dump = adb.shell("dumpsys media_session", timeout=30)
    dump_path = OUT / "dumpsys_media_session.txt"
    dump_path.parent.mkdir(parents=True, exist_ok=True)
    dump_path.write_text(dump.out or dump.err, encoding="utf-8")
    youtube_sessions = [
        line.strip()
        for line in (dump.out or "").splitlines()
        if "youtube" in line.lower() or "PlaybackState" in line
    ]
    report.emit(f"  tam döküm: {dump_path}")
    report.emit(f"  ilgili satır sayısı: {len(youtube_sessions)}")
    for line in youtube_sessions[:15]:
        report.emit(f"    {line}")
    if not youtube_sessions:
        report.emit("  (YouTube'da bir video oynatırken tekrar çalıştırırsan burada")
        report.emit("   PlaybackState görünmesi lazım — Lounge API'ye yedek yol olur)")

    # ----------------------------------------------------------- TvOverlay
    report.section("TvOverlay (R4 — overlay tam ekran YouTube üstünde görünüyor mu)")
    installed = adb.package_installed(TVOVERLAY_PACKAGE)
    report.emit(f"  {TVOVERLAY_PACKAGE}: {'kurulu' if installed else 'kurulu değil'}")

    if not installed and not args.install_tvoverlay:
        report.emit("")
        report.emit("  R4'ü test etmek için --install-tvoverlay ile tekrar çalıştır.")
        report.emit("  Bu, TV'ye üçüncü parti bir uygulama KURAR (sonradan silinebilir):")
        report.emit(f"    adb uninstall {TVOVERLAY_PACKAGE}")
    elif args.install_tvoverlay:
        if not installed:
            apk = download_apk(OUT / "tvoverlay.apk")
            if apk:
                result = adb.install(str(apk))
                installed = report.check("TvOverlay kuruldu", result.ok, result.text)
            else:
                report.check("TvOverlay APK indirildi", False, fatal=True)

        if installed:
            grant = adb.grant_overlay(TVOVERLAY_PACKAGE)
            report.check("appops SYSTEM_ALERT_WINDOW allow", grant.ok, grant.text)
            doze = adb.whitelist_doze(TVOVERLAY_PACKAGE)
            report.check("deviceidle whitelist", doze.ok, doze.text[:80])

            adb.shell(f"monkey -p {TVOVERLAY_PACKAGE} -c android.intent.category.LAUNCHER 1")
            time.sleep(4)

            host = args.ip or device_ip
            if not host:
                report.check("TvOverlay HTTP host bilinmiyor", False, "--ip ver", fatal=True)
            else:
                overlay = TvOverlay(host)
                report.emit(f"  TvOverlay HTTP: http://{host}:5001")
                report.emit("")
                report.emit("  >>> ŞİMDİ TV'DE YOUTUBE'DA BİR VİDEO BAŞLAT VE TAM EKRAN YAP.")
                report.emit("  >>> 15 saniye içinde ekranda yazı görmen gerekiyor.")
                time.sleep(15)

                ok_notify = overlay.notify(
                    "R4 testi: bu yazıyı YouTube üzerinde görüyorsan overlay çalışıyor",
                    title="Evri-Text",
                    seconds=15,
                )
                report.check("POST /notify", ok_notify)

                ok_fixed = overlay.show_fixed(
                    "Bu satır altyazı gibi sabit kalır — 20 saniye sonra silinecek"
                )
                report.check("POST /notify_fixed", ok_fixed)
                time.sleep(20)
                overlay.hide_fixed()

                report.emit("")
                report.emit("  >>> SORU (elle cevapla, R4 kararı bu): yazılar tam ekran")
                report.emit("  >>> YouTube videosunun ÜZERİNDE göründü mü?")

    # ------------------------------------------------------------- verdict
    report.section("özet")
    if report.failures:
        for failure in report.failures:
            report.emit(f"  ! {failure}")
        report.emit("\nÖnce bunları çöz, sonra 02_lounge_probe.py'ye geç.")
    else:
        report.emit("  Bloke eden sorun yok. Sıradaki: 02_lounge_probe.py")

    report.save(OUT / "preflight.txt")
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
