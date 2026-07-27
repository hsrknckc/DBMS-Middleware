# JSON Parser (Bağımsız Kütüphane Projesi)

TCP sunucusundan TAMAMEN bağımsız, sıfırdan yazılmış JSON parser + serializer.
Harici kütüphane yoktur; sadece JDK gerekir (Java 17+).

## Derleme, test ve jar üretimi

    javac -d out src/jsonparser/Json.java src/jsonparser/JsonTest.java
    java -cp out jsonparser.JsonTest
    jar cf json-parser.jar -C out jsonparser

Üretilen json-parser.jar dosyası tcp-server projesinin lib/ klasörüne kopyalanır.

## Kullanım (API)

    import jsonparser.Json;

    Map<String,Object> obj = Json.parseObject("{\"ad\":\"Ali\",\"yas\":30}");
    Object herhangi     = Json.parse("[1,2,3]");
    String metin        = Json.stringify(obj);

Tip eşlemesi: object->Map, array->List, string->String,
tam sayı->Long, ondalık->Double, true/false->Boolean, null->null.

## Kapsam ve sağlamlık

Desteklenenler: object, array, string, sayı (tam/ondalık/bilimsel gösterim),
true/false/null, tüm kaçış dizileri (\n \t \" \\ \/ \b \f \r ve \uXXXX),
Türkçe karakterler, emoji (surrogate çiftleri), çok satırlı/girintili JSON,
iç içe sınırsız derinlikte yapı (güvenlik sınırı: 200 seviye).

Standart dışı girdileri REDDEDER: baştaki sıfırlı sayı (01), sondaki virgül,
bozuk sayı (1.2.3), kapanmamış string/object, JSON sonrası fazlalık karakter.

Sınır durumları: Long'a sığmayan sayılar veri kaybı olmasın diye Double'a
düşürülür; null/boş girdi kontrollü hata verir; aşırı derin yapılar
StackOverflowError yerine JsonException üretir (Error yakalanamadığı için
derinlik ÖNCEDEN sınırlanır).

Bozuk girdi geldiğinde parser ÇÖKMEZ; Json.JsonException fırlatır.
Kullanan taraf bu exception'ı yakalayarak kendini korur - hata
izolasyonunun çalışma anındaki mekanizması budur.
