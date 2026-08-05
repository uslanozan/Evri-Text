# Phase 0 — Durum ve kalan işler

Son güncelleme: 2026-08-05, laptop + Xiaomi MiTV-AFKR0 (Android 11, API 30) üzerinde.

## Risk tablosu

| Risk | Soru | Durum | Kanıt |
|---|---|---|---|
| **R4** | Overlay tam ekran YouTube'un üstünde görünüyor mu? | ✅ **YEŞİL** | Köşe bildirimi ve sabit altyazı, tam ekran video üzerinde göründü |
| **R1** | Lounge API pozisyonu altyazı senkronu için yeterince hassas mı? | ✅ **YEŞİL** | p95 kayma **234 ms**, medyan 49 ms, kayma hızı 2.8 ms/sn |
| **R7** | ASR altyazısının LLM çevirisi anlaşılır mı? | 🟡 **kısmen** | VLC'de kontrol edildi, takip edilebilir — **anne testi yapılmadı** |
| — | Videoların kaçında altyazı var? | ✅ **10/10** | 2 elle yazılmış EN, 8 ASR, **0 video STT gerektiriyor** |
| — | Uçtan uca çalışıyor mu? | ⬜ | Adım 7 çalıştırılmadı |

**Projeyi bloke eden iki riskin ikisi de geçti.**

---

## Kalan işler

### 1. Çeviri kalitesi doğrulaması — *senin işin, en önemlisi*

Dosya hazır: `out/subs/sx8pViXxZQg.tr.gemini.v2.srt` (803 satır)
Video hazır: `out/video/sx8pViXxZQg.mp4` (360p, sesli)
Altyazı videonun yanına kopyalandı, VLC otomatik yüklüyor.

- [ ] Filmin **başı, ortası, sonu**ndan rastgele bak — cümleler tam mı, Türkçe doğal mı, zamanlama tutuyor mu?
- [ ] **Anneye göster, takip edebiliyor mu diye sor.** Projenin gerçek kabul kriteri bu, başka hiçbir metrik değil.

Beğenmezsen prompt `evri/translate.py` içindeki `SYSTEM_INSTRUCTION`'da.
Hızlı deneme: `python 04_make_subs.py --url "..." --limit 20`
(`--limit` artık ayrı dosyaya yazıyor, tam çıktıyı bozmuyor.)

### 2. Adım 6 — altyazı var mı taraması ✅ *bitti*

10 video tarandı (podcast, kısa film, belgesel tarzı):

| Sonuç | Sayı | Ne demek |
|---|---|---|
| Elle yazılmış Türkçe | 0 | — |
| Elle yazılmış İngilizce | 2 (%20) | Çeviri yolu, **en iyi kaynak kalitesi** |
| Sadece otomatik (ASR) | 8 (%80) | Çeviri yolu |
| Hiç altyazı yok | **0** | STT gerekmiyor |

**Sonuç: STT yolu acil değil. Phase 1 tamamen çeviri yoluyla yapılabilir.**
Örneklem küçük (10 video) — gerçek kullanımda altyazısız videoyla karşılaşırsan
Phase 2 tekrar gündeme gelir.

### 3. Adım 7 — uçtan uca canlı test

Ön koşullar:
- TV açık, uyanık, YouTube ekranda
- TvOverlay çalışıyor olmalı (Phase 0 sonunda `force-stop` ile durduruldu):
  ```powershell
  adb shell am start -n com.tabdeveloper.tvoverlay/.SetupActivity
  ```
- [ ] `python 05_live_demo.py`
- [ ] Kumandayla normal video aç/kapat/sar — altyazı takip ediyor mu?
- Kayma varsa: `--offset 0.4` (altyazıyı ileri al) veya `--reanchor 5` (daha sık çapa)

**Bu adım çalışıyorsa Phase 1 sadece "aynı mantığı cihaza taşımak" demek.**

---

## Ölçüm sonuçları (R1 detayı)

`out/probe-20260805-210253.jsonl` → `python 03_analyze.py`

| Ölçüm | Değer | Yorum |
|---|---|---|
| p95 interpolasyon kayması | **234 ms** | 300 ms eşiğinin altında |
| Medyan kayma | 49 ms | Fark edilmez |
| Kayma hızı | 2.8 ms/sn | 600 ms tolerans için 213 sn'de bir çapa yeterli |
| `getNowPlaying` sorgu limiti | **yok** | 0.4 sn aralıkla 25 istek, %100 cevap |
| Komut gecikmesi | pause 657 ms, play 375 ms, seek 343 ms | |
| Seek doğruluğu | ±1 sn | 1183 istendi → 1182.0 bildirildi |

### Phase 1'i doğrudan etkileyen iki bulgu

1. **TV kendiliğinden hiç pozisyon göndermiyor.** Nabız atışı yok — tamamen
   `getNowPlaying` çapasına bağımlıyız. Neyse ki sorgu limiti de yok.
   Kotlin portunda periyodik çapa döngüsü **zorunlu**.

2. **Lounge bind kanalı birkaç dakikada kapanıyor.** `subscribe()` tek bir uzun
   long-poll; sunucu stream'i kapatınca sessizce dönüyor ve sonraki her komut
   `NotConnectedException` atıyor. Yeniden abone olma döngüsü protokolün zorunlu
   parçası, opsiyonel iyileştirme değil. Bkz. `evri/lounge.py::subscribe_forever`.

---

## Bu oturumda düzeltilen hatalar

Hepsi Phase 1'e de taşınacak dersler:

| Dosya | Sorun | Düzeltme |
|---|---|---|
| `01_preflight.py` | TvOverlay'in yalnızca `LEANBACK_LAUNCHER` aktivitesi var, `monkey -c LAUNCHER` hiçbir şey bulamıyor, uygulama açılmıyor, HTTP portu hiç dinlemiyor | `am start -n .../.SetupActivity`, `monkey`'e geri düşüş |
| `evri/lounge.py` | pyytlounge ≥3.3 aiohttp oturumunu `__aenter__`'da kuruyor; onsuz "API is not initialized" | `make_api` içinde `__aenter__` çağrılıyor |
| `evri/lounge.py` | `store_auth_state()` eski anahtar isimlerini yazıyor, `load_auth_state()` yeni isimleri okuyor → `KeyError: 'version'` | `_serialise_auth` / `_normalise_auth`, iki formatı da kabul ediyor |
| `evri/lounge.py` | Oturum koptuğunda ölçüm ölüyordu | `subscribe_forever` — yeniden abone, gerekirse `connect()`, üstel geri çekilme |
| `evri/pipeline.py` | Başarısız chunk içeren (İngilizce kalmış) çıktı önbellekten HIT olarak dönüyordu | `failed_chunks` doluysa önbellek yok sayılıyor |
| `evri/pipeline.py` | `--limit 20` çıktısı gerçek anahtara yazılıyor, sonraki tam çalıştırma 20 satır dönüyordu | Sınırlı çalıştırma ayrı anahtara yazıyor ve önbelleğe alınmıyor |
| `evri/translate.py` | Girdide `text` anahtarı kullanılınca model çıktıda da `tr` yerine `text` döndürüyor, 20 satır boş çıkıyordu | Girdi anahtarı `source`; parser `tr/translation/text/t` kabul ediyor |
| `evri/translate.py` | Model JSON'un ardına fazladan içerik eklerse `json.loads` "Extra data" atıp tüm chunk'ı çöpe atıyordu | `raw_decode` ile ilk geçerli JSON |
| `evri/translate.py` | Tek eksik indeks yüzünden 60 cümlelik chunk komple İngilizce kalıyordu | Kısmi sonuç korunuyor, yalnız eksikler kaynak metinle dolduruluyor |
| `evri/translate.py` | 429 kotasında körlemesine yeniden deneme | Sunucunun `retryDelay` değeri okunup bekleniyor |
| `04_make_subs.py` | 8 paralel işçi ücretsiz katmanın 15 istek/dk limitini patlatıyordu | Varsayılan 3 |
| `04_make_subs.py` | Ekrana basılan SRT yolunda sürüm elle `.v1` yazılmıştı | `cache_key`'den alınıyor |

Sonuç: tam video çevirisinde **4 başarısız chunk → 0**.

---

## Ortam notları

- `adb`: `C:\Users\uslan\Downloads\platform-tools-latest-windows\platform-tools`, kullanıcı PATH'ine eklendi.
  Bu klasör `Downloads` içinde — disk temizliğinde silinirse PATH bozulur, kalıcı bir yere taşımak daha güvenli.
- TV IP: `192.168.33.11` (`.env` → `TV_IP`). DHCP'den geliyor, reboot sonrası değişebilir.
  Modemde MAC `4c:24:ce:09:e0:94` için sabit IP ataması yaparsan bir daha uğraşmazsın.
- `GEMINI_MODEL=gemini-3.5-flash-lite`. `gemini-2.5-flash-lite` yeni API key'lere kapalı (404).
- `ffmpeg` kurulu değil — yt-dlp yüksek çözünürlüklü video+ses birleştiremiyor.
  Gerekirse `winget install ffmpeg`. Şimdilik 360p tek parça format (`-f 18`) kullanıldı.
- TV tarafında bir daha kurulum gerekmiyor: ADB izni kalıcı, TvOverlay kurulu ve izinli,
  Lounge eşleştirmesi `lounge_auth.json`'da, otomatik oynatma kapalı.
