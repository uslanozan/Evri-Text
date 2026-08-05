# TODO

Madde madde, tek satır. Ayrıntı gerekirse `DESIGN.md` veya `phase0/DURUM.md`.
`[x]` bitti, `[ ]` bekliyor, `[~]` kısmen.

## Phase 0 — kalan

- [ ] **Anneye göster, takip edebiliyor mu diye sor** — projenin tek gerçek kabul kriteri
- [ ] Adım 7: `python 05_live_demo.py` — TV'de uçtan uca canlı test (önce TvOverlay'i aç)
- [x] VLC'de çeviri kontrolü — takip edilebilir, kelime hataları tolere edilebilir seviyede
- [x] `phase0/videos.txt` — 10 link (podcast, kısa film, belgesel tarzı)
- [x] Adım 6: 10/10 videoda altyazı var (2 elle yazılmış EN, 8 ASR), **0 video STT gerektiriyor**
- [ ] Kota yarın sıfırlanınca `gemini-3.6-flash` ile tam çeviriyi tekrar üret ve flash-lite ile karşılaştır
- [x] R4 — overlay tam ekran YouTube üstünde görünüyor
- [x] R1 — senkron ölçümü: p95 kayma 234 ms, YEŞİL
- [x] Çeviri hattı uçtan uca çalışıyor: 826 cue, 0 başarısız chunk

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

- [ ] Proje iskeleti: Android TV uygulaması, leanback launcher, min API 28
- [ ] `PositionTracker`'ı Kotlin'e port et — `evri/tracking.py`, R1'in kalbi, birebir çevrilecek
- [ ] Lounge protokolünü Kotlin/OkHttp ile yaz — `evri/lounge.py` referans (R3)
- [ ] **Periyodik `getNowPlaying` çapa döngüsü** — TV kendiliğinden pozisyon göndermiyor, bu zorunlu
- [ ] **Yeniden abone olma döngüsü** — bind kanalı birkaç dakikada kapanıyor, `subscribe_forever` referans
- [ ] Kendi overlay'imiz: `SYSTEM_ALERT_WINDOW`, TvOverlay'in yerine geçecek
- [ ] Overlay izni akışı: kullanıcıyı `MANAGE_OVERLAY_PERMISSION` ekranına yönlendir
- [ ] Altyazı görünümü: yazı boyutu, kontrast, arka plan, konum — anne okuyabilsin diye ayarlanabilir
- [ ] Caption çekme: yt-dlp yerine NewPipeExtractor — `evri/captions.py` referans
- [ ] VTT parse + ASR temizliği portu — `evri/cues.py`
- [ ] Cümle birleştirme portu — `evri/sentences.py`
- [ ] Çeviri hattı portu — `evri/pipeline.py`
- [ ] Cihaz üstü önbellek: `videoId + dil + provider + promptSürümü` anahtarı, Phase 0 ile aynı şema
- [ ] Eşleştirme akışı: TV kodunu uygulama içinden girme, auth state'i kalıcı sakla
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
