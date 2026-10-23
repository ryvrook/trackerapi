package fakes.dungeons;

import java.util.Set;
import java.util.UUID;

public record BossKilledEvent(String bossTypeId, UUID worldUuid, String worldName, String dungeonId, String modeName,
                              Set<UUID> attackerUuids) {
}
