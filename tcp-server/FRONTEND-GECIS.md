# On Yuz (Flutter) Gecis Rehberi — Tek Protokole Gecis

Ara katman artik **tek protokol** konusuyor: PROTOKOL.md. On Yuz'un eski
protokolu (`auth.login`, `payload`, `token`, `{ok, data}`) **kaldirildi**.

Bu belge, On Yuz tarafinda nelerin degismesi gerektigini listeler.

---

## 1. Ozet: uc temel degisiklik

### 1.1 Kimlik: token yerine kullanici adi + sifre

**Eskiden:** `auth.login` ile token alinir, sonraki isteklerde `token` alani gonderilirdi.

**Simdi:** Token yoktur. Kullanici adi ve sifre giriste saklanir ve
**her istege** eklenir.

```dart
// Giristen sonra saklanir
String? _username;  // tam e-posta
String? _password;
```

`LOGIN` action'i hala vardir ama oturum acmaz; sadece girisi dogrular ve
kullanici bilgilerini (rol, yetkiler, departmanlar) doner.

### 1.2 Zarf: payload yerine duz alanlar

**Eskiden:**
```json
{"requestId":"..","action":"records.create","token":"..","payload":{"databaseId":"db1","collectionName":"kayitlar","data":{...}}}
```

**Simdi:**
```json
{"requestId":"..","action":"WRITE","username":"..","password":"..","database":"db1","collection":"kayitlar","document":{...}}
```

`payload` yok. `databaseId` -> `database`, `collectionName` -> `collection`,
`data` -> `document`.

### 1.3 Cevap: ok yerine status, data her zaman dizi

**Eskiden:** `{"ok":true,"data":{...}}` — `data` bazen nesne bazen dizi.

**Simdi:** `{"status":"OK","message":"..","data":[...]}` — `data` **her zaman dizi**.

Tek nesne donen islemlerde `data[0]` okunur:

```dart
final list = response['data'] as List;
final item = list.isEmpty ? null : list.first as Map<String, dynamic>;
```

---

## 2. Action esleme tablosu

| Eski (kaldirildi) | Yeni | Notlar |
|---|---|---|
| `auth.login` | `LOGIN` | Token donmez; kullanici bilgisi `data[0]` |
| `auth.logout` | — | Gerek yok; istemci kimligi unutur |
| `auth.me` | `LOGIN` | Ayni bilgiyi doner |
| `auth.requestReset` | — | Sunucuda karsiligi yok |
| `databases.list` | `LIST_DATABASES_INFO` | Ust verilerle birlikte doner |
| `databases.getById` | `LIST_DATABASES_INFO` | Listeden filtrelenir |
| `databases.create` | `CREATE_DATABASE` | `database` = ad, `document` = {department, description} |
| `databases.update` | `UPDATE_DATABASE` | |
| `databases.softDelete` | `DELETE_DATABASE` | Geri alinabilir |
| `databases.restore` | `RESTORE_DATABASE` | |
| `databases.permanentDelete` | `DROP_DATABASE` | Koleksiyonlar dahil siler |
| `records.list` | `READ` | |
| `records.getById` | `READ` + `filter:{"id":".."}` | |
| `records.create` | `WRITE` | |
| `records.update` | `UPDATE` + `filter:{"id":".."}` | Artik filtre ile calisir |
| `records.delete` | `DELETE` + `filter:{"id":".."}` | |
| `records.import` | Dongu icinde `WRITE` | Toplu ekleme action'i yok |
| `records.export` | `READ` + istemcide bicimlendirme | |
| `users.list` | `LIST_USERS` | Super admin gerekir |
| `users.create` | `CREATE_USER` | |
| `dashboard.stats` | `STATS` | |
| — (yeni) | `CREATE_COLLECTION` | Koleksiyon olusturma artik var |
| — (yeni) | `DROP_COLLECTION` | |
| — (yeni) | `LIST_COLLECTIONS` | |

---

## 3. Dosya bazinda yapilacaklar

### 3.1 `lib/core/services/tcp_socket_service.dart`

`send()` metodu yeni zarfa gore yeniden yazilmali:

```dart
Future<Map<String, dynamic>> send({
  required String action,
  required String username,
  required String password,
  String? database,
  String? collection,
  Map<String, dynamic>? filter,
  Map<String, dynamic>? document,
}) async {
  final req = {
    'requestId': _uuid.v4(),
    'action': action,
    'username': username,
    'password': password,
    if (database != null) 'database': database,
    if (collection != null) 'collection': collection,
    if (filter != null) 'filter': filter,
    if (document != null) 'document': document,
  };

  final res = await sendRequest(req);   // '\n' ile bitirmeyi unutmayin

  if (res['status'] != 'OK') {
    throw TcpException(res['message'] ?? 'Islem basarisiz: $action');
  }
  return res;
}
```

**Push mesajlarina dikkat:** Gelen satirda `requestId` yoksa bu bir olay
(event) mesajidir, bekleyen bir istegin cevabi degildir. Ayirt etme:

```dart
if (msg['requestId'] == null || msg['type'] == 'event') {
  _eventController.add(msg);   // canli guncelleme
} else {
  _pending.remove(msg['requestId'])?.complete(msg);
}
```

### 3.2 `lib/models/db_request.dart` ve `db_response.dart`

Ikili protokol alanlari temizlenebilir:

- `DbRequest`: `token` ve `payload` alanlari **kaldirilir**.
- `DbResponse`: `ok` ve `error` alanlari **kaldirilir**;
  `status`, `message`, `data` kalir. `isOk` sadece `status == 'OK'` olur.

### 3.3 `lib/core/providers/repository_providers.dart`

`_getToken(ref)` yardimcisi **kaldirilir**. Yerine kullanici adi + sifre
saglayan bir yapi gelir:

```dart
/// Giris yapan kullanicinin kimligi — TCP isteklerinde kullanilir
class Credentials {
  final String username;
  final String password;
  const Credentials(this.username, this.password);
}

final credentialsProvider = StateProvider<Credentials?>((ref) => null);
```

Giris basarili olunca `credentialsProvider` doldurulur; repository'ler
bunu okur.

### 3.4 Repository dosyalari

Her `tcp_*_repository.dart` dosyasi yeni action isimlerine ve yeni
alanlara gecmeli. Ornek:

```dart
// ESKI
final response = await _tcp.send(
  action: 'databases.create',
  payload: {'name': name, 'department': department, 'description': description},
  token: _tokenProvider(),
);
return _parseDb(response['data'] as Map<String, dynamic>);

// YENI
final c = _credentials();
final response = await _tcp.send(
  action: 'CREATE_DATABASE',
  username: c.username,
  password: c.password,
  database: name,
  document: {'department': department, 'description': description},
);
final list = response['data'] as List;
return _parseDb(list.first as Map<String, dynamic>);
```

### 3.5 `lib/features/data_type_explorer/data_type_explorer_page.dart`

**Bu sayfa su an sunucuya hic baglanmiyor.** Iki sorun var:

1. Satir 29'daki `final List<_ExplorerDatabase> _databases = [...]` sabit
   kodlanmis sahte veridir. `LIST_DATABASES_INFO` ile sunucudan alinmali.
2. Koleksiyon ekleme (satir ~875) yalnizca `setState` ile yerel listeye
   ekliyor; sunucuya istek gitmiyor. `CREATE_COLLECTION` cagrilmali:

```dart
await _tcp.send(
  action: 'CREATE_COLLECTION',
  username: c.username,
  password: c.password,
  database: selectedDatabaseName,
  collection: name,
);
```

Sunucu artik bos koleksiyonlari da sakliyor ve `LIST_COLLECTIONS` ile
donuyor; yani olusturulan koleksiyon yenilemeden sonra da gorunur kalir.

---

## 4. Dikkat edilecek noktalar

**Kullanici adi tam e-posta olmali.** `ornek` degil `ornek@company.com`.
En sik yapilan hata budur ve `UNAUTHORIZED` ile sonuclanir.

**Veritabani kimligi artik ad.** Eskiden `databaseId: "db-1"` gibi bir kimlik
kullaniliyordu; artik `database: "okul"` — yani veritabaninin **adi**
dogrudan kimlik gorevi goruyor.

**Yetki hatalari `UNAUTHORIZED` doner.** Kullanicinin `databaseCreate`
yetkisi yoksa veritabani olusturamaz. Test kullanicilarindan sinirli
olanin bu yetkisi **yoktur**; veritabani olusturma denemeleri icin tam
yetkili kullaniciyla giris yapin.

**`data` her zaman dizi.** Bos donebilir; `data.first` cagirmadan once
`isEmpty` kontrolu yapin.

**Silinen kayitlar filtreyle bulunur.** `records.getById` yerine
`READ` + `filter:{"id":"rec-5"}` kullanilir.

**Filtreye yalnizca `id` koyun, `_id` eklemeyin.** Filtredeki tum alanlar
birlikte saglanmalidir; kayitlarda `_id` alani bulunmadigi icin
`{"id":"rec-8","_id":"rec-8"}` hicbir kaydi bulamaz. (Sunucu bunu
tolere edip `_id`'yi `id` olarak yorumluyor, ama dogrusu yalnizca `id`
gondermektir.) `DELETE` isteginde `document` alani da gereksizdir.

**Silme sonucunu `deletedCount` ile dogrulayin.** `DELETE` hicbir kayit
bulamasa bile `status: "OK"` doner. Ekrandan satiri kaldirmadan once
kontrol edin:

```dart
final result = (response['data'] as List).first as Map<String, dynamic>;
if ((result['deletedCount'] as num) == 0) {
  throw Exception('Kayit silinemedi (eslesme yok)');
}
```

---

## 5. Test etme

Kod degisikliginden once baglantiyi ham olarak dogrulayin:

```powershell
$c = New-Object System.Net.Sockets.TcpClient("54.154.220.190", 5150)
$s = $c.GetStream()
$w = New-Object System.IO.StreamWriter($s); $r = New-Object System.IO.StreamReader($s)
$w.WriteLine('{"requestId":"1","action":"LOGIN","username":"<eposta>","password":"<sifre>"}'); $w.Flush()
$r.ReadLine()
$c.Close()
```

`{"requestId":"1","status":"OK","message":"Login successful","data":[{...}]}`
gorurseniz sunucu hazir demektir.

Sirali gecis onerisi: once `LOGIN`, sonra `LIST_DATABASES_INFO`, sonra
`CREATE_DATABASE` + `CREATE_COLLECTION`, en son kayit islemleri (`WRITE`,
`READ`, `UPDATE`, `DELETE`). Her adimda calistigini dogrulayip devam edin.
