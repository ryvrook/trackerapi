package fakes.dungeons.broken;

import fakes.dungeons.BossKilledEvent;
import fakes.dungeons.DungeonCompletedEvent;
import fakes.dungeons.DungeonEnteredEvent;
import fakes.dungeons.DungeonExitedEvent;

import java.util.function.Consumer;

/** The API with the boss listener methods renamed, for the partial bind test. */
public interface DungeonFrameworkAPI {
    void addBossSlainListener(Consumer<BossKilledEvent> listener);

    void removeBossSlainListener(Consumer<BossKilledEvent> listener);

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
