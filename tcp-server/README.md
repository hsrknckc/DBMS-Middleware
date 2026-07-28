# DBMS Ara Katman - TCP Sunucusu

On Yuz (Flutter) ve Arka Yuz (Java kutuphanesi) ile veritabani arasindaki
iletisim katmani. JSON isleme, ayri proje olan **json-parser**'dan jar
olarak alinir.

**Tek protokol:** Her iki istemci de ayni sozlesmeyi kullanir (PROTOKOL.md).

---

## Hizli baslangic

### Docker ile (onerilen)

    docker compose up -d --build          # yerel
    docker compose -f docker-compose.server.yml up -d --build   # sunucu

Ayrintili kullanim: **DOCKER.md** — sunucu kurulumu: **DEPLOY.md**

### Dogrudan Java ile

    # Derleme
    javac -encoding UTF-8 -cp "lib/*" -d out $(find src -name "*.java")     # Linux/Mac
    # Windows PowerShell:
    # javac -encoding UTF-8 -cp "lib/*" -d out (Get-ChildItem -Recurse src -Filter *.java).FullName

    # Terminal 1 - sunucu (varsayilan port 5150)
    java -cp "out:lib/*" middleware.Main         # Windows: "out;lib/*"

    # Terminal 2 - protokol + yetki testi (50 test)
    java -cp "out:lib/*" middleware.ProtocolTest localhost 5150

    # Elle deneme
    java -cp "out:lib/*" middleware.TestClient
    java -cp "out:lib/*" middleware.ObserverClient    # canli guncelleme izleme

---

## Protokol

Her mesaj tek satir JSON'dur ve `\n` ile biter (UTF-8).

**Istek**

    {"requestId":"..","action":"WRITE","username":"..","password":"..",
     "database":"okul","collection":"ogrenciler",
     "filter":{...},"document":{...}}

**Cevap**

    {"requestId":"..","status":"OK|UNAUTHORIZED|ERROR","message":"..","data":[...]}

`data` **her zaman dizidir** (bos olabilir). Tek nesne donen islemlerde
istemci `data[0]` okur.

Kimlik her istekte `username` + `password` ile dogrulanir; token yoktur.
`username` alanina **tam e-posta** yazilir.

### Action listesi

    Cekirdek   PING  READ  WRITE  UPDATE  DELETE
               LIST_DATABASES  LIST_COLLECTIONS
    Kimlik     LOGIN
    Veritabani CREATE_DATABASE  UPDATE_DATABASE  DELETE_DATABASE
               RESTORE_DATABASE  DROP_DATABASE  LIST_DATABASES_INFO
    Koleksiyon CREATE_COLLECTION  DROP_COLLECTION
    Kullanici  LIST_USERS  CREATE_USER
    Ozet       STATS
    Observer   SUBSCRIBE  UNSUBSCRIBE

Tam sozlesme ve ornekler: **PROTOKOL.md**

---

## Yetkiler 

Yetki isimleri On Yuz'un Permission listesiyle birebir aynidir:

    databaseView  databaseCreate
    dataView  dataCreate  dataUpdate  dataDelete
    dataImport  dataExport

Rol: `superAdmin` (tum yetkiler) veya `user` (yalnizca verilenler).
`LIST_USERS` ve `CREATE_USER` super admin gerektirir.

Yetkisiz istek `status: "UNAUTHORIZED"` doner.

Baslangic kullanicilari `src/middleware/auth/AuthService.java` icindeki
`seedUsers()` metodunda tanimlidir. Sifreler bu belgede paylasilmaz;
ekip icinden edinilir.

---

## Ekip icin belgeler

| Belge | Kime |
|---|---|
| **PROTOKOL.md** | Sunucuya baglanacak herkes — tam sozlesme |
| **FRONTEND-GECIS.md** | On Yuz gelistiricisi — nelerin degismesi gerektigi |
| **BAGLANTI-REHBERI.md** | Ekip arkadaslari — adim adim baglanma |
| **DOCKER.md** | Docker kullanimi |
| **DEPLOY.md** | AWS sunucu kurulumu |
| **MONGODB.md** | MongoDB kurulumu ve dogrulama |

---

## MongoDB

Veri katmani `Store` arayuzu arkasindadir; iki uygulamasi vardir:
`InMemoryStore` (bellekte, gecici) ve `MongoStore` (gercek MongoDB).
Secimi `MONGO_URI` ortam degiskeni belirler; tanimli degilse bellek
deposu kullanilir.

Surucu jar'larinin indirilmesi, yerel deneme ve kalicilik dogrulamasi
icin: **MONGODB.md**

---

## Dosya yapisi

    src/middleware/
      Main.java              -> giris noktasi, port ve baglantilar
      ProtocolTest.java      -> 50 testlik protokol + yetki paketi
      TestClient.java        -> elle deneme istemcisi
      ObserverClient.java    -> canli guncelleme izleyici

      auth/User.java         -> kullanici + yetki modeli
      auth/AuthService.java  -> kimlik dogrulama, kullanici yonetimi

      protocol/Router.java   -> TEK protokol: action yonlendirme + yetki + zarf

      events/                -> Observer deseni (Event, Observer, EventBus,
                                ConsoleLogObserver)
      server/                -> TcpServer, ClientHandler, ClientSession
      storage/Store.java          -> veri deposu arayuzu
      storage/InMemoryStore.java  -> bellekte (gecici)
      storage/MongoStore.java     -> gercek MongoDB

    lib/json-parser.jar      -> ayri projeden gelen JSON kutuphanesi
    Dockerfile  docker-compose.yml  docker-compose.server.yml
