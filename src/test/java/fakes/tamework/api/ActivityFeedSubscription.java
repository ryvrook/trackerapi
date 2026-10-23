package fakes.tamework.api;

public interface ActivityFeedSubscription extends AutoCloseable {
    String consumerId();

    @Override
    void close();
}
