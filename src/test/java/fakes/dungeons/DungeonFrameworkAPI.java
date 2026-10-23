package fakes.dungeons;

import java.util.function.Consumer;

/** The dungeon framework API as Major Dungeons 0.4.14 bundles it, same member names and shapes. */
public interface DungeonFrameworkAPI {
    String DEFAULT_MODE = "Default";

    void addBossKilledListener(Consumer<BossKilledEvent> listener);

    void removeBossKilledListener(Consumer<BossKilledEvent> listener);

    boolean isDungeonWorld(String worldName);

    String getDungeonId(String worldName);

    String getDungeonMode(String worldName);

    void addDungeonEnteredListener(Consumer<DungeonEnteredEvent> listener);

    void removeDungeonEnteredListener(Consumer<DungeonEnteredEvent> listener);

    void addDungeonExitedListener(Consumer<DungeonExitedEvent> listener);

    void removeDungeonExitedListener(Consumer<DungeonExitedEvent> listener);

    void addDungeonCompletedListener(Consumer<DungeonCompletedEvent> listener);

    void removeDungeonCompletedListener(Consumer<DungeonCompletedEvent> listener);
}
