#!/bin/bash
# ============================================================
#  GERCEK KIMLIK BILGILERINI SAKLAMA YARDIMCISI
#
#  Bu dosya bir ORNEKTIR ve git'e gonderilir (sahte degerler icerir).
#  Gercek bilgilerini asla bu depoya commit'leme - depo herkese acik.
#
#  Kullanimi:
#    1) Bu dosyayi kopyala:
#         cp set-credentials.sh.example set-credentials.sh
#    2) set-credentials.sh icindeki degerleri gercek EC2 kullanici
#       adi/sifresiyle degistir.
#    3) Uygulamayi baslatmadan once bu dosyayi "source" et:
#         source set-credentials.sh
#         ./gradlew :demo-api:bootRun
#
#  set-credentials.sh dosyasi .gitignore icinde oldugu icin YANLISLIKLA
#  commit'lenmez.
# ============================================================

export MIDDLEWARE_USER="arslanbejna@gmail.com"
export MIDDLEWARE_PASSWORD="Bejna:2004*"

# Yerel sahte ara katmanla (mock) test etmek icin:
#   MIDDLEWARE_HOST=localhost MIDDLEWARE_USER=admin MIDDLEWARE_PASSWORD=admin123 ./gradlew :demo-api:bootRun
