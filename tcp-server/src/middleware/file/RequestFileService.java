package middleware.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import jsonparser.Json;

/**
 * VERITABANI / KULLANICI TALEP DOSYASI SERVISI
 *
 * database_*.json -> database import islemleri
 * user_*.json     -> user / permission import islemleri
 */
public class RequestFileService {

    private final Path baseDirectory;

    public RequestFileService(String baseDirectory) {
        String dir = (baseDirectory == null || baseDirectory.isBlank())
                ? "db-requests"
                : baseDirectory;
        this.baseDirectory = Paths.get(dir).toAbsolutePath().normalize();
    }

    /** Taban klasorun mutlak yolu. */
    public String baseDirectory() {
        return baseDirectory.toString();
    }

    /**
     * Istenen yolu taban klasor altinda cozer.
     * ".." ile klasor disina cikmaya calisan yollar reddedilir.
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
     * Dosya var mi, okunabilir mi, JSON gecerli mi kontrol eder.
     */
    public Map<String, Object> check(String requested) {
        Path path = resolve(requested);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", requested);
        result.put("resolvedPath", path.toString());
        result.put("baseDirectory", baseDirectory.toString());
        result.put("fileType", detectRequestFileType(path).name());

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
            // boyut okunamazsa 0 kalir
        }
        result.put("size", size);

        boolean validJson = false;
        if (isFile && readable) {
            try {
                Json.parseObject(readText(path));
                validateRequestFileName(path);
                validJson = true;
            } catch (Exception ignored) {
                validJson = false;
            }
        }

        result.put("validJson", validJson);
        return result;
    }

    /**
     * Dosyayi okuyup JSON object olarak dondurur.
     */
    public Map<String, Object> readJson(String requested) {
        Path path = resolve(requested);
        validateRequestFileName(path);

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

    /**
     * Router buradan dosyanin user dosyasi olup olmadigini anlayacak.
     */
    public boolean isUserFile(String requested) {
        return detectRequestFileType(resolve(requested)) == RequestFileType.USER;
    }

    /**
     * Router buradan dosyanin database dosyasi olup olmadigini anlayacak.
     */
    public boolean isDatabaseFile(String requested) {
        return detectRequestFileType(resolve(requested)) == RequestFileType.DATABASE;
    }

    private void validateRequestFileName(Path path) {
        RequestFileType fileType = detectRequestFileType(path);

        if (fileType == RequestFileType.UNKNOWN) {
            throw new FileProblem(
                    "Request file name must start with user_ or database_: "
                            + path.getFileName()
            );
        }
    }

    private RequestFileType detectRequestFileType(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);

        if (fileName.startsWith("user_") && fileName.endsWith(".json")) {
            return RequestFileType.USER;
        }

        if (fileName.startsWith("database_") && fileName.endsWith(".json")) {
            return RequestFileType.DATABASE;
        }

        return RequestFileType.UNKNOWN;
    }

    private static String readText(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** Taban klasoru yoksa olusturur. */
    public void ensureBaseDirectory() {
        try {
            Files.createDirectories(baseDirectory);
        } catch (IOException e) {
            System.out.println("[file] UYARI: talep klasoru olusturulamadi: " + e.getMessage());
        }
    }

    /** Taban klasor disina cikan yol istekleri. */
    public static class PathNotAllowed extends RuntimeException {
        public PathNotAllowed(String m) {
            super(m);
        }
    }

    /** Dosya bulunamadi / okunamadi / gecersiz JSON. */
    public static class FileProblem extends RuntimeException {
        public FileProblem(String m) {
            super(m);
        }
    }

    private enum RequestFileType {
        USER,
        DATABASE,
        UNKNOWN
    }
}