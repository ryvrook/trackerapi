package dev.ryvrook.trackerapi.mods.melodies;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.trackerapi.facts.WorldTasks;
import dev.ryvrook.trackerapi.util.Guard;
import org.bson.BsonDocument;
import org.bson.BsonNumber;
import org.bson.BsonString;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * The engine behind {@link MelodiesBinding.Engine}. One task per world with players through a
 * {@link WorldTasks.Gate}, as the sampler does. On the world thread each player's hand is read with
 * {@code InventoryComponent.getItemInHand}, and for an instrument the {@code MelodyProgress} document of the item
 * metadata through {@code ItemStack.getFromMetadataOrNull} with the engine's BSON document codec. It is only read, never written.
 */
final class EngineReads implements MelodiesBinding.Engine {
    private final WorldTasks.Gate gate = new WorldTasks.Gate(5_000);

    @Override
    public void check() throws ReflectiveOperationException {
        ItemStack.class.getMethod("getFromMetadataOrNull", String.class, Codec.class);
        Codec.class.getField("BSON_DOCUMENT");
        InventoryComponent.class.getMethod("getItemInHand", com.hypixel.hytale.component.ComponentAccessor.class, Ref.class);
    }

    @Override
    public void scan(MelodiesBinding.WorldVisitor visitor) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        gate.sweep();
        for (World world : universe.getWorlds().values()) {
            try {
                if (world == null || world.getPlayerCount() <= 0) {
                    continue;
                }
                gate.post(world, () -> scanWorld(world, visitor));
            } catch (RuntimeException | LinkageError e) {
                Guard.failed(e);
            }
        }
    }

    private static void scanWorld(World world, MelodiesBinding.WorldVisitor visitor) {
        List<MelodiesBinding.Sample> samples;
        String name;
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            name = world.getName();
            samples = new ArrayList<>(Math.max(4, world.getPlayerCount()));
            for (PlayerRef pr : world.getPlayerRefs()) {
                try {
                    MelodiesBinding.Sample s = sample(pr, store);
                    if (s != null) {
                        samples.add(s);
                    }
                } catch (RuntimeException | LinkageError e) {
                    Guard.failed(e);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Guard.failed(e);
            return;
        }
        visitor.visit(name, samples);
    }

    /** Codec.BSON_DOCUMENT is deprecated in 0.7.1 and 0.7.2 with no replacement for a raw document read. */
    @SuppressWarnings("deprecation")
    private static MelodiesBinding.Sample sample(PlayerRef pr, Store<EntityStore> store) {
        if (pr == null) {
            return null;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) {
            return null; // moved to another world since the list was read
        }
        ItemStack stack = InventoryComponent.getItemInHand(store, ref);
        String item = stack == null || stack.isEmpty() ? null : stack.getItemId();
        if (!Melodies.isInstrument(item)) {
            return new MelodiesBinding.Sample(pr.getUuid(), null, "", 0, 0);
        }
        // Only the one document, getMetadata would clone the whole metadata.
        BsonDocument progress = stack.getFromMetadataOrNull(Melodies.META_KEY, Codec.BSON_DOCUMENT);
        if (progress == null) {
            return new MelodiesBinding.Sample(pr.getUuid(), item, "", 0, 0);
        }
        String melody = progress.get(Melodies.META_MELODY) instanceof BsonString s ? s.getValue() : "";
        return new MelodiesBinding.Sample(pr.getUuid(), item, melody, number(progress.get(Melodies.META_TIME)),
                number(progress.get(Melodies.META_START)));
    }

    /** A BSON number as a long, the codec writes int64 but an int32 reads the same. */
    private static long number(BsonValue v) {
        return v instanceof BsonNumber n ? n.longValue() : 0;
    }
}
