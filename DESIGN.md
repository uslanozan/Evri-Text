# Evri-Text — Tasarım Dokümanı

Resmî YouTube uygulamasının üzerine, yapay zeka ile üretilmiş Türkçe altyazı bindiren bağımsız bir Android TV uygulaması.

**Hedef cihaz:** Xiaomi Mi Box S — Amlogic S905X, 2 GB RAM, Android 9 / API 28
**Durum:** Tasarım + Phase 0 (doğrulama) aşamasında. Android kodu henüz yazılmadı.

---

## 1. Problem

Evde YouTube izlenirken İngilizce videolarda babam takip edebiliyor, annem edemiyor. YouTube'un kendi Türkçe otomatik çevirisi ya yok, ya da takip edilemeyecek kadar kötü. Amaç: aynı videoyu ikisi de rahatça izleyebilsin.

**Kısıt (kullanıcı tarafından belirlendi):** İzleme deneyimi **resmî YouTube uygulamasında** kalmalı. Ayrı bir video uygulamasına geçilmeyecek — hesap, abonelikler, öneriler ve kumanda alışkanlığı bozulmamalı. Bizim uygulamamız sadece ayarları tutar ve arkada çalışır.

### Neden mevcut çözümler yetmiyor

| Çözüm | Neden yetmiyor |
|---|---|
| YouTube'un kendi Türkçe auto-translate'i | ASR parçalarının kelime kelime makine çevirisi; Türkçe'de sık sık anlamsız (bkz. bölüm 4) |
| SmartTube fork'u | Teknik olarak daha sağlam ama **ayrı uygulamaya geçmek gerekiyor** — kısıtla çelişiyor. Bkz. bölüm 8 |
| Sistem sesini yakalayıp anlık STT | `AudioPlaybackCapture` Android 10+ gerektirir, **cihaz Android 9**. Ayrıca altyazı yapısal olarak hep sesin gerisinde kalır |
| Netflix / Prime | Altyazıları zaten var, problem değil |

---

## 2. Mimari

```
   ┌──────────────────────────────────┐
   │   Resmî YouTube TV uygulaması    │  ← babam normal kumandayla kullanıyor
   └──────────────┬───────────────────┘
                  │ Lounge API  (video ID + oynatma pozisyonu)
   ┌──────────────▼───────────────────┐
   │   Evri-Text (bağımsız uygulama)  │
   │                                  │
   │   LoungeClient                   │  ← pozisyon takibi
   │   CaptionSource / SttProvider    │  ← altyazı kaynağı
   │   TranslationProvider            │  ← LLM çevirisi
   │   SubtitleOverlay                │  ← SYSTEM_ALERT_WINDOW
   │   Ayarlar (API key, dil)         │
   └──────────────┬───────────────────┘
                  │ HTTPS
   ┌──────────────▼───────────────────┐
   │   Gemini / OpenAI / DeepL / ...  │
   └──────────────────────────────────┘
```

**Backend yok.** Tüm çağrılar cihazdan, kullanıcının kendi API key'iyle.

### 2.1 Pozisyon takibi — YouTube Lounge API

Telefondaki YouTube'un TV'yi kumanda etmesini sağlayan protokol ("TV kodu ile bağla"). Eşleşen bir istemci, YouTube TV uygulamasından **video ID'sini ve oynatma pozisyonunu** okuyabiliyor.

**Kanıt:** [iSponsorBlockTV](https://github.com/dmunozv04/iSponsorBlockTV) tam olarak bunu yapıp sponsor bölümlerini atlıyor. İhtiyacımız olan yeteneğin çalıştığı kanıtlanmış.

Protokol: `https://www.youtube.com/api/lounge/bc/bind` üzerinden uzun ömürlü, parça parça okunan bir HTTP bağlantısı. Sunucu olay gönderiyor:

| Olay | İçerik |
|---|---|
| `nowPlaying` | `video_id`, `current_time`, `duration`, `state` |
| `onStateChange` | `current_time`, `duration`, `state` — **video_id yok** |
| `onAdStateChange` / `adPlaying` | reklam durumu |

Gönderebildiğimiz komutlar: `play`, `pause`, `seekTo`, `setPlaylist` (videoId ile), `setPlaybackSpeed`, **`getNowPlaying`** (anlık pozisyon sorgusu).

**Kritik davranış:** olaylar **olay bazlı, nabız atışı yok.** İki olay arasında pozisyonu kendi saatimizle ileri sayıyoruz (anchor + interpolasyon). `getNowPlaying` ile periyodik olarak yeniden çapa atmak mümkün — ama sorgulama sıklığı limiti bilinmiyor. **Phase 0'ın ana ölçümü bu.**

### 2.2 Overlay

`TYPE_APPLICATION_OVERLAY` penceresi. Android TV'de çalıştığı kanıtlı ([TvOverlay](https://github.com/gugutab/TvOverlay), ytoverlayapp). YouTube overlay'i **engelleyemiyor**: engellemeye yarayan `setHideOverlayWindows()` API 31+, cihazda API 28. `FLAG_SECURE` de overlay'i engellemiyor.

İzin, Android TV'de ayarlar menüsünden verilemiyor — `MANAGE_OVERLAY_PERMISSION` ekranı TV sürümlerinde yok. Bir kez ADB ile veriliyor:

```
adb shell appops set <paket> SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +<paket>
```

`appops` ayarı `/data/system/appops.xml`'e yazılıyor, **reboot'ta kalıcı.** Bir kez kurulur, sonra kimsenin ADB'ye dokunması gerekmez.

### 2.3 Altyazı kaynağı öncelik sırası

```
1. Manuel Türkçe caption varsa      → hiçbir şey yapma, YouTube kendi gösterir
2. Manuel İngilizce caption varsa   → ÇEVİRİ YOLU  (kalite en iyi)
3. Otomatik (ASR) caption varsa     → ÇEVİRİ YOLU  (kalite iyi)
4. Hiç caption yok                  → STT YOLU     (yavaş, pahalı)
```

Hangisinin ne sıklıkta gerektiği Phase 0'da gerçek videolarla ölçülecek.

---

## 3. Platform kararı: Kotlin, Flutter değil

1. **Extractor'lar JVM kütüphanesi.** NewPipeExtractor Java; Dart'tan kullanmak için platform channel + yine Java yazmak gerekir.
2. **Overlay + arka plan servisi native Android API'si.** `TYPE_APPLICATION_OVERLAY`, `Service`, `appops` — hepsi platform kanalından geçmek zorunda kalır, Flutter katmanı sadece ek yük olur.
3. **Flutter engine 2 GB RAM'li Mi Box S'te fazla.** Uygulamanın görünür UI'ı sadece bir ayarlar ekranı + bir metin overlay'i; Flutter'ın getireceği hiçbir avantaj yok.

**Kotlin bilmemek problem değil** — Dart'tan geçiş neredeyse birebir:

| Dart | Kotlin |
|---|---|
| `String?` + null safety | `String?` + null safety (aynı mantık) |
| `async` / `await` / `Future` | `suspend` / coroutines |
| `class X { final int a; }` | `data class X(val a: Int)` |
| `abstract class` / `implements` | `interface` / `: Interface` |
| extension methods | extension functions |

---

## 4. Çeviri neden Google Translate değil

YouTube'un otomatik altyazıları bize parçalı gelir:

```
00:01:12.340 --> 00:01:14.120   so the thing about this
00:01:14.120 --> 00:01:16.500   engine is that it doesn't really
00:01:16.500 --> 00:01:18.900   care about how much power you feed it
```

Bu parçaları tek tek makine çevirisine vermek → *"yani bununla ilgili olan şey"* / *"motor öyle ki gerçekten değil"*. Anlamsız. **YouTube'un kendi Türkçe çevirisinin kötü olmasının sebebi tam olarak bu.**

Bizim üç adımımız:

1. **Parçaları cümleye birleştir** → `"So the thing about this engine is that it doesn't really care about how much power you feed it."`
2. **LLM'e bağlamla ver** — video başlığı, komşu cümleler, ve *"bu ASR çıktısı, duyma hatalarını bağlamdan düzelt"* talimatı
3. **Sonucu birleştirilen parçaların zaman aralığına tek cue olarak yaz** → `00:01:12.340 --> 00:01:18.900`

Çıktı: **"Bu motorun olayı şu: ona ne kadar güç verdiğin pek fark etmiyor."**

Çeviriyi tekrar orijinal parçalara bölmeye **çalışmıyoruz** — Türkçe kelime sırası farklı olduğu için o zaten yanlış olur. Cümle bazında cue, TV'de okunabilirlik için de doğrusu (1–6 saniye, tam cümle).

---

## 5. Provider adapter'ları

Sağlayıcı ve API key runtime'da değiştirilebilir. Phase 0'daki Python modülleri (`phase0/evri/`) bu arayüzleri birebir yansıtıyor — Kotlin portu doğrudan çeviri olacak.

```kotlin
data class Cue(val startMs: Long, val endMs: Long, val text: String)

interface TranslationProvider {
    val id: String                    // "gemini", "openai", "deepl"
    val displayName: String

    suspend fun translate(
        sentences: List<String>,
        sourceLang: String?,          // null = otomatik algıla
        targetLang: String,
        context: TranslationContext,  // video başlığı, komşu cümleler
    ): List<String>                   // girdiyle 1:1, aynı sırada
}

interface SttProvider {
    val id: String
    val displayName: String
    val acceptedFormats: Set<AudioFormat>
    val translatesDirectly: Boolean   // true ise ayrı çeviri adımı atlanır

    suspend fun transcribe(
        audio: AudioChunk,            // ses + mutlak başlangıç offset'i
        sourceLang: String?,
        targetLang: String?,
    ): List<Cue>                      // offset'e göre normalize edilmiş
}
```

**Başlangıç:** `GeminiTranslationProvider` (Flash-Lite — ucuz, Türkçe kalitesi iyi) ve `GeminiSttProvider` (ses girdisi, tek istekte transkribe + çeviri; Opus/WebM'i doğrudan kabul ediyor).

**Not:** AssemblyAI, Deepgram, ElevenLabs client-side key kullanımını desteklemiyor — backend gerektirdikleri için "backend yok" kısıtıyla çelişiyorlar. Gemini ve OpenAI uygun.

---

## 6. Gecikme ve önbellek

- **Paralel chunk çevirisi:** caption'ı ~5 dakikalık bloklara böl, hepsini eşzamanlı gönder. 1 saatlik video ≈ 12 chunk → duvar saati ≈ tek chunk süresi ≈ **3–8 saniye**. Kullanıcı bunu kabul etti.
- Komşu chunk'lardan birkaç cümle bağlam olarak veriliyor ki sınırlarda kopukluk olmasın.
- **Önbellek anahtarı:** `videoId + hedefDil + providerId + promptSürümü`. Uygulama özel dizini, LRU ile boyut sınırlı. İkinci izlemede sıfır gecikme, sıfır maliyet.
- **Eager üretim:** `nowPlaying` olayı geldiği an üretim başlıyor; kullanıcı fark etmeden hazır oluyor.

---

## 7. Riskler

| # | Risk | Durum / azaltma |
|---|---|---|
| **R1** | **Senkron kayması.** Lounge olayları nabız atışı göndermiyor; arada interpolasyon yapıyoruz. Sponsor atlamak ±1 sn toleranslı, altyazı daha hassas olmak zorunda | **Phase 0'ın ana ölçümü.** Azaltma: `getNowPlaying` ile periyodik yeniden çapa. Sorgulama limiti bilinmiyor — ölçülecek. Kullanıcı ayarında elle offset düzeltmesi de olacak |
| **R2** | **Extractor bakımı bize kalıyor.** SmartTube fork'unda poToken/BotGuard'ı upstream hallediyordu; bağımsız uygulamada caption ve ses akışını kendimiz çekeceğiz | NewPipeExtractor'ı Gradle bağımlılığı olarak ekle (`PoTokenProvider` hook'u var). YouTube değiştirdikçe bağımlılık güncellemek gerekiyor. **Bu mimarinin kabul edilen bedeli** |
| **R3** | **Lounge protokolünün JVM implementasyonu yok.** Python, Rust, Go, Node var; Kotlin yok | Kendimiz yazacağız. Düz HTTP + satır satır okuma → OkHttp ile makul iş. Phase 0'da protokolü canlı gözlemleyip öğreniyoruz |
| **R4** | **Overlay'in tam ekran YouTube üzerinde göründüğü cihazda doğrulanmadı.** API 28'de teorik olarak engellenemez ama rapor yok | **Phase 0 adım 3'te TvOverlay ile kanıtlanıyor** — hiç Android kodu yazmadan |
| **R5** | **Reklamlar.** Reklam sırasında pozisyon anlamını yitiriyor | `onAdStateChange` / `adPlaying` olayları var; reklam boyunca overlay'i gizle |
| **R6** | Gemini STT timestamp'leri güvenilmez (dokümante edilmiş drift) | Sesi 30–60 sn parçalara böl, her parçanın mutlak offset'i bizde → hata tek parçayla sınırlı |
| **R7** | ASR caption kalitesi kötüyse çeviri de kötü olur | Ölçülecek. LLM'e "ASR hatalarını düzelt" talimatı belirgin fark yaratıyor |
| **R8** | Kurulum bir defalık ADB gerektiriyor; `adb tcpip 5555` reboot'ta kalıcı değil | İzinler kalıcı, sadece izin verme anı ADB istiyor. Bir kez kur, bir daha gerekmez |

---

## 8. Değerlendirilip elenen alternatif: SmartTube fork'u

Kayda geçsin diye — ilk tasarım buydu ve **teknik olarak daha sağlamdı**:

| | SmartTube fork | Overlay (seçilen) |
|---|---|---|
| Senkron | **Kesin** — altyazı oynatıcının içinde | İnterpolasyonla tahmin (R1) |
| Extractor bakımı | **Upstream'de** (poToken dahil) | Bize ait (R2) |
| İzin/kurulum | Yok | Bir defalık ADB (R8) |
| İzleme deneyimi | **Ayrı uygulamaya geçmek gerekiyor** | Resmî YouTube'da kalıyor |

Son satır belirleyici oldu: kullanıcı deneyimi kısıtı teknik sağlamlıktan önce geldi. Araştırma notları: SmartTube altyazıları ExoPlayer'a kendi ürettiği DASH manifesti içinde WebVTT URL'i olarak veriyor; enjeksiyon noktası tek metot (`MediaItemFormatInfoImpl.getSubtitles()`). Overlay yolu tıkanırsa buraya dönülebilir.

---

## 9. Yol haritası

### Phase 0 — Doğrulama (Android kodu yazmadan) ← **şu an burada**

PC'de Python. Amaç: R1 ve R4'ü ölçmek, çeviri kalitesini görmek, ve **uçtan uca çalışan bir prototip** çıkarmak. Detaylı protokol: [`phase0/README.md`](phase0/README.md).

`pyytlounge` TV'yi kumanda edebildiği (`play_video`, `seek_to`, `pause`, `get_now_playing`) ve TvOverlay HTTP ile sürülebildiği için **tüm test tek oturumda, script tarafından otomatik** yürütülüyor. Elle yapılacak tek şey eşleştirme kodunu girmek.

Çıktılar:
- Ölçülmüş drift istatistikleri ve `getNowPlaying` sorgulama limiti → R1 kararı
- Overlay'in tam ekran YouTube üstünde göründüğünün kanıtı → R4 kararı
- Çalışan prompt'lar + gecikme/maliyet ölçümü
- Gerçek videolarda caption bulunma oranı → çeviri yolu mu STT yolu mu baskın
- **PC'den TV'ye canlı Türkçe altyazı basan prototip**

### Phase 1 — Android uygulaması, çeviri yolu

1. Ayarlar ekranı (API key, dil, offset) + `EncryptedSharedPreferences`
2. `LoungeClient` — protokolün Kotlin portu (OkHttp)
3. Eşleştirme akışı (TV kodu girişi)
4. `SubtitleOverlay` — `TYPE_APPLICATION_OVERLAY`, foreground service
5. Caption çekme (NewPipeExtractor) + `evri` modüllerinin Kotlin portu
6. Cihazda gerçek test

### Phase 2 — STT fallback

Caption'ı olmayan videolar için ses indirme + 30–60 sn parçalama + `GeminiSttProvider`.

### Phase 3 — Cilalama

Disk önbelleği + LRU, ek provider'lar, altyazı stili, reklam yönetimi (R5), Türkçe hata mesajları.

---

## 10. Maliyet

1 saatlik video, Gemini Flash-Lite:

| Yol | Maliyet |
|---|---|
| Çeviri yolu (~13k girdi + ~15k çıktı token) | **< $0.01** |
| STT yolu (115k ses token'ı, 32 token/sn) | **~$0.04 – $0.06** |
| Tekrar izleme (önbellek) | **$0** |

Ev kullanımında aylık maliyet dolar altı. Ücretsiz katman bile büyük ölçüde yetebilir.

---

## 11. Kapsam dışı

- Backend / sunucu (açık kısıt)
- Kullanıcılar arası altyazı paylaşımı (backend gerektirir)
- YouTube dışı platformlar
- Cihaz üzerinde AI modeli (Mi Box S kaldırmaz)
- Sistem geneli ses yakalama (Android 9'da API yok)
- TTS / sesli okuma

## 12. Lisans notu

Satış planı yok; en fazla open source. SmartTube'un standart dışı lisansı artık ilgisiz — o yolu kullanmıyoruz. Kendi kodumuz özgürce lisanslanabilir; `pyytlounge` (Phase 0) ve NewPipeExtractor (Phase 1) lisansları yayın öncesi kontrol edilmeli.
