# MongoDB Entegrasyonu (Ister_0016 / Ister_0017)

Veri katmani `Store` arayuzu arkasindadir; iki uygulamasi vardir:

```
Router (protokol + yetki)
   |
   v
Store (arayuz)
   |-- InMemoryStore   bellekte, gecici
   |-- MongoStore      gercek MongoDB
```

Hangisinin kullanilacagini **`MONGO_URI` ortam degiskeni** belirler:

| Durum | Sonuc |
|---|---|
| `MONGO_URI` tanimli degil | Bellek deposu (veriler kalici degil) |
| `MONGO_URI` tanimli, erisilebilir | MongoDB kullanilir |
| `MONGO_URI` tanimli, erisilemiyor | Bellek deposuna duser ve **acikca uyarir** |

Son madde onemli: sunucu sessizce veri kaybetmez, loglarda buyuk harfle
UYARI gorursunuz.

---

## 1. Surucu jar'larini indirme (tek seferlik)

MongoDB Java surucusu dort jar'dan olusur. `lib/` klasorune konur —
`json-parser.jar` gibi.

### Windows (PowerShell)

Proje klasorunde (`tcp-server`):

```powershell
$v = "5.9.1"
$base = "https://repo1.maven.org/maven2/org/mongodb"
$jars = @(
  "bson/$v/bson-$v.jar",
  "bson-record-codec/$v/bson-record-codec-$v.jar",
  "mongodb-driver-core/$v/mongodb-driver-core-$v.jar",
  "mongodb-driver-sync/$v/mongodb-driver-sync-$v.jar"
)
foreach ($j in $jars) {
  $name = Split-Path $j -Leaf
  Invoke-WebRequest -Uri "$base/$j" -OutFile "lib\$name"
  Write-Host "indirildi: $name"
}
dir lib
```

### Linux / Mac

```bash
V=5.9.1
BASE=https://repo1.maven.org/maven2/org/mongodb
for J in bson/$V/bson-$V.jar \
         bson-record-codec/$V/bson-record-codec-$V.jar \
         mongodb-driver-core/$V/mongodb-driver-core-$V.jar \
         mongodb-driver-sync/$V/mongodb-driver-sync-$V.jar; do
  curl -sSL -o "lib/$(basename $J)" "$BASE/$J"
done
ls -la lib/
```

Sonunda `lib/` icinde **bes jar** olmali:

```
bson-5.9.1.jar
bson-record-codec-5.9.1.jar
json-parser.jar
mongodb-driver-core-5.9.1.jar
mongodb-driver-sync-5.9.1.jar
```

Surum notu: 5.9.1 yazim aninda gecerli surumdur. Indirme hata verirse
https://repo1.maven.org/maven2/org/mongodb/mongodb-driver-sync/
adresinden guncel surumu secip `$v` degerini degistirin (dort jar da ayni
surum olmali).

**Jar'lari Git deposuna ekleyin** — boylece ekipteki herkes ve sunucu
ayni surumu kullanir.

---

## 2. Derleme (classpath degisti)

Tek tek jar yazmak yerine `lib/*` kullanilir:

```powershell
# Windows
javac -encoding UTF-8 -cp "lib/*" -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
java -cp "out;lib/*" middleware.Main
```

```bash
# Linux / Mac
javac -encoding UTF-8 -cp "lib/*" -d out $(find src -name "*.java")
java -cp "out:lib/*" middleware.Main
```

Tirnak isaretleri onemlidir; yoksa kabuk `*` isaretini kendisi acmaya calisir.

---

## 3. Yerelde MongoDB ile deneme

MongoDB'yi Docker ile calistirmak en kolayi:

```bash
docker run -d --name dbms-mongo -p 27017:27017 -v mongo-data:/data/db mongo:7
```

`-v mongo-data:/data/db` kismi kritiktir: veriler konteynerin disinda,
kalici bir alanda tutulur. Bu olmadan konteyner silininca veri de gider.

Sunucuyu MongoDB'ye baglayarak baslatin:

```powershell
# Windows
$env:MONGO_URI = "mongodb://localhost:27017"
$env:MONGO_DB  = "dbms"
java -cp "out;lib/*" middleware.Main
```

```bash
# Linux / Mac
MONGO_URI=mongodb://localhost:27017 MONGO_DB=dbms java -cp "out:lib/*" middleware.Main
```

Loglarin ilk satirinda sunu gormelisiniz:

```
[store] MongoDB baglantisi kuruldu: mongodb://localhost:27017 (db: dbms)
```

Bunun yerine `UYARI` iceren bir satir goruyorsaniz MongoDB'ye ulasilamiyor
demektir (bkz. Bolum 7).

---

## 4. Dogrulama

### 4.1 Test paketi

```bash
java -cp "out:lib/*" middleware.ProtocolTest localhost 5150
```

50 test gecmelidir. Testler depolama katmanindan bagimsizdir; bellekte de
MongoDB'de de ayni sonucu verir.

### 4.2 Kaliciligi kanitlama (asil onemli test)

Bu, Ister_0016/0017'nin gercek kanitidir:

1. Bir kayit ekleyin:
   ```powershell
   $c = New-Object System.Net.Sockets.TcpClient("localhost", 5150)
   $s = $c.GetStream()
   $w = New-Object System.IO.StreamWriter($s); $r = New-Object System.IO.StreamReader($s)
   $w.WriteLine('{"requestId":"1","action":"WRITE","username":"<eposta>","password":"<sifre>","database":"kalicilik","collection":"test","document":{"mesaj":"bu kalmali"}}'); $w.Flush()
   $r.ReadLine()
   $c.Close()
   ```
2. Sunucuyu **durdurun** (Ctrl+C)
3. Sunucuyu **yeniden baslatin**
4. Kaydi sorgulayin:
   ```powershell
   $w.WriteLine('{"requestId":"2","action":"READ","username":"<eposta>","password":"<sifre>","database":"kalicilik","collection":"test"}'); $w.Flush()
   $r.ReadLine()
   ```

Kayit hala oradaysa MongoDB calisiyor demektir. Bellek deposunda kaybolurdu.

### 4.3 MongoDB Compass ile gorsel dogrulama

`mongodb://localhost:27017` adresine baglanin. Ust katmanda
`kalicilik/test` diye gonderilen bir koleksiyon, MongoDB'de `kalicilik`
veritabani altinda `test` koleksiyonu olarak gorunur.

Ayrica `__meta__` adinda bir veritabani gorursunuz; ara katman veritabani
ust verilerini (ad, departman, aciklama, silinme durumu) burada saklar.

---

## 5. Sunucuda (AWS) devreye alma

`docker-compose.server.yml` zaten `MONGO_URI` ve `MONGO_DB` tanimliyor,
`mongo` servisi de compose icinde ayaga kalkiyor. Yapmaniz gereken:

```bash
cd ~/DBMS-Middleware/tcp-server
git pull
docker compose -f docker-compose.server.yml up -d --build
docker compose -f docker-compose.server.yml logs -f middleware
```

Loglarda `[store] MongoDB baglantisi kuruldu: mongodb://mongo:27017`
satirini arayin.

**Konteyner icinden MongoDB adresi `mongo`'dur, `localhost` degil.**
Docker aginda her servis kendi adiyla bulunur; `localhost` konteynerin
kendisini isaret ederdi.

MongoDB portu (27017) disariya **acilmaz**. Compass ile bakmak isterseniz
SSH tuneli kullanin (DEPLOY.md Bolum 10).

---

## 6. Teknik notlar

**Adlandirma.** Ust katman koleksiyonlari `"veritabani/koleksiyon"` bicimli
tek string olarak gonderir; `MongoStore` bunu ayirip MongoDB'nin dogal
veritabani + koleksiyon yapisina esler.

**Kayit kimlikleri.** Bellek deposu sirali sayac kullanir (`rec-1`, `rec-2`).
MongoStore ise `rec-<UUID>` uretir; boylece sunucu yeniden baslasa bile
kimlikler cakismaz. MongoDB'nin kendi `_id` alani (ObjectId) JSON'a
cevrilemedigi icin okumalarda projeksiyonla dislanir.

**Zaman damgalari** ISO metin olarak saklanir (Date tipi degil), boylece
okunan her belge dogrudan JSON'a cevrilebilir.

**Bos koleksiyonlar korunur.** `CREATE_COLLECTION`, MongoDB'nin
`createCollection` komutunu kullanir; icine kayit girilmeden de koleksiyon
var olur ve `LIST_COLLECTIONS` sonucunda gorunur.

**PING artik gercek.** `PING` action'i (Ister_0018) MongoDB'ye gercek bir
ping komutu gonderir. Erisilemezse `{"status":"ERROR","message":"Database
is not reachable"}` doner.

**Guncelleme kismidir.** `UPDATE` yalnizca gonderilen alanlari degistirir
(`$set`); `id` ve `createdAt` degistirilemez.

---

## 7. Sorun giderme

**`package com.mongodb.client does not exist` (derlerken)**
Jar'lar `lib/` icinde degil ya da classpath'te `lib/*` kullanilmiyor.
`ls lib/` ile bes jar oldugunu dogrulayin.

**`NoClassDefFoundError` (calistirirken)**
Derleme classpath'i dogru ama calistirma classpath'i eksik. `java`
komutunda da `-cp "out:lib/*"` (Windows'ta `out;lib/*`) kullanin.

**`[store] UYARI: MongoDB yanit vermiyor`**
Sunucu calisiyor ama MongoDB'ye ulasamiyor:
- MongoDB ayakta mi? `docker ps`
- Adres dogru mu? Yerelde `localhost:27017`, konteyner icinde `mongo:27017`

**Veriler hala kayboluyor**
Loglarda `[store] MongoDB baglantisi kuruldu` satiri var mi? Yoksa bellek
deposuna dusulmustur. Ayrica MongoDB'yi volume olmadan calistirdiysaniz
(`-v mongo-data:/data/db` olmadan) konteyner silininde veri gider.

**SLF4J uyarisi**
`SLF4J: Failed to load class ...` benzeri bir uyari zararsizdir; surucu
gunlukleme kutuphanesi bulamadigini soyler, isleve etkisi yoktur.
