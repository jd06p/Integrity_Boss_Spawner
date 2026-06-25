package com.bossmod.events;

import com.bossmod.BossMod;
import com.bossmod.config.BossModConfig;
import com.bossmod.sounds.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

public class BossSpawnHandler {

    private static final String SAVED_DATA_KEY = "integrity_boss_spawn_spawn_state";

    private int tickCounter = 0;
    private static final int CHECK_INTERVAL = 20;

    private int seqPhase = -1;
    private int delayTicks = 0;
    private ServerLevel activeLevel = null;

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;

        BossSpawnState state = BossSpawnState.getOrCreate(serverLevel);
        if (state.hasSpawned) return;

        if (seqPhase < 0) {
            tickCounter++;
            if (tickCounter < CHECK_INTERVAL) return;
            tickCounter = 0;

            long currentDay = (serverLevel.getDayTime() / 24_000L) + 1;
            int targetDay = BossModConfig.SERVER.spawnDay.get();
            if (currentDay < targetDay) return;

            startSequence(serverLevel);
        }

        if (activeLevel == serverLevel) {
            tickSequence();
        }
    }

    private void startSequence(ServerLevel level) {
        seqPhase = 0;
        delayTicks = 0;
        activeLevel = level;
    }

    private void tickSequence() {
        ServerLevel level = activeLevel;
        if (level == null) return;

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        switch (seqPhase) {
            case 0:
                broadcastMessage(level, "You do not have permission to use this command", ChatFormatting.RED);
                delayTicks = 40;
                seqPhase = 1;
                break;
            case 1:
                broadcastMessage(level, "You do not have permission to use this command", ChatFormatting.RED);
                delayTicks = 20;
                seqPhase = 2;
                break;
            case 2:
                broadcastMessage(level, "You do not have permission to use this command", ChatFormatting.RED);
                delayTicks = 8;
                seqPhase = 3;
                break;
            case 3:
                broadcastMessage(level, "You do not have permission to use this command", ChatFormatting.RED);
                delayTicks = 8;
                seqPhase = 4;
                break;
            case 4:
                broadcastMessage(level, "You do not have permission to use this command", ChatFormatting.RED);
                delayTicks = 8;
                seqPhase = 5;
                break;
            case 5:
                broadcastMessage(level, "You do not have permission to use this command", ChatFormatting.RED);
                delayTicks = 8;
                seqPhase = 6;
                break;
            case 6:
                broadcastMessage(level, "You do not have permission to use this command", ChatFormatting.RED);
                delayTicks = 0;
                seqPhase = 7;
                break;
            case 7:
                broadcastMessage(level, "Do you think you are safe?", ChatFormatting.WHITE);
                playSoundToAll(level, ModSounds.SAM1.get(), 3.0f);
                playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.8f);
                delayTicks = 80;
                seqPhase = 8;
                break;
            case 8:
                broadcastMessage(level, "Just wait and see.", ChatFormatting.WHITE);
                playSoundToAll(level, ModSounds.SAM2.get(), 3.0f);
                playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.8f);
                delayTicks = 120;
                seqPhase = 9;
                break;
            case 9:
                broadcastMessage(level, "Made Integrity a server operator", ChatFormatting.WHITE);
                delayTicks = 20;
                seqPhase = 10;
                break;
            case 10:
                broadcastMessage(level, "The End is Nigh.", ChatFormatting.WHITE);
                playSoundToAll(level, ModSounds.SAM3.get(), 3.0f);
                playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.8f);
                delayTicks = 40;
                seqPhase = 11;
                break;
            case 11:
                broadcastMessage(level, "GO AWAY GO AWAY", ChatFormatting.RED);
                delayTicks = 10;
                seqPhase = 12;
                break;
            case 12:
                broadcastMessage(level, "GO AWAY GO AWAY", ChatFormatting.RED);
                delayTicks = 0;
                seqPhase = 13;
                break;
            case 13:
                BossSpawnState state = BossSpawnState.getOrCreate(level);
                spawnBoss(level, state);
                seqPhase = -1;
                activeLevel = null;
                break;
        }
    }

    private static void broadcastMessage(ServerLevel level, String text, ChatFormatting color) {
        Component msg = Component.literal(text).withStyle(color);
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(msg);
        }
    }

    private static void playSoundToAll(ServerLevel level, net.minecraft.sounds.SoundEvent sound, float volume) {
        for (ServerPlayer player : level.players()) {
            player.playNotifySound(sound, SoundSource.MASTER, volume, 1.0f);
        }
    }

    private void spawnBoss(ServerLevel level, BossSpawnState state) {
        ResourceLocation entityId = new ResourceLocation("thebrokenscript", "integrity_bossfight");
        Optional<EntityType<?>> maybeType = Optional.ofNullable(ForgeRegistries.ENTITY_TYPES.getValue(entityId));

        if (maybeType.isEmpty()) {
            BossMod.LOGGER.warn("[BossMod] Entity type '{}' not found. Is The Broken Script installed?", entityId);
            state.hasSpawned = true;
            state.setDirty();
            return;
        }

        EntityType<?> type = maybeType.get();
        BlockPos spawnPos = chooseSpawnPos(level);

        Entity entity = type.create(level);
        if (entity == null) {
            BossMod.LOGGER.error("[BossMod] Failed to create entity instance for '{}'.", entityId);
            return;
        }

        entity.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
        boolean added = level.addFreshEntity(entity);

        if (added) {
            BossMod.LOGGER.info("[BossMod] Spawned '{}' at {} on day {}.",
                    entityId, spawnPos, (level.getDayTime() / 24_000L) + 1);
            state.hasSpawned = true;
            state.setDirty();
        } else {
            BossMod.LOGGER.warn("[BossMod] addFreshEntity returned false for '{}'. Will retry next tick cycle.", entityId);
        }
    }

    private BlockPos chooseSpawnPos(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            return player.blockPosition().offset(5, 1, 5);
        }
        BlockPos worldSpawn = level.getSharedSpawnPos();
        return new BlockPos(worldSpawn.getX(), level.getHeight() / 2, worldSpawn.getZ());
    }

    public static class BossSpawnState extends SavedData {

        private static final String TAG_SPAWNED = "hasSpawned";

        boolean hasSpawned = false;

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putBoolean(TAG_SPAWNED, hasSpawned);
            return tag;
        }

        public static BossSpawnState load(CompoundTag tag) {
            BossSpawnState state = new BossSpawnState();
            state.hasSpawned = tag.getBoolean(TAG_SPAWNED);
            return state;
        }

        public static BossSpawnState getOrCreate(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(
                    BossSpawnState::load,
                    BossSpawnState::new,
                    SAVED_DATA_KEY
            );
        }
    }
}
