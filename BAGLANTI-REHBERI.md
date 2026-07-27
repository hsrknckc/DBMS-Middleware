# Ortak Sunucuya Baglanma Rehberi

Bu belge **On Yuz (Flutter)** ve **Arka Yuz (Java kutuphanesi)** gelistiricileri
icindir. Ara katman sunucusu bulut ortaminda (AWS) calisiyor; siz yalnizca
ona baglanacaksiniz.

**Docker, Java sunucusu ya da MongoDB kurmaniza gerek YOKTUR.** Tek yapacaginiz
sey, kendi projenizden bir TCP soketi acip satir bazli JSON mesajlari gonderip
almak.

Bu belge uzundur ama sirayla okunacak sekilde yazilmistir. Kendi tarafiniza
gelene kadar (On Yuz icin Bolum 5, Arka Yuz icin Bolum 6) ortak bolumler
herkes icindir.

---

## Icindekiler

1. Sunucu bilgileri
2. Mimari — sistemde nerede duruyorsunuz
3. Nasil calisir — herkesin bilmesi gerekenler
4. Baglantiyi kod yazmadan test etme
5. ON YUZ protokolu (Flutter) — adim adim
6. ARKA YUZ protokolu (Java) — adim adim
7. Sik yapilan hatalar ve cozumleri
8. Bilinmesi gereken kisitlar

---

## 1. Sunucu bilgileri

| Alan | Deger |
|---|---|
| Adres (IP) | `54.154.220.190` |
| Port | `5150` |
| Tasima | Ham TCP soketi |
| Mesaj bicimi | Satir bazli JSON (her mesaj tek satir, `\n` ile biter) |
| Karakter kodlamasi | UTF-8 |

**Kullanici adi ve sifreler bu belgede YAZMAZ.** Guvenlik geregi ekip
dahili kanaldan paylasilir. Test kullanicilarindan birinin tam yetkisi,
digerinin sinirli yetkisi vardir.

Baglanamiyorsaniz once **Bolum 7 (sik yapilan hatalar)** bolumune bakin;
sorunlarin cogu orada cozuluyor.

---

## 2. Mimari — sistemde nerede duruyorsunuz

```
   On Yuz (Flutter)  ---+
                        +-->  ARA KATMAN  -->  MongoDB
   Arka Yuz (Java)   ---+    54.154.220.190:5150
                             (bulut sunucusu)
```

Ara katman ortadaki tek gecis noktasidir. Onemli sonuclari:

- **MongoDB'ye dogrudan baglanmazsiniz.** Tum okuma/yazma ara katman
  uzerinden gecer. Veritabaninin adresini veya sifresini bilmenize gerek yok.
- **Iki taraf ayni veriyi paylasir.** Arka Yuz'un yazdigi bir kaydi On Yuz
  okuyabilir, tersi de gecerli. Ikisi ayni ara katmana, ayni veritabanina
  baglanir.
- **Iki taraf farkli protokol konusur.** On Yuz ve Arka Yuz'un mesaj bicimi
  farklidir (asagida ayri ayri anlatiliyor), ama sunucu ikisini de ayni anda
  anlar.

---

## 3. Nasil calisir — herkesin bilmesi gerekenler

Kendi tarafiniza gecmeden once su dort kurali anlayin; iki protokol icin de
gecerlidir.

### 3.1 Mesaj sinirlari (framing) — en kritik kural

TCP bir **bayt akisidir**; kendiliginden "mesaj" kavrami yoktur. Iki mesaji
birbirinden ayirmak icin bir kural belirledik:

> **Her mesaj tek satir JSON'dur ve `\n` (newline) ile biter.**

Bunun pratik anlami:

- **Gonderirken:** JSON'un sonuna mutlaka `\n` ekleyin. Eklemezseniz sunucu
  mesajin bittigini anlayamaz ve **sonsuza kadar bekler** — program takilir.
- **Okurken:** gelen veriyi satir satir okuyun (bir sonraki `\n`'e kadar).
  Cogu dilde "readLine" benzeri bir fonksiyon bunu otomatik yapar.

Cogu kutuphanede `println` / `writeLine` gibi fonksiyonlar `\n`'i otomatik
ekler; `print` / `write` kullanirsaniz elle eklemeniz gerekir.

### 3.2 Kalici baglanti

Bir kez baglanip **ayni soket uzerinden** art arda istek gonderebilirsiniz.
Her istek icin yeni baglanti acmak gereksizdir ve yavastir. Baglantiyi acin,
isiniz bitene kadar kullanin, sonunda kapatin.

### 3.3 requestId — istek/cevap eslestirme

Her istege benzersiz bir kimlik (`requestId`) koyun. Sunucu bu kimligi
cevaba **aynen geri koyar**. Ayni anda birden fazla istek gonderirseniz,
gelen cevabin hangi isteginize ait oldugunu bu kimlikle anlarsiniz.
Cevaplar gonderdiginiz siradan farkli sirada gelebilir.

UUID veya artan bir sayac (`"1"`, `"2"`, ...) kullanabilirsiniz; yeter ki
o oturumda benzersiz olsun.

### 3.4 Baglanti kopmaz

Bozuk JSON, bilinmeyen komut, yetkisiz istek — hicbiri baglantinizi
koparmaz. Sunucu her durumda bir hata cevabi doner ve dinlemeye devam eder.

---

## 4. Baglantiyi kod yazmadan test etme

Kod yazmaya baslamadan **once** baglantinin calistigini dogrulayin. Boylece
ileride bir sorun ciktiginda "ag mi bozuk, kodum mu?" ikilemine dusmezsiniz.

### Windows — PowerShell (hicbir kurulum gerektirmez)

PowerShell acin ve su bloğu yapistirin:

```powershell
$c = New-Object System.Net.Sockets.TcpClient("54.154.220.190", 5150)
$s = $c.GetStream()
$w = New-Object System.IO.StreamWriter($s)
$r = New-Object System.IO.StreamReader($s)
$w.WriteLine('{"requestId":"1","action":"ping","payload":{}}')
$w.Flush()
$r.ReadLine()
$c.Close()
```

Beklenen cevap:

```
{"requestId":"1","ok":true,"data":{"message":"pong"}}
```

Bunu goruyorsaniz baglanti calisiyor. Hicbir sey donmuyorsa ya da hata
aliyorsaniz Bolum 7'ye bakin.

### Once yalnizca port acik mi diye bakmak isterseniz

```powershell
Test-NetConnection -ComputerName 54.154.220.190 -Port 5150
```

`TcpTestSucceeded : True` gorurseniz sunucuya ulasabiliyorsunuz demektir.

---

## 5. ON YUZ PROTOKOLU (Flutter)

### 5.1 Mesaj bicimi

**Istek (siz gonderirsiniz):**
```json
{"requestId":"<benzersiz-id>","action":"<action>","token":"<varsa>","payload":{...}}
```

**Cevap (sunucu doner):**
```json
{"requestId":"<ayni-id>","ok":true,"data":<sonuc>}
{"requestId":"<ayni-id>","ok":false,"error":"<mesaj>"}
```

Cevapta once `ok` alanina bakin: `true` ise `data`'yi kullanin, `false` ise
`error` mesajini gosterin.

### 5.2 Once giris yapin (auth.login)

Cogu islem giris yapmis olmayi gerektirir. Once `auth.login` gonderip bir
`token` alirsiniz; sonraki her istekte bu token'i `token` alaninda tasirsiniz.

Istek:
```json
{"requestId":"1","action":"auth.login","payload":{"email":"<eposta>","password":"<sifre>"}}
```

Basarili cevapta kullanici bilgileri ve bir `token` doner. Bu token'i
saklayin; oturum boyunca kullanacaksiniz.

### 5.3 Action listesi

```
KIMLIK
  auth.login    {email, password}          -> kullanici bilgileri + token
  auth.logout   {}                          (token gerekir)
  auth.me       {}                          (token gerekir)
  auth.requestReset {email}

VERITABANLARI
  databases.list        {includeDeleted}
  databases.getById     {id}
  databases.create      {name, department, description}
  databases.update      {id, ...}
  databases.softDelete  {id}
  databases.restore     {id}
  databases.permanentDelete {id}

KAYITLAR (Data Explorer)
  records.list     {databaseId, collectionName, searchQuery?}
  records.getById  {id, databaseId, collectionName}
  records.create   {databaseId, collectionName, data}
  records.update   {id, databaseId, collectionName, data}
  records.delete   {id, databaseId, collectionName}
  records.import   {databaseId, collectionName, records:[...]}
  records.export   {databaseId, collectionName, format}

KULLANICILAR (super admin gerektirir)
  users.list     {}
  users.getById  {id}
  users.create   {name, email, password, role, departments, permissions}

DIGER
  dashboard.stats        {}
  dashboard.systemStatus {}
  ping                   {}
```

### 5.4 Ornek: kayit ekleme

Istek:
```json
{"requestId":"7","action":"records.create","token":"tok-...",
 "payload":{"databaseId":"db-1","collectionName":"sensor_readings",
            "data":{"sensorId":"SEN-001","value":22.8}}}
```

Cevap:
```json
{"requestId":"7","ok":true,
 "data":{"sensorId":"SEN-001","value":22.8,"id":"rec-3",
         "createdAt":"2026-07-24T09:15:00","updatedAt":"2026-07-24T09:15:00"}}
```

Sunucu her kayda otomatik olarak `id`, `createdAt` ve `updatedAt` ekler;
bunlari siz gondermezsiniz.

### 5.5 Calisan Dart ornegi

```dart
import 'dart:convert';
import 'dart:io';

Future<void> main() async {
  // 1) Baglan
  final socket = await Socket.connect('54.154.220.190', 5150);

  // 2) Gelen satirlari dinle (framing: satir satir)
  socket
      .cast<List<int>>()
      .transform(utf8.decoder)
      .transform(const LineSplitter())
      .listen((line) {
    final msg = jsonDecode(line) as Map<String, dynamic>;
    if (msg['ok'] == true) {
      print('OK  : ${msg['data']}');
    } else {
      print('HATA: ${msg['error']}');
    }
  });

  // 3) Istek gonder - sonundaki '\n' SART
  void gonder(Map<String, dynamic> istek) {
    socket.write('${jsonEncode(istek)}\n');
  }

  gonder({
    'requestId': '1',
    'action': 'auth.login',
    'payload': {'email': '<eposta>', 'password': '<sifre>'},
  });

  await Future.delayed(const Duration(seconds: 2));
  await socket.close();
}
```

### 5.6 Uygulamaya baglama sirasi

Projede repository katmanini Mock'tan gercek TCP baglantisina cevirirken
**hepsini birden acmayin.** Once yalnizca kimlik dogrulamayi (auth) acin,
giris ekranini test edin. Calistigini gorunce sirayla digerlerini
(veritabanlari, kayitlar, kullanicilar) acin. Boylece bir sorun ciktiginda
hangi parcadan kaynaklandigi bellidir.

---

## 6. ARKA YUZ PROTOKOLU (Java kutuphanesi)

Bu protokol PROTOKOL.md sozlesmesiyle birebir aynidir.

### 6.1 Mesaj bicimi

**Istek:**
```json
{"requestId":"a1","action":"WRITE","username":"<tam-eposta>","password":"<sifre>",
 "database":"okul","collection":"ogrenciler","filter":{...},"document":{...}}
```

**Cevap:**
```json
{"requestId":"a1","status":"OK","message":"1 record inserted","data":[...]}
```

`status` uc degerden biridir: `OK`, `UNAUTHORIZED`, `ERROR`.

**On Yuz'den farklari:**
- Action isimleri BUYUK HARF (`WRITE`, `READ`), noktali degil.
- Token YOKTUR; her istekte `username` ve `password` gonderilir.
- Veri `payload` icinde degil, dogrudan alanlarda (`database`, `collection`,
  `document`).
- Cevap zarfi `ok:true` degil `status:"OK"`.

### 6.2 En kritik nokta: username tam eposta olmali

`username` alanina kullanici adinin **tam e-posta halini** yazin:

```
DOGRU  : "username":"ornek@company.com"
YANLIS : "username":"ornek"
```

Bu, en sik yapilan hatadir ve `UNAUTHORIZED` cevabina yol acar.

### 6.3 Action listesi

| action | Ne yapar | Kullanilan alanlar | data iceren mi |
|---|---|---|---|
| `PING` | Sunucu/veritabani erisilebilir mi | — | hayir |
| `READ` | Filtreye uyan kayitlari doner | database, collection, filter | evet, kayit listesi |
| `WRITE` | Yeni kayit ekler | database, collection, document | hayir |
| `UPDATE` | Filtreye uyanlari gunceller | database, collection, filter, document | hayir |
| `DELETE` | Filtreye uyanlari siler | database, collection, filter | hayir |
| `LIST_DATABASES` | Veritabani adlarini doner | — | evet, isim listesi |
| `LIST_COLLECTIONS` | Koleksiyon adlarini doner | database | evet, isim listesi |

`filter` bos veya gonderilmezse tum kayitlar islenir. `READ`'de filtresiz
istek tum koleksiyonu doner; `DELETE`'te filtresiz istek TUM kayitlari siler,
dikkatli olun.

### 6.4 Yetki eslemesi

Her islem bir yetki gerektirir. Kullanicinin o yetkisi yoksa `UNAUTHORIZED`
doner:

```
READ   -> dataView       WRITE  -> dataCreate
UPDATE -> dataUpdate      DELETE -> dataDelete
LIST_* -> databaseView
```

### 6.5 Ornek oturum

```
GONDER: {"requestId":"1","action":"PING","username":"<eposta>","password":"<sifre>"}
GELIR : {"requestId":"1","status":"OK","message":"Database is active"}

GONDER: {"requestId":"2","action":"WRITE","username":"<eposta>","password":"<sifre>",
         "database":"okul","collection":"ogrenciler","document":{"ad":"Ali","sinif":3}}
GELIR : {"requestId":"2","status":"OK","message":"1 record inserted"}

GONDER: {"requestId":"3","action":"READ","username":"<eposta>","password":"<sifre>",
         "database":"okul","collection":"ogrenciler","filter":{"sinif":3}}
GELIR : {"requestId":"3","status":"OK","message":"1 record(s) found",
         "data":[{"ad":"Ali","sinif":3,"id":"rec-1","createdAt":"..."}]}
```

### 6.6 Calisan Java ornegi

```java
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class BaglantiDeneme {
    public static void main(String[] args) throws Exception {
        try (Socket s = new Socket("54.154.220.190", 5150);
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                 new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // println satir sonunu otomatik ekler (framing)
            out.println("{\"requestId\":\"1\",\"action\":\"PING\","
                      + "\"username\":\"<eposta>\",\"password\":\"<sifre>\"}");

            System.out.println(in.readLine());  // cevabi oku
        }
    }
}
```

`print` kullanirsaniz satir sonunu (`\n`) kendiniz eklemelisiniz; `println`
otomatik ekler.

---

## 7. Sik yapilan hatalar ve cozumleri

**Program cevap beklerken takiliyor, hicbir sey gelmiyor.**
Mesajin sonuna `\n` koymayi unutmussunuz. Sunucu satir sonu gorene kadar
mesajin bitmedigini varsayar ve bekler. `println`/`writeLine` kullanin ya
da `\n` ekleyin.

**Baglanti hic kurulmuyor / zaman asimi.**
Sunucuya ulasilamiyor. Once Bolum 4'teki `Test-NetConnection` ile porta
ulasip ulasamadiginizi olcun. Ulasamiyorsaniz agla ilgili bir engel vardir;
ekip icinden durumu sorun.

**`Connection refused`.**
Sunucuya ulasiliyor ama 5150'de dinleyen yok; sunucu o an calismiyor
olabilir. Ekip icinden kontrol isteyin.

**On Yuz: `{"ok":false,"error":"Not authenticated"}`.**
Token gondermediniz veya token gecersiz/suresi dolmus. Once `auth.login`
yapip donen token'i sonraki isteklerde `token` alaninda gonderin.

**Arka Yuz: `{"status":"UNAUTHORIZED"}`.**
Iki olasilik: (1) kullanici adi/sifre yanlis, (2) `username` alanina tam
e-posta yazmadiniz (Bolum 6.2). Once bunu kontrol edin.

**`Permission denied: <yetki>` benzeri bir hata.**
Baglantiniz calisiyor ama kullaniciniz o islemi yapmaya yetkili degil.
Yetkili bir kullanici bilgisi icin ekip icinden edinin.

**Turkce karakterler bozuk gorunuyor.**
Soket okuma/yazmada UTF-8 belirtin. Ayrica Windows konsolunda bozuk gorunmesi
verinin bozuk oldugu anlamina gelmez — cogu zaman sadece konsolun gosterim
sorunudur; veri dogru gidip gelir.

**Ayni anda cok istek gonderiyorum, cevaplar karisiyor.**
Cevaplar gonderdiginiz siradan farkli sirada gelebilir. Her cevabi
`requestId` ile kendi isteginize eslestirin.

**Bir sure sonra baglanti kopuyor.**
Sunucu yeniden baslatilmis olabilir. Istemcinizde yeniden baglanma mantigi
bulundurmak iyi olur.

---

## 8. Bilinmesi gereken kisitlar

- **Veriler su an gecici.** Sunucu bellek uzerinde calisiyor; yeniden
  baslatildiginda eklenen kayitlar sifirlanir ve ornek veriler geri gelir.
  Kalici veritabani (MongoDB) devreye alindiginda bu durum degisecek. Test
  sirasinda "kayitlarim kayboldu" derseniz sebebi budur, panik yapmayin.
- **Sunucu yeniden baslarsa acik baglantilar kopar.** Istemcinizin yeniden
  baglanabilmesi iyi olur.
- **Kullanici bilgileri ve sunucu adresi degisebilir.** Degisiklikler ekip
  icinden duyurulur; adresi ve sifreyi koda gomerken bunu aklinizda tutun
  (tek bir yerden okunacak sekilde tutmak, degistirmeyi kolaylastirir).
