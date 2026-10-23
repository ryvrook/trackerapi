package fakes.dungeons.broken;

import fakes.dungeons.BossKilledEvent;

import java.util.function.Consumer;

/** The good implementation under the renamed interface. */
public final class DungeonFrameworkAPIImpl extends fakes.dungeons.internal.DungeonFrameworkAPIImpl implements DungeonFrameworkAPI {
    @Override
    public void addBossSlainListener(Consumer<BossKilledEvent> listener) {
        addBossKilledListener(listener);
    }

    @Override
    public void removeBossSlainListener(Consumer<BossKilledEvent> listener) {
        removeBossKilledListener(listener);
    }
}
