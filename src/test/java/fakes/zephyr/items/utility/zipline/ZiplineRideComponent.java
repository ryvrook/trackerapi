package fakes.zephyr.items.utility.zipline;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** The zipline component of the fake Zephyr, public fields as the real one has, no codec. */
public class ZiplineRideComponent implements Component<EntityStore> {
    public float ziplineProgress;
    public int ziplineRideCount;
    public boolean nearZipline;

    public ZiplineRideComponent() {
    }

    @Override
    public Component<EntityStore> clone() {
        ZiplineRideComponent c = new ZiplineRideComponent();
        c.ziplineProgress = ziplineProgress;
        c.ziplineRideCount = ziplineRideCount;
        c.nearZipline = nearZipline;
        return c;
    }
}
