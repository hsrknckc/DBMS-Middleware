# =============================================================
#  DBMS Ara Katman - TCP Sunucusu
#  Cok asamali (multi-stage) yapi:
#    1. asama JDK ile derler, 2. asama sadece JRE ile calistirir.
#  Boylece son imaj kucuk kalir ve icinde derleyici/kaynak kod bulunmaz.
# =============================================================

# ---------- 1. ASAMA: DERLEME ----------
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Once bagimlilik (jar), sonra kaynak kod kopyalanir.
# Sirasi onemli: lib nadiren degisir, src sik degisir. Docker katmanlari
# onbellege aldigi icin bu sira yeniden derlemeleri hizlandirir.
COPY lib/ lib/
COPY src/ src/

# Tum .java dosyalarini bulup tek seferde derle.
# -encoding UTF-8: kaynak dosyalardaki Turkce yorumlar bozulmasin.
RUN find src -name "*.java" > sources.txt && \
    javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt

# ---------- 2. ASAMA: CALISTIRMA ----------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Derlenmis siniflari ve jar'i onceki asamadan al. Kaynak kod tasinmaz.
COPY --from=builder /build/out ./out
COPY --from=builder /build/lib ./lib

# Yapilandirma imaja gomulmez; calistirma aninda disaridan verilebilir.
# Main sinifi PORT ortam degiskenini okur.
ENV PORT=5150

# Bu port disariya acilacak (belgeleme amacli; asil eslesme compose'ta yapilir).
EXPOSE 5150

# UTF-8 ciktisi: konteyner loglarinda Turkce karakterler bozulmasin.
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

CMD ["java", "-cp", "out:lib/*", "middleware.Main"]
