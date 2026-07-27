package middleware.server;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import middleware.events.Event;
import middleware.events.Observer;
import jsonparser.Json;

public class ClientSession implements Observer {

    private final PrintWriter out;
    private final String clientId;

    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();

    public ClientSession(PrintWriter out, String clientId) {
        this.out = out;
        this.clientId = clientId;
    }

    public synchronized void send(String line) {
        out.println(line);
    }

    public void subscribe(String collection) {
        subscriptions.add(collection);
    }

    public void unsubscribe(String collection) {
        subscriptions.remove(collection);
    }

    public boolean isSubscribed(String collection) {
        return subscriptions.contains(collection) || subscriptions.contains("*");
    }

    public String clientId() {
        return clientId;
    }

    @Override
    public void onEvent(Event event) {
        if (!isSubscribed(event.collection())) {
            return;
        }
        Map<String, Object> message = Map.of(
                "type", "event",
                "event", event.type(),
                "collection", event.collection(),
                "data", event.data() == null ? Map.of() : event.data()
        );
        send(Json.stringify(message));
        System.out.println("[~] " + clientId + " <- event pushed: "
                + event.type() + "/" + event.collection());
    }
}
