# Gizlilik

Son güncelleme: 10 Eylül 2026

Evri Text'in kendine ait bir sunucusu, kullanıcı hesabı, reklamı veya analiz
servisi yoktur. Uygulama çalışabilmek için aşağıdaki haricî servislerle doğrudan
iletişim kurar:

- **YouTube:** TV eşleştirmesi, oynatılan video bilgisi ve mevcut altyazıların
  alınması için.
- **Seçilen LLM sağlayıcısı:** Kaynak altyazı, video başlığı ve yakın cümle bağlamını
  Türkçeye çevirmek için. Kullanıcı Gemini, OpenAI, OpenRouter, Anthropic veya Groq
  arasından seçim yapar; istekler kullanıcının sağladığı API anahtarıyla seçilen
  servis üzerinden gönderilir.

OpenRouter seçildiğinde istek, OpenRouter'ın yönlendirdiği alt model sağlayıcısı
tarafından da işlenebilir. Bu yönlendirme ve veri işleme OpenRouter hesabındaki
tercihlere ve OpenRouter'ın gizlilik şartlarına tabidir.

Her sağlayıcının API anahtarı Android Keystore ile korunan biçimde, ayrı ayrı ve
yalnızca cihazda saklanır. Anahtar, API isteklerinde URL yerine bir kimlik doğrulama
başlığında gönderilir.
Üretilen altyazılar tekrar kullanım için uygulamanın özel önbelleğinde tutulur.
Evri Text bu verileri geliştiriciye veya başka bir analiz servisine göndermez.

Akıllı YouTube bağlantısı etkinleştirilirse Android, Evri Text'e bildirim erişimi
verir. Uygulama bildirimlerin metnini, gönderenini veya içeriğini okumaz ve
saklamaz; yalnızca YouTube paketinin medya oynatma durumunu kullanarak normal video
dışındayken bağlantıyı keser.

Kayıtlı API anahtarı uygulama ayarlarından kaldırılabilir. Önbellek ve diğer yerel
verilerin tamamı Android ayarlarından uygulama verileri temizlenerek veya uygulama
kaldırılarak silinebilir.

YouTube'un ve seçilen LLM sağlayıcısının gönderilen verileri nasıl işlediği kendi
gizlilik şartlarına tabidir. Uygulamayı kullanmadan önce bu şartları inceleyin.

## Android izinleri

- **İnternet:** YouTube ve seçilen LLM sağlayıcısıyla iletişim kurmak için.
- **Ekran üzerinde gösterim:** Türkçe altyazıyı YouTube'un üzerinde göstermek için.
- **Ön plan hizmeti ve bildirim:** Altyazı eşzamanlamasını kullanıcıya görünür bir
  hizmet olarak çalıştırmak için.
- **Bildirim erişimi (isteğe bağlı):** Yalnızca YouTube medya oturumunun durumunu
  yerel olarak gözlemlemek için.

Uygulama açılışta otomatik çalışma, konum, mikrofon, kamera, depolama veya kişi
erişimi istemez.
