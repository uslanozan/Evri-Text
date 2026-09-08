# TODO

Madde madde, tek satır. Ayrıntı gerekirse `DESIGN.md`.
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
9. **Açık kaynak yayını:** README, gizlilik/güvenlik metinleri, GPL ve üçüncü taraf
   bildirimleri, logo, marka politikası ve ekran görüntüleri tamamlandı; imzalı APK bekliyor

Kurulum sihirbazı, geniş cihaz matrisi ve CI şu an hedef değil.

---

## Phase 1 — kalan işler

### Kurulabilirlik (en kritik)

- [~] **Eşleştirme ekranı** — TV kodunu kumandayla girme, auth state'i kalıcı saklama,
  değiştirme/kaldırma ve çalışan servisi yenileme kodu tamamlandı; cihaz testi bekliyor
- [x] **API anahtarı ayarı** — TV arayüzünden ekleme/kaldırma, Android Keystore ile
  şifreli saklama ve kaydetmeden Gemini doğrulaması tamamlandı. Uzun anahtar Google TV
  telefon kumandasının klavyesiyle yapıştırılabiliyor; eski `adb push` dosyası geçiş
  uyumluluğu için okunuyor
- [ ] Overlay izni akışı: Android TV'de `MANAGE_OVERLAY_PERMISSION` ekranı yok, kullanıcıya ne söyleyeceğiz

### Ayarlar

- [x] Arayüz yerelleştirmesi — Android kaynak sistemiyle TV dilini otomatik izleyen
  Türkçe ve İngilizce metinler; yeni diller yalnız `values-<dil>` eklenerek büyüyebilir
- [~] Altyazı görünümü: 6 yazı rengi, 4 boyut, 4 arka plan seviyesi,
  3 dikey konum ve canlı önizleme tamamlandı; gerçek video üstünde toplu cihaz testi
  bekliyor. Özel renk, gölge ve maksimum genişlik ayarı daha sonra eklenebilir
- [ ] Hedef dil, model ve çeviri üslubu seçimi. Manuel offset kullanıcıya
  yüklenmeyecek; senkron bozulursa uygulama tarafında düzeltilecek
- [x] Kaynak dili otomatik seçme — İngilizceye özel kısıt kaldırıldı; Fransızca ve
  Arapça videolar gerçek TV'de kaynak dil seçimi, Gemini çevirisi ve overlay'e cue
  teslimiyle uçtan uca doğrulandı
- [ ] "Önbelleği temizle" düğmesi + kullanılan alanı göster

### Önbellek

- [ ] Boyut tavanı (~100 MB) + LRU tahliye — şu an sınırsız büyüyor. 98 dk film 72 KB, yani tavanla ~1400 film sığar
- [ ] Anahtara model adını ekle — model değişince eski çıktı HIT dönüyor
- [ ] Prompt sürümü değişince eski anahtarları temizle

### Servis

- Otomatik boot başlangıcı yapılmayacak; uygulama kullanıcı açmadıkça kendi kendine
  devreye girmemeli
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

**Şu anki hafifletme:** isteğe bağlı bildirim erişimiyle YouTube'un yerel MediaSession'ı
izleniyor. Normal video ayrıntıları geldiğinde Lounge bağlanıyor; oynatma ekranından
çıkılırken oturum `STOPPED/null` olur olmaz ayrılıyor. Shorts metadata'sı başlık,
sanatçı ve kapak alanlarını boş bıraktığı için orada yeniden bağlanmıyor. Bu izin
olmadan eski davranış korunuyor.

**Denenecek fikirler**
- [~] `MediaSessionManager` kapısı tamamlandı ve normal video → bağlan, Shorts → bağlı
  kalma akışı cihazda doğrulandı. Süre eşiği kullanılmıyor; Shorts artık üç dakikaya
  çıkabildiği için cihazda ölçülen boş metadata ayrımı kullanılıyor. Normal videodan
  doğrudan Shorts bağlantısına atlamak hâlâ uyarıyı bir kez gösteriyor: YouTube uyarıyı
  MediaSession'ın `STOPPED` olayından önce açıyor. Düğmeye basınca Shorts açılıyor ve
  Evri Text açık kalarak sonraki normal videoda yeniden bağlanabiliyor
- [~] MediaSession pozisyonu da kullanılabiliyor. Eşzamanlı cihaz koşularında fark
  sabit kaldı; duraklatmada 0 ms, ileri sarma yerleştikten sonra yaklaşık +134 ms ölçüldü.
  Lounge'ı yalnız video kimliğini almak için kısa süreli kullanmaya geçmeden önce reklam
  boyunca daha uzun ölçüm gerekli
- [ ] `AccessibilityService` yolu: Shorts ekranını görüp otomatik çekilmek. Aynı izin tartışması, ama tuş kısayolunu da beraberinde getirir

---

## Cihazda doğrulananlar

- [x] Otomatik oynatma — video bitince kendiliğinden sonrakine **geçmiyor** (babanın şikâyeti çözüldü)
- [x] Aç/kapa anahtarı — overlay anında kayboluyor, YouTube'un "bağlı cihaz" göstergesi de gidiyor
- [x] Görsel switch — odakta net, OK ile dönüyor
- [x] Hedef dil tespiti — Türkçe ses ile başka dildeki videoda hazır Türkçe altyazı
  ayrılıyor ve kullanıcıya doğru neden gösteriliyor
- [x] Hazırlanıyor bildirimi — yeni videoda çıkıyor, önbellektekinde çıkmıyor
- [x] Önbellek — daha önce izlenen video anında geliyor
- [x] Shorts, altyazı **kapalıyken** sorunsuz
- [x] Google TV telefon kumandası — API alanına metin gönderiyor, giriş bitince D-pad'e dönüyor
- [x] Geçersiz API anahtarı — Gemini doğrulamasında reddediliyor ve kayıtlı anahtar korunuyor
- [x] Akıllı YouTube bağlantısı — normal videoda Lounge bağlanıyor; Shorts oynarken bağlı kalmıyor
- [~] Altyazı konumu ve boyutu — kullanılabilir; ince ayar kullanıcı tercihine bağlanacak

### Henüz doğrulanmadı

- [ ] **Toplu ayar testi** — TV kodu gir/değiştir/kaldır; API anahtarı gir/değiştir/kaldır;
  uygulamayı kapatıp açınca ikisinin de korunduğunu ve servisin anında yenilendiğini doğrula
- [ ] **Toplu görünüm testi** — üç renk, dört boyut, arka plan kapalı/koyu ve üç konumun
  hem önizlemede hem YouTube overlay'inde anında ve okunaklı değiştiğini doğrula
- [~] **Toplu hata testi** — yanlış API anahtarı mesajı doğrulandı; internet kapalıyken
  doğru mesajın çıktığını ve başarısız çevirinin önbelleğe yazılmadığını doğrula
- [x] **"Bağlantıyı kes" düğmesi** — Shorts açılıyor, Evri Text Shorts boyunca ayrık
  kalıyor; akıllı bağlantı etkinken altyazı anahtarı açık kalıp sonraki normal videoda
  yeniden kullanılabiliyor
- [x] **Video sonu ekranı** — MediaSession kapısı video sonunda Lounge'ı ayırdı ve
  önerilenler ekranı cihazda geri geldi
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

- [x] `README.md` (kök): amaç, kurulum, derleme, mimari ve sınırlamalar
- [x] Logo ve uygulama kimliği — SVG marka kaynağı, Android vector launcher ikonu ve
  TV banner tamamlandı; paket kimliği `tr.com.uslanozan.evritext` olarak doğrulandı
- [x] Türkçe ve İngilizce README ile ayarlar, API anahtarı ve gerçek video üzerinde
  Türkçe altyazı ekran görüntüleri
- [x] Yayın güvenliği — Git geçmişinde anahtar taraması, en az izin, TLS zorlaması,
  yedekleme engeli, güvenlik politikası ve üçüncü taraf lisans bildirimi tamamlandı
- [ ] `platform-tools`'u `Downloads`'tan kalıcı bir yere taşı
- [x] Açık kaynak lisansı ve dağıtım sırası — GPL-3.0-or-later, önce GitHub

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
- [x] Proje iskeleti — depo kökünde tek modül, leanback launcher, minSdk 28 / target 35, Gradle 8.13 + AGP 8.9 + Kotlin 2.1
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
- [x] 62 birim testi — tracker, VTT, cümle birleştirme, bozuk model cevapları, auth dosyası
- [x] `DESIGN.md` Phase 0 ve Phase 1 bulgularıyla güncellendi
