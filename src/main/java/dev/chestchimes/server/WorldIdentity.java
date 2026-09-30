package dev.chestchimes.server;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class WorldIdentity extends SavedData {
    private final UUID id;
    private WorldIdentity(UUID id) { this.id = id; }
    private static WorldIdentity create() {
        WorldIdentity data = new WorldIdentity(UUID.randomUUID());
        data.setDirty();
        return data;
    }
    private static WorldIdentity load(CompoundTag tag) {
        return tag.hasUUID("id") ? new WorldIdentity(tag.getUUID("id")) : create();
    }
    public static UUID get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(WorldIdentity::load, WorldIdentity::create, "chestchimes_world").id;
    }
    @Override public CompoundTag save(CompoundTag tag) { tag.putUUID("id", id); return tag; }
}
