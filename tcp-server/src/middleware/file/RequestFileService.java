package middleware.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import jsonparser.Json;

/**
 * VERITABANI TALEP DOSYASI SERVISI (Ister_0011, Ister_0012)
 *
 * On Yuz, kullanicinin tasarladigi veritabani alanlarini JSON dosyasina
 * yazip belirledigi bir klasore kaydeder (Ister_0006, Ister_0007).
 * Ara katman bu dosyanin var olup olmadigini kontrol eder (Ister_0011)
 * ve iceriginden veritabani alanlarini yaratir (Ister_0012).
 *
 * GUVENLIK: DIZIN SINIRI
 * Dosya yolu ag uzerinden geldigi icin serbest birakilamaz; aksi halde
 * istemci sunucudaki herhangi bir dosyayi okuyabilirdi (path traversal).
 * Bu yuzden tum yollar bir TABAN KLASOR altinda cozulur ve disari cikma
 * denemeleri reddedilir.
 *
 * Taban klasor REQUEST_DIR ortam degiskeni ile belirlenir;
 * verilmezse calisma dizinindeki "db-requests" kullanilir.
 */
public class RequestFileService {

    private final Path baseDirectory;

    public RequestFileService(String baseDirectory) {
        String dir = (baseDirectory == null || baseDirectory.isBlank())
                ? "db-requests" : baseDirectory;
        this.baseDirectory = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** Taban klasorun mutlak yolu (loglarda ve cevaplarda gosterilir). */
    public String baseDirectory() {
        return baseDirectory.toString();
    }

    /**
     * Istenen yolu taban klasor altinda cozer.
     * Disari cikmaya calisan yollar (".." vb.) reddedilir.
     *
     * @throws PathNotAllowed yol taban klasorun disina cikiyorsa
     */
    private Path resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new PathNotAllowed("path is required");
        }
        Path candidate;
        try {
            candidate = baseDirectory.resolve(requested).normalize();
        } catch (InvalidPathException e) {
            throw new PathNotAllowed("Invalid path: " + requested);
        }
        if (!candidate.startsWith(baseDirectory)) {
            throw new PathNotAllowed("Path is outside the request directory: " + requested);
        }
        return candidate;
    }

    /**
     * Ister_0011: dosya belirtilen yolda var mi?
     *
     * Yalnizca varligi degil, okunabilirligi ve gecerli JSON olup
     * olmadigini da bildirir — cunku bir sonraki adim (Ister_0012)
     * bu dosyayi okuyacaktir.
     */
    public Map<String, Object> check(String requested) {
        Path path = resolve(requested);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", requested);
        result.put("resolvedPath", path.toString());
        result.put("baseDirectory", baseDirectory.toString());

        boolean exists = Files.exists(path);
        result.put("exists", exists);

        if (!exists) {
            result.put("readable", false);
            result.put("isFile", false);
            result.put("validJson", false);
            result.put("size", 0L);
            return result;
        }

        boolean isFile = Files.isRegularFile(path);
        boolean readable = Files.isReadable(path);
        result.put("isFile", isFile);
        result.put("readable", readable);

        long size = 0L;
        try {
            size = Files.size(path);
        } catch (IOException ignored) {
            // boyut okunamadi; 0 birakilir
        }
        result.put("size", size);

        // Icerik gercekten JSON mu? (Ister_0014 ile ayni ruh: format dogrulama)
        boolean validJson = false;
        if (isFile && readable) {
            try {
                Json.parseObject(readText(path));
                validJson = true;
            } catch (Exception ignored) {
                // gecersiz JSON; validJson false kalir
            }
        }
        result.put("validJson", validJson);
        return result;
    }

    /**
     * Dosyayi okuyup JSON olarak ayristirir (Ister_0012'nin girdisi).
     *
     * @throws FileProblem dosya yoksa, okunamiyorsa veya JSON gecersizse
     */
    public Map<String, Object> readJson(String requested) {
        Path path = resolve(requested);

        if (!Files.exists(path)) {
            throw new FileProblem("File not found: " + requested);
        }
        if (!Files.isRegularFile(path)) {
            throw new FileProblem("Not a regular file: " + requested);
        }
        if (!Files.isReadable(path)) {
            throw new FileProblem("File is not readable: " + requested);
        }

        String text;
        try {
            text = readText(path);
        } catch (IOException e) {
            throw new FileProblem("File could not be read: " + e.getMessage());
        }

        try {
            return Json.parseObject(text);
        } catch (Exception e) {
            throw new FileProblem("File does not contain valid JSON: " + e.getMessage());
        }
    }

    private static String readText(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** Taban klasoru yoksa olusturur; basarisiz olursa sessizce gecer. */
    public void ensureBaseDirectory() {
        try {
            Files.createDirectories(baseDirectory);
        } catch (IOException e) {
            System.out.println("[file] UYARI: talep klasoru olusturulamadi: " + e.getMessage());
        }
    }

    /** Taban klasor disina cikan yol istekleri. */
    public static class PathNotAllowed extends RuntimeException {
        public PathNotAllowed(String m) { super(m); }
    }

    /** Dosya bulunamadi / okunamadi / gecersiz JSON. */
    public static class FileProblem extends RuntimeException {
        public FileProblem(String m) { super(m); }
    }
}
