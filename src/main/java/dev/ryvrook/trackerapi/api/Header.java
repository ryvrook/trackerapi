package dev.ryvrook.trackerapi.api;

import java.util.UUID;

/**
 * The part every event shares. {@code at} is wall clock milliseconds, {@code seq} rises in publish order,
 * {@code world} is the world name or null, {@code player} the player the event is about or null. See
 * {@link TrackerEvent} for what each means.
 */
public record Header(long at, long seq, String world, UUID player) {
}
