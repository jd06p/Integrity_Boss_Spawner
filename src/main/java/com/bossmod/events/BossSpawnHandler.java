package com.bossmod.events;

import com.bossmod.BossMod;
import com.bossmod.config.BossModConfig;
import com.bossmod.network.BossNetwork;
import com.bossmod.network.IntegrityFxPacket;
import com.bossmod.sounds.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;
import java.util.UUID;

public class BossSpawnHandler {

    private static final String SAVED_DATA_KEY = "integrity_boss_spawn_spawn_state";
    private static final String TAG_SPAWNED = "hasSpawned";
    private static final String TAG_PENDING = "pending";

    private int tickCounter = 0;
    private static final int CHECK_INTERVAL = 20;

    private int seqPhase = -1;
    private int delayTicks = 0;
    private ServerLevel activeLevel = null;

    // -------------------------------------------------------------------------
    // Post-spawn event: titles + Revuxor dialogue + cave/trevoga sounds
    // -------------------------------------------------------------------------

    /** Titles shown the moment Integrity spawns, in order. */
    private static final String[] SPAWN_TITLES = {
            "Integrity is here",
            "Please go away",
            "The End Is nigh",
            "GO AWAY",
            "nullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnull"
    };

    /** Revuxor dialogue, kept verbatim (Base64-style text, do not decode). */
    private static final String[] REVUXOR_LINES = {
            "SW50ZWdyaXR5IGlzIGhlcmU=",
            "V2h5IGRpZG4ndCB5b3UgbGlzdGVuIHRvIHVz",
            "SXQgd2lsbCBraWxsIHlvdQ==",
            "QW5kIHlvdSB3aWxsIHR1cm4gaW50byBvbmUgb2YgdXM=",
            "VGhpcyB0aGUgbGFzdCBjaGFuY2U=",
            "UGxlYXNlIGRvbnQgZGll"
    };

    private static final int TITLE_REPEATS = 3;
    /** Each title snaps in and stays this many ticks: fast and jarring, but still readable. */
    private static final int TITLE_STEP_TICKS = 6;
    /** Ticks between Revuxor messages; slower than the title clock for a natural, deliberate cadence. */
    private static final int CHAT_INTERVAL_TICKS = 18;
    /** Extra ticks of Darkness after the last title, safety margin. */
    private static final int DARKNESS_EXTRA_TICKS = 40;

    private static final int TOTAL_TITLE_STEPS = SPAWN_TITLES.length * TITLE_REPEATS;
    private static final int DARKNESS_TICKS = TOTAL_TITLE_STEPS * TITLE_STEP_TICKS + DARKNESS_EXTRA_TICKS;

    /** Index of the current title step (-1 = idle), driven by the server tick scheduler. */
    private int titleStep = -1;
    private int stepTick = 0;
    private int dialogueIndex = 0;
    private int dialogueTick = 0;
    private ServerLevel titleLevel = null;

    private static final ResourceLocation TREVOGA = new ResourceLocation("thebrokenscript", "trevoga");
    private static final ResourceLocation NULL_IS_HERE_LOOP = new ResourceLocation("thebrokenscript", "nullishereloop");
    private static final ResourceLocation BOSS_ENTITY_ID = new ResourceLocation("thebrokenscript", "integrity_bossfight");
    private static final ResourceLocation INTEGRITY_DIES = new ResourceLocation("thebrokenscript", "integritydies");

    // -------------------------------------------------------------------------
    // 50% health event (once per encounter)
    // -------------------------------------------------------------------------

    private static final ResourceLocation INTEGRITY_WATCHING = new ResourceLocation("thebrokenscript", "integritywatching");
    private static final ResourceLocation YOU_WILL_REGRET_THAT = new ResourceLocation("thebrokenscript", "youwillregretthat");
    private static final ResourceLocation MOONGLITCH = new ResourceLocation("thebrokenscript", "moonglitch");
    private static final ResourceLocation WHY_CANT_YOU_LEAVE_EFFECT = new ResourceLocation("thebrokenscript", "why_cant_you_leave");

    /** Dark-red titles flashed when Integrity first drops below 50% HP. */
    private static final String[] HP50_TITLES = {
            "YOU WILL REGRET THIS",
            "<0>",
            "YOU CAN'T TRAP US",
            "THE END IS NIGH",
            "THE VOID WILL CONSUME YOU",
            "YOU WILL BECOME ONE OF US"
    };

    /** Desperate multi-voice conversation; the encoded bytes stay exactly as written. */
    private static final String[] HP50_CHAT = {
            "<xXram2dieXx> 01110000 01101100 01100101 01100001 01110011 01100101",
            "<Revuxor> UGxlYXNlIGZyZWUgdXM=",
            "<DyeXD412> .--. .-.. . .- ... . / -.- .. .-.. .-.. / .. -"
    };

    /** The three dread sounds are played deeply slowed (0.01 pitch). Do not clamp. */
    private static final float HP50_SOUND_PITCH = 0.01f;
    /** Short beat after the regret alert, before the titles start. */
    private static final int HP50_LEAD_TICKS = 10;
    /** Each title snaps in and holds briefly: glitchy, still readable. */
    private static final int HP50_TITLE_STEP_TICKS = 6;
    /** Total title steps = 6 titles * 3 full passes. */
    private static final int HP50_TITLE_STEPS = HP50_TITLES.length * 3;
    /** Beat of silence after the titles before the voices start. */
    private static final int HP50_FIRST_CHAT_DELAY = 40;
    /** Short pauses between the chat messages. */
    private static final int HP50_CHAT_INTERVAL_TICKS = 80;
    /** Pause after the last message before the final alert. */
    private static final int HP50_FINAL_DELAY = 30;
    /** Fallback duration for the effects; they are removed explicitly at the end. */
    private static final int HP50_EFFECT_TICKS = 20 * 30;

    /** The Integrity boss entity currently tracked for the 50% threshold. */
    private UUID activeBossUuid = null;
    private boolean halfHealthTriggered = false;

    /** Half-health event state machine (phases: lead -> titles -> chat -> final). */
    private int hp50Phase = -1;
    private int hp50Tick = 0;
    private int hp50Step = 0;
    private int hp50ChatIndex = 0;

    // -------------------------------------------------------------------------
    // Surface spawn search
    // -------------------------------------------------------------------------

    private static final int SURFACE_SEARCH_RADIUS = 32;
    private static final int SURFACE_MIN_CLEARANCE = 4;
    private static final int SURFACE_MAX_CLEARANCE = 12;

    @SubscribeEvent
    public void onServerTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;

        BossSpawnState state = BossSpawnState.getOrCreate(serverLevel);

        // Pre-spawn: day detection + intro. The encounter only begins while a
        // player is actually present in the Overworld. If the configured day
        // passes with everyone in another dimension, the spawn is marked
        // pending and starts later when a player returns to the Overworld.
        if (!state.hasSpawned) {
            boolean playersInOverworld = !serverLevel.players().isEmpty();

            if (seqPhase >= 0) {
                if (activeLevel == serverLevel) {
                    tickSequence();
                }
            } else if (state.pending) {
                if (playersInOverworld) {
                    startSequence(serverLevel);
                }
            } else {
                tickCounter++;
                if (tickCounter >= CHECK_INTERVAL) {
                    tickCounter = 0;

                    // Minecraft's displayed day is dayTime / 24000 (0-based; a
                    // fresh world is dayTime 0, F3 shows Day 0).
                    long currentDay = serverLevel.getDayTime() / 24_000L;
                    int targetDay = BossModConfig.SERVER.spawnDay.get();
                    if (currentDay >= targetDay) {
                        if (playersInOverworld) {
                            startSequence(serverLevel);
                        } else {
                            state.pending = true;
                            state.setDirty();
                            BossMod.LOGGER.info("[BossMod] Spawn day {} reached while no player is in the Overworld; encounter marked pending.", currentDay);
                        }
                    }
                }
            }
        }

        // Post-spawn event. Runs every tick on the same server tick scheduler,
        // so titles/dialogue/sounds can never be started more than once.
        if (titleStep >= 0 && titleLevel == serverLevel) {
            tickTitleSequence();
        }

        // Boss health tracking + the one-shot 50% health event.
        tickHealthEvents(serverLevel);
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
                playSoundToAll(level, ModSounds.SAM1.get(), 3.0f, 1.0f);
                playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.8f, 1.0f);
                delayTicks = 80;
                seqPhase = 8;
                break;
            case 8:
                broadcastMessage(level, "Just wait and see.", ChatFormatting.WHITE);
                playSoundToAll(level, ModSounds.SAM2.get(), 3.0f, 1.0f);
                playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.8f, 1.0f);
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
                playSoundToAll(level, ModSounds.SAM3.get(), 3.0f, 1.0f);
                playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.8f, 1.0f);
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

    /**
     * Begins the post-spawn event: Darkness for every player in the level, then
     * the faster title sequence with interleaved dialogue and sounds. Runs on
     * the server thread via the tick scheduler.
     */
    private void startTitleSequence(ServerLevel level) {
        titleStep = 0;
        stepTick = 0;
        dialogueIndex = 0;
        dialogueTick = 0;
        titleLevel = level;

        applyDarkness(level);

        // No fade-in/out: titles snap in and stay until replaced, which feels
        // fast and unnatural while still being perfectly readable.
        ClientboundSetTitlesAnimationPacket animation =
                new ClientboundSetTitlesAnimationPacket(0, DARKNESS_TICKS, 0);
        for (ServerPlayer player : level.players()) {
            player.connection.send(animation);
        }
    }

    private void tickTitleSequence() {
        ServerLevel level = titleLevel;
        if (level == null) return;

        if (stepTick == 0) {
            sendTitleToAll(level, SPAWN_TITLES[titleStep % SPAWN_TITLES.length]);
            playStepSounds(level, titleStep);
        }

        // Revuxor dialogue runs on its own slower timer, so the chat keeps a
        // natural, deliberate pace even though the titles snap by much faster.
        if (dialogueIndex < REVUXOR_LINES.length) {
            dialogueTick++;
            if (dialogueTick >= CHAT_INTERVAL_TICKS) {
                broadcastMessage(level, "<Revuxor> " + REVUXOR_LINES[dialogueIndex], ChatFormatting.WHITE);
                dialogueIndex++;
                dialogueTick = 0;
            }
        }

        stepTick++;
        if (stepTick >= TITLE_STEP_TICKS) {
            titleStep++;
            stepTick = 0;
            if (titleStep >= TOTAL_TITLE_STEPS) {
                endTitleSequence(level);
            }
        }
    }

    /**
     * Plays the one-shot trevoga sting on the creepiest title and sparse cave
     * ambience at the sequence's beats (never constant over the dialogue).
     */
    private void playStepSounds(ServerLevel level, int step) {
        if (step % SPAWN_TITLES.length == SPAWN_TITLES.length - 1) {
            playSoundToAll(level, createTrevoSound(), 1.1f, 1.0f);
        }
        if (step % 4 == 0) {
            playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.5f, step % 8 == 0 ? 0.9f : 1.1f);
        }
    }

    private void endTitleSequence(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            player.connection.send(new ClientboundClearTitlesPacket(true));
            player.removeEffect(MobEffects.DARKNESS);
        }
        titleStep = -1;
        stepTick = 0;
        dialogueIndex = 0;
        dialogueTick = 0;
        titleLevel = null;
    }

    private static void applyDarkness(ServerLevel level) {
        // ambient=false, visible=false (no particles), showIcon=false.
        MobEffectInstance darkness =
                new MobEffectInstance(MobEffects.DARKNESS, DARKNESS_TICKS, 0, false, false, false);
        for (ServerPlayer player : level.players()) {
            player.addEffect(darkness);
        }
    }

    private static void sendTitleToAll(ServerLevel level, String text) {
        for (ServerPlayer player : level.players()) {
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(text)));
        }
    }

    private static void sendTitleToAll(ServerLevel level, String text, ChatFormatting color) {
        for (ServerPlayer player : level.players()) {
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal(text).withStyle(color)));
        }
    }

    private static void broadcastMessage(ServerLevel level, String text, ChatFormatting color) {
        Component msg = Component.literal(text).withStyle(color);
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(msg);
        }
    }

    private static void playSoundToAll(ServerLevel level, SoundEvent sound, float volume, float pitch) {
        for (ServerPlayer player : level.players()) {
            player.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
        }
    }

    /**
     * Client-side equivalent of the "/stopsound" command with no source or
     * location arguments: the {@link ClientboundStopSoundPacket} is sent with
     * both the source AND the sound id null, which makes it stop every sound
     * currently playing on each client. Used at the start of the boss death
     * sequence to silence any still-running ambience (e.g. the looping
     * nullishereloop) before the death sting starts, so everything plays in
     * the exact requested order: stop first, then integritydies exactly once.
     */
    private static void stopSoundToAll(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            player.connection.send(new ClientboundStopSoundPacket((ResourceLocation) null, (SoundSource) null));
        }
    }

    /**
     * Starts the continuous {@code nullishereloop} ambience for every present
     * player. Intentionally invoked only once per spawn; the sound's own
     * asset is responsible for looping, and we never re-trigger it on ticks.
     */
    private static void playLoopSoundToAll(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            player.playNotifySound(createLoopSound(), SoundSource.MASTER, 0.6f, 1.0f);
        }
    }

    /**
     * The host mod ships these sounds in its own assets; creating the events by
     * location means we never bundle extra assets and the calls are harmless
     * no-ops if a sound is absent. {@code trevoga} is a one-shot sting, while
     * {@code nullishereloop} is the continuous ambience started once at spawn;
     * its looping comes from the sound asset itself (we never re-send it).
     */
    private static SoundEvent createTrevoSound() {
        return SoundEvent.createVariableRangeEvent(TREVOGA);
    }

    private static SoundEvent createLoopSound() {
        return SoundEvent.createVariableRangeEvent(NULL_IS_HERE_LOOP);
    }

    private void spawnBoss(ServerLevel level, BossSpawnState state) {
        ResourceLocation entityId = BOSS_ENTITY_ID;
        Optional<EntityType<?>> maybeType = Optional.ofNullable(ForgeRegistries.ENTITY_TYPES.getValue(entityId));

        if (maybeType.isEmpty()) {
            BossMod.LOGGER.warn("[BossMod] Entity type '{}' not found. Is The Broken Script installed?", entityId);
            state.hasSpawned = true;
            state.setDirty();
            return;
        }

        EntityType<?> type = maybeType.get();
        BlockPos spawnPos = findSurfaceSpawnPos(level);

        Entity entity = type.create(level);
        if (entity == null) {
            BossMod.LOGGER.error("[BossMod] Failed to create entity instance for '{}'.", entityId);
            return;
        }

        entity.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f);
        boolean added = level.addFreshEntity(entity);

        if (added) {
            BossMod.LOGGER.info("[BossMod] Spawned '{}' at {} on day {}.",
                    entityId, spawnPos, level.getDayTime() / 24_000L);
            state.hasSpawned = true;
            state.setDirty();
            startTitleSequence(level);
            // Integrity is here: play the trevoga sting, then start the
            // nullishereloop ambience exactly once per spawn (never on every
            // tick, so multiple loop copies can never stack up).
            playSoundToAll(level, createTrevoSound(), 1.2f, 1.0f);
            playLoopSoundToAll(level);
            // Track this encounter for the 50% health event (fires later, once).
            activeBossUuid = entity.getUUID();
            halfHealthTriggered = false;
            // Ask every client to run the fake technical-error sequence
            // (windowed mode + a single "HERE I AM" LWJGL alert).
            notifyClients(level, IntegrityFxPacket.KIND_BOSS_SPAWN);
        } else {
            BossMod.LOGGER.warn("[BossMod] addFreshEntity returned false for '{}'. Will retry next tick cycle.", entityId);
        }
    }

    /**
     * Sends the client-only fake technical-error trigger to every player present.
     * The common server never executes any window/LWJGL code itself; it only
     * sends the packet, and the client performs the visual effects.
     *
     * @param kind which fake-alert sequence to trigger on each client
     *             ({@code IntegrityFxPacket.KIND_*}).
     */
    private static void notifyClients(ServerLevel level, int kind) {
        for (ServerPlayer player : level.players()) {
            BossNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new IntegrityFxPacket(kind));
        }
    }

    // -------------------------------------------------------------------------
    // 50% health ("trapped/contained") event
    // -------------------------------------------------------------------------

    /**
     * Watches the Integrity boss each tick and fires the half-health event when
     * its health first drops to 50% of its maximum or below. The latch means
     * healing back above 50% can never re-trigger the event. If the boss is
     * gone without ever crossing the threshold, tracking stops silently.
     */
    private void tickHealthEvents(ServerLevel level) {
        if (activeBossUuid != null) {
            Entity boss = level.getEntity(activeBossUuid);
            if (boss == null) {
                activeBossUuid = null;
            } else if (!halfHealthTriggered
                    && boss instanceof LivingEntity living
                    && living.getHealth() <= living.getMaxHealth() * 0.5f) {
                halfHealthTriggered = true;
                startHalfHealthEvent(level);
            }
        }

        if (hp50Phase >= 0) {
            tickHalfHealthEvent(level);
        }
    }

    /**
     * Begins the 50% health cinematic: darkness + why_cant_you_leave, the three
     * deeply-pitched dread sounds, then the "YOU WILL REGRET THIS" fake alert
     * and windowed-mode transition.
     */
    private void startHalfHealthEvent(ServerLevel level) {
        hp50Phase = 0;
        hp50Tick = HP50_LEAD_TICKS;
        hp50Step = 0;
        hp50ChatIndex = 0;

        applyHalfHealthEffects(level);

        playSoundToAll(level, createTbsSound(INTEGRITY_WATCHING), 3.0f, HP50_SOUND_PITCH);
        playSoundToAll(level, createTbsSound(YOU_WILL_REGRET_THAT), 3.0f, HP50_SOUND_PITCH);
        playSoundToAll(level, createTbsSound(MOONGLITCH), 3.0f, HP50_SOUND_PITCH);

        // Fake LWJGL alert + windowed mode; the rapid titles follow right after.
        notifyClients(level, IntegrityFxPacket.KIND_HALF_HEALTH);

        // No fade-in/out: titles snap in and stay until cleared.
        ClientboundSetTitlesAnimationPacket animation =
                new ClientboundSetTitlesAnimationPacket(0, 200, 0);
        for (ServerPlayer player : level.players()) {
            player.connection.send(animation);
        }
    }

    private void tickHalfHealthEvent(ServerLevel level) {
        switch (hp50Phase) {
            case 0: // short beat after the regret alert, before the titles
                if (--hp50Tick <= 0) {
                    hp50Phase = 1;
                    hp50Tick = 0;
                }
                break;
            case 1: // rapid dark-red title sequence, 3 full passes
                if (hp50Tick == 0) {
                    sendTitleToAll(level, HP50_TITLES[hp50Step % HP50_TITLES.length], ChatFormatting.DARK_RED);
                }
                hp50Tick++;
                if (hp50Tick >= HP50_TITLE_STEP_TICKS) {
                    hp50Tick = 0;
                    hp50Step++;
                    if (hp50Step >= HP50_TITLE_STEPS) {
                        for (ServerPlayer player : level.players()) {
                            player.connection.send(new ClientboundClearTitlesPacket(true));
                        }
                        hp50Phase = 2;
                        hp50Tick = HP50_FIRST_CHAT_DELAY;
                    }
                }
                break;
            case 2: // desperate multi-voice chat, one cave sound per message
                if (--hp50Tick <= 0) {
                    playSoundToAll(level, SoundEvents.AMBIENT_CAVE.get(), 0.8f, 1.0f);
                    broadcastMessage(level, HP50_CHAT[hp50ChatIndex], ChatFormatting.WHITE);
                    hp50ChatIndex++;
                    if (hp50ChatIndex >= HP50_CHAT.length) {
                        hp50Phase = 3;
                        hp50Tick = HP50_FINAL_DELAY;
                    } else {
                        hp50Tick = HP50_CHAT_INTERVAL_TICKS;
                    }
                }
                break;
            case 3: // closing beat, then the final alert + cleanup
                if (--hp50Tick <= 0) {
                    endHalfHealthEvent(level);
                }
                break;
            default:
                hp50Phase = -1;
                break;
        }
    }

    /**
     * Cleans up the effects and fires the final "THEY CAN'T BE HELPED" alert +
     * windowed-mode transition. Runs exactly once: it is the last state of the
     * half-health state machine.
     */
    private void endHalfHealthEvent(ServerLevel level) {
        MobEffect whyCantLeave = whyCantYouLeaveEffect();
        for (ServerPlayer player : level.players()) {
            player.removeEffect(MobEffects.DARKNESS);
            if (whyCantLeave != null) {
                player.removeEffect(whyCantLeave);
            }
        }

        notifyClients(level, IntegrityFxPacket.KIND_FINAL);

        hp50Phase = -1;
        hp50Tick = 0;
        hp50Step = 0;
        hp50ChatIndex = 0;
    }

    /**
     * Applies darkness + why_cant_you_leave to every present player for the
     * length of the sequence. Both are ambient-less and particle-less, and both
     * are removed explicitly in {@link #endHalfHealthEvent(ServerLevel)}.
     */
    private static void applyHalfHealthEffects(ServerLevel level) {
        MobEffect whyCantLeave = whyCantYouLeaveEffect();
        for (ServerPlayer player : level.players()) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, HP50_EFFECT_TICKS, 0, false, false, false));
            if (whyCantLeave != null) {
                player.addEffect(new MobEffectInstance(whyCantLeave, HP50_EFFECT_TICKS, 0, false, false, false));
            }
        }
    }

    private static MobEffect whyCantYouLeaveEffect() {
        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(WHY_CANT_YOU_LEAVE_EFFECT);
        if (effect == null) {
            BossMod.LOGGER.debug("[BossMod] Effect '{}' not found; skipping.", WHY_CANT_YOU_LEAVE_EFFECT);
        }
        return effect;
    }

    private static SoundEvent createTbsSound(ResourceLocation location) {
        return SoundEvent.createVariableRangeEvent(location);
    }

    // -------------------------------------------------------------------------
    // Boss death sequence (once per encounter)
    // -------------------------------------------------------------------------

    /**
     * Fires only when the tracked Integrity boss is actually killed (a real
     * death, never a mere unload, dimension change or removal). Stops any
     * currently playing sound (the client-side equivalent of /stopsound, so
     * the lingering ambience is cut off first), then plays the death sting
     * exactly once, and asks every client to run the windowed-mode + alert
     * fake technical-error sequence.
     *
     * <p>The still-live tracked UUID is claimed (nulled) synchronously before
     * any work happens, so no number of re-fired death/handling events can ever
     * start the sequence twice during the same encounter. All window/LWJGL work
     * stays on the client (the packet is handled through DistExecutor); this
     * handler only sends packets and plays sounds, so the dedicated server
     * stays safe and the server thread is never blocked.
     */
    @SubscribeEvent
    public void onBossDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (activeBossUuid == null) return;
        LivingEntity dying = event.getEntity();
        if (!dying.getUUID().equals(activeBossUuid)) return;
        if (!(dying.level() instanceof ServerLevel serverLevel)) return;
        if (!BOSS_ENTITY_ID.equals(ForgeRegistries.ENTITY_TYPES.getKey(dying.getType()))) return;

        // Claim the sequence immediately so it can never fire twice.
        activeBossUuid = null;
        halfHealthTriggered = false;

        BossMod.LOGGER.info("[BossMod] Integrity boss defeated; starting death sequence.");
        // Step 1: stop whatever sound is currently playing on every client.
        stopSoundToAll(serverLevel);
        // Step 2: the death sting, played exactly once per death.
        playSoundToAll(serverLevel, createDeathSound(), 3.0f, 1.0f);
        notifyClients(serverLevel, IntegrityFxPacket.KIND_DEATH);
    }

    private static SoundEvent createDeathSound() {
        return SoundEvent.createVariableRangeEvent(INTEGRITY_DIES);
    }

    /**
     * Finds a solid Overworld surface position near the anchor player (or world
     * spawn) so Integrity never spawns in caves or underground. Scans outward in
     * ever-wider rings, preferring columns with the most headroom so the boss
     * does not intersect terrain. The player is never teleported.
     */
    private BlockPos findSurfaceSpawnPos(ServerLevel level) {
        ServerPlayer anchor = level.players().stream().findFirst().orElse(null);
        BlockPos start = anchor != null
                ? anchor.blockPosition()
                : level.getSharedSpawnPos();

        BlockPos best = null;
        int bestClearance = -1;

        for (int radius = 0; radius <= SURFACE_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;

                    BlockPos probe = new BlockPos(start.getX() + dx, 0, start.getZ() + dz);
                    if (!level.isLoaded(probe)) continue;

                    BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
                    if (surface.getY() <= level.getMinBuildHeight() + 1) continue;

                    // Skip columns that are under water or sitting on a pool.
                    if (!level.getFluidState(surface).isEmpty()) continue;
                    if (!level.getFluidState(surface.above()).isEmpty()) continue;

                    BlockState ground = level.getBlockState(surface);
                    BlockState below = level.getBlockState(surface.below());
                    if (ground.getCollisionShape(level, surface).isEmpty()) continue;
                    if (below.getCollisionShape(level, surface.below()).isEmpty()) continue;

                    int clearance = 0;
                    for (int i = 1; i <= SURFACE_MAX_CLEARANCE; i++) {
                        BlockState above = level.getBlockState(surface.above(i));
                        VoxelShape shape = above.getCollisionShape(level, surface.above(i));
                        if (!shape.isEmpty()) break;
                        clearance++;
                    }
                    if (clearance < SURFACE_MIN_CLEARANCE) continue;

                    if (clearance > bestClearance) {
                        bestClearance = clearance;
                        best = new BlockPos(surface.getX(), surface.getY() + 1, surface.getZ());
                    }
                }
            }
            if (best != null && bestClearance >= SURFACE_MAX_CLEARANCE) break;
        }

        if (best != null) return best;

        // Fallback: treat the anchor column's surface as good enough, else nudge
        // the original anchor position so the boss still has a place to appear.
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start);
        if (surface.getY() > level.getMinBuildHeight() + 1
                && level.getFluidState(surface.above()).isEmpty()) {
            return new BlockPos(surface.getX(), surface.getY() + 1, surface.getZ());
        }
        return new BlockPos(start.getX() + 5, Math.max(start.getY(), level.getMinBuildHeight() + 1), start.getZ() + 5);
    }

    public static class BossSpawnState extends SavedData {

        boolean hasSpawned = false;
        boolean pending = false;

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putBoolean(TAG_SPAWNED, hasSpawned);
            tag.putBoolean(TAG_PENDING, pending);
            return tag;
        }

        public static BossSpawnState load(CompoundTag tag) {
            BossSpawnState state = new BossSpawnState();
            state.hasSpawned = tag.getBoolean(TAG_SPAWNED);
            state.pending = tag.getBoolean(TAG_PENDING);
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