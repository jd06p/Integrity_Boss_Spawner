package com.bossmod.worldlock;

import com.bossmod.BossMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the set of player UUIDs that have been locked out of this world by
 * the Integrity boss-death mechanic.
 *
 * <p>Stored at {@code <world>/data/integrity_boss_spawn_world_lock.dat} (overworld only —
 * the overworld's data storage is the authoritative per-world store in Forge).
 */
public class WorldLockData extends SavedData {

    private static final String SAVE_KEY    = "integrity_boss_spawn_world_lock";
    private static final String TAG_PLAYERS = "lockedPlayers";

    /** UUIDs of players that may not re-enter this world. */
    private final Set<UUID> lockedPlayers = new HashSet<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns {@code true} if the given player is locked out of this world. */
    public boolean isLocked(UUID playerId) {
        return lockedPlayers.contains(playerId);
    }

    /**
     * Adds {@code playerId} to the lock set and marks the data dirty so it is
     * written to disk on the next auto-save.
     */
    public void lock(UUID playerId) {
        if (lockedPlayers.add(playerId)) {
            setDirty();
            BossMod.LOGGER.info("[BossMod] Locked player {} out of this world.", playerId);
        }
    }

    /** Removes a lock (e.g. for admin reset). Marks dirty. */
    public void unlock(UUID playerId) {
        if (lockedPlayers.remove(playerId)) {
            setDirty();
            BossMod.LOGGER.info("[BossMod] Unlocked player {} for this world.", playerId);
        }
    }

    /** Read-only view of all locked UUIDs (for admin/debug use). */
    public Set<UUID> getLockedPlayers() {
        return Collections.unmodifiableSet(lockedPlayers);
    }

    // -------------------------------------------------------------------------
    // SavedData serialisation
    // -------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID id : lockedPlayers) {
            list.add(StringTag.valueOf(id.toString()));
        }
        tag.put(TAG_PLAYERS, list);
        return tag;
    }

    public static WorldLockData load(CompoundTag tag) {
        WorldLockData data = new WorldLockData();
        ListTag list = tag.getList(TAG_PLAYERS, Tag.TAG_STRING);
        for (Tag entry : list) {
            try {
                data.lockedPlayers.add(UUID.fromString(entry.getAsString()));
            } catch (IllegalArgumentException e) {
                BossMod.LOGGER.warn("[BossMod] Skipping malformed UUID in world lock data: {}", entry.getAsString());
            }
        }
        return data;
    }

    // -------------------------------------------------------------------------
    // Access helper
    // -------------------------------------------------------------------------

    /**
     * Retrieves or creates the {@link WorldLockData} for the server.
     * Always uses the overworld's data storage as the canonical location.
     *
     * @param overworld the overworld {@link ServerLevel} (dimension = OVERWORLD)
     */
    public static WorldLockData getOrCreate(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                WorldLockData::load,
                WorldLockData::new,
                SAVE_KEY
        );
    }

    /**
     * Convenience: resolves the overworld from any {@link ServerLevel} and
     * returns the shared {@link WorldLockData}.
     */
    public static WorldLockData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("[BossMod] Overworld not available when accessing WorldLockData");
        }
        return getOrCreate(overworld);
    }
}
