# TODO

Madde madde, tek satır. Ayrıntı gerekirse `DESIGN.md` veya `phase0/DURUM.md`.
`[x]` bitti, `[~]` kısmen, `[ ]` bekliyor.

## Durum

Phase 0 kapandı. Phase 1'in gövdesi çalışıyor: uygulama Mi Box'ta kendi başına
YouTube'u dinliyor, altyazıyı çekiyor, çeviriyor, ekrana basıyor. Kalan işler
ürünleştirme — riskli bir bilinmeyen yok.

API anahtarı ve Lounge eşleştirmesi artık uygulama içinden yönetilebiliyor.
Overlay izninin cihazdan cihaza değişen akışı açık kaynak kurulum notlarında
açıklanmalı; iki yeni ekran da toplu cihaz testini bekliyor.

## Kullanıcı odaklı uygulama sırası

Mağaza hazırlığı değil, günlük kullanım ve açık kaynak yayın öncelikli. Sıra:

1. **ADB'siz kullanım:** uygulama içinden API anahtarı ve TV kodu girişi
2. **Altyazı görünümü:** renk, boyut, arka plan, konum ve canlı önizleme
3. **Akıcılık:** sarma/video değişiminde doğru iptal, daha hızlı algılanan başlangıç,
   gereksiz overlay güncellemelerini önleme
4. **Anlaşılır durumlar:** teknik hata yerine neden + kullanıcının yapacağı işlem
5. **Hızlı aç/kapa:** önce izinsiz seçenekler; AccessibilityService yalnız isteğe bağlı
6. **Kullanışlı ayarlar:** hedef dil, model ve çeviri üslubu
7. **Önbellek kontrolü:** kullanılan alan, temizleme ve boyut sınırı
8. **R10/Shorts deneyi:** MediaSession ile Lounge'a yalnız gerektiğinde bağlanma
9. **Açık kaynak yayını:** README, ekran görüntüleri, lisanslar, imzalı APK

Kurulum sihirbazı, geniş cihaz matrisi ve CI şu an hedef değil.

---

## Phase 1 — kalan işler

### Kurulabilirlik (en kritik)

- [~] **Eşleştirme ekranı** — TV kodunu kumandayla girme, auth state'i kalıcı saklama,
  değiştirme/kaldırma ve çalışan servisi yenileme kodu tamamlandı; cihaz testi bekliyor
- [~] **API anahtarı ayarı** — TV arayüzünden ekleme/kaldırma ve Android Keystore ile
  şifreli saklama tamamlandı; anahtarı kaydetmeden doğrulama ve QR bağlantısı bekliyor.
  Eski `adb push` dosyası geçiş uyumluluğu için okunuyor
- [ ] Overlay izni akışı: Android TV'de `MANAGE_OVERLAY_PERMISSION` ekranı yok, kullanıcıya ne söyleyeceğiz

### Ayarlar

- [~] Altyazı görünümü: 6 yazı rengi, 4 boyut, 4 arka plan seviyesi,
  3 dikey konum ve canlı önizleme tamamlandı; gerçek video üstünde toplu cihaz testi
  bekliyor. Özel renk, gölge ve maksimum genişlik ayarı daha sonra eklenebilir
- [ ] Hedef dil, model ve çeviri üslubu seçimi. Manuel offset kullanıcıya
  yüklenmeyecek; senkron bozulursa uygulama tarafında düzeltilecek
- [ ] "Önbelleği temizle" düğmesi + kullanılan alanı göster

### Önbellek

- [ ] Boyut tavanı (~100 MB) + LRU tahliye — şu an sınırsız büyüyor. 98 dk film 72 KB, yani tavanla ~1400 film sığar
- [ ] Anahtara model adını ekle — model değişince eski çıktı HIT dönüyor
- [ ] Prompt sürümü değişince eski anahtarları temizle

### Servis

- [ ] Boot'ta otomatik başlama
- [ ] Pil/doze davranışı: `deviceidle whitelist` gerekli mi, kullanıcıdan nasıl istenir

### Kısayol

- [ ] **Tek tuşla aç/kapa — ya `AccessibilityService` ile ya da hiç.** Ana ekrana kısayol koyma denendi ve kaldırıldı: YouTube'dan çıkıp Home'a gidip geri dönmek "tık diye kapatmak" değil.
  YouTube ön plandayken tuş yakalamanın tek desteklenen yolu `AccessibilityService` (`canRequestFilterKeyEvents` + `onKeyEvent`). Tuşu öğrenme ekranı izin gerektirmiyor, sadece global dinleme gerektiriyor.
  **Karar bekliyor:** Play Store erişilebilirlik iznine sert bakıyor. Bu uygulamanın amacı gerçekten erişilebilirlik — gerekçe yazılabilir ama garanti değil. Mağazaya çıkılmayacaksa risk yok.
  **Gerekçe:** Shorts'a girmeden önce kapatmak gerekiyor (R10). Türkçe video meselesi otomatik tespitle çözüldü, bu çözülemedi.

  **Mi Box deneyi (2026-09-06):** Xiaomi TV+ tuşu ham girişte `KEY_WWW`
  (`KEYCODE_EXPLORER`) üretiyor fakat üretici uygulaması tuşu normal Activity'den ve
  etkin `AccessibilityService` key filtresinden önce tüketiyor. Servis açıkken de
  Xiaomi TV+ açıldı; deneysel kısayol kodu geri çıkarıldı. Kısayol şimdilik ertelendi.

### Kalite

- [~] Video değişince eski altyazı hemen temizleniyor, önceki çeviri işi iptal ediliyor
  ve geç dönen sonuçların yeni videoya yazılması engelleniyor; hızlı ve önbelleksiz
  video değişimini kullanıcı akışında doğrula
- [ ] Overlay'i yalnız metin değiştiğinde güncelleme mevcut; geçişlerde titreme ve boşlukları ölç
- [~] Ağ, geçersiz API anahtarı, kota, caption ve kısmi çeviri hataları ayrı ve eyleme
  dönük mesajlara ayrıldı. Hazırlanıyor/çeviriliyor durumuna sayısal ilerleme bekliyor
- [ ] Cue'ların ~%11'i 2 satırı aşıyor
- [ ] Diyalog çizgileri tutarsız — model gerekli yerlerin hepsinde koymuyor, `3.6-flash` daha iyi ama 5 kat yavaş
- [ ] ASR yanlış duymaları çeviriye sızıyor; büyük model kısmen düzeltiyor

---

## Bağlı olmanın bedelleri (R10 / R10b) — çözülmeye değer

Cihazda ölçüldü. İkisi de "Lounge oturumu açıkken YouTube bizi yayın yapan bir telefon
sanıyor" başlığının altında.

**Belirtiler**
- Shorts hiç açılmıyor: "Cihazın bağlantısını kesin" diyaloğu çıkıyor
- Video bitince "SIRADAKİ / önerilenler" ekranı gelmiyor, ana sayfaya dönüyor

**Denenen ve işe yaramayan:** `capabilities`'i `vsp`'ye indirmek. Sunucu `que,mus`'u
kendisi ekliyor — `loungeStatus`'ta kendi kaydımız `"capabilities":"vsp,que,mus"`
görünüyor. Kuyruk yeteneğini reddetmek mümkün değil.

**Denenen ve alakasız çıkan:** `setAutoplayMode(DISABLED)`. Kaldırıldı; otomatik geçiş
geri gelmedi (yani sorunun sebebi o değilmiş) ve önerilenler ekranı da geri gelmedi
(yani onu bastıran da o değilmiş). Her ikisi de sadece bağlı olmaktan kaynaklanıyor.

**Şu anki hafifletme:** ekran bizi attığında ısrar etmiyoruz — YouTube'un kendi
"Bağlantıyı kes" düğmesi artık gerçekten işe yarıyor, altyazı kendini kapatıyor.

**Denenecek fikirler**
- [ ] `MediaSessionManager` ile oynatmayı yerelden izle (NotificationListener izni). Süre bilgisi de geliyor: **60 sn'den kısaysa Shorts'tur, hiç bağlanma.** Lounge'a yalnız gerçek video oynarken bağlanmak iki belirtiyi de büyük ölçüde giderir. Phase 0 preflight'ı `dumpsys media_session` dökümünü tam bu ihtimal için kaydetmişti
- [ ] Yukarıdaki iyi çalışırsa: pozisyonu da oradan almak mümkün mü, Lounge'a hiç gerek kalır mı? Hassasiyeti R1'e (p95 234 ms) karşı ölçülmeli
- [ ] `AccessibilityService` yolu: Shorts ekranını görüp otomatik çekilmek. Aynı izin tartışması, ama tuş kısayolunu da beraberinde getirir

---

## Cihazda doğrulananlar

- [x] Otomatik oynatma — video bitince kendiliğinden sonrakine **geçmiyor** (babanın şikâyeti çözüldü)
- [x] Aç/kapa anahtarı — overlay anında kayboluyor, YouTube'un "bağlı cihaz" göstergesi de gidiyor
- [x] Görsel switch — odakta net, OK ile dönüyor
- [x] Hedef dil tespiti — Türkçe videoda duruyor, "Video zaten Türkçe" bildirimi çıkıyor
- [x] Hazırlanıyor bildirimi — yeni videoda çıkıyor, önbellektekinde çıkmıyor
- [x] Önbellek — daha önce izlenen video anında geliyor
- [x] Shorts, altyazı **kapalıyken** sorunsuz
- [~] Altyazı konumu ve boyutu — kullanılabilir; ince ayar kullanıcı tercihine bağlanacak

### Henüz doğrulanmadı

- [ ] **Toplu ayar testi** — TV kodu gir/değiştir/kaldır; API anahtarı gir/değiştir/kaldır;
  uygulamayı kapatıp açınca ikisinin de korunduğunu ve servisin anında yenilendiğini doğrula
- [ ] **Toplu görünüm testi** — üç renk, dört boyut, arka plan kapalı/koyu ve üç konumun
  hem önizlemede hem YouTube overlay'inde anında ve okunaklı değiştiğini doğrula
- [ ] **Toplu hata testi** — yanlış API anahtarı ve internet kapalıyken doğru mesajın
  çıktığını; başarısız çevirinin önbelleğe yazılmadığını doğrula
- [ ] **"Bağlantıyı kes" düğmesi** — Shorts diyaloğunda basınca Shorts açılmalı, biz geri bağlanmamalıyız, anahtar kendiliğinden kapanmalı, sağ üstte bildirim çıkmalı *(kod hazır, kurulu, test edilmedi)*
- [ ] Reklam sonrası senkron — birim testinin yakaladığı hata düzeltildi, gerçek reklamlı videoda görülmedi
- [ ] Duraklat/devam — uzun duraklamadan sonra doğru yerden sürüyor mu

---

## Çeviri sağlayıcıları

- [ ] `TranslationProvider` arayüzü var — yeni sağlayıcı eklemek için glue code
- [ ] OpenAI sağlayıcısı
- [ ] Anthropic sağlayıcısı
- [ ] Sağlayıcı arası kalite karşılaştırması: aynı video, aynı prompt, yan yana
- [ ] Maliyet takibi: video başına token ve kuruş
- [ ] API key'i kullanıcının kendi hesabından alma akışı (uygulamaya gömülü key yok)

---

## Phase 2 — STT (altyazısı olmayan videolar)

> **Ertelendi.** Tarama: 10/10 videoda altyazı var. Phase 1 tamamen çeviri yoluyla
> yapılabilir. Buradaki maddeler gerçek kullanımda altyazısız videoyla karşılaşınca
> gündeme gelir.

- [x] Ses nereden gelecek — cihazdan yakalamaya gerek yok, videoId'den kaynağından çekiliyor (98 dk film = 32 MB opus)
- [x] Zaman damgası riski ölçüldü (R6) — **KIRMIZI**: \|hata\| medyan 2,27 sn, p95 4,01 sn. Metin iyi, zamanlama değil. Hata birikimli değil, cümle başına belirsizlik
- [ ] Modelden zaman istemeyen tasarım: 6-8 sn'lik parçalar, her parçanın metni kendi aralığında
- [ ] Alternatif: kelime düzeyinde zaman damgası veren STT (Deepgram, AssemblyAI, Whisper API) — saatlik $0,15-0,60
- [ ] NewPipeExtractor ile ses akışı URL'si çözümleme (Kotlin)
- [ ] Konuşmacı ayrıştırma (diarization) — diyalog çizgisi sorununun gerçek çözümü
- [ ] Canlı yayın ayrı iş: akışın "başı" olmadığı için streaming STT gerekiyor

---

## Genel

- [ ] `README.md` (kök) yaz: proje ne, kim için, nasıl kurulur
- [ ] `phase0` tarafına birim testleri (Kotlin tarafında var, Python tarafında yok)
- [ ] `01_preflight.py --install-tvoverlay` her çalıştırmada APK'yı yeniden indiriyor
- [ ] `platform-tools`'u `Downloads`'tan kalıcı bir yere taşı
- [ ] İş planı konuşması: mağazaya çıkma, lisans, kimin kurabileceği

---

## Bitenler

### Phase 0
- [x] R1 senkron ölçümü — p95 kayma **234 ms**, YEŞİL
- [x] R4 overlay — tam ekran YouTube üstünde çiziliyor
- [x] R7 çeviri kalitesi — **anne okuyabiliyor**, projenin tek gerçek kabul kriteri
- [x] Altyazı kapsamı taraması — 10/10 videoda mevcut, STT gerekmiyor
- [x] Uçtan uca prototip (`05_live_demo.py`) — senkron, sarma, duraklat/devam, video değişimi
- [x] Model kararı — canlı yolda `3.5-flash-lite` (30 cümlelik chunk 3,0 sn vs `3.6-flash` 15,2 sn)

### Phase 1
- [x] Proje iskeleti — `android/`, tek modül, leanback launcher, minSdk 28 / target 35, Gradle 8.13 + AGP 8.9 + Kotlin 2.1
- [x] **Lounge protokolü Kotlin/OkHttp portu (R3 KAPANDI)** — cihazda doğrulandı
- [x] `PositionTracker` portu — bağımlılıksız, enjekte edilebilir `Clock`
- [x] Periyodik `getNowPlaying` çapası (20 sn) ve yeniden abone olma döngüsü — ikisi de zorunlu, opsiyonel değil
- [x] Foreground service — oturum Activity'de yaşayamıyor, YouTube öne gelince Android Activity'yi yok ediyor
- [x] Kendi overlay'imiz — `TYPE_APPLICATION_OVERLAY`, alt-orta. TvOverlay iskelesi kaldırıldı
- [x] Caption çekme (NewPipeExtractor) — iki tuzak: `fmt=vtt` zorlanmalı, `desugar_jdk_libs_nio` şart
- [x] VTT parse + cümle birleştirme portu — Python'la **birebir aynı** (1305 cue, 826 cümle), parity testi sabitliyor
- [x] Çeviri portu — Phase 0'ın beş prompt dersi taşındı
- [x] **Kademeli çeviri** — izlenen pozisyonun chunk'ı önce; ilk altyazı 89 sn → **8,5 sn**
- [x] Cihaz üstü önbellek — `videoId + dil + provider + promptSürümü`
- [x] Aç/kapa anahtarı — varsayılan kapalı; kapalıyken bağlantı da kesiliyor
- [x] Hedef dil tespiti — otomatik Türkçe altyazı varsa video zaten Türkçe, uygulama duruyor
- [x] Durum bildirimleri — hazırlanıyor / zaten Türkçe / altyazı yok / alınamadı
- [x] 31 birim testi — tracker, VTT, cümle birleştirme, bozuk model cevapları, auth dosyası
- [x] `DESIGN.md` Phase 0 ve Phase 1 bulgularıyla güncellendi
