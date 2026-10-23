package dev.ryvrook.trackerapi.mods.mmo;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.ModAbilityUsed;
import dev.ryvrook.trackerapi.api.event.ModChoiceChanged;
import dev.ryvrook.trackerapi.api.event.ModLevelUp;
import dev.ryvrook.trackerapi.api.event.ModMilestone;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The binding to MMO Skill Tree 1.7.3 (Ziggfreed:MMOSkillTree) with its common library ZiggfreedCommon 2.5.0.
 * Skill XP and skill level ups come from the mod's static XP listener list, ability casts from the resolved
 * cast event on the engine bus, quests, bounties and achievements from the common library's engine bus events,
 * and the current skill levels, the total level and the class from the skill component read on the world thread
 * at bind, at join and after a level up.
 *
 * <p>Everything is keyed by the plugin instance. A rebind resolves every class again through the new loaders, and
 * every registration is undone at unbind. The common library's classes resolve through the MMO plugin's own loader
 * first, which links them as a manifest dependency, and through the ZiggfreedCommon plugin's loader when that fails.
 */
public final class MmoBinding extends AbstractBinding {
    public static final String ID = "mmo";
    public static final String PLUGIN_ID = "Ziggfreed:MMOSkillTree";
    public static final String COMMON_PLUGIN_ID = "Ziggfreed:ZiggfreedCommon";

    static final String F_XP = "xp listener";
    static final String F_ABILITIES = "ability casts";
    static final String F_QUESTS = "quests and bounties";
    static final String F_ACHIEVEMENTS = "achievements";
    static final String F_SEED = "skill levels and class";

    static final String SLOT_CLASS = "class";
    static final String CAT_QUEST = "quest";
    static final String CAT_BOUNTY = "bounty";
    static final String CAT_ACHIEVEMENT = "achievement";

    /** Quests completed and not yet claimed remembered, so the claim does not publish the same quest twice. */
    private static final int COMPLETED_LIMIT = 2048;

    private final MmoNames names;
    /** When set, the XP feature asks for a method the mod does not have, to test a partial bind. */
    volatile boolean breakXp;

    private volatile Seed seed;
    private final Map<UUID, String> classes = new ConcurrentHashMap<>();
    private final Set<String> completedUnclaimed = ConcurrentHashMap.newKeySet();

    /** The handles of the seed reads, dropped at unbind. */
    private record Seed(MethodHandle levels, MethodHandle total, MethodHandle component, MethodHandle service,
                        MethodHandle currentClass, MethodHandle classId) {
    }

    /** The handles of the XP event, held by the listener only. */
    private record Xp(MethodHandle player, MethodHandle skill, MethodHandle amount, MethodHandle before, MethodHandle after) {
    }

    public MmoBinding() {
        this(MmoNames.LIVE);
    }

    public MmoBinding(MmoNames names) {
        super(ID, PLUGIN_ID, "MMO Skill Tree");
        this.names = names;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.MOD_XP_GAINED, EventKind.MOD_LEVEL_UP, EventKind.MOD_ABILITY_USED,
                EventKind.MOD_MILESTONE, EventKind.MOD_CHOICE_CHANGED);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.max(ModKeys.key(ID, ModKeys.LEVEL), "Total level"),
                new StatSpec(ModKeys.key(ID, ModKeys.SKILL_LEVEL), "Skill level", StatSpec.Aggregate.MAX, true, "Skill", false, null),
                StatSpec.sum(ModKeys.key(ID, ModKeys.SKILL_LEVEL_UPS), "Skill level ups"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.XP), "XP").unit("xp"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.XP), "XP by skill", "Skill").unit("xp"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.ABILITIES), "Abilities cast"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.ABILITIES), "Casts by ability", "Ability"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(SLOT_CLASS)), "Classes picked"),
                StatSpec.perId(ModKeys.key(ID, SLOT_CLASS), "Class", "Class"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_QUEST)), "Quests completed"),
                StatSpec.perId(ModKeys.key(ID, CAT_QUEST), "Quests by id", "Quest"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_BOUNTY)), "Bounties completed"),
                StatSpec.perId(ModKeys.key(ID, CAT_BOUNTY), "Bounties by id", "Bounty"),
                StatSpec.count(ModKeys.key(ID, ModKeys.plural(CAT_ACHIEVEMENT)), "Achievements unlocked"),
                StatSpec.perId(ModKeys.key(ID, CAT_ACHIEVEMENT), "Achievements by id", "Achievement"));
    }

    // ---- resolve -----------------------------------------------------------------------------------------

    @Override
    protected void resolve(Object plugin) throws Throwable {
        Class<?> apiType = Handles.type(loader, names.mod() + ".api.MMOSkillTreeAPI");

        feature(F_XP, () -> {
            Class<?> event = Handles.type(loader, names.mod() + ".api.events.PlayerGainXpEvent");
            MethodHandle add = Handles.statik(apiType, breakXp ? "addXpListeners" : "addXpListener", void.class, Consumer.class);
            MethodHandle remove = Handles.statik(apiType, "removeXpListener", boolean.class, Consumer.class);
            Xp xp = new Xp(Handles.virtual(event, "getPlayerRef", PlayerRef.class),
                    Handles.virtual(event, "getSkillId", String.class),
                    Handles.virtual(event, "getFinalAmount", long.class),
                    Handles.virtual(event, "getLevelBefore", int.class),
                    Handles.virtual(event, "getLevelAfter", int.class));
            Consumer<Object> listener = e -> guard(F_XP, () -> onXp(e, xp));
            add.invoke(listener);
            onUnbind(() -> {
                try {
                    remove.invoke(listener);
                } catch (Throwable ignored) {
                    // the mod is gone
                }
            });
        });

        feature(F_ABILITIES, () -> {
            Class<?> event = Handles.type(loader, names.mod() + ".ability.event.MmoAbilityResolvedEvent");
            MethodHandle ability = Handles.virtual(event, "abilityId", String.class);
            MethodHandle caster = Handles.virtual(event, "casterRef", Ref.class);
            MethodHandle success = Handles.virtual(event, "success", boolean.class);
            bus(event, e -> guard(F_ABILITIES, () -> onAbility(e, ability, caster, success)));
        });

        feature(F_QUESTS, () -> {
            Class<?> claimed = commonType(".quest.event.QuestClaimedEvent");
            Class<?> completed = commonType(".quest.event.QuestCompletedEvent");
            MethodHandle claimedQuest = Handles.virtual(claimed, "questId", String.class);
            MethodHandle claimedPlayer = Handles.virtual(claimed, "playerId", UUID.class);
            MethodHandle completedQuest = Handles.virtual(completed, "questId", String.class);
            MethodHandle completedPlayer = Handles.virtual(completed, "playerId", UUID.class);
            MethodHandle parked = Handles.virtual(completed, "parked", boolean.class);
            Class<?> bounties = Handles.type(loader, names.mod() + ".quest.MmoBounties");
            MethodHandle isBounty = Handles.statik(bounties, "isBounty", boolean.class, String.class);
            bus(completed, e -> guard(F_QUESTS, () -> {
                if (Handles.boolOf(parked.invoke(e), false)) {
                    return;
                }
                onQuest(uuidOf(completedPlayer.invoke(e)), Handles.stringOf(completedQuest.invoke(e)), isBounty, false);
            }));
            bus(claimed, e -> guard(F_QUESTS, () ->
                    onQuest(uuidOf(claimedPlayer.invoke(e)), Handles.stringOf(claimedQuest.invoke(e)), isBounty, true)));
        });

        feature(F_ACHIEVEMENTS, () -> {
            Class<?> unlocked = commonType(".achievement.event.AchievementUnlockedEvent");
            MethodHandle achievement = Handles.virtual(unlocked, "achievementId", String.class);
            MethodHandle player = Handles.virtual(unlocked, "playerId", UUID.class);
            MethodHandle[] owned = ownershipTest();
            bus(unlocked, e -> guard(F_ACHIEVEMENTS, () ->
                    onAchievement(uuidOf(player.invoke(e)), Handles.stringOf(achievement.invoke(e)), owned)));
        });

        feature(F_SEED, () -> {
            Class<?> skills = Handles.type(loader, names.mod() + ".data.SkillComponent");
            Class<?> classService = Handles.type(loader, names.mod() + ".classes.ClassService");
            Class<?> classDefinition = Handles.type(loader, names.mod() + ".classes.ClassDefinition");
            seed = new Seed(Handles.statik(apiType, "getAllLevelsByName", Map.class, Store.class, Ref.class),
                    Handles.statik(apiType, "getTotalLevel", int.class, Store.class, Ref.class),
                    Handles.statik(apiType, "getSkillComponent", skills, Store.class, Ref.class),
                    Handles.statik(classService, "getInstance", classService),
                    Handles.virtual(classService, "getCurrentClass", classDefinition, skills),
                    Handles.virtual(classDefinition, "getId", String.class));
            onUnbind(() -> seed = null);
            Subscription joins = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.PLAYER_JOINED), Delivery.ASYNC,
                    e -> seedPlayer(e.player()));
            onUnbind(joins::close);
        });
        if (has(F_SEED)) {
            names.worlds().readAll(this::seedRead);
        }
    }

    @Override
    protected void beforeFactsCleared() {
        classes.clear();
        completedUnclaimed.clear();
    }

    /** Registers for an event class on the engine bus, through the test seam when there is one. */
    private void bus(Class<?> eventClass, Consumer<Object> consumer) {
        if (names.bus() != null) {
            onUnbind(names.bus().listen(eventClass, consumer));
        } else {
            engineEvent(eventClass, consumer);
        }
    }

    /**
     * A class of the common library, through the mod's loader first, which resolves the classes of its manifest
     * dependencies, and through the ZiggfreedCommon plugin's loader when the mod's loader does not have it.
     */
    private Class<?> commonType(String suffix) throws ClassNotFoundException {
        String name = names.common() + suffix;
        try {
            return Handles.type(loader, name);
        } catch (ClassNotFoundException first) {
            ClassLoader common = commonLoader();
            if (common == null || common == loader) {
                throw first;
            }
            return Handles.type(common, name);
        }
    }

    /** The ZiggfreedCommon plugin's loader, or null without an engine. */
    private static ClassLoader commonLoader() {
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            PluginBase common = manager.getPlugin(PluginIdentifier.fromString(COMMON_PLUGIN_ID));
            return common == null ? null : common.getClass().getClassLoader();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The achievement definition lookup, {@code AchievementConfig.getInstance().getAchievement(id)}, so
     * achievements of the other Ziggfreed mods that share the common events are not counted here. Null when the
     * lookup does not resolve, every unlock counts then.
     */
    private MethodHandle[] ownershipTest() {
        try {
            Class<?> config = Handles.type(loader, names.mod() + ".config.AchievementConfig");
            Class<?> definition = Handles.type(loader, names.mod() + ".achievement.AchievementDefinition");
            return new MethodHandle[]{Handles.statik(config, "getInstance", config),
                    Handles.virtual(config, "getAchievement", definition, String.class)};
        } catch (ReflectiveOperationException | LinkageError e) {
            if (log != null) {
                log.at(Level.FINE).withCause(e).log("TrackerAPI counts every achievement under MMO Skill Tree, the definition lookup did not resolve");
            }
            return null;
        }
    }

    // ---- callbacks ---------------------------------------------------------------------------------------

    private void onXp(Object event, Xp xp) throws Throwable {
        UUID uuid = uuidOf(xp.player().invoke(event));
        String skill = Handles.stringOf(xp.skill().invoke(event));
        if (uuid == null || skill == null || skill.isEmpty()) {
            return;
        }
        long amount = Handles.longOf(xp.amount().invoke(event), 0L);
        int before = Handles.intOf(xp.before().invoke(event), 0);
        int after = Handles.intOf(xp.after().invoke(event), 0);
        if (amount > 0 && wants(EventKind.MOD_XP_GAINED)) {
            publish(new ModXpGained(header(uuid), ID, skill, amount, null));
        }
        if (after > before) {
            if (wants(EventKind.MOD_LEVEL_UP)) {
                publish(new ModLevelUp(header(uuid), ID, skill, before, after, 0));
            }
            seedPlayer(uuid);
        }
    }

    private void onAbility(Object event, MethodHandle ability, MethodHandle caster, MethodHandle success) throws Throwable {
        if (!wants(EventKind.MOD_ABILITY_USED) || !Handles.boolOf(success.invoke(event), false)) {
            return;
        }
        UUID uuid = names.worlds().playerOf(caster.invoke(event));
        String id = Handles.stringOf(ability.invoke(event));
        if (uuid == null || id == null || id.isEmpty()) {
            return;
        }
        publish(new ModAbilityUsed(header(uuid), ID, id, null, null));
    }

    private void onQuest(UUID uuid, String quest, MethodHandle isBounty, boolean claimed) throws Throwable {
        if (uuid == null || quest == null || quest.isEmpty()) {
            return;
        }
        String mark = uuid + "/" + quest;
        if (claimed) {
            if (completedUnclaimed.remove(mark)) {
                return; // published at completion
            }
        } else {
            if (completedUnclaimed.size() >= COMPLETED_LIMIT) {
                completedUnclaimed.clear();
            }
            completedUnclaimed.add(mark);
        }
        if (!wants(EventKind.MOD_MILESTONE)) {
            return;
        }
        String category = Handles.boolOf(isBounty.invoke(quest), false) ? CAT_BOUNTY : CAT_QUEST;
        publish(new ModMilestone(header(uuid), ID, category, quest, null));
    }

    private void onAchievement(UUID uuid, String achievement, MethodHandle[] owned) throws Throwable {
        if (uuid == null || achievement == null || achievement.isEmpty() || !wants(EventKind.MOD_MILESTONE)) {
            return;
        }
        if (owned != null) {
            Object config = owned[0].invoke();
            if (config == null || owned[1].invoke(config, achievement) == null) {
                return;
            }
        }
        publish(new ModMilestone(header(uuid), ID, CAT_ACHIEVEMENT, achievement, null));
    }

    // ---- seeds -------------------------------------------------------------------------------------------

    /** Posts a read of one player's levels and class to the world thread. Any thread. */
    private void seedPlayer(UUID uuid) {
        if (uuid == null || seed == null || !has(F_SEED)) {
            return;
        }
        names.worlds().read(uuid, this::seedRead);
    }

    /** The world thread read. */
    private void seedRead(UUID uuid, Object store, Object ref) {
        guard(F_SEED, () -> readOne(uuid, store, ref));
    }

    private void readOne(UUID uuid, Object store, Object ref) throws Throwable {
        Seed s = seed;
        if (s == null || uuid == null || !online(uuid)) {
            return;
        }
        if (s.levels().invoke(store, ref) instanceof Map<?, ?> levels) {
            for (Map.Entry<?, ?> e : levels.entrySet()) {
                String skill = Handles.stringOf(e.getKey());
                int level = Handles.intOf(e.getValue(), 0);
                if (skill != null && !skill.isEmpty() && level > 0) {
                    services.counters.raise(uuid, ModKeys.key(ID, ModKeys.SKILL_LEVEL, skill), level);
                }
            }
        }
        int total = Handles.intOf(s.total().invoke(store, ref), 0);
        if (total > 0) {
            services.counters.raise(uuid, ModKeys.key(ID, ModKeys.LEVEL), total);
        }
        String classId = null;
        Object skills = s.component().invoke(store, ref);
        if (skills != null) {
            Object service = s.service().invoke();
            Object definition = service == null ? null : s.currentClass().invoke(service, skills);
            classId = definition == null ? null : Handles.stringOf(s.classId().invoke(definition));
        }
        fact(uuid, SLOT_CLASS, classId);
        String before = classes.put(uuid, classId == null ? "" : classId);
        if (classId != null && !classId.equals(before) && wants(EventKind.MOD_CHOICE_CHANGED)) {
            publish(new ModChoiceChanged(header(uuid), ID, SLOT_CLASS, classId, null, 0));
        }
    }
}
