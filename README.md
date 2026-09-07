<p align="center">
  <img src="docs/brand/evri-text-mark.svg" width="128" alt="Evri Text logosu">
</p>

# Evri Text

Android TV'deki resmî YouTube uygulamasında oynatılan videolara yapay zekâ destekli
Türkçe altyazı ekleyen açık kaynak yardımcı uygulama.

Evri Text videoyu değiştirmez veya ayrı bir oynatıcı kullanmaz. YouTube'da oynayan
videoyu takip eder, erişilebilir altyazı parçasını Gemini ile Türkçeye çevirir ve
sonucu videoyla eşzamanlı bir ekran katmanında gösterir.

> [!IMPORTANT]
> Proje aktif geliştirme aşamasındadır. Evri Text; Google, YouTube veya Gemini
> tarafından geliştirilmiş, desteklenmiş ya da onaylanmış değildir.

## Neler sunuyor?

- Resmî YouTube for Android TV uygulamasıyla birlikte çalışır.
- Kaynak dili otomatik algılar; İngilizce, Fransızca ve Arapça dahil, erişilebilir
  YouTube altyazısı bulunan farklı dillerden Türkçeye çevirebilir.
- İzlenen bölümü önceliklendirir ve tamamlanan parçaları bekletmeden gösterir.
- Video değiştirme, sarma, duraklatma, reklam, Shorts ve video sonu durumlarını izler.
- Altyazı boyutu, rengi, arka planı ve ekran konumu TV kumandasıyla ayarlanabilir.
- Arayüz TV'nin sistem dilini izler; Türkçe varsayılandır, İngilizce desteklenir.
- API anahtarını yalnızca cihazda, şifreli biçimde saklar.
- Kendine ait sunucu, kullanıcı hesabı, reklam veya analiz sistemi kullanmaz.

## Gereksinimler

- Android TV veya Google TV — Android 9 / API 28 ve üzeri
- Resmî YouTube uygulaması
- Kullanıcının kendi [Gemini API anahtarı](https://aistudio.google.com/apikey)
- Ekran üzerinde gösterim izni
- Akıllı bağlantı takibi isteniyorsa bildirim erişimi

## Kurulum ve kullanım

1. Evri Text APK'sını TV'ye kurup uygulamayı açın.
2. YouTube'da **Ayarlar → TV koduyla bağla** yolundan bir kod oluşturun.
3. Kodu Evri Text'teki eşleştirme alanına girin.
4. Gemini API anahtarınızı ekleyin. Uzun anahtarları girmek için Google TV mobil
   kumandasının klavyesini kullanabilirsiniz.
5. Ekran üzerinde gösterim iznini verin.
6. İsterseniz **Akıllı YouTube bağlantısı** için bildirim erişimini etkinleştirin.
7. Evri Text altyazısını açın ve YouTube'da bir video oynatın.

Evri Text bildirimlerin içeriğini okumaz. Bildirim erişimini yalnızca YouTube'un aktif
bir medya oturumu olup olmadığını anlamak ve uygulamalar arasında geçiş yapılınca
bağlantıyı doğru yönetmek için kullanır.

## Nasıl çalışıyor?

1. YouTube Lounge oturumu oynatılan videonun kimliğini ve konumunu sağlar.
2. NewPipeExtractor videonun erişilebilir altyazı parçalarını bulur.
3. Altyazılar okunabilir cümlelere dönüştürülür ve Gemini ile Türkçeye çevrilir.
4. Çevrilen cümleler Android overlay penceresinde doğru zamanda gösterilir.

Ayrıntılı teknik kararlar [DESIGN.md](DESIGN.md), planlanan işler ise
[TODO.md](TODO.md) içinde yer alır.

## Kaynaktan derleme

Depo kökünü Android Studio ile açın veya Java 17 kurulu bir ortamda çalıştırın:

```powershell
.\gradlew.bat assembleDebug
```

Oluşan APK: `app/build/outputs/apk/debug/app-debug.apk`

Birim testleri çalıştırmak için:

```powershell
.\gradlew.bat testDebugUnitTest
```

Gerçek Gemini API'sini kullanan isteğe bağlı testler için `.env.example` dosyasını
`.env` adıyla kopyalayıp kendi anahtarınızı girin. Anahtarınızı Git'e eklemeyin.

## Yerelleştirme

Android kaynak sistemi kullanılır. `app/src/main/res/values/strings.xml` varsayılan
Türkçe metinleri, `values-en/strings.xml` İngilizce metinleri içerir. Yeni bir arayüz
dili eklemek için örneğin `values-fr/strings.xml` oluşturulabilir.

Arayüz dili ile altyazı hedef dili birbirinden bağımsızdır. Mevcut sürümün altyazı
hedefi Türkçedir.

## Gizlilik ve sınırlamalar

Veri akışı ve kullanılan servisler [PRIVACY.md](PRIVACY.md) içinde açıklanmıştır.
Evri Text resmî olmayan YouTube uç noktalarına ve NewPipeExtractor'a dayanır;
YouTube'daki değişiklikler uygulamayı geçici olarak bozabilir. Yalnızca erişilebilir
bir altyazı parçası bulunan videolar çevrilebilir.

## Katkıda bulunma

Hata bildirimleri ve pull request'ler memnuniyetle karşılanır. Davranış değişiklikleri
için ilgili birim testini ekleyin ve mümkünse sonucu gerçek bir Android TV / Google TV
cihazında doğrulayın. API anahtarlarını, eşleştirme verilerini veya kişisel cihaz
loglarını depoya göndermeyin.

Paket kimliği `tr.com.uslanozan.evritext` değeridir. Debug derlemeleri cihazda
`tr.com.uslanozan.evritext.debug` kimliğiyle kurulur.

## Lisans

Evri Text, [GNU General Public License v3.0 veya sonrası](LICENSE) altında dağıtılır.
Üçüncü taraf bileşenler kendi lisanslarına tabidir. Altyazı çıkarma için kullanılan
NewPipeExtractor da GPL-3.0-or-later lisanslıdır.

Ayrıntılar için [üçüncü taraf bildirimlerine](THIRD_PARTY_NOTICES.md) ve
[marka politikasına](TRADEMARKS.md) bakın. Güvenlik açıklarını bildirme yöntemi
[SECURITY.md](SECURITY.md) içinde açıklanmıştır.
