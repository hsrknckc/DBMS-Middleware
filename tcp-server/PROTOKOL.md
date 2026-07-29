# Ara Katman TCP Protokolu (v2)

Bu belge, **Ara Katman sunucusu** ile ona baglanan tum istemciler
(**On Yuz - Flutter** ve **Arka Yuz - Java kutuphanesi**) arasindaki
haberlesme sozlesmesidir.

**v1'den fark:** Onceden On Yuz ve Arka Yuz ayri protokoller konusuyordu.
v2'de **tek protokol** vardir; iki taraf da ayni zarfi ve ayni action
isimlerini kullanir. v1'in yedi cekirdek islemi (PING, READ, WRITE, UPDATE,
DELETE, LIST_DATABASES, LIST_COLLECTIONS) **aynen korunmustur**; uzerine
On Yuz'un ihtiyac duydugu islemler eklenmistir.

---

## 1. Tasima katmani

- Haberlesme **ham TCP soketi** uzerinden yapilir.
- Ara Katman bir **TCP sunucusudur**; varsayilan port **5150**
  (PORT ortam degiskeni ile degistirilebilir).
- Her mesaj **tek satir JSON**'dur, **UTF-8** kodlanir, **`\n` ile biter**.
- Akis istek-cevap duzenindedir: istemci bir satir yazar, sunucu bir satir yazar.
- Bir baglanti uzerinden art arda birden cok istek gonderilebilir.
- Sunucu, istemci baglantiyi kapatana kadar satir okumaya devam eder.
- Bozuk girdi baglantiyi **koparmaz**; `ERROR` cevabi doner.

---

## 2. Istek formati

```json
{"requestId":"a1b2c3","action":"READ","username":"ornek@company.com","password":"...","database":"okul","collection":"ogrenciler","filter":{"sinif":3},"document":null}
```

| Alan | Tip | Zorunlu | Aciklama |
|---|---|---|---|
| `requestId` | string | evet | Istemcinin urettigi benzersiz kimlik. Cevapta aynen doner. |
| `action` | string | evet | Islem turu (BUYUK HARF). Bkz. Bolum 4. |
| `username` | string | evet | Istegi yapan kullanicinin **tam e-posta adresi**. |
| `password` | string | evet | Kullanicinin sifresi. |
| `database` | string | isleme gore | Hedef veritabani adi. |
| `collection` | string | isleme gore | Hedef koleksiyon adi. |
| `filter` | object | hayir | Hangi kayitlar? Bos/yok ise tum kayitlar. |
| `document` | object | isleme gore | Eklenecek/degisecek alanlar. |

`null` olan alanlar JSON'a hic yazilmayabilir.

**Kimlik dogrulama her istekte yapilir; token yoktur.** Istemci kullanici
adi ve sifreyi oturum boyunca saklayip her istege ekler.

---

## 3. Cevap formati

```json
{"requestId":"a1b2c3","status":"OK","message":"2 record(s) found","data":[{"ad":"Ali"},{"ad":"Ayse"}]}
```

| Alan | Tip | Aciklama |
|---|---|---|
| `requestId` | string | Istekteki degerin aynisi. |
| `status` | string | `OK`, `UNAUTHORIZED` veya `ERROR`. |
| `message` | string | Insan okunur aciklama. |
| `data` | array | **Her zaman bir dizidir** (bos olabilir). |

**Onemli:** `data` her zaman dizidir. Tek nesne donen islemlerde
(LOGIN, CREATE_DATABASE, WRITE...) dizi tek elemanlidir; istemci
`data[0]` okur.

---

## 4. Islem turleri (action)

### 4.1 Cekirdek islemler (v1'den degismedi)

| action | Ne yapar | Kullanilan alanlar | `data` icerigi | Yetki |
|---|---|---|---|---|
| `PING` | Veritabani erisilebilir mi (Ister_0018) | — | bos | — |
| `READ` | Filtreye uyan kayitlari doner (Ister_0016) | database, collection, filter | kayit listesi | dataView |
| `WRITE` | Yeni kayit ekler (Ister_0017) | database, collection, document | eklenen kayit | dataCreate |
| `UPDATE` | Filtreye uyanlari gunceller | database, collection, filter, document | guncellenen kayitlar | dataUpdate |
| `DELETE` | Filtreye uyanlari siler | database, collection, filter | `[{"deletedCount":N}]` | dataDelete |
| `LIST_DATABASES` | Veritabani **adlarini** doner | — | `["okul","stok"]` | databaseView |
| `LIST_COLLECTIONS` | Koleksiyon **adlarini** doner | database | `["ogrenciler"]` | databaseView |

### 4.2 Kimlik

| action | Ne yapar | Kullanilan alanlar | `data` icerigi | Yetki |
|---|---|---|---|---|
| `LOGIN` | Girisi dogrular, kullanici bilgilerini doner (Ister_0002) | — | `[{id,name,email,role,departments,permissions,isActive}]` | — |

Kimlik zaten her istekte dogrulandigi icin `LOGIN` ayri bir oturum acmaz;
giris ekraninin kullaniciyi tanimasi (rol, yetkiler) icindir. Sifre yanlissa
`UNAUTHORIZED` doner.

### 4.3 Veritabani yonetimi

| action | Ne yapar | Kullanilan alanlar | Yetki |
|---|---|---|---|
| `CREATE_DATABASE` | Yeni veritabani olusturur | database, document{department, description} | databaseCreate |
| `UPDATE_DATABASE` | Ust verisini gunceller | database, document | databaseCreate |
| `DELETE_DATABASE` | **Yumusak** siler (geri alinabilir) | database | databaseCreate |
| `RESTORE_DATABASE` | Yumusak silinmis olani geri alir | database | databaseCreate |
| `DROP_DATABASE` | **Kalici** siler (koleksiyonlar dahil) | database | databaseCreate |
| `LIST_DATABASES_INFO` | Ust verilerle birlikte listeler | filter{includeDeleted} | databaseView |

`LIST_DATABASES_INFO` her veritabani icin sunlari doner:

```json
{"id":"rec-1","name":"okul","department":"Sensor","description":"...",
 "isDeleted":false,"deletedBy":null,"collections":["ogrenciler"],
 "collectionCount":1,"recordCount":12,"createdAt":"...","updatedAt":"..."}
```

`filter.includeDeleted = true` gonderilirse yumusak silinmis olanlar da gelir
(cop kutusu gorunumu).

### 4.4 Koleksiyon yonetimi

| action | Ne yapar | Kullanilan alanlar | Yetki |
|---|---|---|---|
| `CREATE_COLLECTION` | Bos koleksiyon olusturur | database, collection | databaseCreate |
| `DROP_COLLECTION` | Koleksiyonu ve kayitlarini siler | database, collection | databaseCreate |

Bos koleksiyonlar da `LIST_COLLECTIONS` sonucunda gorunur.

### 4.5 Kullanici yonetimi (Ister_0004, Ister_0005)

| action | Ne yapar | Kullanilan alanlar | Yetki |
|---|---|---|---|
| `LIST_USERS` | Kullanicilari listeler | — | super admin |
| `CREATE_USER` | Yeni kullanici ekler | document{name,email,password,role,departments,permissions} | super admin |

Sifreler cevaplarda **asla** dondurulmez.

### 4.6 Ozet bilgiler

| action | Ne yapar | `data` icerigi | Yetki |
|---|---|---|---|
| `STATS` | Panel istatistikleri | `[{totalDatabases,totalCollections,totalRecords,activeUsers}]` | giris yeterli |

### 4.7 Veritabani talep dosyasi (Ister_0011, Ister_0012)

On Yuz, kullanicinin tasarladigi veritabani alanlarini JSON dosyasina yazip
sunucudaki **talep klasorune** kaydeder. Ara katman bu dosyayi kontrol eder
ve iceriginden veritabani alanlarini yaratir.

| action | Ne yapar | Kullanilan alanlar | `data` icerigi | Yetki |
|---|---|---|---|---|
| `CHECK_FILE` | Dosya belirtilen yolda var mi (Ister_0011) | document.path | `[{exists,isFile,readable,validJson,size,...}]` | databaseView |
| `IMPORT_FILE` | Dosya icerigine gore alan yaratir (Ister_0012) | document.path | `[{database,collectionsCreated,recordsInserted}]` | databaseCreate |
| `DESCRIBE_COLLECTION` | Kaydedilmis alan tanimlarini doner | database, collection | `[{database,collection,fields}]` | databaseView |

**Dosya yolu guvenligi.** Yol, sunucudaki talep klasoru (`REQUEST_DIR`
ortam degiskeni, varsayilan `db-requests`) altinda cozulur. Disari cikmaya
calisan yollar (`../` vb.) `ERROR` ile reddedilir.

**Beklenen dosya bicimi:**

```json
{
  "database": "okul",
  "department": "Egitim",
  "description": "Okul veritabani",
  "collections": [
    {
      "name": "ogrenciler",
      "fields": [
        {"name": "ad", "type": "string"},
        {"name": "sinif", "type": "int"}
      ],
      "records": [
        {"ad": "Ali", "sinif": 3}
      ]
    }
  ]
}
```

`records` istege baglidir; verilirse baslangic kayitlari eklenir.
`fields` tanimlari saklanir ve `DESCRIBE_COLLECTION` ile okunabilir
(MongoDB semasiz oldugu icin alan tanimlari ust veride tutulur).

### 4.8 Canli guncelleme (Observer)

| action | Ne yapar | Kullanilan alanlar |
|---|---|---|
| `SUBSCRIBE` | Koleksiyona abone olur | database, collection |
| `UNSUBSCRIBE` | Abonelikten cikar | database, collection |

Abone olunan koleksiyonda degisiklik oldugunda sunucu **istenmeden**
bir satir gonderir:

```json
{"type":"event","event":"insert","collection":"okul/ogrenciler","data":{...}}
```

Push mesajlarinda `requestId` **yoktur**. Istemci, `requestId` tasimayan
satirlari olay olarak islemelidir.

**Sunucu durumu bildirimi.** MongoDB kullanildiginda ara katman, veritabani
sunucusunun erisilebilirligini surekli izler (Observer deseni). Durum
degistiginde abonelere olay gonderilir:

```json
{"type":"event","event":"server_down","collection":"__server__/status","data":{"available":false}}
```

Bu bildirimleri almak icin:
`{"action":"SUBSCRIBE","database":"__server__","collection":"status", ...}`

---

### 4.9 Filtreler hakkinda

Filtredeki tum alanlar **birlikte** saglanmalidir (VE mantigi).
`{"sinif":3,"gecti":true}` -> hem sinifi 3 hem gecmis olanlar.

**`_id` alani:** Kayitlarda `_id` diye bir alan bulunmaz; kimlik alani
`id`'dir. MongoDB'ye alisik istemciler icin sunucu filtredeki `_id`
degerini `id` olarak yorumlar. Yani `{"id":"rec-8","_id":"rec-8"}`
filtresi `{"id":"rec-8"}` gibi calisir.

**Silme sonucunu dogrulama:** `DELETE` hicbir kayit bulamasa bile
`status: "OK"` doner (islem gecerlidir, sadece eslesme yoktur).
Gercekten silinip silinmedigini `data[0].deletedCount` ile kontrol edin.

## 5. Yetkiler

Yetki isimleri On Yuz'un Permission listesiyle birebir aynidir:

```
databaseView    databaseCreate
dataView        dataCreate      dataUpdate      dataDelete
dataImport      dataExport
```

Rol iki turdur: `superAdmin` (tum yetkiler) ve `user` (yalnizca verilenler).

---

## 6. Hata kurallari

- Kullanici adi/sifre yanlis **veya** yetki yok -> `status: "UNAUTHORIZED"` (Ister_0015)
- Eksik alan, bozuk JSON, bulunamayan kayit -> `status: "ERROR"`, sebep `message` icinde
- Sunucu hicbir durumda baglantiyi koparmaz
- `PING` veritabanina ulasamazsa `ERROR` doner (Arka Yuz bunu "pasif" sayar)

---

## 7. Ornek oturum

```
-> {"requestId":"1","action":"LOGIN","username":"ornek@company.com","password":"..."}
<- {"requestId":"1","status":"OK","message":"Login successful","data":[{"id":"user-1","name":"...","role":"superAdmin","permissions":[...]}]}

-> {"requestId":"2","action":"CREATE_DATABASE","username":"...","password":"...","database":"okul","document":{"department":"Sensor","description":"Okul veritabani"}}
<- {"requestId":"2","status":"OK","message":"Database created: okul","data":[{"name":"okul","collections":[],"collectionCount":0,"recordCount":0,...}]}

-> {"requestId":"3","action":"CREATE_COLLECTION","username":"...","password":"...","database":"okul","collection":"ogrenciler"}
<- {"requestId":"3","status":"OK","message":"Collection created: ogrenciler","data":[{"database":"okul","collection":"ogrenciler","recordCount":0}]}

-> {"requestId":"4","action":"WRITE","username":"...","password":"...","database":"okul","collection":"ogrenciler","document":{"ad":"Ali","sinif":3}}
<- {"requestId":"4","status":"OK","message":"1 record inserted","data":[{"ad":"Ali","sinif":3,"id":"rec-5","createdAt":"...","updatedAt":"..."}]}

-> {"requestId":"5","action":"READ","username":"...","password":"...","database":"okul","collection":"ogrenciler","filter":{"sinif":3}}
<- {"requestId":"5","status":"OK","message":"1 record(s) found","data":[{"ad":"Ali","sinif":3,"id":"rec-5",...}]}
```

---

## 8. Ayrilmis isimler

`__meta__` veritabani adi sunucu tarafindan ust veri saklamak icin
kullanilir; istemciler bu ada islem yapamaz.
