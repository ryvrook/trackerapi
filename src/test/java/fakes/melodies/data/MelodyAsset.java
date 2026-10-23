package fakes.melodies.data;

import java.util.Map;

/** The fake song asset with the static store accessor of the real {@code MelodyAsset}. Two songs. */
public class MelodyAsset {
    private static final AssetStore STORE = new AssetStore(new DefaultAssetMap(Map.of(
            "Axel_F", new MelodyAsset(new Melody("Axel F", 60_000)),
            "Megalovania", new MelodyAsset(new Melody("Megalovania", 90_000)))));

    private final Melody melody;

    public MelodyAsset(Melody melody) {
        this.melody = melody;
    }

    public static AssetStore getAssetStore() {
        return STORE;
    }

    public Melody getMelody() {
        return melody;
    }

    /** Stands in for the engine's asset store. */
    public static final class AssetStore {
        private final DefaultAssetMap map;

        AssetStore(DefaultAssetMap map) {
            this.map = map;
        }

        public DefaultAssetMap getAssetMap() {
            return map;
        }
    }

    /** Stands in for the engine's asset map, keyed by song id. */
    public static final class DefaultAssetMap {
        private final Map<String, MelodyAsset> assets;

        DefaultAssetMap(Map<String, MelodyAsset> assets) {
            this.assets = assets;
        }

        public MelodyAsset getAsset(Object id) {
            return assets.get(id);
        }
    }
}
