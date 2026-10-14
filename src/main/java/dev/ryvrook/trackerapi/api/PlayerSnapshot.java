package dev.ryvrook.trackerapi.api;

import java.util.UUID;

/**
 * The last once a second sample TrackerAPI took of an online player. Positions are block coordinates of
 * the player's feet. {@code gameMode} is the engine's name, such as {@code Adventure} or {@code Creative}.
 * {@code zone}, {@code region} and {@code biome} are null when the engine has not told the player yet or the
 * read is off on this server. {@code sessionStart} is when the player's current session began.
 *
 * <p>Immutable, components are only ever added.
 */
public record PlayerSnapshot(UUID uuid, String name, String world, double x, double y, double z, float yaw,
                             float pitch, String gameMode, String zone, String region, String biome,
                             boolean creative, boolean swimming, boolean gliding, boolean riding,
                             long sessionStart, long sampledAt) {
}
