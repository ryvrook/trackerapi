package fakes.dungeons;

import java.util.UUID;

public record DungeonEnteredEvent(UUID playerUuid, String worldName, String dungeonId, String modeName) {
}
