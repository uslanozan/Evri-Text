# Gizlilik

Son güncelleme: 8 Eylül 2026

Evri Text'in kendine ait bir sunucusu, kullanıcı hesabı, reklamı veya analiz
servisi yoktur. Uygulama çalışabilmek için aşağıdaki haricî servislerle doğrudan
iletişim kurar:

- **YouTube:** TV eşleştirmesi, oynatılan video bilgisi ve mevcut altyazıların
  alınması için.
- **Google Gemini API:** Seçilen kaynak altyazı, video başlığı ve yakın cümle
  bağlamını Türkçeye çevirmek için. İstekler kullanıcının sağladığı API anahtarıyla
  doğrudan Google'a gönderilir.

Gemini API anahtarı Android Keystore ile korunan biçimde yalnızca cihazda saklanır.
Anahtar, Google API isteklerinde URL yerine bir kimlik doğrulama başlığında gönderilir.
Üretilen altyazılar tekrar kullanım için uygulamanın özel önbelleğinde tutulur.
Evri Text bu verileri geliştiriciye veya başka bir analiz servisine göndermez.

Akıllı YouTube bağlantısı etkinleştirilirse Android, Evri Text'e bildirim erişimi
verir. Uygulama bildirimlerin metnini, gönderenini veya içeriğini okumaz ve
saklamaz; yalnızca YouTube paketinin medya oynatma durumunu kullanarak normal video
dışındayken bağlantıyı keser.

Kayıtlı API anahtarı uygulama ayarlarından kaldırılabilir. Önbellek ve diğer yerel
verilerin tamamı Android ayarlarından uygulama verileri temizlenerek veya uygulama
kaldırılarak silinebilir.

Google ve YouTube'un gönderilen verileri nasıl işlediği kendi gizlilik şartlarına
tabidir. Uygulamayı kullanmadan önce bu şartları inceleyin.

## Android izinleri

- **İnternet:** YouTube ve Gemini API ile iletişim kurmak için.
- **Ekran üzerinde gösterim:** Türkçe altyazıyı YouTube'un üzerinde göstermek için.
- **Ön plan hizmeti ve bildirim:** Altyazı eşzamanlamasını kullanıcıya görünür bir
  hizmet olarak çalıştırmak için.
- **Bildirim erişimi (isteğe bağlı):** Yalnızca YouTube medya oturumunun durumunu
  yerel olarak gözlemlemek için.

Uygulama açılışta otomatik çalışma, konum, mikrofon, kamera, depolama veya kişi
erişimi istemez.
