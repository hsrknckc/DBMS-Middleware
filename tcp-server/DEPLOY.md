# AWS EC2 Dagitim Rehberi (ayrintili)

DBMS Ara Katman'i ekip icin ortak bir sunucuda calistirma rehberi.
Hicbir AWS deneyimi varsayilmaz; her adim tek tek yazilmistir.

**Hedef:** Ekipteki herkes kendi bilgisayarindan tek bir adrese baglanacak,
ayni ara katmani ve ayni MongoDB'yi kullanacak.

**Kapsam notu:** Bu bir *gelistirme* ortamidir. Veriler sahtedir; TLS,
sifre hash'leme gibi guvenlik katmanlari sonraki asamaya birakilmistir.

**Onemli:** AWS konsolunun arayuzu zaman zaman degisir. Buradaki buton
isimleri birebir tutmazsa, ayni anlama gelen secenegi arayin. Mantik ayni kalir.

**Toplam sure:** ilk kurulum yaklasik 45-60 dakika.

---

# BOLUM 0 — On hazirlik

## 0.1 AWS hesabi acma

1. https://aws.amazon.com adresine gidin, sag ustten **Create an AWS Account**.
2. E-posta, hesap adi ve sifre girin.
3. Hesap turu olarak **Personal** secin.
4. Ad, adres, telefon bilgilerini doldurun.
5. **Kredi karti** bilgisi istenir. Bu zorunludur; dogrulama icin kucuk bir
   tutar (~1 USD) cekilip iade edilir. Free Tier icinde kaldiginiz surece
   ucret alinmaz.
6. Telefon dogrulamasi yapin (SMS veya arama).
7. Destek plani olarak **Basic support - Free** secin.
8. Hesap acildiktan sonra **Sign in to the Console** ile giris yapin.

## 0.2 Bolge (Region) secimi

Sag ust kosede bir bolge adi yazar (orn. "N. Virginia"). Tiklayin ve
**Europe (Frankfurt) eu-central-1** secin.

Neden: Turkiye'ye en yakin, tum servislerin bulundugu bolgedir; gecikme dusuk olur.

**Ekipteki herkes ayni bolgeyi kullanmalidir.** Kaynaklar bolgeye ozeldir;
yanlis bolgedeyseniz olusturdugunuz makineyi listede goremezsiniz.

## 0.3 Fatura alarmi kurma (atlamayin)

1. Sag ust kosede hesap adiniza tiklayin -> **Billing and Cost Management**.
2. Sol menuden **Budgets** -> **Create budget**.
3. **Use a template (simplified)** -> **Monthly cost budget** secin.
4. Budget name: `dbms-butce`
5. Enter your budgeted amount: `5` (USD)
6. Email recipients: kendi e-posta adresiniz
7. **Create budget**.

Aylik harcama 5 dolari asma egilimine girerse mail gelir.

## 0.4 Makine tipi hakkinda not

AWS'nin Free Tier kosullari zaman zaman degisir. Makine secerken listede
**"Free tier eligible"** etiketini arayin — hangi tipin ucretsiz oldugunu
kesin olarak o etiket soyler. Genellikle `t3.micro` veya `t2.micro` olur.

Etiketli secenek yoksa `t3.micro` yine de dusuk maliyetlidir
(yaklasik 8-10 USD/ay) ve kullanmadiginiz zaman makineyi durdurabilirsiniz.

---

# BOLUM 1 — EC2 makinesini olusturma

## 1.1 EC2 sayfasina gitme

1. Konsolun ust kismindaki arama kutusuna `EC2` yazin.
2. Cikan **EC2** sonucuna tiklayin.
3. Sol menuden **Instances** -> sagdaki turuncu **Launch instances** butonu.

## 1.2 Makine ayarlari

Formu yukaridan asagi doldurun:

### Name and tags
- **Name:** `dbms-dev`

### Application and OS Images
- **Quick Start** sekmesinde **Ubuntu** secin.
- Listede **Ubuntu Server 24.04 LTS (HVM), SSD Volume Type** olsun.
- Architecture: **64-bit (x86)**
- Yaninda "Free tier eligible" yaziyorsa dogru secimdesiniz.

### Instance type
- **t3.micro** secin (veya "Free tier eligible" etiketli olan).

### Key pair (login)
Bu, sunucuya girisin anahtaridir.

1. **Create new key pair** baglantisina tiklayin.
2. Key pair name: `dbms-key`
3. Key pair type: **RSA**
4. Private key file format: **.pem**
5. **Create key pair** -> tarayici `dbms-key.pem` dosyasini indirir.

**Bu dosya cok onemlidir:**
- Kaybederseniz makineye bir daha giremezsiniz (yeni makine kurmak gerekir).
- Git deposuna KOYMAYIN.
- Sunucuya girecek ekip arkadaslarinizla guvenli sekilde paylasin.
- Bilinen bir yere kaydedin, orn: `C:\Users\<kullanici>\Desktop\dbms-key.pem`

### Network settings

Sag ustteki **Edit** butonuna basin.

- **Auto-assign public IP:** Enable
- **Firewall (security groups):** Create security group
- **Security group name:** `dbms-sg`
- **Description:** `DBMS ara katman erisimi`

Simdi kurallari ekleyin. Varsayilan olarak bir SSH kurali gelir:

**Kural 1 (SSH — sunucuya giris):**
- Type: `SSH`
- Port range: `22`
- Source type: **My IP**

**Kural 2 (ara katman):** **Add security group rule** butonuna basin.
- Type: `Custom TCP`
- Port range: `5150`
- Source type: **My IP**
- Description: `ara katman - benim`

**Kural 3, 4, ... (ekip arkadaslari):** Her arkadas icin bir satir daha:
- Type: `Custom TCP`
- Port range: `5150`
- Source type: **Custom**
- Source: arkadasinizin IP adresi + `/32`  (orn. `88.240.15.7/32`)
- Description: adi

Arkadaslariniz kendi IP'lerini https://checkip.amazonaws.com adresini
acarak ogrenebilir.

**MongoDB portunu (27017) EKLEMEYIN.** Buna gerek yoktur: arkadaslariniz
MongoDB'ye degil, 5150 portundaki ara katmana baglanir. Acik birakilan bir
MongoDB portu tarayici botlar tarafindan bulunur ve makinenin kotuye
kullanilmasina yol acar — bu size fatura olarak doner.

### Configure storage
- **1 x 20 GiB gp3** (varsayilan 8 GiB'i 20'ye cikarin; Docker imajlari yer kaplar)

## 1.3 Baslatma

1. Sagdaki ozet panelinde **Launch instance** butonuna basin.
2. Yesil onay ekrani gelir -> **View all instances**.
3. Listede `dbms-dev` gorunur. **Instance state** birkac saniye icinde
   `Running`, **Status check** ~2 dakika icinde `2/2 checks passed` olur.

---

# BOLUM 2 — Sabit IP (Elastic IP) alma

Makineyi durdurup baslattiginizda genel IP degisir ve herkesin ayari bozulur.
Bunu onlemek icin sabit bir IP baglayin.

1. EC2 sol menuden **Elastic IPs** (Network & Security altinda).
2. **Allocate Elastic IP address** -> ayarlara dokunmadan **Allocate**.
3. Listede yeni IP gorunur. Secin -> **Actions** -> **Associate Elastic IP address**.
4. Resource type: **Instance**
5. Instance: `dbms-dev` secin.
6. **Associate**.

Artik bu IP kalicidir. **Not alin** — rehberin geri kalaninda buna
`<SUNUCU-IP>` diyecegim.

Maliyet notu: Elastic IP, **calisan** bir makineye bagliyken ucretsizdir.
Makineyi uzun sure kapatacaksaniz IP'yi de serbest birakin (Release),
yoksa saatlik ucret islemeye baslar.

---

# BOLUM 3 — Sunucuya baglanma (SSH)

## 3.1 Anahtar dosyasinin izinlerini duzeltme (Windows)

Windows'ta `.pem` dosyasi "herkese acik" oldugu icin SSH reddedebilir.
PowerShell'i acin, `.pem` dosyasinin bulundugu klasore gidin:

```powershell
cd C:\Users\<kullanici>\Desktop
icacls .\dbms-key.pem /inheritance:r
icacls .\dbms-key.pem /grant:r "$($env:USERNAME):(R)"
```

## 3.2 Baglanma

```powershell
ssh -i .\dbms-key.pem ubuntu@<SUNUCU-IP>
```

- Ilk baglantida "Are you sure you want to continue connecting?" sorulur:
  `yes` yazip Enter.
- Basarili olursa komut satiri `ubuntu@ip-172-31-...:~$` seklinde degisir.
  Artik sunucunun icindesiniz.

**Hata: `Permission denied (publickey)`**
- Kullanici adi `ubuntu` mu? (Ubuntu imajlarinda kullanici adi `ubuntu`'dur.)
- Dogru `.pem` dosyasini mi gosteriyorsunuz?
- 3.1 adimini uyguladiniz mi?

**Baglanti takiliyor / zaman asimi**
- Security Group'ta 22 portu sizin IP'nize acik mi?
- IP'niz degismis olabilir (checkip.amazonaws.com ile bakip kurali guncelleyin).

**Cikmak icin:** `exit`

---

# BOLUM 4 — Sunucuyu hazirlama

Asagidaki komutlarin hepsi **sunucu icinde** (SSH baglantisi acikken) calistirilir.

## 4.1 Sistem guncellemesi

```bash
sudo apt update && sudo apt upgrade -y
```

Birkac dakika surer. Mavi ekranda bir soru cikarsa (servis yeniden baslatma
listesi) Enter'a basip gecin.

## 4.2 Docker kurulumu

```bash
curl -fsSL https://get.docker.com | sudo sh
```

Kurulum bitince kullanicinizi docker grubuna ekleyin:

```bash
sudo usermod -aG docker ubuntu
```

**Simdi oturumu kapatip yeniden acin** — grup degisikligi ancak yeni
oturumda gecerli olur:

```bash
exit
```

Sonra yerel bilgisayarinizdan tekrar:

```powershell
ssh -i .\dbms-key.pem ubuntu@<SUNUCU-IP>
```

Dogrulama (sunucuda):

```bash
docker --version
docker compose version
```

Ikisi de bir surum numarasi yazdirmali. `permission denied` hatasi
aliyorsaniz oturumu kapatip acmayi atlamissinizdir.

## 4.3 Takas alani (swap) ekleme

t3.micro'da 1 GB RAM vardir; MongoDB ve Java birlikte bunu zorlar.
2 GB takas alani ekleyin:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Dogrulama:

```bash
free -h
```

`Swap:` satirinda `2.0Gi` gorunmeli. Son komut, sunucu yeniden
baslatildiginda takas alaninin otomatik acilmasini saglar.

---

# BOLUM 5 — Kodu sunucuya tasima

Iki yol var. **Yol A (Git) onerilir**, cunku guncelleme tek komut olur.

## Yol A — Git ile

Proje bir GitHub deposunda olmali. Deponuz yoksa once olusturun ve yerel
projenizi oraya gonderin.

Sunucuda:

```bash
sudo apt install -y git
git clone https://github.com/<kullanici>/<depo-adi>.git tcp-server
cd tcp-server
ls
```

`ls` ciktisinda sunlari gormelisiniz:
`Dockerfile  README.md  docker-compose.server.yml  docker-compose.yml  lib  src`

Depo ozel (private) ise kullanici adi ve token sorar. GitHub sifresi
calismaz; **Personal Access Token** olusturmaniz gerekir
(GitHub -> Settings -> Developer settings -> Personal access tokens).

## Yol B — Dogrudan kopyalama (scp)

Git kullanmiyorsaniz, **yerel bilgisayarinizda** yeni bir PowerShell acin
(SSH oturumunda degil). Proje klasorunun **bir ust dizinine** gidin:

```powershell
cd C:\Users\<kullanici>\Desktop
scp -i .\dbms-key.pem -r .\tcp-server ubuntu@<SUNUCU-IP>:~/
```

Kopyalama bitince SSH oturumuna donup kontrol edin:

```bash
cd ~/tcp-server
ls
```

Bu yontemde her kod degisikliginde kopyalamayi tekrarlamaniz gerekir.

---

# BOLUM 6 — Calistirma

Sunucuda, dogru klasorde oldugunuzdan emin olun:

```bash
cd ~/tcp-server
pwd
```

Ciktida `/home/ubuntu/tcp-server` yazmali.

## 6.1 Baslatma

```bash
docker compose -f docker-compose.server.yml up -d --build
```

Ilk calistirmada:
- MongoDB ve Java imajlari indirilir (~600 MB, 2-5 dakika)
- Sunucu kodunuz imaj icinde derlenir
- Iki konteyner baslar

Sonraki calistirmalar cok daha hizlidir (imajlar zaten inmistir).

## 6.2 Kontrol

```bash
docker compose -f docker-compose.server.yml ps
```

Iki satir gormelisiniz: `dbms-mongo` ve `dbms-middleware`, ikisi de `Up`.
Mongo'nun yaninda `(healthy)` yazmasi beklenir.

Loglara bakin:

```bash
docker compose -f docker-compose.server.yml logs -f middleware
```

Su kutuyu gormelisiniz:

```
==============================================
 DBMS Middleware - TCP Server (Observer)
 Port          : 5150
 Max clients   : 16
 Protocol      : line-based JSON (ending with \n)
 Observer      : subscribe/unsubscribe + push
==============================================
```

Log izlemeden cikmak icin **Ctrl+C** (bu sunucuyu kapatmaz, sadece log
akisini birakir).

## 6.3 Sunucunun kendi icinden hizli test

```bash
docker compose -f docker-compose.server.yml exec middleware \
  java -cp out:lib/json-parser.jar middleware.AuthProtocolTest localhost 5150
```

`RESULT: 24 passed, 0 failed` gormelisiniz.

---

# BOLUM 7 — Yerel bilgisayarinizdan dogrulama

Bu adim, disaridan erisimin gercekten calistigini kanitlar.

Kendi bilgisayarinizda, yerel proje klasorunde:

```powershell
cd C:\Users\<kullanici>\Desktop\tcp-server
javac -cp lib\json-parser.jar -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
java -cp "out;lib/json-parser.jar" middleware.AuthProtocolTest <SUNUCU-IP> 5150
java -cp "out;lib/json-parser.jar" middleware.BackendProtocolTest <SUNUCU-IP> 5150
```

Beklenen:
```
RESULT: 24 passed, 0 failed
ALL PROTOCOL+AUTH TESTS PASSED

RESULT: 18 passed, 0 failed
ALL BACKEND PROTOCOL TESTS PASSED
```

Bunlari goruyorsaniz ortak sunucu calisiyor demektir.

**Baglanti kurulamiyorsa sirasiyla kontrol edin:**
1. Konteynerler ayakta mi? (`docker compose ... ps`)
2. Security Group'ta 5150 portu **sizin** IP'nize acik mi?
3. IP'niz degisti mi? (checkip.amazonaws.com)
4. Dogru IP'yi mi yazdiniz? (Elastic IP)

---

# BOLUM 8 — Ekip ayarlari

Herkes artik `localhost` yerine sunucu adresini kullanacak.

**On yuz (Flutter):** `lib/core/services/tcp_socket_service.dart` icindeki
`TcpConfig` sinifinda:
```dart
this.host = '<SUNUCU-IP>',
this.port = 5150,
```

**Arka yuz (Java kutuphanesi):** baglanti adresi `<SUNUCU-IP>`, port `5150`.

**Her ekip uyesi icin:** Security Group'ta o kisinin IP'sine 5150 portu
acilmis olmali (Bolum 1.2). IP'ler degisken oldugu icin (ozellikle mobil
internet) zaman zaman guncellemek gerekebilir.

---

# BOLUM 9 — Gunluk kullanim

Tum komutlar sunucuda, `~/tcp-server` klasorunde calistirilir.

**Kod guncellendiginde (Git ile):**
```bash
cd ~/tcp-server
git pull
docker compose -f docker-compose.server.yml up -d --build
```

**Servisleri durdurma (veriler KORUNUR):**
```bash
docker compose -f docker-compose.server.yml down
```

**Servisleri tekrar baslatma:**
```bash
docker compose -f docker-compose.server.yml up -d
```

**Sadece ara katmani yeniden baslatma:**
```bash
docker compose -f docker-compose.server.yml restart middleware
```

**Loglari izleme:**
```bash
docker compose -f docker-compose.server.yml logs -f middleware
docker compose -f docker-compose.server.yml logs -f mongo
```

**Verileri de silerek sifirlama (DIKKAT: MongoDB verileri gider):**
```bash
docker compose -f docker-compose.server.yml down -v
```

**Disk kullanimi ve temizlik:**
```bash
df -h                      # disk doluluk
docker system df           # docker'in kapladigi yer
docker system prune -f     # kullanilmayan imaj/konteynerleri sil
```

**Sunucu kaynak durumu:**
```bash
free -h                    # RAM ve swap
docker stats --no-stream   # konteynerlerin RAM/CPU kullanimi
```

---

# BOLUM 10 — MongoDB'ye gozle bakma (SSH tuneli)

MongoDB portu disariya kapali oldugu icin Compass ile dogrudan
baglanilmaz. Guvenli yol SSH tuneli acmaktir.

**1.** Yerel bilgisayarinizda yeni bir PowerShell acin:

```powershell
cd C:\Users\<kullanici>\Desktop
ssh -i .\dbms-key.pem -L 27017:localhost:27017 ubuntu@<SUNUCU-IP>
```

**2.** Bu pencereyi **acik birakin**. Tunel bu oturum boyunca calisir.

**3.** MongoDB Compass'i acin (yoksa mongodb.com/products/compass
adresinden indirin) ve su adrese baglanin:

```
mongodb://localhost:27017
```

Compass'ta `localhost` yazmasi kafa karistirabilir: trafik SSH tuneli
uzerinden sunucudaki MongoDB'ye gider. Internete hicbir port acilmaz.

**4.** Isiniz bitince tunel penceresinde `exit` yazin.

---

# BOLUM 11 — Sorun giderme

**`docker: permission denied`**
`sudo usermod -aG docker ubuntu` komutundan sonra oturumu kapatip
acmadiniz. `exit` yapip tekrar SSH ile baglanin.

**`no configuration file provided: not found`**
Yanlis klasordesiniz. `cd ~/tcp-server` yapin ve `ls` ile
`docker-compose.server.yml` dosyasini gordugunuzden emin olun.

**Konteyner surekli yeniden basliyor**
```bash
docker compose -f docker-compose.server.yml logs middleware
```
Hata mesajini okuyun. En yaygin sebep bellek yetersizligi — takas alani
ekleme adimini (4.3) atlamis olabilirsiniz.

**Derleme sirasinda makine donuyor / cok yavas**
t3.micro'da RAM sinirlidir. Takas alaninin acik oldugunu `free -h` ile
dogrulayin.

**`Connection refused` (baglanti reddedildi)**
Sunucuya ulasiliyor ama 5150'de dinleyen yok. Konteyner calismiyor demektir.

**Baglanti takiliyor / zaman asimi**
Sunucuya hic ulasilamiyor. Security Group kurali veya yanlis IP.

**MongoDB verilerini kaybettim**
`down -v` komutunu calistirdiysaniz volume silinmistir. `down` (v'siz)
veriyi korur.

---

# BOLUM 12 — Maliyet yonetimi

- **Free Tier:** yeni hesaplarda 12 ay boyunca 750 saat/ay ucretsiz makine
  (yani bir makine 7/24). Konsoldaki "Free tier eligible" etiketine guvenin.
- **Elastic IP:** calisan makineye bagliyken ucretsiz, bosta ucretli.
- **Disk (EBS):** 30 GB'a kadar Free Tier kapsaminda.
- **Veri cikisi:** aylik 100 GB'a kadar ucretsiz — sizin kullaniminiz bunun
  cok altinda kalir.

**Kullanmadiginiz donemde makineyi durdurun:**
EC2 -> Instances -> makineyi secin -> **Instance state** -> **Stop instance**.
Durdurulmus makinede islemci ucreti islemez, sadece disk ucreti kalir.
Tekrar baslatmak icin **Start instance** (Elastic IP sayesinde adres degismez).

**Projeyi tamamen bitirdiginizde:**
1. Instances -> makineyi secin -> **Instance state** -> **Terminate instance**
2. Elastic IPs -> IP'yi secin -> **Actions** -> **Release Elastic IP address**
3. Volumes -> arta kalan disk varsa silin

Bu ucunu yapmazsaniz kucuk de olsa ucret islemeye devam edebilir.

---

# Ozet komut karti

Sunucuya baglanma (yerel):
```powershell
ssh -i .\dbms-key.pem ubuntu@<SUNUCU-IP>
```

Sunucuda gunluk kullanim:
```bash
cd ~/tcp-server
git pull
docker compose -f docker-compose.server.yml up -d --build
docker compose -f docker-compose.server.yml ps
docker compose -f docker-compose.server.yml logs -f middleware
docker compose -f docker-compose.server.yml down
```

Yerelden dogrulama:
```powershell
java -cp "out;lib/json-parser.jar" middleware.AuthProtocolTest <SUNUCU-IP> 5150
```

Compass tuneli (yerel):
```powershell
ssh -i .\dbms-key.pem -L 27017:localhost:27017 ubuntu@<SUNUCU-IP>
```
