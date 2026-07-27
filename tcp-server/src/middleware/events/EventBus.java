package middleware.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {

    private final List<Observer> observers = new CopyOnWriteArrayList<>();

    public void register(Observer observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void unregister(Observer observer) {
        observers.remove(observer);
    }

    public void publish(Event event) {
        for (Observer o : observers) {
            try {
                o.onEvent(event);
            } catch (Exception e) {
                System.out.println("[!] Observer error: " + e.getMessage());
            }
        }
    }

    public int observerCount() {
        return observers.size();
    }
}
