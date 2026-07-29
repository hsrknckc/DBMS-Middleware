package middleware.config;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class AppConfig {

    private final Map<String, String> values = new LinkedHashMap<>();
    private String loadedFrom = null;

    public AppConfig() {
        String dir = firstNonBlank(
                System.getenv("MONGO_CONFIG_DIR"),
                System.getenv("CONFIG_DIR"),
                ".");
        File file = new File(dir, "config.xml");

        if (file.isFile()) {
            try {
                readXml(file);
                loadedFrom = file.getAbsolutePath();
            } catch (Exception e) {
                System.out.println("[config] UYARI: config.xml okunamadi: " + e.getMessage());
            }
        }
    }

    /** config.xml'i DOM ile okur; her alt elemani ad -> deger olarak alir. */
    private void readXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        // Guvenlik: disaridan gelen XML'in harici varlik cagirmasini engelle
        // (XXE saldirilarina karsi standart onlem).
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Element root = builder.parse(file).getDocumentElement();

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = node.getNodeName();
            String value = node.getTextContent();
            if (value != null && !value.isBlank()) {
                values.put(name, value.trim());
            }
        }
    }

    /** Yapilandirmanin okundugu dosya, yoksa null. */
    public String loadedFrom() {
        return loadedFrom;
    }

    /**
     * Bir ayari cozer: ortam degiskeni > config.xml > varsayilan.
     *
     * @param envKey ortam degiskeni adi (orn. "MONGO_DB")
     * @param xmlKey config.xml eleman adi (orn. "MongoDatabase")
     */
    public String get(String envKey, String xmlKey, String fallback) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv.trim();

        String fromXml = values.get(xmlKey);
        if (fromXml != null && !fromXml.isBlank()) return fromXml;

        return fallback;
    }

    /**
     * MongoDB baglanti adresini cozer.
     *
     * Once MONGO_URI ortam degiskenine bakilir (Docker bunu kullanir).
     * Yoksa config.xml'deki MongoAddress + MongoPort birlestirilir.
     * Ikisi de yoksa null doner ve bellek deposu kullanilir.
     */
    public String mongoUri() {
        String uri = System.getenv("MONGO_URI");
        if (uri != null && !uri.isBlank()) return uri.trim();

        String address = values.get("MongoAddress");
        String port = values.get("MongoPort");
        if (address == null || address.isBlank()) return null;

        return "mongodb://" + address + (port == null || port.isBlank() ? "" : ":" + port);
    }

    public String mongoDatabase() {
        return get("MONGO_DB", "MongoDatabase", "dbms");
    }

    public String requestDirectory() {
        return get("REQUEST_DIR", "RequestDir", "db-requests");
    }

    /** Sunucu portu; gecersiz deger verilirse varsayilana duser. */
    public int serverPort(int fallback) {
        String value = get("PORT", "ServerPort", null);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.out.println("[config] UYARI: gecersiz port degeri '" + value
                    + "', varsayilan kullaniliyor: " + fallback);
            return fallback;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return ".";
    }
}
