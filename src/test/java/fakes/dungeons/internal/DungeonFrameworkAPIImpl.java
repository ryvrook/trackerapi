package fakes.dungeons.internal;

import fakes.dungeons.BossKilledEvent;
import fakes.dungeons.DungeonCompletedEvent;
import fakes.dungeons.DungeonEnteredEvent;
import fakes.dungeons.DungeonExitedEvent;
import fakes.dungeons.DungeonFrameworkAPI;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** The one implementation, four listener lists and the fire methods the framework's systems call. */
public class DungeonFrameworkAPIImpl implements DungeonFrameworkAPI {
    private final List<Consumer<BossKilledEvent>> bossKilled = new CopyOnWriteArrayList<>();
    private final List<Consumer<DungeonEnteredEvent>> entered = new CopyOnWriteArrayList<>();
    private final List<Consumer<DungeonExitedEvent>> exited = new CopyOnWriteArrayList<>();
    private final List<Consumer<DungeonCompletedEvent>> completed = new CopyOnWriteArrayList<>();

    @Override
    public void addBossKilledListener(Consumer<BossKilledEvent> listener) {
        bossKilled.add(listener);
    }

    @Override
    public void removeBossKilledListener(Consumer<BossKilledEvent> listener) {
        bossKilled.remove(listener);
    }

    @Override
    public boolean isDungeonWorld(String worldName) {
        return getDungeonId(worldName) != null;
    }

    @Override
    public String getDungeonId(String worldName) {
        if (worldName == null || !worldName.startsWith("instance-") || worldName.length() < 47) {
            return null;
        }
        return worldName.substring("instance-".length(), worldName.length() - 37);
    }

    @Override
    public String getDungeonMode(String worldName) {
        return isDungeonWorld(worldName) ? DEFAULT_MODE : null;
    }

    @Override
    public void addDungeonEnteredListener(Consumer<DungeonEnteredEvent> listener) {
        entered.add(listener);
    }

    @Override
    public void removeDungeonEnteredListener(Consumer<DungeonEnteredEvent> listener) {
        entered.remove(listener);
    }

    @Override
    public void addDungeonExitedListener(Consumer<DungeonExitedEvent> listener) {
        exited.add(listener);
    }

    @Override
    public void removeDungeonExitedListener(Consumer<DungeonExitedEvent> listener) {
        exited.remove(listener);
    }

    @Override
    public void addDungeonCompletedListener(Consumer<DungeonCompletedEvent> listener) {
        completed.add(listener);
    }

    @Override
    public void removeDungeonCompletedListener(Consumer<DungeonCompletedEvent> listener) {
        completed.remove(listener);
    }

    public void fireBossKilled(BossKilledEvent event) {
        for (Consumer<BossKilledEvent> l : bossKilled) {
            l.accept(event);
        }
    }

    public void fireDungeonEntered(DungeonEnteredEvent event) {
        for (Consumer<DungeonEnteredEvent> l : entered) {
            l.accept(event);
        }
    }

    public void fireDungeonExited(DungeonExitedEvent event) {
        for (Consumer<DungeonExitedEvent> l : exited) {
            l.accept(event);
        }
    }

    public void fireDungeonCompleted(DungeonCompletedEvent event) {
        for (Consumer<DungeonCompletedEvent> l : completed) {
            l.accept(event);
        }
    }

    /** Hands the entered listeners an object that is not an event, for the guard test. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void fireRaw(Object anything) {
        for (Consumer l : entered) {
            l.accept(anything);
        }
    }

    public int listenerCount() {
        return bossKilled.size() + entered.size() + exited.size() + completed.size();
    }

    public void enter(UUID player, String world, String dungeonId, String mode) {
        fireDungeonEntered(new DungeonEnteredEvent(player, world, dungeonId, mode));
    }

    public void exit(UUID player, String world, String dungeonId, String mode) {
        fireDungeonExited(new DungeonExitedEvent(player, world, dungeonId, mode));
    }

    public void complete(UUID player, String world, String dungeonId, String mode) {
        fireDungeonCompleted(new DungeonCompletedEvent(player, world, dungeonId, mode));
    }

    public void boss(String bossTypeId, String world, String dungeonId, String mode, Set<UUID> attackers) {
        fireBossKilled(new BossKilledEvent(bossTypeId, UUID.randomUUID(), world, dungeonId, mode, attackers));
    }
}
