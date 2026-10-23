package fakes.zephyr;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import fakes.zephyr.items.utility.zipline.ZiplineRideComponent;

/** The plugin object of the fake Zephyr, with the static component type getters the real one has. */
public final class Zephyr {
    private static final ComponentType<EntityStore, ZiplineRideComponent> ZIPLINE = new ComponentType<>();

    public Zephyr() {
    }

    public static ComponentType<EntityStore, ZiplineRideComponent> getZiplineRideComponentType() {
        return ZIPLINE;
    }
}
