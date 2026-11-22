package io.github.toolcanvas;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * World-level store of every design ever applied (data/toolcanvas_designs.dat in the world folder).
 * Items only hold the id, so the pixels live here once, no matter how many copies of a tool exist.
 */
public final class DesignStorage extends SavedData {
    private static final String NAME = "toolcanvas_designs";
    private static final Factory<DesignStorage> FACTORY = new Factory<>(DesignStorage::new, DesignStorage::load);

    private final Long2ObjectOpenHashMap<DesignData> designs = new Long2ObjectOpenHashMap<>();

    public static DesignStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    @Nullable
    public DesignData get(long id) {
        return designs.get(id);
    }

    /** Stores the design and returns its id. */
    public long put(DesignData data) {
        long id = data.id();
        if (!designs.containsKey(id)) {
            designs.put(id, data);
            setDirty();
        }
        return id;
    }

    private static DesignStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        DesignStorage storage = new DesignStorage();
        ListTag list = tag.getList("designs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                DesignData data = new DesignData(entry.getInt("w"), entry.getInt("h"), entry.getIntArray("p"));
                storage.designs.put(entry.getLong("id"), data);
            } catch (IllegalArgumentException e) {
                ToolCanvas.LOGGER.warn("Skipping corrupt tool design {}", entry.getLong("id"));
            }
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        designs.long2ObjectEntrySet().forEach(e -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("id", e.getLongKey());
            entry.putInt("w", e.getValue().width());
            entry.putInt("h", e.getValue().height());
            entry.putIntArray("p", e.getValue().pixels());
            list.add(entry);
        });
        tag.put("designs", list);
        return tag;
    }
}
