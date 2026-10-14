package dev.ryvrook.trackerapi.hooks.world;

import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import dev.ryvrook.trackerapi.Services;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.event.WorldAdded;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;

/**
 * WORLD_ADDED from {@code StartWorldEvent}. The portal type and the instance flag are store and config reads,
 * so they run on the world's own thread, now when the event already is there, else as a posted task. Each
 * read sits in its own try (the Chronicle {@code WorldFactsReader} steps), a world is an instance when it has
 * an instance config or is a portal world. The same task teaches {@code WorldKeys} the world's counter key.
 */
public final class WorldAddedListener {
    private final Services s;

    public WorldAddedListener(Services services) {
        this.s = services;
    }

    public void register(EventRegistry events) {
        events.registerGlobal(StartWorldEvent.class, this::onStart);
    }

    private void onStart(StartWorldEvent event) {
        try {
            World world = event.getWorld();
            if (world != null) {
                // The counter key of the world is learned whether or not anyone wants the event.
                WorldTasks.run(world, () -> {
                    s.counters.worldKeys().learn(world);
                    publish(world);
                });
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        }
    }

    private void publish(World world) {
        if (!s.publisher.wants(EventKind.WORLD_ADDED)) {
            return;
        }
        long start = System.nanoTime();
        try {
            String portalTypeId = portalTypeId(world);
            boolean instance = portalTypeId != null || hasInstanceConfig(world);
            s.publisher.publish(new WorldAdded(s.header(WorldNames.name(world), null), instance, portalTypeId));
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
        } finally {
            s.perf.record(EventKind.WORLD_ADDED, System.nanoTime() - start);
        }
    }

    /** The portal type id of a portal world, null for any other world. World thread. */
    static String portalTypeId(World world) {
        try {
            PortalWorld pw = world.getEntityStore().getStore().getResource(PortalWorld.getResourceType());
            if (pw == null || !pw.exists()) {
                return null;
            }
            PortalType type = pw.getPortalType();
            return type == null ? null : type.getId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean hasInstanceConfig(World world) {
        try {
            return InstanceWorldConfig.get(world.getWorldConfig()) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
