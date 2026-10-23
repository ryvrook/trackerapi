package fakes.tamework.api;

public record ActivityFeedStatus(boolean available, boolean subscribed, long lastAttemptedSequence, String detail) {
}
