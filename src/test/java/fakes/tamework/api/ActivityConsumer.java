package fakes.tamework.api;

/** One abstract method, the binding proxies it. */
public interface ActivityConsumer {
    void accept(ActivityView view);
}
