package fakes.tamework.api;

public interface ActivityFeedApi {
    ActivityFeedSubscription subscribe(String consumerId, ActivityFilter filter, ActivityConsumer consumer);

    ActivityFeedStatus status(String consumerId);

    static ActivityFeedApi unavailable() {
        return new ActivityFeedApi() {
            @Override
            public ActivityFeedSubscription subscribe(String consumerId, ActivityFilter filter, ActivityConsumer consumer) {
                throw new IllegalStateException("activity feed unavailable");
            }

            @Override
            public ActivityFeedStatus status(String consumerId) {
                return new ActivityFeedStatus(false, false, 0, "unavailable");
            }
        };
    }
}
