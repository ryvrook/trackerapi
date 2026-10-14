package dev.ryvrook.trackerapi;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The plugins that list TrackerAPI as a dependency. After a {@code /plugin reload} of TrackerAPI their
 * linked api classes belong to the old instance, so they count nothing until they are reloaded. On a
 * fresh boot none of them is enabled yet when TrackerAPI starts, because the dependency orders the load,
 * so an enabled dependent at start time means TrackerAPI itself was reloaded and the dependents are
 * reloaded for the owner, in load order, one after another.
 */
final class Dependents {
    private Dependents() {
    }

    /** The enabled plugins whose manifest names {@code self} under its dependencies. */
    static List<PluginIdentifier> enabled(PluginIdentifier self) {
        List<PluginIdentifier> out = new ArrayList<>();
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null || self == null) {
                return out;
            }
            for (PluginBase plugin : manager.getPlugins()) {
                try {
                    if (plugin == null || plugin.getState() != PluginState.ENABLED) {
                        continue;
                    }
                    PluginManifest manifest = plugin.getManifest();
                    if (manifest == null || !dependsOn(manifest, self)) {
                        continue;
                    }
                    PluginIdentifier id = plugin.getIdentifier();
                    if (id != null && !id.equals(self)) {
                        out.add(id);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // one odd plugin never stops the walk
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no plugin manager, nothing to reload
        }
        return out;
    }

    private static boolean dependsOn(PluginManifest manifest, PluginIdentifier self) {
        return names(manifest.getDependencies(), self) || names(manifest.getOptionalDependencies(), self);
    }

    private static boolean names(Map<PluginIdentifier, ?> deps, PluginIdentifier self) {
        if (deps == null) {
            return false;
        }
        for (PluginIdentifier id : deps.keySet()) {
            if (id != null && id.getGroup().equalsIgnoreCase(self.getGroup()) && id.getName().equalsIgnoreCase(self.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Reloads each plugin through the plugin manager, logging one line per plugin. Returns how many succeeded. */
    static int reload(List<PluginIdentifier> ids, HytaleLogger logger) {
        int n = 0;
        PluginManager manager;
        try {
            manager = PluginManager.get();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
        if (manager == null) {
            return 0;
        }
        for (PluginIdentifier id : ids) {
            try {
                logger.at(Level.INFO).log("TrackerAPI was reloaded, reloading %s so it links to the new instance", id);
                if (manager.reload(id)) {
                    n++;
                } else {
                    logger.at(Level.WARNING).log("TrackerAPI could not reload %s, run /plugin reload %s yourself", id, id);
                }
            } catch (RuntimeException | LinkageError e) {
                logger.at(Level.WARNING).withCause(e).log("TrackerAPI could not reload %s, run /plugin reload %s yourself", id, id);
            }
        }
        return n;
    }
}
