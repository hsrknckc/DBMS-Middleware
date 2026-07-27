package middleware.events;

public class ConsoleLogObserver implements Observer {

    @Override
    public void onEvent(Event event) {
        System.out.println("[log] EVENT: " + event.type()
                + " / " + event.collection()
                + " -> " + event.data());
    }
}
