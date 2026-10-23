package fakes.rpg;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The API singleton of the fake RPG Leveling, with the listener lists, the static accessors and the two reads the
 * binding uses, shaped like the real class. The engine typed parameters of the reads are the fake player and plain
 * objects, the binding resolves those methods by name and count and never names the types. The {@code set},
 * {@code clear} and {@code fire} methods are the test's levers.
 */
public final class RPGLevelingAPI {
    private static volatile RPGLevelingAPI instance;
    private final CopyOnWriteArrayList<ExperienceGainedListener> experienceGainedListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LevelUpListener> levelUpListeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, PlayerLevelInfo> levels = new HashMap<>();
    private final Map<UUID, PlayerClassInfo> classes = new HashMap<>();

    private RPGLevelingAPI() {
    }

    public static String getVersion() {
        return "0.2.0";
    }

    public static void clearInstance() {
        instance = null;
    }

    public static RPGLevelingAPI get() {
        RPGLevelingAPI api = instance;
        if (api == null) {
            api = new RPGLevelingAPI();
            instance = api;
        }
        return api;
    }

    public static boolean isAvailable() {
        return true;
    }

    public void registerExperienceGainedListener(ExperienceGainedListener listener) {
        experienceGainedListeners.add(listener);
    }

    public boolean unregisterExperienceGainedListener(ExperienceGainedListener listener) {
        return experienceGainedListeners.remove(listener);
    }

    public int getExperienceGainedListenerCount() {
        return experienceGainedListeners.size();
    }

    public void fireExperienceGainedEvent(ExperienceGainedEvent event) {
        for (ExperienceGainedListener l : experienceGainedListeners) {
            l.onExperienceGained(event);
        }
    }

    public void registerLevelUpListener(LevelUpListener listener) {
        levelUpListeners.add(listener);
    }

    public boolean unregisterLevelUpListener(LevelUpListener listener) {
        return levelUpListeners.remove(listener);
    }

    public int getLevelUpListenerCount() {
        return levelUpListeners.size();
    }

    public void fireLevelUpEvent(LevelUpEvent event) {
        for (LevelUpListener l : levelUpListeners) {
            l.onLevelUp(event);
        }
    }

    /** The UUID overload the binding must not use, it answers a fresh default as the real one does in a world. */
    public PlayerLevelInfo getPlayerLevelInfo(UUID uuid) {
        return new PlayerLevelInfo(uuid, "", 1, 0, 100, 50, false);
    }

    public PlayerLevelInfo getPlayerLevelInfo(FakePlayerRef player, Object store) {
        return player == null ? null : levels.get(player.getUuid());
    }

    public PlayerClassInfo getPlayerClassInfo(UUID uuid) {
        return new PlayerClassInfo(null, null, 0, 0, Map.of());
    }

    public PlayerClassInfo getPlayerClassInfo(FakePlayerRef player, Object ref, Object store) {
        return player == null ? null : classes.get(player.getUuid());
    }

    public int getMonsterLevel(Object store, Object ref) {
        return 0;
    }

    // ---- the test's levers ------------------------------------------------------------------------------

    public void setLevel(UUID uuid, int level, int maxLevel) {
        levels.put(uuid, new PlayerLevelInfo(uuid, "", level, 0, 100, maxLevel, level >= maxLevel));
    }

    public void setClass(UUID uuid, String classId, String displayName, int tier) {
        classes.put(uuid, new PlayerClassInfo(classId, displayName, tier, 0, Map.of(classId, tier)));
    }

    public void clearClass(UUID uuid) {
        classes.put(uuid, new PlayerClassInfo(null, null, 0, 0, Map.of()));
    }

    public void fireXp(UUID uuid, double amount, String source, boolean cancelled) {
        ExperienceGainedEvent e = new ExperienceGainedEvent(new FakePlayerRef(uuid), amount,
                source == null ? null : XPSource.create(source), null);
        e.setCancelled(cancelled);
        fireExperienceGainedEvent(e);
    }

    public void fireLevelUp(UUID uuid, int oldLevel, int newLevel) {
        fireLevelUpEvent(new LevelUpEvent(new FakePlayerRef(uuid), oldLevel, newLevel, newLevel - oldLevel));
    }

    public static final class PlayerLevelInfo {
        private final UUID uuid;
        private final String username;
        private final int level;
        private final double experience;
        private final double xpNeededForNext;
        private final int maxLevel;
        private final boolean isMaxLevel;

        public PlayerLevelInfo(UUID uuid, String username, int level, double experience, double xpNeededForNext, int maxLevel,
                               boolean isMaxLevel) {
            this.uuid = uuid;
            this.username = username;
            this.level = level;
            this.experience = experience;
            this.xpNeededForNext = xpNeededForNext;
            this.maxLevel = maxLevel;
            this.isMaxLevel = isMaxLevel;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getUsername() {
            return username;
        }

        public int getLevel() {
            return level;
        }

        public double getExperience() {
            return experience;
        }

        public double getXpNeededForNext() {
            return xpNeededForNext;
        }

        public int getMaxLevel() {
            return maxLevel;
        }

        public boolean isMaxLevel() {
            return isMaxLevel;
        }
    }

    public static final class PlayerClassInfo {
        private final String classId;
        private final String displayName;
        private final int selectedClassTier;
        private final int killsInCurrentTier;
        private final Map<String, Integer> allClassTiers;

        public PlayerClassInfo(String classId, String displayName, int selectedClassTier, int killsInCurrentTier,
                               Map<String, Integer> allClassTiers) {
            this.classId = classId;
            this.displayName = displayName;
            this.selectedClassTier = selectedClassTier;
            this.killsInCurrentTier = killsInCurrentTier;
            this.allClassTiers = allClassTiers;
        }

        public String getClassId() {
            return classId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getSelectedClassTier() {
            return selectedClassTier;
        }

        public int getKillsInCurrentTier() {
            return killsInCurrentTier;
        }

        public boolean hasClass() {
            return classId != null && !classId.isEmpty();
        }

        public Map<String, Integer> getAllClassTiers() {
            return allClassTiers;
        }
    }
}
