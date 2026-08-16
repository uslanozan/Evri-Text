# TODO

Madde madde, tek satır. Ayrıntı gerekirse `DESIGN.md` veya `phase0/DURUM.md`.
`[x]` bitti, `[ ]` bekliyor, `[~]` kısmen.

## Phase 0 — kalan

- [x] **Anneye gösterildi — okuyabiliyor.** R7 yeşil
- [x] Adım 7: canlı test geçti — senkron, sarma, duraklat/devam, video değişimi çalışıyor
- [x] **Phase 0 kapandı**
- [x] VLC'de çeviri kontrolü — takip edilebilir, kelime hataları tolere edilebilir seviyede
- [x] `phase0/videos.txt` — 10 link (podcast, kısa film, belgesel tarzı)
- [x] Adım 6: 10/10 videoda altyazı var (2 elle yazılmış EN, 8 ASR), **0 video STT gerektiriyor**
- [x] Model kararı: canlı yolda `3.5-flash-lite` (30 cümlelik chunk 3.0s vs 3.6-flash 15.2s), kalite gerekirse `--model gemini-3.6-flash`
- [x] R4 — overlay tam ekran YouTube üstünde görünüyor
- [x] R1 — senkron ölçümü: p95 kayma 234 ms, YEŞİL
- [x] Çeviri hattı uçtan uca çalışıyor: 826 cue, 0 başarısız chunk

## Cihazda doğrulanacaklar (TV müsait olunca, babanla birlikte)

Hepsi kod olarak hazır ve derleniyor; sadece gerçek TV'de görülmedi.
Kurulum: `cd android; .\gradlew.bat assembleDebug; adb install -r app\build\outputs\apk\debug\app-debug.apk`

- [ ] **Otomatik oynatma** — babanın bildirdiği hata. Bağlanınca ekranın autoplay'i kendiliğinden açılıyordu (Phase 0 logunda bağlantı anındaki ilk olay: `onAutoplayModeChanged {enabled:true}`). Artık her bağlanışta `setAutoplayMode(DISABLED)` gönderiliyor. **Test:** video bitene kadar bekle, kendiliğinden sonrakine geçmemeli
- [ ] **Aç/kapa anahtarı** — ayarlarda ilk satır, OK ile açılıp kapanmalı; kapatınca overlay anında kaybolmalı, açınca aynı videoda yeniden çeviri yapmamalı
- [ ] **Reklam sonrası senkron** — birim testinin yakaladığı hata düzeltildi; gerçek bir reklamlı videoda altyazı reklam sonrası kaymamalı
- [ ] **Duraklat/devam** — uzun süre duraklatıp devam ettir, altyazı doğru yerden sürmeli
- [ ] Altyazı konumu ve boyutu (40dp / 22sp) koltuktan rahat mı

## Phase 0 — bilinen eksikler (bloke etmiyor)

- [ ] Önbellek anahtarı model adını içermiyor: model değişince eski çıktı HIT dönüyor, elle silmek gerekiyor
- [ ] Cue'ların ~%11'i 2 satırı aşıyor (`max_chars=120` Türkçe'de 160 karaktere kadar şişebiliyor)
- [ ] Diyalog çizgileri tutarsız: model gerekli yerlerin hepsinde koymuyor, `3.6-flash` daha iyi
- [ ] `sentences.py` cümle sınırını karakter sayısıyla buluyor — ASR'de noktalama olmadığı için tek çare bu, daha iyisi araştırılabilir
- [ ] ASR yanlış duymaları çeviriye sızıyor (kelime hataları), büyük model kısmen düzeltiyor
- [ ] Ücretsiz katman kotası: `3.6-flash` günlük limiti 28 chunk'lık tek videoda doluyor
- [ ] `01_preflight.py --install-tvoverlay` her çalıştırmada APK'yı yeniden indirip kuruyor
- [ ] Hiç otomatik test yok; `tracking.py` bilinçli bağımsız tutuldu ama testi yazılmadı

## Phase 1 — Android uygulaması (Kotlin)

- [x] Proje iskeleti: `android/`, tek modül, leanback launcher, minSdk 28 / target 35, Gradle 8.13 + AGP 8.9 + Kotlin 2.1
- [x] `PositionTracker` Kotlin portu — bağımlılıksız, `Clock` enjekte edilebilir (birim testi için)
- [x] **Lounge protokolü Kotlin/OkHttp ile yazıldı (R3 KAPANDI)** — cihazda doğrulandı: bağlanma, chunk çözme, olay ayrıştırma, pozisyon takibi
- [x] Periyodik `getNowPlaying` çapa döngüsü — 20 sn, cihazda çalıştığı loglandı
- [x] Yeniden abone olma döngüsü — `LoungeSession.subscribeForever`, üstel geri çekilme
- [x] **Foreground service** — oturum Activity'de yaşayamıyor: YouTube öne gelince Android Activity'yi yok edip takibi öldürüyor
- [ ] Servisi boot'ta başlat + kullanıcı ayarından aç/kapat
- [ ] `PositionTracker` birim testleri (hız değişimi, reklam, video değişimi)
- [ ] Kendi overlay'imiz: `SYSTEM_ALERT_WINDOW`, TvOverlay'in yerine geçecek
- [ ] Overlay izni akışı: kullanıcıyı `MANAGE_OVERLAY_PERMISSION` ekranına yönlendir
- [x] Kendi overlay'imiz — `TYPE_APPLICATION_OVERLAY`, alt-orta, cihazda YouTube üstünde doğrulandı
- [ ] **Altyazı görünümü ayarlanabilir olsun:** yazı boyutu, alttan boşluk, arka plan opaklığı, maksimum genişlik. Şu an `dimens.xml`'de sabit (22sp / 40dp), TV'nin overscan miktarına göre değişmesi gerekiyor
- [ ] **Kademeli çeviri:** ilk chunk biter bitmez altyazıyı göster, tüm videoyu bekleme — Phase 0'da ilk altyazı 89 sn sonra geldi
- [ ] Altyazı çizimi ana thread'den ayrı olmalı — Phase 0'da senkron HTTP çağrısı event loop'u dondurdu, gecikme kartopu oldu
- [ ] "Altyazı hazırlanıyor" göstergesi: kullanıcı bekleme sırasında ne olduğunu görsün
- [x] Caption çekme: NewPipeExtractor — cihazda çalışıyor. İki tuzak: TTML yerine `fmt=vtt` zorlanmalı, ve `desugar_jdk_libs_nio` şart (API 30'da `URLDecoder.decode(String,Charset)` yok)
- [x] VTT parse + ASR temizliği portu — Python'la **birebir aynı sonuç** (1305 cue), parity testi var
- [x] Cümle birleştirme portu — birebir aynı (826 cümle)
- [x] Çeviri portu — Phase 0'ın beş prompt dersi taşındı, gerçek API'ye karşı testi var
- [x] **Kademeli çeviri** — izlenen pozisyonun chunk'ı önce; ilk altyazı 89 sn → **8,5 sn**
- [x] Cihaz üstü önbellek: `videoId + dil + provider + promptSürümü`
- [ ] Önbellek boyut tavanı + LRU tahliye (şu an sınırsız büyüyor)
- [ ] Önbellek saklama politikası: boyut tavanı (~100 MB) + LRU tahliye — 98 dk film 72 KB, yani ~1400 film sığar
- [ ] Prompt sürümü değişince eski anahtarları temizle (Phase 0'da elle siliyoruz)
- [ ] Önbellek anahtarına model adını ekle — şu an model değişince eski çıktı HIT dönüyor
- [ ] Ayarlarda "önbelleği temizle" düğmesi + kullanılan alanı göster
- [ ] Eşleştirme akışı: TV kodunu uygulama içinden girme, auth state'i kalıcı sakla
- [x] **Aç/kapa anahtarı** — ayarlarda ilk satır, varsayılan kapalı. Kapalıyken overlay çizilmiyor ve çeviri isteği atılmıyor
- [ ] Kısayolla aç/kapa — kumandadan uygulamaya girmeden. Seçenekler: `MediaSession` üzerinden bir tuş, ya da YouTube'un kullanmadığı bir tuşu `InputManager` ile yakalamak (izin sınırları araştırılmalı)
- [ ] Ayarlar ekranı: offset, yeniden çapa aralığı, hedef dil, model seçimi
- [ ] Servis olarak arka planda çalışma + boot'ta otomatik başlama
- [ ] Pil/doze davranışı: `deviceidle whitelist` gerekli mi, kullanıcıdan nasıl istenir
- [ ] Uygulamayı otomatik açacak shortcut ekleme yeri

## Çeviri sağlayıcıları

- [ ] `TranslationProvider` arayüzü zaten var — sağlayıcı ekleme için glue code yaz
- [ ] OpenAI sağlayıcısı
- [ ] Anthropic sağlayıcısı
- [ ] Sağlayıcı arası kalite karşılaştırması: aynı video, aynı prompt, yan yana
- [ ] Maliyet takibi: video başına token ve kuruş, kullanıcıya göster
- [ ] API key'i kullanıcının kendi hesabından alma akışı (uygulamaya gömülü key yok)

## Phase 2 — STT (altyazısı olmayan videolar)

> **Ertelendi.** Adım 6 taraması: 10/10 videoda altyazı var, hiçbiri STT gerektirmiyor.
> Phase 1 tamamen çeviri yoluyla yapılabilir. Buradaki maddeler ancak gerçek
> kullanımda altyazısız videolarla karşılaşınca gündeme gelir.

- [x] Ses nereden gelecek sorusu çözüldü: **cihazdan yakalamaya gerek yok**, videoId'den ses akışını doğrudan çekiyoruz — altyazıyı çektiğimiz yolun aynısı (98 dk film = 32 MB opus)
- [ ] `AudioPlaybackCapture` / `MediaProjection` yolu **gerekmiyor** — sadece canlı yayın veya YouTube dışı uygulama desteği istenirse gündeme gelir
- [ ] NewPipeExtractor ile ses akışı URL'si çözümleme (Kotlin tarafı)
- [ ] STT sağlayıcı karşılaştırması — hepsi online API: Deepgram, AssemblyAI, Google STT v2, ElevenLabs Scribe, Whisper API (cihaz yerel model çalıştıramayacak kadar güçsüz)
- [ ] Konuşmacı ayrıştırma (diarization) — diyalog çizgisi sorununun gerçek çözümü, çoğu STT API'si veriyor
- [ ] Gecikme tasarımı: sesi izlenen pozisyonun ilerisinden işle, altyazı yetişsin
- [ ] Canlı yayın ayrı iş: akışın "başı" olmadığı için gerçek zamanlı streaming STT gerekiyor
- [ ] Maliyet: STT saatlik ~$0.15-0.60, çeviri ~$0.01 — bu yüzden "sadece altyazı yoksa" kuralı şart

## Genel

- [ ] `DESIGN.md`'yi Phase 0 bulgularıyla güncelle (nabız atışı yok, bind kanalı kapanıyor)
- [ ] `README.md` (kök) yaz: proje ne, kim için, nasıl kurulur
- [ ] Otomatik testler: `tracking.py`, `sentences.py`, `translate.py` parser'ları
- [ ] `platform-tools`'u `Downloads`'tan kalıcı bir yere taşı, PATH'i güncelle
