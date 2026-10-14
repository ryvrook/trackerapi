package dev.ryvrook.trackerapi.hooks;

import com.hypixel.hytale.builtin.instances.config.InstanceDiscoveryConfig;
import com.hypixel.hytale.builtin.instances.event.DiscoverInstanceEvent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.portals.components.PortalDevice;
import com.hypixel.hytale.builtin.portals.events.EnterPortalEvent;
import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.protocol.BlockMountType;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.ChatMessage;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.ChangeGameModeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DiscoverZoneEvent;
import com.hypixel.hytale.server.core.event.events.ecs.RespawnEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerGroupEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.Actor;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.GameModeChanged;
import dev.ryvrook.trackerapi.api.event.InstanceDiscovered;
import dev.ryvrook.trackerapi.api.event.MountChanged;
import dev.ryvrook.trackerapi.api.event.PermissionChanged;
import dev.ryvrook.trackerapi.api.event.PlayerChatted;
import dev.ryvrook.trackerapi.api.event.PlayerCommand;
import dev.ryvrook.trackerapi.api.event.PlayerConnected;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import dev.ryvrook.trackerapi.api.event.PlayerLeft;
import dev.ryvrook.trackerapi.api.event.PlayerRespawned;
import dev.ryvrook.trackerapi.api.event.PlayerTeleported;
import dev.ryvrook.trackerapi.api.event.PlayerWorldChanged;
import dev.ryvrook.trackerapi.api.event.PortalEntered;
import dev.ryvrook.trackerapi.api.event.SpectatingChanged;
import dev.ryvrook.trackerapi.api.event.ZoneDiscovered;
import dev.ryvrook.trackerapi.facts.PlayerRegistry;
import dev.ryvrook.trackerapi.facts.Sampler;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;
import org.joml.Vector3d;
import org.joml.Vector3ic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The {@code players} hook set. It keeps {@link PlayerRegistry} current, calls {@code counters.join} and
 * {@code counters.leave}, runs the once a second {@link Sampler} and publishes every player kind of the
 * contract. It installs first because the other sets read the registry.
 *
 * <p>Lifecycle order. A connection publishes PLAYER_CONNECTED. The first {@link PlayerReadyEvent} of a
 * session joins the registry and the counters, publishes PLAYER_JOINED and then the first world entry as
 * PLAYER_WORLD_CHANGED with no {@code fromWorld}. Later world entries come from
 * {@link AddPlayerToWorldEvent}. A disconnect publishes PLAYER_LEFT before the counters are saved, then
 * drops the session state, and the anti farm memory of every fact is forgotten an hour later unless the
 * player came back.
 *
 * <p>Commands have no engine event. The chat packet carries them with a leading slash, so one inbound
 * packet watcher, which never filters anything, reads the first word.
 */
public final class PlayerHooks implements HookSet {
    private static final int MAX_COMMAND_LENGTH = 64;

    private Services s;
    private Sampler sampler;
    private MovementEdgeSystem edgeSystem;
    /** The world each player is in, so a world change knows where they came from. Kept from the first entry. */
    private final Map<UUID, String> lastWorld = new ConcurrentHashMap<>();
    private volatile PacketFilter commandWatcher;

    @Override
    public String name() {
        return "players";
    }

    @Override
    public void install(PluginBase plugin, Services services, Registrar registrar) {
        this.s = services;
        this.sampler = new Sampler(services, services.logger);
        services.forgetting.onWorld(this::forgetWorld);

        registrar.listener("player connect", events -> events.registerAsyncGlobal(PlayerConnectEvent.class,
                future -> future.thenApply(this::onConnect)));
        registrar.listener("player ready", events -> events.registerGlobal(PlayerReadyEvent.class, this::onReady));
        registrar.listener("player disconnect", events -> events.registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect));
        registrar.listener("player world change", events -> events.registerGlobal(AddPlayerToWorldEvent.class, this::onAddToWorld));
        // Late, so moderation plugins have cancelled before the cancelled flag is read.
        registrar.listener("player chat", events -> events.registerAsyncGlobal(EventPriority.LATE, PlayerChatEvent.class,
                future -> future.thenApply(this::onChat)));

        registrar.listener("permissions added", events -> events.registerGlobal(
                PlayerPermissionChangeEvent.PermissionsAdded.class, e -> permissions(e.getPlayerUuid(),
                        PermissionChanged.Change.PERMISSIONS_ADDED, e.getAddedPermissions())));
        registrar.listener("permissions removed", events -> events.registerGlobal(
                PlayerPermissionChangeEvent.PermissionsRemoved.class, e -> permissions(e.getPlayerUuid(),
                        PermissionChanged.Change.PERMISSIONS_REMOVED, e.getRemovedPermissions())));
        registrar.listener("permission group added", events -> events.registerGlobal(
                PlayerPermissionChangeEvent.GroupAdded.class, e -> permissions(e.getPlayerUuid(),
                        PermissionChanged.Change.GROUP_ADDED, List.of(nonNull(e.getGroupName())))));
        registrar.listener("permission group removed", events -> events.registerGlobal(
                PlayerPermissionChangeEvent.GroupRemoved.class, e -> permissions(e.getPlayerUuid(),
                        PermissionChanged.Change.GROUP_REMOVED, List.of(nonNull(e.getGroupName())))));
        // The permissions module of 0.7.1 dispatches group changes as PlayerGroupEvent, never the two above.
        registrar.listener("player group added", events -> events.registerGlobal(
                PlayerGroupEvent.Added.class, e -> permissions(e.getPlayerUuid(),
                        PermissionChanged.Change.GROUP_ADDED, List.of(nonNull(e.getGroupName())))));
        registrar.listener("player group removed", events -> events.registerGlobal(
                PlayerGroupEvent.Removed.class, e -> permissions(e.getPlayerUuid(),
                        PermissionChanged.Change.GROUP_REMOVED, List.of(nonNull(e.getGroupName())))));

        registrar.entitySystem("respawns", () -> new RespawnSystem(services, sampler));
        registrar.entitySystem("teleports", () -> new TeleportSystem(services, this));
        registrar.entitySystem("game mode changes", () -> new GameModeSystem(services));
        registrar.entitySystem("zone discovery", () -> new ZoneSystem(services));
        registrar.entitySystem("movement edges", () -> {
            edgeSystem = new MovementEdgeSystem(services);
            return edgeSystem;
        });
        registrar.entitySystem("mounts", () -> new MountSystem(services));
        registrar.entitySystem("portal entry", () -> new PortalSystem(services));
        registrar.entitySystem("instance discovery", () -> new InstanceSystem(services));
        registrar.entitySystem("spectating", () -> new SpectatingSystem(services));
        registrar.guard("player commands", this::watchCommands);
    }

    @Override
    public void start() {
        sampler.start();
        joinOnlinePlayers();
    }

    @Override
    public void stop() {
        if (sampler != null) {
            sampler.stop();
        }
        PacketFilter watcher = commandWatcher;
        commandWatcher = null;
        if (watcher != null) {
            try {
                PacketAdapters.deregisterInbound(watcher); // a static list, the plugin unload does not clear it
            } catch (RuntimeException | LinkageError e) {
                s.logger.at(Level.FINE).withCause(e).log("TrackerAPI could not remove its command watcher");
            }
        }
        if (edgeSystem != null) {
            edgeSystem.clear();
        }
        lastWorld.clear();
    }

    /** The sampler, for the other sets and the command. */
    public Sampler sampler() {
        return sampler;
    }

    // ---- connection and session ---------------------------------------------------------------------------

    /** Async, off any world thread. Only values are read. */
    private PlayerConnectEvent onConnect(PlayerConnectEvent event) {
        if (!s.publisher.wants(EventKind.PLAYER_CONNECTED)) {
            return event;
        }
        long start = System.nanoTime();
        try {
            PlayerRef pr = event.getPlayerRef();
            if (pr != null) {
                World world = event.getWorld();
                s.publisher.publish(new PlayerConnected(s.header(world == null ? null : world.getName(), pr.getUuid()),
                        pr.getUsername()));
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PLAYER_CONNECTED, System.nanoTime() - start);
        }
        return event;
    }

    /**
     * World thread. Fires on every world entry, so only the first of a session joins the player. Every one
     * reads the game mode and the world back and starts a fresh movement step.
     */
    private void onReady(PlayerReadyEvent event) {
        long start = System.nanoTime();
        try {
            Ref<EntityStore> ref = event.getPlayerRef();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
            if (pr == null) {
                return;
            }
            UUID uuid = pr.getUuid();
            String name = pr.getUsername();
            sampler.markOnline(uuid);
            PlayerRegistry.Online o = s.players.get(uuid);
            boolean first = o == null || !o.readyOnce;
            boolean firstEver = false;
            if (first) {
                long now = System.currentTimeMillis();
                firstEver = s.counters.names().nameOf(uuid).isEmpty();
                o = s.players.join(uuid, name, now);
                o.sessionStart = now;
                o.readyOnce = true;
                s.counters.join(uuid, name, now);
            }
            readScope(o, ref, store);
            sampler.resetPosition(uuid);
            if (!first) {
                return;
            }
            String world = o.world;
            if (world != null) {
                lastWorld.put(uuid, world);
            }
            if (s.publisher.wants(EventKind.PLAYER_JOINED)) {
                s.publisher.publish(new PlayerJoined(s.header(world, uuid), name, firstEver));
            }
            if (world != null && s.publisher.wants(EventKind.PLAYER_WORLD_CHANGED)) {
                // The first world of a session. Its portal is not an entry through a portal, as Feats counts it.
                s.publisher.publish(new PlayerWorldChanged(s.header(world, uuid), null, null, portalTypeOf(store) != null));
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PLAYER_JOINED, System.nanoTime() - start);
        }
    }

    /** Reads the player's game mode and world into the registry. On the world thread that owns the store. */
    private void readScope(PlayerRegistry.Online o, Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                GameMode mode = player.getGameMode();
                if (mode != null) {
                    o.gameMode = mode.name();
                    o.creative = mode == GameMode.Creative;
                }
            }
            String world = PlayerEventSystem.worldName(store);
            if (!world.isEmpty()) {
                o.world = world;
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    /** Network thread. Publishes before the counters are saved, then drops the session. */
    private void onDisconnect(PlayerDisconnectEvent event) {
        long start = System.nanoTime();
        try {
            PlayerRef pr = event.getPlayerRef();
            if (pr == null) {
                return;
            }
            UUID uuid = pr.getUuid();
            long now = System.currentTimeMillis();
            PlayerRegistry.Online o = s.players.get(uuid);
            lastWorld.remove(uuid);
            if (o == null) {
                return; // never got ready, nothing was joined
            }
            if (s.publisher.wants(EventKind.PLAYER_LEFT)) {
                s.publisher.publish(new PlayerLeft(s.header(o.world, uuid), o.name, Math.max(0, now - o.sessionStart)));
            }
            s.players.leave(uuid);
            s.counters.leave(uuid);
            sampler.left(uuid, now);
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PLAYER_LEFT, System.nanoTime() - start);
        }
    }

    /**
     * After a {@code /plugin reload} nobody fires PlayerReady for the players already online, so they are
     * joined here, without a PLAYER_JOINED, since their session did not begin now. On a normal boot nobody
     * is online yet and this does nothing.
     */
    private void joinOnlinePlayers() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (PlayerRef pr : universe.getPlayers()) {
                try {
                    UUID uuid = pr.getUuid();
                    long now = System.currentTimeMillis();
                    PlayerRegistry.Online o = s.players.join(uuid, pr.getUsername(), now);
                    o.readyOnce = true;
                    s.counters.join(uuid, pr.getUsername(), now);
                    World world = pr.getWorldUuid() == null ? null : universe.getWorld(pr.getWorldUuid());
                    if (world == null) {
                        continue;
                    }
                    o.world = world.getName();
                    lastWorld.put(uuid, world.getName());
                    WorldTasks.post(world, () -> {
                        Ref<EntityStore> ref = pr.getReference();
                        if (ref != null && ref.isValid()) {
                            readScope(o, ref, ref.getStore());
                        }
                    });
                } catch (RuntimeException | LinkageError e) {
                    s.logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not join online player %s", pr.getUuid());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            s.logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not join the players already online");
        }
    }

    // ---- worlds -------------------------------------------------------------------------------------------

    /**
     * Every world entry. The first of a session is published by {@link #onReady}, since the player is not
     * joined yet here. The portal type is read from the new world's {@link PortalWorld} on that world's
     * thread.
     */
    private void onAddToWorld(AddPlayerToWorldEvent event) {
        long start = System.nanoTime();
        try {
            World world = event.getWorld();
            Holder<EntityStore> holder = event.getHolder();
            if (world == null || holder == null) {
                return;
            }
            PlayerRef pr = holder.getComponent(PlayerRef.getComponentType());
            if (pr == null) {
                return;
            }
            UUID uuid = pr.getUuid();
            String to = world.getName();
            sampler.resetPosition(uuid);
            PlayerRegistry.Online o = s.players.get(uuid);
            if (o == null || !o.readyOnce) {
                if (to != null) {
                    lastWorld.put(uuid, to);
                }
                return;
            }
            String from = to == null ? lastWorld.get(uuid) : lastWorld.put(uuid, to);
            if (from == null) {
                from = o.world;
            }
            if (to != null) {
                o.world = to;
            }
            if (Objects.equals(from, to) || !s.publisher.wants(EventKind.PLAYER_WORLD_CHANGED)) {
                return;
            }
            String fromWorld = from;
            WorldTasks.run(world, () -> {
                try {
                    String portal = portalTypeOf(world.getEntityStore().getStore());
                    boolean instance = portal != null || isPortalWorld(world);
                    s.publisher.publish(new PlayerWorldChanged(s.header(to, uuid), fromWorld, portal, instance));
                } catch (RuntimeException | LinkageError e) {
                    Guard.failed(e);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PLAYER_WORLD_CHANGED, System.nanoTime() - start);
        }
    }

    /** The portal type id of a portal world, or null for an ordinary world or a failed read. World thread. */
    private static String portalTypeOf(Store<EntityStore> store) {
        try {
            PortalWorld portal = store.getResource(PortalWorld.getResourceType());
            PortalType type = portal != null && portal.exists() ? portal.getPortalType() : null;
            return type != null ? type.getId() : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean isPortalWorld(World world) {
        try {
            PortalWorld portal = world.getEntityStore().getStore().getResource(PortalWorld.getResourceType());
            return portal != null && portal.exists();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private void forgetWorld(String name) {
        if (edgeSystem != null) {
            edgeSystem.forgetWorld(name);
        }
        try {
            Universe universe = Universe.get();
            World world = universe == null ? null : universe.getWorld(name);
            if (world != null) {
                sampler.forgetWorld(world);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // the gate entry goes stale on its own
        }
    }

    /** A teleport on the world thread. The sampler and the fall in progress start afresh. */
    void teleported(UUID uuid, World world, Ref<EntityStore> ref) {
        sampler.resetPosition(uuid);
        if (edgeSystem != null) {
            edgeSystem.teleported(world, ref);
        }
    }

    // ---- chat and commands --------------------------------------------------------------------------------

    /** Async, late, off any world thread. Hands the event on unchanged. */
    private PlayerChatEvent onChat(PlayerChatEvent event) {
        if (!s.publisher.wants(EventKind.PLAYER_CHATTED)) {
            return event;
        }
        long start = System.nanoTime();
        try {
            PlayerRef sender = event.getSender();
            if (sender != null) {
                UUID uuid = sender.getUuid();
                String content = event.getContent();
                String text = s.config().chatRecordText ? content : null;
                s.publisher.publish(new PlayerChatted(s.header(s.players.worldOf(uuid), uuid), sender.getUsername(),
                        content == null ? 0 : content.length(), event.isCancelled(), text));
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PLAYER_CHATTED, System.nanoTime() - start);
        }
        return event;
    }

    /**
     * Commands travel in the chat packet with a leading slash, and the game packet handler hands them to
     * the command manager without any event. The engine's packet adapters take global inbound watchers
     * that see every packet of a player before it is handled and can never drop it, so one watcher reads
     * the first word. It is a static engine list, so {@link #stop} removes it again.
     */
    private void watchCommands() {
        commandWatcher = PacketAdapters.registerInbound((PlayerPacketWatcher) this::onPacket);
    }

    /** Network thread, every inbound packet of every player, so it returns at the first test that fails. */
    private void onPacket(PlayerRef player, Packet packet) {
        if (!(packet instanceof ChatMessage chat) || !s.publisher.wants(EventKind.PLAYER_COMMAND)) {
            return;
        }
        String message = chat.message;
        if (message == null || message.length() < 2 || message.charAt(0) != '/' || player == null) {
            return;
        }
        long start = System.nanoTime();
        try {
            String command = commandOf(message);
            if (command != null) {
                UUID uuid = player.getUuid();
                s.publisher.publish(new PlayerCommand(s.header(s.players.worldOf(uuid), uuid), player.getUsername(), command));
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PLAYER_COMMAND, System.nanoTime() - start);
        }
    }

    /** The first word of a chat line starting with a slash, lower case and without the slash, or null. */
    static String commandOf(String message) {
        if (message == null || message.length() < 2 || message.charAt(0) != '/') {
            return null;
        }
        int end = 1;
        int n = message.length();
        while (end < n && !Character.isWhitespace(message.charAt(end))) {
            end++;
        }
        if (end == 1 || end - 1 > MAX_COMMAND_LENGTH) {
            return null;
        }
        return message.substring(1, end).toLowerCase(Locale.ROOT);
    }

    // ---- permissions --------------------------------------------------------------------------------------

    /** The thread a permission command ran on. Only values are read. */
    private void permissions(UUID uuid, PermissionChanged.Change change, Collection<String> values) {
        if (uuid == null || !s.publisher.wants(EventKind.PERMISSION_CHANGED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            List<String> list = new ArrayList<>(values == null ? 0 : values.size());
            if (values != null) {
                for (String v : values) {
                    if (v != null && !v.isEmpty()) {
                        list.add(v);
                    }
                }
            }
            s.publisher.publish(new PermissionChanged(s.header(s.players.worldOf(uuid), uuid), change, List.copyOf(list)));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.PERMISSION_CHANGED, System.nanoTime() - start);
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    // ---- shared reads -------------------------------------------------------------------------------------

    /** A player or creature actor for an entity of this store, or null. */
    static Actor actorOf(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return null;
        }
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (pr != null) {
            return Actor.player(pr.getUuid(), pr.getUsername());
        }
        String type = null;
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            type = npc == null ? null : npc.getNPCTypeId();
        } catch (RuntimeException | LinkageError ignored) {
            // not an NPC this server knows
        }
        return type == null ? null : Actor.creature(uuidOf(ref, store), type);
    }

    static UUID uuidOf(Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            UUIDComponent c = store.getComponent(ref, UUIDComponent.getComponentType());
            return c == null ? null : c.getUuid();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static PlayerRef playerOf(Ref<EntityStore> ref, Store<EntityStore> store) {
        return ref == null ? null : store.getComponent(ref, PlayerRef.getComponentType());
    }

    // ---- ECS systems, one class each since the engine registers systems by class --------------------------

    /** PLAYER_RESPAWNED. The position is read once the respawn applied, on the next task of the world. */
    static final class RespawnSystem extends PlayerEventSystem<RespawnEvent> {
        private final Sampler sampler;

        RespawnSystem(Services services, Sampler sampler) {
            super(RespawnEvent.class, EventKind.PLAYER_RESPAWNED, services);
            this.sampler = sampler;
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store, RespawnEvent event) {
            if (event.isCancelled()) {
                return;
            }
            sampler.resetPosition(uuid);
            World world = worldOf(store);
            String name = worldName(store);
            WorldTasks.post(world, () -> {
                try {
                    if (!ref.isValid()) {
                        return;
                    }
                    TransformComponent tc = ref.getStore().getComponent(ref, TransformComponent.getComponentType());
                    Vector3d p = tc == null ? null : tc.getPosition();
                    if (p != null) {
                        s.publisher.publish(new PlayerRespawned(s.header(name, uuid), p.x, p.y, p.z));
                    }
                } catch (RuntimeException | LinkageError e) {
                    Guard.failed(e);
                }
            });
        }
    }

    /** PLAYER_TELEPORTED. Every teleport adds the Teleport component, a set replaces a pending one. */
    static final class TeleportSystem extends RefChangeSystem<EntityStore, Teleport> {
        private final Services s;
        private final PlayerHooks hooks;

        TeleportSystem(Services services, PlayerHooks hooks) {
            this.s = services;
            this.hooks = hooks;
        }

        @Override
        public ComponentType<EntityStore, Teleport> componentType() {
            return Teleport.getComponentType();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, Teleport teleport, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> commandBuffer) {
            long start = System.nanoTime();
            try {
                PlayerRef pr = playerOf(ref, store);
                if (pr == null || teleport == null) {
                    return;
                }
                UUID uuid = pr.getUuid();
                World here = PlayerEventSystem.worldOf(store);
                hooks.teleported(uuid, here, ref);
                if (!s.publisher.wants(EventKind.PLAYER_TELEPORTED)) {
                    return;
                }
                World target = teleport.getWorld();
                if (target != null && target != here && (here == null || !Objects.equals(target.getName(), here.getName()))) {
                    return; // a world change, PLAYER_WORLD_CHANGED covers it
                }
                Vector3d to = teleport.getPosition();
                if (to == null) {
                    return;
                }
                double fx;
                double fy;
                double fz;
                TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
                Vector3d now = tc == null ? null : tc.getPosition();
                PlayerRegistry.Online o = s.players.get(uuid);
                if (now != null) {
                    fx = now.x;
                    fy = now.y;
                    fz = now.z;
                } else if (o != null && o.placed) {
                    fx = o.lastX;
                    fy = o.lastY;
                    fz = o.lastZ;
                } else {
                    fx = to.x;
                    fy = to.y;
                    fz = to.z;
                }
                s.publisher.publish(new PlayerTeleported(s.header(PlayerEventSystem.worldName(store), uuid),
                        fx, fy, fz, to.x, to.y, to.z));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            } finally {
                s.perf.record(EventKind.PLAYER_TELEPORTED, System.nanoTime() - start);
            }
        }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, Teleport before, Teleport after, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
            // A replaced pending teleport is the same jump.
        }

        @Override
        public void onComponentRemoved(Ref<EntityStore> ref, Teleport teleport, Store<EntityStore> store,
                                       CommandBuffer<EntityStore> commandBuffer) {
            // Removal means the teleport was applied.
        }
    }

    /**
     * GAME_MODE_CHANGED and the registry creative flag. The event carries the requested mode and a later
     * system may still cancel it, so the mode is read back on the world thread straight after.
     */
    static final class GameModeSystem extends EntityEventSystem<EntityStore, ChangeGameModeEvent> {
        private final Services s;

        GameModeSystem(Services services) {
            super(ChangeGameModeEvent.class);
            this.s = services;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, ChangeGameModeEvent event) {
            long start = System.nanoTime();
            try {
                if (event.isCancelled()) {
                    return;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                PlayerRef pr = playerOf(ref, store);
                PlayerRegistry.Online o = pr == null ? null : s.players.get(pr.getUuid());
                if (o == null) {
                    return;
                }
                UUID uuid = pr.getUuid();
                String from = o.gameMode;
                GameMode requested = event.getGameMode();
                if (requested != null) {
                    o.gameMode = requested.name();
                    o.creative = requested == GameMode.Creative;
                }
                WorldTasks.post(PlayerEventSystem.worldOf(store), () -> {
                    try {
                        if (!ref.isValid()) {
                            return;
                        }
                        Store<EntityStore> st = ref.getStore();
                        Player player = st.getComponent(ref, Player.getComponentType());
                        GameMode mode = player == null ? null : player.getGameMode();
                        if (mode == null) {
                            return;
                        }
                        o.gameMode = mode.name();
                        o.creative = mode == GameMode.Creative;
                        if (!mode.name().equals(from) && s.publisher.wants(EventKind.GAME_MODE_CHANGED)) {
                            s.publisher.publish(new GameModeChanged(s.header(PlayerEventSystem.worldName(st), uuid),
                                    from == null || from.isEmpty() ? null : from, mode.name()));
                        }
                    } catch (RuntimeException | LinkageError e) {
                        Guard.failed(e);
                    }
                });
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            } finally {
                s.perf.record(EventKind.GAME_MODE_CHANGED, System.nanoTime() - start);
            }
        }
    }

    /** ZONE_DISCOVERED, cancelled displays skipped. */
    static final class ZoneSystem extends PlayerEventSystem<DiscoverZoneEvent.Display> {
        ZoneSystem(Services services) {
            super(DiscoverZoneEvent.Display.class, EventKind.ZONE_DISCOVERED, services);
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    DiscoverZoneEvent.Display event) {
            if (event.isCancelled()) {
                return;
            }
            WorldMapTracker.ZoneDiscoveryInfo info = event.getDiscoveryInfo();
            if (info == null || info.zoneName() == null) {
                return;
            }
            s.publisher.publish(new ZoneDiscovered(s.header(worldName(store), uuid), info.zoneName(), info.regionName()));
        }
    }

    /**
     * MOUNT_CHANGED. The mount id is the NPC type id of the mounted entity when it lives in the same store,
     * {@code Minecart} for a minecart and else the block seat type name. A set is a change of seat.
     */
    static final class MountSystem extends RefChangeSystem<EntityStore, MountedComponent> {
        private final Services s;

        MountSystem(Services services) {
            this.s = services;
        }

        @Override
        public ComponentType<EntityStore, MountedComponent> componentType() {
            return MountedComponent.getComponentType();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, MountedComponent component, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> commandBuffer) {
            publish(ref, component, store, true);
        }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, MountedComponent before, MountedComponent after,
                                   Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
            // A change of seat is not a new mount.
        }

        @Override
        public void onComponentRemoved(Ref<EntityStore> ref, MountedComponent component, Store<EntityStore> store,
                                       CommandBuffer<EntityStore> commandBuffer) {
            publish(ref, component, store, false);
        }

        private void publish(Ref<EntityStore> ref, MountedComponent component, Store<EntityStore> store, boolean mounted) {
            if (!s.publisher.wants(EventKind.MOUNT_CHANGED)) {
                return;
            }
            long start = System.nanoTime();
            try {
                PlayerRef pr = playerOf(ref, store);
                if (pr == null || component == null) {
                    return;
                }
                String typeId = null;
                UUID entity = null;
                Ref<EntityStore> mount = component.getMountedToEntity();
                if (mount != null && mount.isValid() && mount.getStore() == store) {
                    entity = uuidOf(mount, store);
                    try {
                        NPCEntity npc = store.getComponent(mount, NPCEntity.getComponentType());
                        typeId = npc == null ? null : npc.getNPCTypeId();
                    } catch (RuntimeException | LinkageError ignored) {
                        // fall back to the controller
                    }
                }
                if (typeId == null) {
                    MountController controller = component.getControllerType();
                    BlockMountType seat = component.getBlockMountType();
                    if (controller == MountController.Minecart) {
                        typeId = "Minecart";
                    } else if (seat != null) {
                        typeId = seat.name();
                    }
                }
                s.publisher.publish(new MountChanged(s.header(PlayerEventSystem.worldName(store), pr.getUuid()),
                        mounted, typeId, entity));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            } finally {
                s.perf.record(EventKind.MOUNT_CHANGED, System.nanoTime() - start);
            }
        }
    }

    /**
     * PORTAL_ENTERED. The portal device names no portal type, its destination world does, and that world
     * belongs to another thread. The id is the device's base block type, which names the portal kind.
     */
    static final class PortalSystem extends PlayerEventSystem<EnterPortalEvent> {
        PortalSystem(Services services) {
            super(EnterPortalEvent.class, EventKind.PORTAL_ENTERED, services);
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    EnterPortalEvent event) {
            PortalDevice device = event.getPortalDevice();
            String typeId = device == null ? null : device.getBaseBlockTypeKey();
            Vector3ic block = event.getPortalBlock();
            int x = block == null ? 0 : block.x();
            int y = block == null ? 0 : block.y();
            int z = block == null ? 0 : block.z();
            s.publisher.publish(new PortalEntered(s.header(worldName(store), uuid), typeId, x, y, z));
        }
    }

    /** INSTANCE_DISCOVERED. The discovery config has no id of its own, its title key names the instance. */
    static final class InstanceSystem extends PlayerEventSystem<DiscoverInstanceEvent.Display> {
        InstanceSystem(Services services) {
            super(DiscoverInstanceEvent.Display.class, EventKind.INSTANCE_DISCOVERED, services);
        }

        @Override
        protected void handlePlayer(UUID uuid, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                    DiscoverInstanceEvent.Display event) {
            if (event.isCancelled()) {
                return;
            }
            InstanceDiscoveryConfig config = event.getDiscoveryConfig();
            String id = config == null ? null : config.getTitleKey();
            if (id != null && id.isEmpty()) {
                id = null;
            }
            s.publisher.publish(new InstanceDiscovered(s.header(worldName(store), uuid), event.getInstanceWorldUuid(), id));
        }
    }

    /** SPECTATING_CHANGED, the Spectating component added and removed. */
    static final class SpectatingSystem extends RefChangeSystem<EntityStore, Spectating> {
        private final Services s;

        SpectatingSystem(Services services) {
            this.s = services;
        }

        @Override
        public ComponentType<EntityStore, Spectating> componentType() {
            return Spectating.getComponentType();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, Spectating component, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> commandBuffer) {
            publish(ref, component, store, true);
        }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, Spectating before, Spectating after, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
            // A new target while spectating, not a change of state.
        }

        @Override
        public void onComponentRemoved(Ref<EntityStore> ref, Spectating component, Store<EntityStore> store,
                                       CommandBuffer<EntityStore> commandBuffer) {
            publish(ref, component, store, false);
        }

        private void publish(Ref<EntityStore> ref, Spectating component, Store<EntityStore> store, boolean spectating) {
            if (!s.publisher.wants(EventKind.SPECTATING_CHANGED)) {
                return;
            }
            long start = System.nanoTime();
            try {
                PlayerRef pr = playerOf(ref, store);
                if (pr == null) {
                    return;
                }
                Actor target = component == null ? null : actorOf(component.getTargetRef(), store);
                s.publisher.publish(new SpectatingChanged(s.header(PlayerEventSystem.worldName(store), pr.getUuid()),
                        spectating, target));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            } finally {
                s.perf.record(EventKind.SPECTATING_CHANGED, System.nanoTime() - start);
            }
        }
    }
}
