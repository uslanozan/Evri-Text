# Evri-Text — Tasarım Dokümanı

Resmî YouTube uygulamasının üzerine, yapay zeka ile üretilmiş Türkçe altyazı bindiren bağımsız bir Android TV uygulaması.

**Hedef cihaz:** Xiaomi Mi Box S — ürün adı `MiTV-AFKR0`, kod adı `jaws`, armeabi-v7a, 2 GB RAM, **Android 11 / API 30**

> Cihaz kendini `MiTV-AFKR0` diye tanıtıyor ama donanım kutu, televizyon değil:
> `ro.build.characteristics=mbx` (*media box*) ve `ro.hdmi.device_type=4`
> (HDMI-CEC Playback Device, yani TV'ye takılan kaynak). Uygulama buraya kurulacak.
>
> **Tasarım başlangıçta Android 9 / API 28 varsayıyordu; cihaz Android 11 / API 30
> bildiriyor.** Bu bazı gerekçeleri değiştirdi — aşağıda işaretli.

**Durum:** **Phase 0 tamamlandı.** Bütün riskler ölçüldü, PC'den TV'ye canlı Türkçe altyazı basan prototip çalışıyor. Sıradaki: Phase 1, Android uygulaması.
Ölçüm sonuçları ve düzeltilen hatalar: [`phase0/DURUM.md`](phase0/DURUM.md). Yapılacaklar: [`TODO.md`](TODO.md).

---

## 1. Problem

Evde YouTube izlenirken İngilizce videolarda babam takip edebiliyor, annem edemiyor. YouTube'un kendi Türkçe otomatik çevirisi ya yok, ya da takip edilemeyecek kadar kötü. Amaç: aynı videoyu ikisi de rahatça izleyebilsin.

**Kısıt (kullanıcı tarafından belirlendi):** İzleme deneyimi **resmî YouTube uygulamasında** kalmalı. Ayrı bir video uygulamasına geçilmeyecek — hesap, abonelikler, öneriler ve kumanda alışkanlığı bozulmamalı. Bizim uygulamamız sadece ayarları tutar ve arkada çalışır.

### Neden mevcut çözümler yetmiyor

| Çözüm | Neden yetmiyor |
|---|---|
| YouTube'un kendi Türkçe auto-translate'i | ASR parçalarının kelime kelime makine çevirisi; Türkçe'de sık sık anlamsız (bkz. bölüm 4) |
| SmartTube fork'u | Teknik olarak daha sağlam ama **ayrı uygulamaya geçmek gerekiyor** — kısıtla çelişiyor. Bkz. bölüm 8 |
| Klasik çeviri API'si (DeepL, Google Translate) | Cümleyi bağlamsız çeviriyor, ASR hatalarını düzeltemiyor — **YouTube'un kendi kötü çevirisinin yaptığı işin aynısı.** Üstelik LLM'den 40-75 kat pahalı (bölüm 10) |
| Sistem sesini yakalayıp anlık STT | Cihaz API 30 olduğu için `AudioPlaybackCapture` teknik olarak **var** — ama uygulamalar `allowAudioPlaybackCapture=false` ile kapatabiliyor, DRM'li ses zaten yakalanamıyor ve altyazı yapısal olarak hep sesin gerisinde kalır. **Zaten gerekmiyor:** STT gerekirse sesi videoId'den kaynağından çekeriz (bölüm 2.4) |
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

**Kritik davranış:** olaylar **olay bazlı, nabız atışı yok.** İki olay arasında pozisyonu kendi saatimizle ileri sayıyoruz (anchor + interpolasyon).

### Phase 0'da ölçülenler — Kotlin portunu bunlar belirliyor

| Ölçüm | Sonuç |
|---|---|
| İnterpolasyon kayması | **p95 234 ms**, medyan 49 ms, kayma hızı **2.8 ms/sn** |
| 600 ms tolerans için gereken çapa aralığı | **213 sn** (biz 20 sn'de bir atıyoruz — 10× pay) |
| `getNowPlaying` sorgulama limiti | **Bulunamadı.** 0.4 sn aralıkla 25 istek, %100 cevap |
| Komut gecikmesi | pause 657 ms, play 375 ms, seek 343 ms |
| Seek doğruluğu | ±1 sn (1183 istendi → 1182.0 bildirildi) |

**Nabız atışının olmadığı doğrulandı: TV kendiliğinden hiç pozisyon göndermedi.** 90 saniyelik pasif dinlemede sıfır olay. Yani pozisyon tamamen bizim `getNowPlaying` çapamıza bağlı — neyse ki sorgu limiti de yok. **Kotlin portunda periyodik çapa döngüsü zorunlu, opsiyonel iyileştirme değil.**

**İkinci zorunluluk — yeniden abone olma.** `bind` kanalı tek bir uzun ömürlü HTTP okuması ve sunucu bunu **birkaç dakikada bir kapatıyor.** Kapanınca istemci sessizce "bağlı değil" durumuna düşüyor ve sonraki her komut hata veriyor. Phase 0'ın ilk ölçümü tam olarak bu yüzden yarıda öldü. Doğru davranış: stream bitince yeniden abone ol, oturum düştüyse `connect()`'i tekrarla, üstel geri çekilme uygula. Referans: `phase0/evri/lounge.py::subscribe_forever`.

### 2.2 Overlay

`TYPE_APPLICATION_OVERLAY` penceresi. YouTube overlay'i **engelleyemiyor**: engellemeye yarayan `setHideOverlayWindows()` API 31+, cihazda API 30. `FLAG_SECURE` de overlay'i engellemiyor.

**Phase 0'da bu cihazda kanıtlandı (R4).** TvOverlay ile hem geçici köşe bildirimi hem sabit altyazı, tam ekran oynayan YouTube videosunun üzerinde göründü. Sonra `05_live_demo.py` gerçek altyazıları aynı yoldan bastı.

> **Uyarı:** cihaz API 30, engelleme API'si API 31'de geliyor. Yani **bir Android sürümü payımız var.** Cihaz güncellenirse YouTube bu API'yi kullanmaya başlayabilir ve overlay yolu kapanabilir — o durumda bölüm 8'deki SmartTube yolu devreye girer.

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

**Phase 0'da ölçüldü** — 10 video (podcast, kısa film, belgesel tarzı):

| Kaynak | Oran |
|---|---|
| Manuel Türkçe | %0 |
| Manuel İngilizce | **%20** |
| Otomatik (ASR) | **%80** |
| Hiç caption yok | **%0** |

**Hiçbir video STT gerektirmedi.** Phase 1 tamamen çeviri yoluyla yapılabilir, Phase 2 ertelendi. Örneklem küçük (10 video); gerçek kullanımda altyazısız videoyla karşılaşılırsa Phase 2 geri gelir.

### 2.4 STT gerekirse ses nereden gelir

Sesi cihazdan yakalamıyoruz. Lounge zaten **videoId** veriyor; ses akışını caption'ı çektiğimiz yerin aynısından — NewPipeExtractor ile — çekeriz.

```
Lounge → videoId → NewPipeExtractor → ses akışı URL'si → STT API → cue'lar
```

98 dakikalık film için **sadece ses 32 MB** (opus 46 kbps), yani saatlik ~20 MB. `AudioPlaybackCapture`, `MediaProjection`, izin diyalogları — hiçbiri gerekmiyor. Bu yol ayrıca **diarization**'ı (konuşmacı ayrıştırma) da açar; diyalog çizgisi sorununun gerçek çözümü orada.

**Sınır:** canlı yayında çalışmaz — akışın "başı" olmadığı için gerçek zamanlı streaming STT gerekir, ayrı iş.

### 2.5 STT'nin asıl sorunu zaman, metin değil

`phase0/06_stt_probe.py` ile ölçüldü: aynı filmin sesini, altyazısı yokmuş gibi Gemini'ye
yazdırıp sonucu videonun gerçek altyazısına hizaladık.

| | |
|---|---|
| Transkripsiyon metni | Referansla neredeyse birebir — **sorun yok** |
| Zaman damgası \|hata\| medyan | **2,27 s** |
| p95 | **4,01 s** |
| Hatanın karakteri | İşaretler karışık, büyüklük konumla artmıyor → **birikimli kayma değil, cümle başına belirsizlik** |

Altyazı senkronu için gereken hassasiyet ~300 ms (R1'de ölçülen p95 234 ms). 2 saniye
izlerken fark edilir. Ve hata birikimli olmadığı için "başta hizala" işe yaramıyor.

**İkinci bulgu:** modelden "45. dakikadan itibaren yaz" diye istemek çalışmıyor. Model o
noktaya atlamıyor, baştan yazıp damgaları istenen pencerenin başına kaydırıyor. Yani
**sesi kendimiz kesmek zorundayız**, prompt'la aralık seçilemiyor.

**Çıkış yolu — modelden zaman hiç istememek.** Sesi biz kestiğimiz için her parçanın
dosyadaki yeri kesin. Parça yeterince kısaysa (6–8 sn) o parçanın metnini o parçanın
aralığında göstermek yeter; modelin zaman tahmini hiç kullanılmaz. Bedeli: altyazının en
küçük birimi parça uzunluğu olur ve parçaya bölünen cümleler ikiye ayrılır. 98 dakikalık
film 8 sn'lik parçalarla ~740 istek demek.

**Alternatif:** kelime düzeyinde zaman damgası veren STT servisleri (Deepgram, AssemblyAI,
Whisper API) hizalamayı kendileri yapıyor, milisaniye hassasiyeti veriyorlar. Saatlik
$0.15–0.60 — pahalı ama doğru araç. Phase 2 zaten ertelenmiş olduğu için karar o zamana
bırakıldı.

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

Dağıtım modeli **BYOK**'tur (kullanıcı kendi anahtarını getirir). Açık kaynak APK'ya
ortak bir anahtar gömülmez. Anahtar TV arayüzünden girilir, değeri UI'da geri
gösterilmez ve Android Keystore tarafından korunan AES-GCM şifreli veri olarak
saklanır. Eski geliştirme kurulumlarındaki `gemini_api_key.txt` yalnız geriye
uyumluluk için okunur; kullanıcı anahtarı UI'dan yönettiği anda dosya fallback'i
devre dışı kalır.

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

### Model seçimi — Phase 0'da ölçüldü

30 cümlelik (1488 karakter) tek chunk:

| Model | Süre |
|---|---|
| `gemini-3.5-flash-lite` | **3.0 s** |
| `gemini-3.6-flash` | **15.2 s** |

**Canlı yolun varsayılanı Flash-Lite** — gecikme burada kaliteden önemli. `3.6-flash` daha iyi çeviriyor (diyalog çizgilerini daha tutarlı koyuyor, ASR hatalarını daha iyi düzeltiyor) ama 5 kat yavaş; toplu üretim için seçenek olarak kalıyor.

**Not:** `gemini-2.5-flash-lite` yeni API key'lere kapatıldı (404 NOT_FOUND). Model adı bir yapılandırma değeri olmalı, koda gömülmemeli — sağlayıcılar modelleri emekliye ayırıyor.

### Prompt tasarımında öğrenilenler

Bunlar Kotlin portuna aynen taşınmalı, hepsi gerçek hata sonucu bulundu:

- **Girdi JSON'unda `text` anahtarını kullanma.** Model girdi anahtarını taklit edip çıktıda beklenen `tr` yerine `text` döndürüyor ve sonuç sessizce boş çıkıyor. `source` kullan.
- **Cevabı `raw_decode` ile ayrıştır**, `parse` ile değil — model dizinin ardına fazladan içerik ekleyebiliyor, katı ayrıştırma tüm chunk'ı çöpe atıyor.
- **Satır sonu isteme.** JSON string'i içine gerçek satır sonu koyunca cevap bozuluyor; ayırıcı bir işaret (` || `) isteyip kodda satır sonuna çevir.
- **Kısmi sonucu koru.** Model 30 maddeden 29'unu döndürdüyse eksik olanı kaynak metinle doldur, tüm chunk'ı düşürme.
- **429'da sunucunun `retryDelay` değerini oku**, kör yeniden deneme yapma.

**Not:** AssemblyAI, Deepgram, ElevenLabs client-side key kullanımını desteklemiyor — backend gerektirdikleri için "backend yok" kısıtıyla çelişiyorlar. Gemini ve OpenAI uygun.

---

## 6. Gecikme ve önbellek

- **Paralel chunk çevirisi:** cümleleri 30'luk bloklara böl, eşzamanlı gönder. Komşu chunk'lardan birkaç cümle bağlam olarak veriliyor ki sınırlarda kopukluk olmasın.
- **Önbellek anahtarı:** `videoId + hedefDil + providerId + promptSürümü`. Uygulama özel dizini, LRU ile boyut sınırlı. İkinci izlemede sıfır gecikme, sıfır maliyet.
- **Eager üretim:** `nowPlaying` olayı geldiği an üretim başlıyor.

### Gerçek ölçümler ve düzeltilmesi gereken varsayım

| | Ölçüm |
|---|---|
| 98,5 dk film, tam çeviri (Flash-Lite, 30'luk chunk, 3 işçi) | 826 cue, **75 sn**, 0 başarısız chunk |
| Üretilen `.srt` boyutu | **72 KB** → 100 MB önbellek tavanı ≈ 1400 film |
| Canlı testte **ilk altyazının ekrana gelmesi** | **89 sn** (182 cümle, `3.6-flash`, 30 sn'si 429 beklemesi) |

Son satır tasarımı değiştiriyor. Mevcut hat **tüm video çevrilene kadar hiçbir şey göstermiyor** — kullanıcı için "video başladı, bir buçuk dakika hiçbir şey yok" demek. Kabul edilemez.

**Phase 1 gereksinimi — kademeli çeviri:** izlenen pozisyonu kapsayan chunk hazır olur olmaz göstermeye başla, kalanı arka planda tamamla. Video baştan izleniyorsa ilk altyazı Flash-Lite ile ~3 saniyede gelir.

**İkinci gereksinim — çizim ana thread'de olmamalı.** Phase 0'da overlay çağrısı senkron HTTP'ydi ve doğrudan olay döngüsünde çalışıyordu; TV bir kez takıldığında her çizim denemesi döngüyü saniyelerce dondurdu, pozisyon takibi ve Lounge aboneliği de durdu. Tek bir takılma kartopuna dönüştü. Çizim ayrı thread'de, aynı anda tek istek olmalı.

---

## 7. Riskler

| # | Risk | Durum / azaltma |
|---|---|---|
| **R1** | **Senkron kayması.** Lounge olayları nabız atışı göndermiyor; arada interpolasyon yapıyoruz | ✅ **KAPANDI — p95 234 ms.** Nabız atışı olmadığı doğrulandı, ama `getNowPlaying`'in sorgu limiti de yok; 20 sn'de bir çapa fazlasıyla yetiyor. Canlı testte senkron tuttu. Kullanıcıya manuel offset yüklenmeyecek; sapma olursa uygulama tarafında düzeltilecek |
| **R2** | **Extractor bakımı bize kalıyor.** SmartTube fork'unda poToken/BotGuard'ı upstream hallediyordu; bağımsız uygulamada caption ve ses akışını kendimiz çekeceğiz | 🟡 **Çalışıyor, ama risk kalıcı.** NewPipeExtractor v0.26.4 cihazda caption buluyor. İki tuzak çıktı: URL `fmt=ttml` ile geliyor, `fmt=vtt`'ye zorlanmalı; ve kütüphane `URLDecoder.decode(String,Charset)` (API 33+) çağırdığı için API 30'da **`desugar_jdk_libs_nio`** zorunlu. YouTube değiştikçe güncellemek gerekecek — **bu mimarinin kabul edilen bedeli** |
| **R3** | **Lounge protokolünün JVM implementasyonu yok.** Python, Rust, Go, Node var; Kotlin yok | ✅ **KAPANDI.** OkHttp ile sıfırdan yazıldı ve cihazda doğrulandı: bağlanma, bind kanalı chunk çözme, olay ayrıştırma, interpolasyon, 20 sn'lik yeniden çapa. Çapa anındaki sapma ~110 ms, Phase 0 ölçümüyle tutarlı |
| **R4** | **Overlay'in tam ekran YouTube üzerinde göründüğü cihazda doğrulanmadı** | ✅ **KAPANDI.** Bu cihazda kanıtlandı. Tek uyarı: engelleme API'si API 31'de, cihaz API 30 — bir sürüm payımız var |
| **R5** | **Reklamlar.** Reklam sırasında pozisyon anlamını yitiriyor | 🟡 **Test edilmedi** — Phase 0 koşularında hiç reklam çıkmadı. Kod yolu var (`tracker.in_ad` → overlay gizleniyor) ama gerçek reklamla doğrulanmadı |
| **R6** | Gemini STT timestamp'leri güvenilmez | 🔴 **ÖLÇÜLDÜ — KIRMIZI.** Gerçek filmde, YouTube ASR'si doğruluk referansı alınarak: \|hata\| medyan **2,27 s**, p95 **4,01 s**. Metin kalitesi iyi, zamanlama değil. **Hata birikimli değil, her cümlede bağımsız** — yani "parçala, her parçanın offset'i bizde olsun" azaltması yetmiyor, belirsizlik parça içinde de duruyor. Ayrıntı ve çıkış yolu: bölüm 2.5 |
| **R7** | ASR caption kalitesi kötüyse çeviri de kötü olur | ✅ **KAPANDI.** ASR kaynaklı çeviri VLC'de kontrol edildi ve **hedef kullanıcı takip edebildi** — projenin tek gerçek kabul kriteri. Kalan kusurlar kozmetik: yer yer kelime hatası, cue'ların ~%11'i iki satırı aşıyor, diyalog çizgileri tutarsız |
| **R8** | Kurulum bir defalık ADB gerektiriyor; `adb tcpip 5555` reboot'ta kalıcı değil | ✅ Doğrulandı. Bu cihazda ayrı "ağ üzerinden hata ayıklama" seçeneği yoktu ama USB hata ayıklama açıkken 5555 zaten dinliyordu. İzin "her zaman" verilince kalıcı |
| **R10b** | **Bağlıyken video sonu ekranı kayboluyor.** Video bitince "SIRADAKİ / önerilenler" gelmiyor, ana sayfaya dönülüyor | ✅ **KAPANDI.** İsteğe bağlı MediaSession izniyle video `STOPPED/null` olduğunda Lounge hemen ayrılıyor. Gerçek videoda sona kadar izlendi ve önerilenler ekranının geri geldiği doğrulandı |
| **R10** | **Bağlıyken Shorts oynatılamıyor.** Lounge bir yayın protokolü ve Shorts o akışta desteklenmiyor; ekran bağlı bir kumanda görünce "cihazın bağlantısını kesin" diyor ve Shorts'tan çıkıyor | 🟡 **Büyük ölçüde azaltıldı.** MediaSession kapısı normal video ayrıntıları görünürken bağlanıyor, oynatma ekranı kapanınca ayrılıyor ve metadata'sı boş olan Shorts'ta geri bağlanmıyor. Normal video → Shorts doğrudan bağlantısında YouTube diyaloğu `STOPPED` olayından önce açtığı için bir kez "Bağlantıyı kes" gerekebiliyor; sonrasında Evri-Text anahtarı açık kalıyor ve normal videoda otomatik geri bağlanıyor |
| **R9** | **Sağlayıcı modeli emekliye ayırıyor.** `gemini-2.5-flash-lite` yeni key'lere kapatıldı, kod 404 aldı | Model adı yapılandırma değeri, koda gömülü değil. Ayarlarda seçilebilir olacak |

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

### Phase 0 — Doğrulama (Android kodu yazmadan) ✅ **TAMAMLANDI**

PC'de Python. Detaylı protokol: [`phase0/README.md`](phase0/README.md), sonuçlar: [`phase0/DURUM.md`](phase0/DURUM.md).

Elde edilenler:
- **R1 kapandı** — p95 234 ms kayma, sorgu limiti yok
- **R4 kapandı** — overlay tam ekran YouTube üstünde çiziliyor
- **R7 kapandı** — hedef kullanıcı çeviriyi takip edebiliyor
- Caption bulunma oranı **%100** → STT yolu ertelendi
- Çalışan prompt'lar + ölçülmüş gecikme/maliyet
- **PC'den TV'ye canlı Türkçe altyazı basan prototip** (`05_live_demo.py`) — senkron, sarma, duraklat/devam, video değişimi çalışıyor

### Phase 1 — Android uygulaması, çeviri yolu ← **şu an burada**

Teknik riskler kapandığı için kalan sıra kullanıcı deneyimine göredir:

1. ✅ **Proje iskeleti** — `android/`, tek modül, minSdk 28 / target 35
2. ✅ **İlk dikey dilim: pozisyon takipçisi.** `LoungeClient` + `PositionTracker` + foreground service. **R3 kapandı.**
   Buradan çıkan mimari kural: **oturum Activity'de yaşayamaz.** YouTube öne geldiği anda Android bizim Activity'mizi yok ediyor ve takip ölüyor. Süreç hayatta kalıyor ama coroutine'ler iptal oluyor. Servis zorunlu, ekran yalnızca bir ayar paneli.
3. 🟡 **ADB'siz kimlik bilgileri** — API anahtarı UI'sı, Keystore saklama, kaydetmeden
   Gemini doğrulaması ve Google TV telefon klavyesi tamamlandı. TV koduyla Lounge
   eşleştirmesi de çalışıyor; toplu ekle/değiştir/kaldır testi bekliyor. Eşleştirme
   değişince servis eski oturum döngülerini iptal edip yeni auth ile yerinde kuruluyor
4. ✅ **`SubtitleOverlay`** — `TYPE_APPLICATION_OVERLAY`, alt-orta, cihazda YouTube üstünde doğrulandı
5. ✅ **Caption + çeviri hattı portu** — NewPipeExtractor + `evri` modüllerinin portu, kademeli çeviriyle.
   Cihazda uçtan uca çalışıyor: **ilk altyazı 8,5 saniyede** (Phase 0'da 89 sn), tam video 19 saniyede.
   Ayrıştırma ve cümle birleştirme Python'la birebir aynı sonucu veriyor — parity testiyle sabitlendi.
6. **Görsel deneyim** — renk, boyut, arka plan, konum, hazır temalar ve canlı önizleme
7. **Akıcılık ve geri bildirim** — video/sarma geçişleri, ilerleme ve eyleme dönük hata mesajları
8. **Günlük kullanım** — hızlı aç/kapa, dil/model ayarları ve önbellek kontrolü
9. 🟡 **R10 azaltması** — MediaSession kapısı kuruldu ve normal video/Shorts ayrımı
   cihazda doğrulandı; doğrudan Shorts geçişi ve video sonu ekranı için sınır testleri sürüyor
10. **Açık kaynak yayını** — README, lisans kontrolü ve imzalı APK

Görsel deneyimin ilk dilimi tamamlandı: altı yazı rengi, dört yazı boyutu, dört siyah
arka plan opaklığı ve üç dikey konum seçeneği var. Ayar ekranındaki örnek metin renk,
boyut ve arka planı anında gösteriyor; çalışan overlay aynı `Settings` kaynağını her
çizimde okuyarak uygulama yeniden başlamadan güncelleniyor. Ağ, API yetkilendirme,
kota, caption indirme ve kısmi çeviri hataları da ayrı kullanıcı mesajlarına çevrildi.
Başarısız çeviri parçaları artık disk önbelleğine yazılmıyor.

### Phase 2 — STT fallback *(ertelendi)*

Taramada hiçbir video STT gerektirmedi. Gerekirse: videoId → NewPipeExtractor ile ses akışı → 30–60 sn parçalama → `GeminiSttProvider` (bölüm 2.4).

### Phase 3 — İsteğe bağlı genişleme

Ek provider'lar, reklam yönetimi (R5), boot davranışı ve mağaza hazırlığı.

---

## 10. Maliyet

1 saatlik video, Gemini Flash-Lite:

| Yol | Maliyet |
|---|---|
| Çeviri yolu | **< $0.01** *(Phase 0'da doğrulandı: 98,5 dk film, 38k kaynak karakter)* |
| STT yolu (Gemini ses girdisi) | ~$0.04 – $0.06 |
| Harici STT API'si (Deepgram, AssemblyAI vb.) | $0.15 – $0.60 |
| Tekrar izleme (önbellek) | **$0** |

**Ücretsiz katman gerçekte yetiyor** — Phase 0'ın tamamı ücretsiz key'le yapıldı. Tek sınır dakika/gün başına istek sayısı: 3 paralel işçi ve 30'luk chunk'la sorun çıkmıyor, `3.6-flash`'ın günlük limiti ise tek uzun videoda dolabiliyor.

**Karşılaştırma için — klasik makine çevirisi API'leri** aynı 38k karakterlik film için: DeepL/Google $0.77, Amazon $0.57, Microsoft $0.38. Yani LLM'den **40-75 kat pahalı** ve üstelik ASR hatalarını düzeltme, bağlam, ton, uzunluk kontrolü gibi ihtiyaçlarımızın hiçbirini karşılamıyorlar (bkz. bölüm 4).

---

## 11. Kapsam dışı

- Backend / sunucu (açık kısıt)
- Kullanıcılar arası altyazı paylaşımı (backend gerektirir)
- YouTube dışı platformlar
- Cihaz üzerinde AI modeli (2 GB RAM'li Mi Box S kaldırmaz)
- Sistem geneli ses yakalama — API 30'da teknik olarak mümkün ama **gereksiz**: ses gerekirse kaynağından çekiliyor (bölüm 2.4)
- Canlı yayınlarda STT (akışın başı olmadığı için streaming STT gerektirir)
- TTS / sesli okuma

## 12. Lisans notu

Satış planı yok; en fazla open source. SmartTube'un standart dışı lisansı artık ilgisiz — o yolu kullanmıyoruz. Kendi kodumuz özgürce lisanslanabilir; `pyytlounge` (Phase 0) ve NewPipeExtractor (Phase 1) lisansları yayın öncesi kontrol edilmeli.
