package fakes.dungeons;

import java.util.UUID;

public record DungeonExitedEvent(UUID playerUuid, String worldName, String dungeonId, String modeName) {
}
