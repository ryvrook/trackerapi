package fakes.dungeons;

import java.util.UUID;

public record DungeonCompletedEvent(UUID playerUuid, String worldName, String dungeonId, String modeName) {
}
