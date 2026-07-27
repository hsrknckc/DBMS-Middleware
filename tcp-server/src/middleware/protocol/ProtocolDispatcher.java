package middleware.protocol;

import java.util.Map;

import jsonparser.Json;
import middleware.server.ClientSession;

/**
 * PROTOKOL AYIRICI (dispatcher).
 *
 * Ara katman iki farkli istemci turune hizmet verir:
 *   - On Yuz (Flutter): {"requestId":..,"action":"records.create","token":..,"payload":..}
 *   - Arka Yuz (backend kutuphanesi): {"requestId":..,"action":"WRITE","username":..,..}
 *
 * Bu sinif gelen ham satiri bir kez parse eder, hangi protokol oldugunu
 * anlar ve dogru router'a yonlendirir. Iki router da AYNI DataStore ve
 * AuthService'i kullandigi icin iki taraf ayni veriyi paylasir.
 *
 * ClientHandler artik dogrudan RequestRouter'i degil bu dispatcher'i cagirir.
 */
public class ProtocolDispatcher {

    private final RequestRouter frontendRouter;
    private final BackendRouter backendRouter;

    public ProtocolDispatcher(RequestRouter frontendRouter, BackendRouter backendRouter) {
        this.frontendRouter = frontendRouter;
        this.backendRouter = backendRouter;
    }

    public String handle(String rawJson, ClientSession session) {
        Map<String, Object> request;
        try {
            request = Json.parseObject(rawJson);
        } catch (Json.JsonException e) {
            // Bozuk JSON: frontend router'a birak, o kendi zarfinda hata dondursun.
            return frontendRouter.handle(rawJson, session);
        }

        if (BackendRouter.matches(request)) {
            return backendRouter.handle(request);
        }
        return frontendRouter.handle(rawJson, session);
    }
}
