# Phase 0 — Doğrulama

Android kodu yazmadan önce projenin **bütün riskini** ölçüyoruz. Sonunda elimizde PC'den TV'ye canlı Türkçe altyazı basan çalışan bir prototip olacak.

| Risk | Soru | Adım | API key gerekli mi? |
|---|---|---|---|
| **R4** | Overlay tam ekran YouTube'un üstünde görünüyor mu? | 2 | **Hayır** |
| **R1** | Lounge API pozisyonu altyazı senkronu için yeterince hassas mı? | 3 + 4 | **Hayır** |
| — | Videoların kaçında altyazı var (STT ne kadar gerekli)? | 6 | **Hayır** |
| **R7** | ASR altyazısının LLM çevirisi anlaşılır mı? | 5 | Evet |
| — | Uçtan uca çalışıyor mu? | 7 | Evet |

**Projeyi bloke eden iki risk (R1 ve R4) API key olmadan ölçülüyor.** Key'i sonra da alabilirsin — 1., 2., 3., 4. ve 6. adımlar hiç istek atmaz. Ama zaten ücretsiz ve 2 dakika sürüyor, baştan almak daha pratik: https://aistudio.google.com/apikey

---

## 0. Gereksinimler

Laptop'ta bunlar kurulu olmalı:

| | Neden | Kontrol |
|---|---|---|
| **Python 3.9+** (3.11 ile test edildi) | script'ler | `python --version` |
| **Android platform-tools** (`adb`) | 1., 2. adım | `adb version` |
| **VLC** | 5. adımda `.srt`'yi videoyla test etmek | — |
| Git | repoyu çekmek | `git --version` |

`adb` yoksa: [platform-tools indir](https://developer.android.com/tools/releases/platform-tools), bir klasöre çıkar, o klasörü **PATH**'e ekle. Kurulum gerektirmiyor, tek zip.

**Ağ:** Laptop ile Mi Box **aynı Wi-Fi/LAN'da** olmalı — 1., 2. ve 7. adımlar bunu gerektiriyor (`adb` ve TvOverlay'in HTTP portu yerel ağdan gidiyor). 3. ve 4. adım Lounge API'yi YouTube'un sunucusu üzerinden kullandığı için aynı ağda olmak şart değil, ama hepsini aynı ağda yapmak en basiti.

---

## 1. Laptop'ta kurulum (repoyu çektikten sonra)

`.venv`, `.env` ve `lounge_auth.json` **git'e girmiyor** — bunları laptop'ta yeniden kurman gerekiyor. Tek seferlik:

```powershell
cd phase0

python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt

copy .env.example .env
notepad .env        # TV_IP ve (istersen) GEMINI_API_KEY doldur
```

> **PowerShell "betik çalıştırma devre dışı" hatası verirse:**
> ```powershell
> Set-ExecutionPolicy -Scope Process -Bypass
> ```
> Sadece o pencere için geçerli, kalıcı değişiklik yapmaz. Alternatif: `.venv\Scripts\activate.bat`

Bundan sonraki her komut, venv aktif haldeyken `phase0` klasöründen çalıştırılıyor.

---

## 2. TV başına bir kez git

Hepsini tek seferde yap, sonrası laptop'tan:

1. **IP adresini yaz bir yere:** Ayarlar → Ağ ve İnternet → (Wi-Fi ağın) → IP adresi
   → `.env` içindeki `TV_IP`'ye bunu koy.
2. **ADB'yi aç:**
   - Ayarlar → Cihaz Tercihleri → **Hakkında**
   - **"Yapım" (Build)** satırına **7 kez** bas → "Artık geliştiricisiniz"
   - Geri → Cihaz Tercihleri → **Geliştirici seçenekleri**
   - **"USB hata ayıklama"** → AÇIK
   - Varsa **"Ağ üzerinden hata ayıklama" / "ADB over network"** → AÇIK
3. **Autoplay'i kapat:** YouTube → Ayarlar → Otomatik oynatma → KAPALI.
   *(3. adımda video biterse ölçüm bozulur.)*
4. **YouTube'da uzun bir video başlat: en az 20 dakika, İngilizce.**
   Süre önemli — 3. adım 5 dakika kesintisiz oynatma ölçüyor ve araya sarma
   testleri giriyor. 20+ dakika güvenli sınır.
5. **Eşleştirme kodunu al:** YouTube → Ayarlar → **"TV kodu ile bağla"**
   → 12 haneli kod. Bu kod **bir kez** gerekiyor, sonra `lounge_auth.json`'a
   kaydediliyor. **Kodlar zaman aşımına uğruyor** — 3. adımı hemen ardından çalıştır.
6. Laptop'tan ilk script'i çalıştırınca TV'de **"USB hata ayıklamaya izin ver?"**
   çıkacak → **İZİN VER** + "bu bilgisayardan her zaman".

Bundan sonra TV'ye sadece **ekrana bakmak** için döneceksin.

> "Ağ üzerinden hata ayıklama" seçeneği yoksa: TV'yi USB ile laptop'a bağla,
> `adb tcpip 5555` çalıştır, kabloyu çıkar, `adb connect <IP>:5555` yap.

> **İpucu (senin MAC fikrinin işe yaradığı yer):** TV'nin IP'si DHCP'den geldiği
> için reboot sonrası değişebilir ve `TV_IP` bozulur. Modem/router arayüzünde
> Mi Box'ın MAC adresine **sabit IP ataması (DHCP reservation)** yaparsan bir daha
> uğraşmazsın. MAC adresi: Ayarlar → Ağ ve İnternet → (ağın) → MAC adresi.

---

## Adım 1 — Cihaz kontrolü

```powershell
python 01_preflight.py
```

`adb`'yi bulur, bağlanır, cihaz bilgilerini toplar, `dumpsys media_session`
dökümünü kaydeder. Her şeyi `out/preflight.txt` içine yazar.

ADB açık değilse veya cihaz görünmüyorsa script tam olarak ne yapman gerektiğini
Türkçe yazdırıyor — TV'ye ikinci sefer gitmeden.

## Adım 2 — Overlay kanıtı (R4)

```powershell
python 01_preflight.py --install-tvoverlay
```

Bu adım TV'ye **TvOverlay** adlı üçüncü parti bir uygulama **kurar**, overlay iznini
`appops` ile verir, uygulamayı açar, sonra ekrana yazı basar.

Silmek istersen: `adb uninstall com.tabdeveloper.tvoverlay`

Script **"ŞİMDİ TV'DE YOUTUBE'DA BİR VİDEO BAŞLAT VE TAM EKRAN YAP"** dediğinde
**15 saniyen var** — TvOverlay ön plana gelmiş olacak, kumandayla YouTube'a dön ve
videoyu tam ekran yap.

**Bakman gereken:** yazılar tam ekran YouTube videosunun **üzerinde** göründü mü?

- Göründü → **R4 yeşil.** Overlay mimarisi çalışıyor.
- Görünmedi → durup bana söyle. `DESIGN.md` bölüm 8'deki SmartTube yolu devreye girer.

> HTTP hatası alırsan (`:5001` cevap vermiyor): TvOverlay'i TV'de kumandayla bir kez
> elle aç, sonra script'i tekrar çalıştır. Bazı sürümlerde HTTP sunucusu ilk elle
> açılışta başlıyor.

## Adım 3 — Senkron ölçümü (R1) — ana test

```powershell
python 02_lounge_probe.py --pair 123456789012
```

`--pair` sadece ilk çalıştırmada gerekli. `--video <ID>` verirsen o videoyu kendisi
başlatır; vermezsen TV'de zaten oynayan videoyu kullanır (2. bölüm 4. maddede
başlattığın uzun video — **önerilen kullanım bu**).

**~11 dakika sürer, tamamen kendi kendine çalışır.** Script TV'yi kendisi sürüyor:
durduruyor, sarıyor, hızı değiştiriyor. **Bu sürede kumandaya dokunma.** Başka bir
şey yapabilirsin.

Video 15 dakikadan kısaysa script uyarır ve 10 saniye bekler — o noktada `Ctrl-C`
ile durdurup daha uzun bir videoyla tekrar başlayabilirsin.

| Aşama | Ne yapıyor | Neyi ölçüyor |
|---|---|---|
| A | 90 sn sadece dinliyor | TV kendiliğinden ne sıklıkta pozisyon gönderiyor |
| B/C/D | `getNowPlaying`'i 5s → 1s → 0.4s aralıkla zorluyor | Sorgu limiti nerede başlıyor |
| E | Duraklat / devam ettir | Olay gecikmesi, pozisyon doğru donuyor mu |
| F | 3 farklı yere sarıyor (video süresine göre) | Sarma sonrası bildirilen pozisyon doğru mu |
| G | Hızı 1.5x yapıyor, sonra 1.0x | Hız değişiminde takip bozuluyor mu |
| H | 5 dakika, her 20 sn'de bir çapa | **Kayma (drift) — asıl ölçüm** |

## Adım 4 — Sonuçları oku

```powershell
python 03_analyze.py
```

- **YEŞİL** (p95 kayma < 300 ms) → senkron sorunsuz.
- **SARI** (< 600 ms) → izlenebilir; ayarlara elle offset koyarız (`--offset`).
- **KIRMIZI** → çıplak interpolasyon yetmiyor. `getNowPlaying` sık çalışıyorsa
  kurtarılır; o da olmazsa SmartTube yolunu tekrar değerlendiririz.

**Çıktıyı bana yapıştır, birlikte yorumlarız.**

## Adım 5 — Çeviri kalitesi (R7) — *API key gerekli*

```powershell
# ucuz deneme, ilk 20 cümle
python 04_make_subs.py --url "https://www.youtube.com/watch?v=..." --limit 20

# tam video
python 04_make_subs.py --url "https://www.youtube.com/watch?v=..."
```

`out/subs/` altına `.srt` yazar. VLC'de videoyu aç → **Altyazı → Altyazı dosyası
ekle** → üretilen `.srt`.

**Asıl test:** rastgele 3-4 yerden kontrol et — cümleler tam mı, Türkçe doğal mı?
Sonra **anneye göster ve takip edebiliyor mu diye sor.** Projenin gerçek kabul
kriteri bu, başka hiçbir metrik değil.

Beğenmezsen prompt `evri/translate.py` içindeki `SYSTEM_INSTRUCTION`'da —
değiştirip `--limit 20` ile hızlı hızlı deneyebilirsin.

## Adım 6 — Altyazı var mı taraması — *API key gerekmez*

Evde izlediğiniz tipten 20-30 video linkini `phase0/videos.txt` içine koy
(her satırda bir link, `#` ile yorum):

```
https://www.youtube.com/watch?v=...
https://www.youtube.com/watch?v=...
```

```powershell
python 04_make_subs.py --survey videos.txt
```

Kaç videonun İngilizce altyazısı var, kaçının hiç yok? Bu, STT yolunun (Phase 2)
ne kadar acil olduğunu belirliyor. Çeviri API'sine hiç istek atmaz, bedava.

## Adım 7 — Uçtan uca canlı test — *API key + Adım 2 gerekli*

```powershell
python 05_live_demo.py
```

Tüm ürün, laptop'tan çalışıyor: TV'yi dinler, oynayan videoyu görür, altyazıyı
üretir ve YouTube'un üstüne basar. **Normal kumandayla video aç, kapat, sar** —
takip eder. `Ctrl-C` ile durdur.

Altyazı kayıyorsa:

```powershell
python 05_live_demo.py --offset 0.4      # altyazıyı 0.4 sn ileri al
python 05_live_demo.py --reanchor 5      # pozisyonu daha sık tazele
```

**Bu adım çalışıyorsa Phase 1 sadece "aynı mantığı cihaza taşımak" demek.**

---

## Bana ne göndereceksin

1. `out/preflight.txt`
2. `python 03_analyze.py` çıktısının tamamı
3. **R4 cevabı:** yazılar tam ekran YouTube üstünde göründü mü? (evet/hayır)
4. 6. adımın özet tablosu
5. 5. adımdan bir çeviri örneği + annenin tepkisi
6. Bir şey patladıysa hata mesajının tamamı

---

## Sorun giderme

| Belirti | Sebep / çözüm |
|---|---|
| `adb: no devices/emulators found` | TV uykuda olabilir — uyandır. `adb connect <IP>:5555` tekrar dene. `adb tcpip 5555` reboot'ta sıfırlanır. |
| `unauthorized` | TV ekranındaki izin sorusunu kaçırdın. `adb disconnect` + tekrar bağlan, TV'ye bak. |
| `pairing failed` | Kod zaman aşımına uğradı. TV'den yeni kod al, hemen çalıştır. |
| `connect() failed` | TV uyanık ve YouTube açık olmalı. |
| `saved pairing is no longer valid` | `--pair <yeni kod>` ile tekrar eşleş. |
| TvOverlay `:5001` cevap vermiyor | TV'de uygulamayı bir kez elle aç. Laptop ve TV aynı ağda mı? |
| Overlay hiç görünmüyor | `adb shell appops get com.tabdeveloper.tvoverlay SYSTEM_ALERT_WINDOW` → `allow` diyor mu? |
| `03_analyze.py`: "Kullanılabilir örnek yok" | Ölçüm sırasında video oynamıyordu. Autoplay kapalı mı, video yeterince uzun mu? |
| Gemini `429` / kota | Ücretsiz katman limiti. `--workers 2` ile paralelliği düşür veya bekle. |

---

## Dosyalar

```
phase0/
├── 01_preflight.py      cihaz kontrolü + overlay kanıtı (R4)
├── 02_lounge_probe.py   otomatik senkron ölçümü (R1)
├── 03_analyze.py        ölçümü karara çevirir
├── 04_make_subs.py      altyazı üretimi + tarama
├── 05_live_demo.py      uçtan uca canlı prototip
└── evri/
    ├── adb.py           adb sarmalayıcıları
    ├── captions.py      yt-dlp ile caption çekme      -> Phase 1: NewPipeExtractor
    ├── cues.py          VTT parse, ASR temizliği      -> Phase 1: aynen port
    ├── sentences.py     parça -> cümle birleştirme    -> Phase 1: aynen port
    ├── translate.py     TranslationProvider + Gemini  -> Phase 1: aynen port
    ├── pipeline.py      caption -> çeviri -> cue      -> Phase 1: aynen port
    ├── tracking.py      PositionTracker + olay logu   -> Phase 1: aynen port
    ├── lounge.py        pyytlounge adaptörü           -> Phase 1: Kotlin/OkHttp
    └── overlay.py       TvOverlay HTTP                -> Phase 1: kendi overlay'imiz
```

`tracking.py` bilinçli olarak **hiçbir bağımlılık içermiyor** — `PositionTracker`
R1'in kalbi ve Kotlin'e port edilecek en kritik parça, o yüzden tek başına test
edilebilir tutuldu.

`evri/` altındaki modüller `DESIGN.md` bölüm 5'teki Kotlin arayüzlerini bilinçli
olarak yansıtıyor — Phase 1'de doğrudan port edilecekler.

## Notlar

- Git'e girmeyenler: `out/`, `.env`, `lounge_auth.json`, `.venv/`.
  Yeni bir makinede bölüm 1'i tekrar yap.
- Üretilen altyazılar `out/subs/` altında `videoId + dil + provider + promptSürümü`
  anahtarıyla önbelleğe alınıyor — aynı videoyu tekrar işlemek bedava ve anlık.
  Bu, Phase 1'deki cihaz önbelleğinin aynısı. Prompt'u değiştirdikten sonra eski
  çeviriyi görmek istemiyorsan `--no-cache` kullan.
- Maliyet: 1 saatlik video ≈ **$0.01'in altı**. 5. adımı gönül rahatlığıyla tekrarla.
- `05_live_demo.py` bir laptop'a bağımlı olduğu için kalıcı çözüm değil — ama işe
  yararsa Phase 1 bitene kadar geçici olarak kullanılabilir.
