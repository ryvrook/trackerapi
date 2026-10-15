package dev.ryvrook.trackerapi;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * The plugins that list TrackerAPI as a dependency. After a {@code /plugin reload} of TrackerAPI their
 * linked api classes belong to the old instance, so they count nothing until they are reloaded. On a
 * fresh boot none of them is enabled yet when TrackerAPI starts, because the dependency orders the load,
 * so an enabled dependent at start time means TrackerAPI itself was reloaded and the dependents are
 * reloaded for the owner one after another, each after the dependents it reads itself.
 */
final class Dependents {
    private Dependents() {
    }

    /**
     * The enabled plugins whose manifest names {@code self} under its dependencies, ordered so a plugin
     * comes after the others in the list that it names itself. Chronicle Stats and Dominion read Coinage,
     * so Coinage is reloaded before them and they link to its new instance too.
     */
    static List<PluginIdentifier> enabled(PluginIdentifier self, HytaleLogger logger) {
        List<PluginIdentifier> found = new ArrayList<>();
        Map<String, PluginIdentifier> byKey = new HashMap<>();
        Map<String, Set<String>> names = new HashMap<>();
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null || self == null) {
                return found;
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
                        String key = key(id);
                        found.add(id);
                        byKey.put(key, id);
                        Set<String> deps = new HashSet<>();
                        addKeys(manifest.getDependencies(), deps);
                        addKeys(manifest.getOptionalDependencies(), deps);
                        names.put(key, deps);
                    }
                } catch (RuntimeException | LinkageError e) {
                    // one odd plugin never stops the walk
                    logger.at(Level.FINE).withCause(e).log("TrackerAPI skipped a plugin it could not read while looking for dependents");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            logger.at(Level.FINE).withCause(e).log("TrackerAPI could not list the plugins to find its dependents");
            return found;
        }
        List<String> keys = new ArrayList<>(found.size());
        for (PluginIdentifier id : found) {
            keys.add(key(id));
        }
        List<PluginIdentifier> out = new ArrayList<>(found.size());
        for (String key : order(keys, names)) {
            out.add(byKey.get(key));
        }
        return out;
    }

    /**
     * Orders the keys so each comes after every other key in the list it depends on, and otherwise keeps
     * the given order, which is the load order. A cycle is broken at the first key reached again.
     */
    static List<String> order(List<String> keys, Map<String, Set<String>> deps) {
        Set<String> visiting = new HashSet<>();
        Set<String> done = new LinkedHashSet<>();
        for (String key : keys) {
            visit(key, keys, deps, visiting, done);
        }
        return new ArrayList<>(done);
    }

    private static void visit(String key, List<String> keys, Map<String, Set<String>> deps, Set<String> visiting,
                              Set<String> done) {
        if (done.contains(key) || !visiting.add(key)) {
            return;
        }
        Set<String> mine = deps.getOrDefault(key, Set.of());
        for (String other : keys) {
            if (mine.contains(other)) {
                visit(other, keys, deps, visiting, done);
            }
        }
        visiting.remove(key);
        done.add(key);
    }

    private static String key(PluginIdentifier id) {
        return (id.getGroup() + ":" + id.getName()).toLowerCase(Locale.ROOT);
    }

    private static void addKeys(Map<PluginIdentifier, ?> deps, Set<String> into) {
        if (deps != null) {
            for (PluginIdentifier id : deps.keySet()) {
                if (id != null) {
                    into.add(key(id));
                }
            }
        }
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
