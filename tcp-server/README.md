# DBMS Ara Katman - TCP Sunucusu (Cift Protokol: On Yuz + Arka Yuz)

Frontend (Flutter) ile veritabanı arasındaki iletişim katmanı.
JSON işleme, AYRI PROJE olan json-parser'dan jar olarak alınır.


## Ortak sunucu (AWS)

Ekip icin ortak gelistirme sunucusu kurulumu ayri bir belgede adim adim
anlatilmistir: **DEPLOY.md**. Sunucuda `docker-compose.server.yml` kullanilir
(MongoDB portu disariya acilmaz, bellek sinirlari vardir).

## Docker ile calistirma (yerel, onerilen)

Java kurmaya gerek yoktur; Docker her seyi kendi icinde halleder.
Sunucu + MongoDB tek komutla ayaga kalkar:

    docker compose up -d --build      # ilk sefer (imaji derler)
    docker compose up -d              # sonraki seferler
    docker compose logs -f middleware # sunucu loglarini izle
    docker compose ps                 # servisler ayakta mi
    docker compose down               # durdur (veriler KORUNUR)
    docker compose down -v            # durdur ve verileri de sil

Ayaga kalkinca:
  - Ara katman  -> localhost:5150  (frontend ve backend buraya baglanir)
  - MongoDB     -> localhost:27017 (MongoDB Compass ile bakilabilir)

Testleri konteyner icinde calistirmak icin:

    docker compose exec middleware java -cp out:lib/json-parser.jar middleware.AuthProtocolTest localhost 5150
    docker compose exec middleware java -cp out:lib/json-parser.jar middleware.BackendProtocolTest localhost 5150

### Onemli notlar

- Kod degistiginde imaji yeniden derlemek gerekir: `docker compose up -d --build`
- MongoDB verileri `mongo-data` adli kalici volume'de tutulur; `docker compose down`
  veriyi SILMEZ, `down -v` siler.
- Konteyner icinden MongoDB adresi `mongodb://mongo:27017`'dir (localhost DEGIL).
  Docker agi icinde her servis kendi adiyla bulunur.
- Port disaridan degistirilebilir: `PORT=6000 docker compose up -d`
  (compose dosyasindaki ports esleсmesini de guncellemeyi unutma.)

## Derleme ve calistirma (Docker olmadan, dogrudan Java ile)

    javac -cp lib/json-parser.jar -d out $(find src -name "*.java")   # Linux/Mac
    # Windows PowerShell:
    # javac -cp lib\json-parser.jar -d out (Get-ChildItem -Recurse src -Filter *.java).FullName

    # Terminal 1 - sunucu:
    java -cp "out:lib/json-parser.jar" middleware.Main          # varsayilan port 5150
    # Windows: : yerine ;

    # Terminal 2 - On Yuz protokol + yetki testi (24 test):
    java -cp "out:lib/json-parser.jar" middleware.AuthProtocolTest localhost 5150
    # Arka Yuz protokol testi (18 test):
    java -cp "out:lib/json-parser.jar" middleware.BackendProtocolTest localhost 5150

## IKI PROTOKOL, TEK SUNUCU

Sunucu gelen her satiri okuyup hangi protokol oldugunu anlar ve dogru
router'a yonlendirir (ProtocolDispatcher). Ikisi de AYNI DataStore ve
AuthService'i kullanir; yani On Yuz ve Arka Yuz ayni veriyi paylasir.

Ayirt etme kurali: BUYUK HARF action + "username" alani -> Arka Yuz;
noktali action (records.create) + "token" -> On Yuz.

Varsayilan port 5150'dir (PROTOKOL.md). Farkli port: `java middleware.Main 5000`.

---

## On Yuz Protokolu (frontend tcp_socket_service.dart ile birebir)

Her mesaj TEK SATIR JSON'dur ve '\n' ile biter.

İstek:
    {"requestId":"<id>","action":"<action>","token":"<varsa>","payload":{...}}

Cevap:
    {"requestId":"<id>","ok":true,"data":<...>}
    {"requestId":"<id>","ok":false,"error":"<mesaj>"}

requestId cevaba AYNEN geri konur; frontend istek/cevap eşleşmesini bununla yapar.

## Action listesi

    auth.login {email,password} -> {..user.., token}
    auth.logout {} (token)      auth.me {} (token)      auth.requestReset {email}
    databases.list {includeDeleted}    databases.getById {id}
    databases.create {name,department,description}     databases.update {id,...}
    databases.softDelete {id}   databases.restore {id}   databases.permanentDelete {id}
    records.list {databaseId,collectionName,searchQuery?}    records.getById {id,...}
    records.create {databaseId,collectionName,data}   records.update {id,...}
    records.delete {id,...}   records.import {..,records:[...]}   records.export {..,format}
    users.list {}   users.getById {id}   users.create {name,email,password,role,departments,permissions}
    dashboard.stats {}   dashboard.systemStatus {}   ping {}

## Kimlik doğrulama ve yetki

- auth.login email+şifre doğrular, token döner. Sonraki her istek token taşır.
- Yazma/güncelleme/silme action'ları ilgili Permission'ı kontrol eder; yoksa reddeder.
- Yetki isimleri frontend Permission enum'ı ile birebir aynı:
  databaseView, databaseCreate, dataView, dataCreate, dataUpdate, dataDelete, dataImport, dataExport
- users.* action'ları super admin gerektirir.

Başlangıç kullanıcıları (demo):
    ayse@company.com   / Ddsfkln1as1sFd  -> superAdmin (tüm yetkiler)
    mehmet@company.com / 3QPKdvlca34avSl   -> user (databaseView, dataView, dataCreate)

## Arka Yuz Protokolu

Istek : {"requestId":"..","action":"WRITE","username":"..","password":"..",
         "database":"okul","collection":"ogrenciler","filter":{..},"document":{..}}
Cevap : {"requestId":"..","status":"OK|UNAUTHORIZED|ERROR","message":"..","data":[..]}

Action'lar: PING, READ, WRITE, UPDATE, DELETE, LIST_DATABASES, LIST_COLLECTIONS.
Her istek username+password tasir (token degil). Yetki eslemesi:
READ->dataView, WRITE->dataCreate, UPDATE->dataUpdate, DELETE->dataDelete,
LIST_*->databaseView. Yetkisiz istek status:"UNAUTHORIZED" doner.

## MongoDB notu

Veriler şu an bellekte (DataStore) tutulur; sunucu yeniden başlayınca sıfırlanır.
DataStore'un içi gerçek MongoDB çağrılarıyla değiştirilecek;
RequestRouter, auth ve protokol katmanına dokunulmayacak.

## Dosya yapısı

    src/middleware/
      Main.java  TestClient.java  ObserverClient.java  AuthProtocolTest.java
      auth/{User,AuthService}.java          -> kimlik + yetki
      protocol/RequestRouter.java           -> action yönlendirme + yetki + zarf
      events/{Event,Observer,EventBus,ConsoleLogObserver}.java
      server/{TcpServer,ClientHandler,ClientSession}.java
      storage/DataStore.java                -> geçici in-memory DB
