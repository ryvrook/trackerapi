package fakes.tamework;

import fakes.tamework.api.ActivityConsumer;
import fakes.tamework.api.ActivityDomain;
import fakes.tamework.api.ActivityFeedApi;
import fakes.tamework.api.ActivityFeedStatus;
import fakes.tamework.api.ActivityFeedSubscription;
import fakes.tamework.api.ActivityFilter;
import fakes.tamework.api.ActivityHeader;
import fakes.tamework.api.ActivityParticipantView;
import fakes.tamework.api.ActivityView;
import fakes.tamework.api.CompanionXpAwardedEvent;
import fakes.tamework.api.CompanionXpSource;
import fakes.tamework.api.ManagedActivityView;
import fakes.tamework.api.NpcDeathRecordedEvent;
import fakes.tamework.api.NpcLostRecordedEvent;
import fakes.tamework.api.NpcProfileChangedEvent;
import fakes.tamework.api.NpcProfileView;
import fakes.tamework.api.PopulationGroupApi;
import fakes.tamework.api.PopulationGroupCountsView;
import fakes.tamework.api.PopulationGroupDefinitionView;
import fakes.tamework.api.PopulationGroupScope;
import fakes.tamework.api.ProfileChangeType;
import fakes.tamework.api.ProgressionApi;
import fakes.tamework.api.ProgressionView;
import fakes.tamework.api.TameworkApi;
import fakes.tamework.api.TameworkApiCapability;
import fakes.tamework.api.TameworkEvent;
import fakes.tamework.api.TameworkEventsApi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The API of the fake mod with a bus, a feed, a progression index and population counts, plus fire methods that
 * take only strings, numbers and UUIDs so a test can call them by name through the isolating loader. The feed
 * applies the subscriber's filter as the real one does, so an action the binding did not ask for never reaches it.
 */
public final class FakeApi implements TameworkApi {
    private record Sub(Class<?> type, Consumer<Object> consumer) {
    }

    private record FeedSub(String consumerId, ActivityFilter filter, ActivityConsumer consumer) {
    }

    private final EnumSet<TameworkApiCapability> caps = EnumSet.allOf(TameworkApiCapability.class);
    private final List<Sub> subs = new CopyOnWriteArrayList<>();
    private final List<FeedSub> feedSubs = new CopyOnWriteArrayList<>();
    private final Map<UUID, Integer> levels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> owned = new ConcurrentHashMap<>();
    private final Set<String> groupsAsked = new HashSet<>();
    private final List<Set<String>> countGroups = new ArrayList<>();
    private boolean feedBroken;
    private long seq;

    // ---- the API -----------------------------------------------------------------------------------------

    @Override
    public String getApiVersion() {
        return "3.0.0";
    }

    @Override
    public EnumSet<TameworkApiCapability> getCapabilities() {
        return EnumSet.copyOf(caps);
    }

    @Override
    public TameworkEventsApi events() {
        return new TameworkEventsApi() {
            @Override
            @SuppressWarnings("unchecked")
            public <E extends TameworkEvent> AutoCloseable subscribe(Class<E> type, Consumer<E> consumer) {
                Sub s = new Sub(type, (Consumer<Object>) (Consumer<?>) consumer);
                subs.add(s);
                return () -> subs.remove(s);
            }
        };
    }

    @Override
    public ActivityFeedApi activities() {
        if (feedBroken) {
            return ActivityFeedApi.unavailable();
        }
        return new ActivityFeedApi() {
            @Override
            public ActivityFeedSubscription subscribe(String consumerId, ActivityFilter filter, ActivityConsumer consumer) {
                FeedSub s = new FeedSub(consumerId, filter, consumer);
                feedSubs.add(s);
                return new ActivityFeedSubscription() {
                    @Override
                    public String consumerId() {
                        return consumerId;
                    }

                    @Override
                    public void close() {
                        feedSubs.remove(s);
                    }
                };
            }

            @Override
            public ActivityFeedStatus status(String consumerId) {
                return new ActivityFeedStatus(true, !feedSubs.isEmpty(), seq, "ok");
            }
        };
    }

    @Override
    public ProgressionApi progression() {
        return new ProgressionApi() {
            @Override
            public Optional<ProgressionView> getByProfileId(String profileId) {
                return Optional.empty();
            }

            @Override
            public Optional<ProgressionView> getByNpcUuid(UUID npcUuid) {
                Integer level = levels.get(npcUuid);
                if (level == null) {
                    return Optional.empty();
                }
                return Optional.of(new ProgressionView("p-" + npcUuid, npcUuid, "default", "Tamed_Cow", null, null, null,
                        new ProgressionView.LevelingView("AHLevelLivestock", level, 0, 0, 100, 25, false), null, null, null, null));
            }
        };
    }

    @Override
    public PopulationGroupApi populationGroups() {
        return new PopulationGroupApi() {
            @Override
            public Optional<PopulationGroupDefinitionView> getDefinition(String groupId) {
                return Optional.empty();
            }

            @Override
            public List<PopulationGroupDefinitionView> resolveForRole(String roleId) {
                groupsAsked.add(roleId);
                if (roleId.startsWith("Tamed_Wolf")) {
                    return List.of(new PopulationGroupDefinitionView("AHGroupBeasts", 1, "beasts", Set.of(roleId), 10, 5, PopulationGroupScope.GLOBAL));
                }
                return List.of();
            }

            @Override
            public Optional<PopulationGroupCountsView> getCounts(UUID ownerUuid, String groupId, String worldName) {
                return Optional.empty();
            }

            @Override
            public OptionalLong getDurableOwnedCount(UUID ownerUuid, Set<String> groupIds) {
                countGroups.add(Set.copyOf(groupIds));
                if (groupIds.isEmpty()) {
                    return OptionalLong.of(0);
                }
                Long n = owned.get(ownerUuid);
                return n == null ? OptionalLong.empty() : OptionalLong.of(n);
            }
        };
    }

    // ---- knobs and reads for the test ----------------------------------------------------------------------

    public int busCount() {
        return subs.size();
    }

    public int feedCount() {
        return feedSubs.size();
    }

    /** The action ids of the one feed filter, joined and sorted, for an assertion. */
    public String feedActions() {
        return feedSubs.isEmpty() ? "" : String.join(",", new java.util.TreeSet<>(feedSubs.get(0).filter().actionIds()));
    }

    public String feedDomains() {
        if (feedSubs.isEmpty()) {
            return "";
        }
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (ActivityDomain d : feedSubs.get(0).filter().domains()) {
            names.add(d.name());
        }
        return String.join(",", names);
    }

    public void withoutCapability(String name) {
        caps.remove(TameworkApiCapability.valueOf(name));
    }

    public void breakFeed() {
        feedBroken = true;
    }

    public void setLevel(UUID npc, int level) {
        levels.put(npc, level);
    }

    public void setOwned(UUID owner, long count) {
        owned.put(owner, count);
    }

    /** The group ids of the last owned count read, joined and sorted, empty when none was asked. */
    public String lastCountGroups() {
        return countGroups.isEmpty() ? "" : String.join(",", new java.util.TreeSet<>(countGroups.get(countGroups.size() - 1)));
    }

    public int countReads() {
        return countGroups.size();
    }

    // ---- firing --------------------------------------------------------------------------------------------

    private void dispatch(Object event) {
        for (Sub s : subs) {
            if (s.type().isInstance(event)) {
                s.consumer().accept(event);
            }
        }
    }

    /** Hands an object of the wrong class to every bus subscriber, so a handler throws. */
    public void fireWrongType() {
        for (Sub s : subs) {
            s.consumer().accept(new Object());
        }
    }

    private static NpcProfileView view(UUID owner, UUID npc, String roleId, String customName, boolean tamed) {
        return new NpcProfileView("p-" + npc, npc, owner, "Alder", roleId, roleId, customName, tamed, null, null, Set.of(), Set.of(), 1L);
    }

    public void fireTamed(UUID owner, UUID npc, String roleId, String groupId) {
        dispatch(new NpcProfileChangedEvent("p-" + npc, EnumSet.of(ProfileChangeType.CREATED, ProfileChangeType.TAMED, ProfileChangeType.OWNER),
                null, view(owner, npc, roleId, null, true), 1L, null, "live", groupId == null ? Set.of() : Set.of(groupId), Set.of()));
    }

    public void fireRenamed(UUID owner, UUID npc, String roleId, String name) {
        dispatch(new NpcProfileChangedEvent("p-" + npc, EnumSet.of(ProfileChangeType.CUSTOM_NAME),
                view(owner, npc, roleId, null, true), view(owner, npc, roleId, name, true), 2L, "live", "live", Set.of(), Set.of()));
    }

    public void fireReleased(UUID owner, UUID npc, String roleId) {
        dispatch(new NpcProfileChangedEvent("p-" + npc, EnumSet.of(ProfileChangeType.RELEASED, ProfileChangeType.OWNER),
                view(owner, npc, roleId, null, true), view(null, npc, roleId, null, false), 3L, "live", "released", Set.of(), Set.of()));
    }

    public void fireDeath(UUID owner, UUID npc, String roleId, boolean tamed) {
        dispatch(new NpcDeathRecordedEvent(view(owner, npc, roleId, null, tamed), npc, owner, "Alder", Set.of(), roleId, roleId, null, tamed,
                null, null, 4L, 5L, 4L));
    }

    public void fireLost(UUID owner, UUID npc, String roleId) {
        dispatch(new NpcLostRecordedEvent(view(owner, npc, roleId, null, true), npc, null, null, 0L, 6L, 3, 6L));
    }

    public void fireXp(UUID owner, UUID npc, String roleId, String source, double xp, int previousLevel, int currentLevel, boolean leveledUp) {
        dispatch(new CompanionXpAwardedEvent(npc, owner, Set.of(), roleId, "AHLevelLivestock", CompanionXpSource.valueOf(source), xp,
                previousLevel, currentLevel, 0, xp, 0, xp, 100, 25, false, leveledUp, 7L, 7L));
    }

    private void publishActivity(ActivityView view) {
        for (FeedSub s : feedSubs) {
            if (s.filter().matches(view.domain(), view.header().actionId())) {
                s.consumer().accept(view);
            }
        }
    }

    private ActivityHeader header(String actionId) {
        return new ActivityHeader(UUID.randomUUID(), ++seq, actionId, Instant.ofEpochMilli(1000L * seq));
    }

    private ManagedActivityView managed(String actionId, UUID actor, UUID companion, UUID owner, String roleId, Map<String, Integer> items,
                                        List<UUID> offspring) {
        return new ManagedActivityView(header(actionId), "p-" + companion, Set.of("livestock"),
                List.of(new ActivityParticipantView(companion, owner, "p-" + companion, roleId)), actionId, items, offspring, null, null, null, actor);
    }

    /** A breeding with {@code offspring} babies, the actor the breeder, the parent the one participant. */
    public void fireBreed(UUID actor, UUID parent, String roleId, int offspring) {
        List<UUID> babies = new ArrayList<>();
        for (int i = 0; i < offspring; i++) {
            babies.add(new UUID(0xB0L, i + 1));
        }
        publishActivity(managed("tamework:breed_success", actor, parent, actor, roleId, Map.of(), babies));
    }

    /** A breeding with no actor, the owner comes from the participant. */
    public void fireBreedWithoutActor(UUID owner, UUID parent, String roleId) {
        publishActivity(managed("tamework:breed_success", null, parent, owner, roleId, Map.of(), List.of(new UUID(0xB0L, 9))));
    }

    public void fireHarvest(UUID actor, UUID companion, String roleId, String itemId, int quantity) {
        publishActivity(managed("tamework:harvest", actor, companion, actor, roleId, Map.of(itemId, quantity), List.of()));
    }

    public void fireCull(UUID actor, UUID companion, String roleId, String itemId, int quantity) {
        publishActivity(managed("tamework:cull_success", actor, companion, actor, roleId, Map.of(itemId, quantity), List.of()));
    }

    /** A feed action, which the binding did not subscribe to. */
    public void fireFeedAction(UUID actor, UUID companion, String roleId) {
        publishActivity(managed("tamework:feed", actor, companion, actor, roleId, Map.of(), List.of()));
    }
}
