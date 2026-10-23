package dev.ryvrook.trackerapi.mods.tamework;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.ModKeys;
import dev.ryvrook.trackerapi.api.StatSpec;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.event.CompanionChanged;
import dev.ryvrook.trackerapi.api.event.CompanionChanged.Change;
import dev.ryvrook.trackerapi.api.event.ModAction;
import dev.ryvrook.trackerapi.api.event.ModXpGained;
import dev.ryvrook.trackerapi.facts.PlayerRegistry;
import dev.ryvrook.trackerapi.mods.AbstractBinding;
import dev.ryvrook.trackerapi.mods.Handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Reads Alec's Tamework, the companion framework Alec's Animal Husbandry puts its animals on. Tamework publishes
 * records on its own event bus ({@code api.events().subscribe(Class, Consumer)}) and a filtered activity feed
 * ({@code api.activities().subscribe(id, filter, consumer)}). Both deliver on the publishing thread, so every
 * callback copies the values and publishes.
 *
 * <p>The API object is null for a while after Tamework enables, since it is assembled by an async setup step, and
 * null again after Tamework shuts down. A bind that finds it null is not a failed bind. The classes and handles
 * resolve as features at once, the subscriptions wait, and a retry on the timer every {@link #RETRY_SECONDS}
 * seconds for up to {@link #RETRY_ATTEMPTS} tries completes them when the API appears.
 *
 * <p>Animal Husbandry has no classes. When it is enabled its version is a fact, {@code tamework.husbandry}.
 */
public final class TameworkBinding extends AbstractBinding {
    public static final String ID = "tamework";
    public static final String PLUGIN_ID = "Alechilles:Alec's Tamework!";
    public static final String HUSBANDRY_GROUP = "Alechilles";
    public static final String HUSBANDRY_NAME = "Alec's Animal Husbandry!";

    /** The track of companion XP in {@link ModXpGained}. */
    public static final String TRACK = "companion";
    static final String CONSUMER_ID = "trackerapi";
    static final String API_VERSION = "3.0.0";
    static final String ROLE_PREFIX = "Tamed_";

    static final String F_CORE = "core";
    static final String F_LIFECYCLE = "lifecycle";
    static final String F_XP = "companion xp";
    static final String F_ACTIVITY = "activity feed";
    static final String F_PROGRESSION = "progression reads";
    static final String F_POPULATION = "companion count";

    static final long RETRY_SECONDS = 5;
    static final int RETRY_ATTEMPTS = 24;
    static final long REFRESH_SECONDS = 60;

    static final String ACTION_BREED = "tamework:breed_success";
    static final String ACTION_HARVEST = "tamework:harvest";
    static final String ACTION_CULL = "tamework:cull_success";
    static final String HARVEST = "harvest";
    static final String CULL = "cull";

    private final Names names;
    private final Supplier<String> husbandry;

    // the core, cleared at unbind
    private Class<?> apiType;
    private Object instance;
    private MethodHandle getApi;
    private MethodHandle getApiVersion;
    private MethodHandle getCapabilities;
    private MethodHandle events;
    private MethodHandle subscribe;
    private volatile Object api;
    private final List<AutoCloseable> closers = new CopyOnWriteArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private volatile ScheduledFuture<?> retry;
    private volatile ScheduledFuture<?> refresh;
    private int attempts;
    private volatile String husbandryVersion;
    private volatile Progression progression;
    private volatile Population population;

    /** A subscription or check that waits for the API when it was null at bind. */
    private record Pending(String feature, Resolver step) {
    }

    @FunctionalInterface
    private interface Handler {
        void handle(Object event) throws Throwable;
    }

    private record ProfileView(MethodHandle owner, MethodHandle npc, MethodHandle role, MethodHandle tamed) {
    }

    private record ProfileChange(MethodHandle changeTypes, MethodHandle after, MethodHandle before, MethodHandle groupIds) {
    }

    private record Death(MethodHandle owner, MethodHandle npc, MethodHandle role, MethodHandle tamed) {
    }

    private record Lost(MethodHandle profile, MethodHandle npc) {
    }

    private record Xp(MethodHandle owner, MethodHandle npc, MethodHandle role, MethodHandle source, MethodHandle amount,
                      MethodHandle currentLevel, MethodHandle leveledUp) {
    }

    private record Activity(Class<?> managed, MethodHandle header, MethodHandle actionId, MethodHandle participants,
                            MethodHandle itemQuantities, MethodHandle offspringIds, MethodHandle actorId,
                            MethodHandle participantOwner, MethodHandle participantRole) {
    }

    private record Progression(MethodHandle progression, MethodHandle byNpc, MethodHandle leveling, MethodHandle level) {
    }

    private record Population(MethodHandle groups, MethodHandle loaded, MethodHandle durable, MethodHandle forRole,
                              MethodHandle groupId, Set<String> groupIds, Set<String> roles) {
    }

    public TameworkBinding() {
        this(Names.PRODUCTION, TameworkBinding::husbandryFromEngine);
    }

    /** A binding against the classes in {@code names}, with {@code husbandry} answering the Husbandry version or null. */
    public TameworkBinding(Names names, Supplier<String> husbandry) {
        super(ID, PLUGIN_ID, "Alec's Tamework");
        this.names = names;
        this.husbandry = husbandry;
    }

    @Override
    public Set<EventKind> kinds() {
        return Set.of(EventKind.COMPANION_CHANGED, EventKind.MOD_XP_GAINED, EventKind.MOD_ACTION);
    }

    @Override
    public List<StatSpec> stats() {
        return List.of(
                StatSpec.sum(ModKeys.key(ID, ModKeys.TAMED), "Companions tamed"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.TAMED), "Tamed by species", "Species"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.BRED), "Companions bred"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.BRED), "Bred by species", "Species"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.COMPANION_LEVEL_UPS), "Companion level ups"),
                StatSpec.max(ModKeys.key(ID, ModKeys.COMPANION_LEVEL_BEST), "Highest companion level"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.COMPANIONS_LOST), "Companions lost"),
                StatSpec.sum(ModKeys.key(ID, ModKeys.XP), "Companion XP").unit("xp"),
                StatSpec.perId(ModKeys.key(ID, ModKeys.XP), "Companion XP by track", "Track").unit("xp"),
                StatSpec.sum(ModKeys.key(ID, HARVEST), "Harvests"),
                StatSpec.sum(ModKeys.key(ID, CULL), "Culls"));
    }

    // ---- bind ----------------------------------------------------------------------------------------------

    @Override
    protected void resolve(Object plugin) throws Throwable {
        api = null;
        instance = null;
        attempts = 0;
        pending.clear();
        progression = null;
        population = null;
        boolean core = feature(F_CORE, () -> {
            Class<?> tamework = Handles.type(loader, names.plugin());
            apiType = Handles.type(loader, names.type("TameworkApi"));
            Class<?> eventsType = Handles.type(loader, names.type("TameworkEventsApi"));
            MethodHandle getInstance = Handles.statik(tamework, "getInstance", tamework);
            getApi = Handles.virtual(tamework, "getApi", apiType);
            getApiVersion = Handles.virtual(apiType, "getApiVersion", String.class);
            getCapabilities = Handles.virtual(apiType, "getCapabilities", EnumSet.class);
            events = Handles.virtual(apiType, "events", eventsType);
            subscribe = Handles.virtual(eventsType, "subscribe", AutoCloseable.class, Class.class, Consumer.class);
            instance = tamework.isInstance(plugin) ? plugin : getInstance.invoke();
            if (instance == null) {
                throw new IllegalStateException("Tamework has no instance yet");
            }
            onUnbind(this::letGo);
            Subscription joins = services.publisher.subscribe("mods/" + ID, Set.of(EventKind.PLAYER_JOINED), Delivery.ASYNC,
                    e -> onJoin(e.player()));
            onUnbind(joins::close);
            Object found = getApi.invoke(instance);
            if (found != null) {
                api = found;
                checkVersion(found);
            }
        });
        if (!core) {
            return;
        }
        feature(F_LIFECYCLE, this::resolveLifecycle);
        feature(F_XP, this::resolveXp);
        feature(F_ACTIVITY, this::resolveActivity);
        feature(F_PROGRESSION, this::resolveProgression);
        feature(F_POPULATION, this::resolvePopulation);
        try {
            husbandryVersion = husbandry.get();
        } catch (RuntimeException | LinkageError e) {
            husbandryVersion = null;
        }
        if (api != null) {
            afterAttach();
        } else {
            startRetry();
        }
    }

    /** Runs a subscription step now when the API is there, else keeps it for the retry. */
    private void attach(String feature, Resolver step) throws Throwable {
        if (api != null) {
            step.resolve();
        } else {
            pending.add(new Pending(feature, step));
        }
    }

    private void letGo() {
        cancel(retry);
        retry = null;
        cancel(refresh);
        refresh = null;
        for (AutoCloseable c : closers) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // the mod is gone
            }
        }
        closers.clear();
        pending.clear();
        api = null;
        instance = null;
        apiType = null;
        getApi = null;
        getApiVersion = null;
        getCapabilities = null;
        events = null;
        subscribe = null;
        progression = null;
        population = null;
        husbandryVersion = null;
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) {
            f.cancel(false);
        }
    }

    // ---- the null API window -------------------------------------------------------------------------------

    private void startRetry() {
        attempts = 0;
        log.at(Level.FINE).log("TrackerAPI found %s before its API was ready and looks again every %d s for %d s",
                name(), RETRY_SECONDS, RETRY_SECONDS * RETRY_ATTEMPTS);
        retry = services.timer.scheduleAtFixedRate(this::retryTick, RETRY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
    }

    /** True while the binding waits for the API to appear. */
    boolean retrying() {
        return retry != null;
    }

    /** One look for the API that was null at bind. Package private so a test can drive it in place of the timer. */
    void retryTick() {
        synchronized (this) {
            if (!isBound() || api != null || getApi == null) {
                cancel(retry);
                retry = null;
                return;
            }
            Object found;
            try {
                found = getApi.invoke(instance);
            } catch (Throwable t) {
                found = null;
            }
            if (found == null) {
                attempts++;
                if (attempts >= RETRY_ATTEMPTS) {
                    cancel(retry);
                    retry = null;
                    log.at(Level.FINE).log("TrackerAPI gave up waiting for the %s API after %d s, it binds again when %s reloads",
                            name(), RETRY_SECONDS * RETRY_ATTEMPTS, name());
                }
                return;
            }
            cancel(retry);
            retry = null;
            api = found;
            checkVersion(found);
            for (Pending p : pending) {
                try {
                    p.step().resolve();
                } catch (Throwable t) {
                    log.at(Level.FINE).withCause(t).log("TrackerAPI %s binding could not complete %s", name(), p.feature());
                    off(p.feature());
                }
            }
            pending.clear();
            afterAttach();
            log.at(Level.FINE).log("TrackerAPI completed the %s binding, its API appeared after %d s", name(), attempts * RETRY_SECONDS);
        }
    }

    /** Once the API is there: the facts for everyone online and the periodic count refresh. */
    private void afterAttach() {
        for (PlayerRegistry.Online o : services.players.all()) {
            seed(o.uuid);
        }
        if (has(F_POPULATION)) {
            refresh = services.timer.scheduleAtFixedRate(this::refreshAll, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void checkVersion(Object found) {
        try {
            String v = Handles.stringOf(getApiVersion.invoke(found));
            if (!API_VERSION.equals(v)) {
                log.at(Level.FINE).log("TrackerAPI reads the %s API %s, it was written against %s", name(), v, API_VERSION);
            }
        } catch (Throwable ignored) {
            // the version is informational
        }
    }

    private void requireCapability(String capability) throws Throwable {
        Object caps = getCapabilities.invoke(api);
        if (caps instanceof Iterable<?> it) {
            for (Object c : it) {
                if (capability.equals(Handles.nameOf(c))) {
                    return;
                }
            }
        }
        throw new IllegalStateException(name() + " has no " + capability + " capability");
    }

    /** Subscribes a consumer for one event class on the bus and keeps the closer for unbind. */
    private void listen(Class<?> event, String feature, Handler handler) throws Throwable {
        Object bus = events.invoke(api);
        if (bus == null) {
            throw new IllegalStateException(name() + " has no event bus");
        }
        Consumer<Object> consumer = e -> guard(feature, () -> handler.handle(e));
        Object closer = subscribe.invoke(bus, event, consumer);
        if (closer instanceof AutoCloseable c) {
            closers.add(c);
        } else {
            throw new IllegalStateException(name() + " gave no closer for " + event.getSimpleName());
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------

    private void resolveLifecycle() throws Throwable {
        Class<?> changed = Handles.type(loader, names.type("NpcProfileChangedEvent"));
        Class<?> view = Handles.type(loader, names.type("NpcProfileView"));
        Class<?> death = Handles.type(loader, names.type("NpcDeathRecordedEvent"));
        Class<?> lost = Handles.type(loader, names.type("NpcLostRecordedEvent"));
        ProfileChange c = new ProfileChange(
                Handles.virtual(changed, "changeTypes", EnumSet.class),
                Handles.virtual(changed, "after", view),
                Handles.virtual(changed, "before", view),
                Handles.virtual(changed, "groupIds", Set.class));
        ProfileView v = new ProfileView(
                Handles.virtual(view, "ownerUuid", UUID.class),
                Handles.virtual(view, "currentNpcUuid", UUID.class),
                Handles.virtual(view, "roleId", String.class),
                Handles.virtual(view, "tamed", boolean.class));
        Death d = new Death(
                Handles.virtual(death, "ownerUuid", UUID.class),
                Handles.virtual(death, "npcUuid", UUID.class),
                Handles.virtual(death, "roleId", String.class),
                Handles.virtual(death, "tamed", boolean.class));
        Lost l = new Lost(
                Handles.virtual(lost, "profile", view),
                Handles.virtual(lost, "npcUuid", UUID.class));
        attach(F_LIFECYCLE, () -> {
            requireCapability("EVENTS");
            listen(changed, F_LIFECYCLE, e -> onProfileChanged(e, c, v));
            listen(death, F_LIFECYCLE, e -> onDeath(e, d));
            listen(lost, F_LIFECYCLE, e -> onLost(e, l, v));
        });
    }

    private void onProfileChanged(Object e, ProfileChange c, ProfileView v) throws Throwable {
        Set<String> types = namesOf(c.changeTypes().invoke(e));
        Object after = c.after().invoke(e);
        Object before = c.before().invoke(e);
        Object view = after != null ? after : before;
        if (view == null) {
            return;
        }
        UUID owner = (UUID) v.owner().invoke(view);
        if (owner == null && before != null) {
            owner = (UUID) v.owner().invoke(before);
        }
        if (owner == null) {
            return;
        }
        String role = Handles.stringOf(v.role().invoke(view));
        learnGroups(c.groupIds().invoke(e), role);
        boolean tamed = types.contains("TAMED") && after != null && Handles.boolOf(v.tamed().invoke(after), false);
        boolean renamed = types.contains("CUSTOM_NAME") && !types.contains("CREATED");
        boolean released = types.contains("RELEASED");
        if (wants(EventKind.COMPANION_CHANGED)) {
            String companion = text(v.npc().invoke(view));
            String species = species(role);
            if (tamed) {
                publish(new CompanionChanged(header(owner), ID, companion, species, Change.TAMED, 0));
            }
            if (renamed) {
                publish(new CompanionChanged(header(owner), ID, companion, species, Change.RENAMED, 0));
            }
            if (released) {
                publish(new CompanionChanged(header(owner), ID, companion, species, Change.RELEASED, 0));
            }
        }
        if (tamed || released) {
            refreshCount(owner);
        }
    }

    private void onDeath(Object e, Death d) throws Throwable {
        UUID owner = (UUID) d.owner().invoke(e);
        if (owner == null || !Handles.boolOf(d.tamed().invoke(e), false)) {
            return;
        }
        String role = Handles.stringOf(d.role().invoke(e));
        learnGroups(null, role);
        if (wants(EventKind.COMPANION_CHANGED)) {
            publish(new CompanionChanged(header(owner), ID, text(d.npc().invoke(e)), species(role), Change.DIED, 0));
        }
        refreshCount(owner);
    }

    private void onLost(Object e, Lost l, ProfileView v) throws Throwable {
        Object profile = l.profile().invoke(e);
        if (profile == null) {
            return;
        }
        UUID owner = (UUID) v.owner().invoke(profile);
        if (owner == null) {
            return;
        }
        String role = Handles.stringOf(v.role().invoke(profile));
        learnGroups(null, role);
        if (wants(EventKind.COMPANION_CHANGED)) {
            publish(new CompanionChanged(header(owner), ID, text(l.npc().invoke(e)), species(role), Change.DIED, 0));
        }
        refreshCount(owner);
    }

    // ---- companion xp -------------------------------------------------------------------------------------

    private void resolveXp() throws Throwable {
        Class<?> event = Handles.type(loader, names.type("CompanionXpAwardedEvent"));
        Class<?> source = Handles.type(loader, names.type("CompanionXpSource"));
        Xp x = new Xp(
                Handles.virtual(event, "ownerUuid", UUID.class),
                Handles.virtual(event, "npcUuid", UUID.class),
                Handles.virtual(event, "roleId", String.class),
                Handles.virtual(event, "source", source),
                Handles.virtual(event, "awardedXp", double.class),
                Handles.virtual(event, "currentLevel", int.class),
                Handles.virtual(event, "leveledUp", boolean.class));
        attach(F_XP, () -> {
            requireCapability("EVENTS");
            requireCapability("COMPANION_XP_EVENTS");
            listen(event, F_XP, e -> onXp(e, x));
        });
    }

    private void onXp(Object e, Xp x) throws Throwable {
        UUID owner = (UUID) x.owner().invoke(e);
        if (owner == null) {
            return;
        }
        String role = Handles.stringOf(x.role().invoke(e));
        learnGroups(null, role);
        double amount = Handles.doubleOf(x.amount().invoke(e), 0);
        if (amount > 0 && wants(EventKind.MOD_XP_GAINED)) {
            publish(new ModXpGained(header(owner), ID, TRACK, amount, Handles.nameOf(x.source().invoke(e))));
        }
        if (Handles.boolOf(x.leveledUp().invoke(e), false) && wants(EventKind.COMPANION_CHANGED)) {
            Object npc = x.npc().invoke(e);
            int level = Handles.intOf(x.currentLevel().invoke(e), 0);
            if (level <= 0 && npc instanceof UUID u) {
                level = readLevel(u);
            }
            publish(new CompanionChanged(header(owner), ID, text(npc), species(role), Change.LEVEL_UP, level));
        }
    }

    // ---- activity feed ------------------------------------------------------------------------------------

    private void resolveActivity() throws Throwable {
        Class<?> feedType = Handles.type(loader, names.type("ActivityFeedApi"));
        Class<?> filterType = Handles.type(loader, names.type("ActivityFilter"));
        Class<?> domainType = Handles.type(loader, names.type("ActivityDomain"));
        Class<?> consumerType = Handles.type(loader, names.type("ActivityConsumer"));
        Class<?> viewType = Handles.type(loader, names.type("ActivityView"));
        Class<?> headerType = Handles.type(loader, names.type("ActivityHeader"));
        Class<?> managedType = Handles.type(loader, names.type("ManagedActivityView"));
        Class<?> participantType = Handles.type(loader, names.type("ActivityParticipantView"));
        MethodHandle activities = Handles.virtual(apiType, "activities", feedType);
        MethodHandle feedSubscribe = Handles.virtualByName(feedType, "subscribe", 3);
        MethodHandle newFilter = MethodHandles.publicLookup()
                .findConstructor(filterType, MethodType.methodType(void.class, Set.class, Set.class));
        newFilter = newFilter.asType(newFilter.type().generic());
        Object managedDomain = Handles.staticField(domainType, "MANAGED_CARE_PRODUCTION");
        Activity a = new Activity(managedType,
                Handles.virtual(viewType, "header", headerType),
                Handles.virtual(headerType, "actionId", String.class),
                Handles.virtual(managedType, "participants", List.class),
                Handles.virtual(managedType, "itemQuantities", Map.class),
                Handles.virtual(managedType, "offspringIds", List.class),
                Handles.virtual(managedType, "actorId", UUID.class),
                Handles.virtual(participantType, "ownerId", UUID.class),
                Handles.virtual(participantType, "roleId", String.class));
        MethodHandle filterCtor = newFilter;
        attach(F_ACTIVITY, () -> {
            requireCapability("ACTIVITY_FEED_V2");
            Object feed = activities.invoke(api);
            if (feed == null) {
                throw new IllegalStateException(name() + " has no activity feed");
            }
            Object filter = filterCtor.invoke(Set.of(managedDomain), Set.of(ACTION_BREED, ACTION_HARVEST, ACTION_CULL));
            Object proxy = Handles.proxy(consumerType, (p, m, args) -> {
                if (m.getName().equals("accept") && args != null && args.length == 1) {
                    guard(F_ACTIVITY, () -> onActivity(args[0], a));
                    return null;
                }
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    case "toString" -> "TrackerAPI activity consumer";
                    default -> null;
                };
            });
            Object sub = feedSubscribe.invoke(feed, CONSUMER_ID, filter, proxy);
            if (sub instanceof AutoCloseable c) {
                closers.add(c);
            } else {
                throw new IllegalStateException(name() + " gave no feed subscription");
            }
        });
    }

    private void onActivity(Object view, Activity a) throws Throwable {
        if (view == null || !a.managed().isInstance(view)) {
            return;
        }
        Object header = a.header().invoke(view);
        String action = header == null ? null : Handles.stringOf(a.actionId().invoke(header));
        if (action == null) {
            return;
        }
        Object participants = a.participants().invoke(view);
        Object first = participants instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
        UUID owner = (UUID) a.actorId().invoke(view);
        if (owner == null && first != null) {
            owner = (UUID) a.participantOwner().invoke(first);
        }
        if (owner == null) {
            return;
        }
        String role = first == null ? null : Handles.stringOf(a.participantRole().invoke(first));
        learnGroups(null, role);
        String species = species(role);
        switch (action) {
            case ACTION_BREED -> {
                if (wants(EventKind.COMPANION_CHANGED)) {
                    Object offspring = a.offspringIds().invoke(view);
                    if (offspring instanceof List<?> list && !list.isEmpty()) {
                        for (Object o : list) {
                            publish(new CompanionChanged(header(owner), ID, text(o), species, Change.BRED, 0));
                        }
                    } else {
                        publish(new CompanionChanged(header(owner), ID, null, species, Change.BRED, 0));
                    }
                }
                refreshCount(owner);
            }
            case ACTION_HARVEST -> {
                if (wants(EventKind.MOD_ACTION)) {
                    publish(new ModAction(header(owner), ID, HARVEST, false, species, quantity(a.itemQuantities().invoke(view))));
                }
            }
            case ACTION_CULL -> {
                if (wants(EventKind.MOD_ACTION)) {
                    publish(new ModAction(header(owner), ID, CULL, false, species, quantity(a.itemQuantities().invoke(view))));
                }
                refreshCount(owner);
            }
            default -> {
                // not asked for
            }
        }
    }

    // ---- progression reads --------------------------------------------------------------------------------

    private void resolveProgression() throws Throwable {
        Class<?> progType = Handles.type(loader, names.type("ProgressionApi"));
        Class<?> viewType = Handles.type(loader, names.type("ProgressionView"));
        Class<?> levelingType = Handles.type(loader, names.type("ProgressionView$LevelingView"));
        Progression p = new Progression(
                Handles.virtual(apiType, "progression", progType),
                Handles.virtual(progType, "getByNpcUuid", Optional.class, UUID.class),
                Handles.virtual(viewType, "leveling", levelingType),
                Handles.virtual(levelingType, "level", int.class));
        attach(F_PROGRESSION, () -> {
            requireCapability("PROGRESSION");
            progression = p;
        });
    }

    /** The level of a companion from the progression index, 0 when unknown. */
    private int readLevel(UUID npc) {
        Progression p = progression;
        Object a = api;
        if (p == null || a == null || npc == null || !has(F_PROGRESSION)) {
            return 0;
        }
        try {
            Object prog = p.progression().invoke(a);
            Object opt = prog == null ? null : p.byNpc().invoke(prog, npc);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) {
                return 0;
            }
            Object leveling = p.leveling().invoke(o.get());
            return leveling == null ? 0 : Handles.intOf(p.level().invoke(leveling), 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- companion count ----------------------------------------------------------------------------------

    private void resolvePopulation() throws Throwable {
        Class<?> popType = Handles.type(loader, names.type("PopulationGroupApi"));
        Class<?> defType = Handles.type(loader, names.type("PopulationGroupDefinitionView"));
        Population p = new Population(
                Handles.virtual(apiType, "populationGroups", popType),
                Handles.virtual(popType, "getLoadedOwnedCount", OptionalLong.class, UUID.class, Set.class),
                Handles.virtual(popType, "getDurableOwnedCount", OptionalLong.class, UUID.class, Set.class),
                Handles.virtual(popType, "resolveForRole", List.class, String.class),
                Handles.virtual(defType, "groupId", String.class),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
        attach(F_POPULATION, () -> {
            requireCapability("POPULATION_GROUPS");
            population = p;
        });
    }

    /**
     * Remembers the population groups the counts are asked for. The API counts companions by group, and there is
     * no list of groups, so they are learnt from the group ids on the profile events and from the groups of every
     * role seen.
     */
    private void learnGroups(Object groupIds, String role) {
        Population p = population;
        Object a = api;
        if (p == null) {
            return;
        }
        if (groupIds instanceof Iterable<?> it) {
            for (Object g : it) {
                if (g instanceof String s && !s.isEmpty()) {
                    p.groupIds().add(s);
                }
            }
        }
        if (role != null && !role.isEmpty() && a != null && p.roles().add(role)) {
            try {
                Object pop = p.groups().invoke(a);
                Object defs = pop == null ? null : p.forRole().invoke(pop, role);
                if (defs instanceof Iterable<?> it) {
                    for (Object d : it) {
                        String g = Handles.stringOf(p.groupId().invoke(d));
                        if (g != null && !g.isEmpty()) {
                            p.groupIds().add(g);
                        }
                    }
                }
            } catch (Throwable t) {
                log.at(Level.FINE).withCause(t).log("TrackerAPI could not read the %s population groups of %s", name(), role);
            }
        }
    }

    /** Reads the owned companion count of an online player into the {@code tamework.companions} fact. */
    private void refreshCount(UUID owner) {
        Population p = population;
        Object a = api;
        if (p == null || a == null || owner == null || p.groupIds().isEmpty() || !online(owner)) {
            return;
        }
        guard(F_POPULATION, () -> {
            Object pop = p.groups().invoke(a);
            if (pop == null) {
                return;
            }
            Set<String> groups = Set.copyOf(p.groupIds());
            Object n = p.loaded().invoke(pop, owner, groups);
            if (!(n instanceof OptionalLong o) || o.isEmpty()) {
                n = p.durable().invoke(pop, owner, groups);
            }
            if (n instanceof OptionalLong o && o.isPresent()) {
                fact(owner, "companions", Long.toString(o.getAsLong()));
            }
        });
    }

    private void refreshAll() {
        if (!isBound()) {
            return;
        }
        for (PlayerRegistry.Online o : services.players.all()) {
            refreshCount(o.uuid);
        }
    }

    // ---- players ------------------------------------------------------------------------------------------

    private void onJoin(UUID player) {
        if (player == null || !isBound()) {
            return;
        }
        seed(player);
    }

    private void seed(UUID player) {
        String h = husbandryVersion;
        if (h != null) {
            fact(player, "husbandry", h);
        }
        refreshCount(player);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** The species of a role id, {@code Cow} for {@code Tamed_Cow}, the id itself without the prefix, null for null. */
    static String species(String roleId) {
        if (roleId == null || roleId.isEmpty()) {
            return null;
        }
        if (roleId.startsWith(ROLE_PREFIX) && roleId.length() > ROLE_PREFIX.length()) {
            return roleId.substring(ROLE_PREFIX.length());
        }
        return roleId;
    }

    private static Set<String> namesOf(Object constants) {
        Set<String> out = new HashSet<>();
        if (constants instanceof Iterable<?> it) {
            for (Object c : it) {
                String n = Handles.nameOf(c);
                if (n != null) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }

    private static double quantity(Object map) {
        if (!(map instanceof Map<?, ?> m)) {
            return 0;
        }
        double total = 0;
        for (Object v : m.values()) {
            total += Handles.doubleOf(v, 0);
        }
        return total;
    }

    /** The version of Alec's Animal Husbandry when it is enabled on this server, else null. */
    static String husbandryFromEngine() {
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            PluginBase p = manager.getPlugin(new PluginIdentifier(HUSBANDRY_GROUP, HUSBANDRY_NAME));
            if (p == null || p.getState() != PluginState.ENABLED) {
                return null;
            }
            return String.valueOf(p.getManifest().getVersion());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
