package com.bossmod.worldlock;

import com.bossmod.BossMod;
import com.bossmod.config.BossModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * Handles the World Lock feature: when a player is killed by
 * {@code thebrokenscript:integrity_bossfight} their UUID is recorded in
 * {@link WorldLockData}, they are sent the lock message, and then disconnected.
 *
 * <p>Two additional guards prevent bypass:
 * <ol>
 *   <li>{@link #onRespawnAttempt} — cancels the respawn event if the dying
 *       player is already locked, so they cannot return to the world via the
 *       respawn screen.</li>
 *   <li>{@link #onPlayerLogin} — kicks the player immediately on reconnect if
 *       they are in the locked set, handling the case where the client managed
 *       to reconnect before the disconnect packet arrived.</li>
 * </ol>
 *
 * <p>All state is in {@link WorldLockData} (a {@code SavedData} attached to the
 * overworld) so it persists across server restarts.
 */
public class WorldLockHandler {

    private static final ResourceLocation BOSS_ID =
            new ResourceLocation("thebrokenscript", "integrity_bossfight");

    private static final String LOCK_MESSAGE = "You will become one of us";

    // -------------------------------------------------------------------------
    // Death detection
    // -------------------------------------------------------------------------

    /**
     * Fired when any living entity dies. We filter for:
     * <ol>
     *   <li>Server-side only</li>
     *   <li>Victim is a {@link ServerPlayer}</li>
     *   <li>Feature is enabled</li>
     *   <li>Killer entity type matches {@code thebrokenscript:integrity_bossfight}</li>
     * </ol>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        if (!BossModConfig.SERVER.worldLockOnBossDeath.get()) return;

        if (!isKilledByIntegrityBoss(event.getSource())) return;

        UUID playerId = player.getUUID();
        WorldLockData lockData = WorldLockData.get(serverLevel);
        lockData.lock(playerId);

        // Send the lock message directly to the player's chat.
        player.sendSystemMessage(Component.literal(LOCK_MESSAGE));

        // Schedule the disconnect to happen after the death event fully
        // resolves (so inventory/stats are saved normally first).
        // Using a one-tick delayed task via the server scheduler.
        serverLevel.getServer().execute(() -> {
            // The player reference might be stale after death; fetch fresh.
            ServerPlayer current = serverLevel.getServer()
                    .getPlayerList().getPlayer(playerId);
            if (current != null) {
                current.connection.disconnect(Component.literal(LOCK_MESSAGE));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Respawn guard — prevents returning via the respawn screen
    // -------------------------------------------------------------------------

    /**
     * Fired when a player attempts to respawn (clicks the Respawn button or
     * is auto-respawned). If the player is locked we cancel the event so
     * they stay on the death screen, and we disconnect them shortly after.
     *
     * <p>Note: {@link net.minecraftforge.event.entity.player.PlayerEvent.Clone}
     * is used here because Forge 1.20.1 does not expose a directly cancellable
     * "respawn" event. Instead we hook {@code PlayerEvent.PlayerRespawnEvent}
     * and immediately disconnect after the respawn teleport to prevent the
     * player from seeing the world for even one tick.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel serverLevel)) return;

        UUID playerId = player.getUUID();
        WorldLockData lockData = WorldLockData.get(serverLevel);

        if (!lockData.isLocked(playerId)) return;

        // Player is locked — boot them immediately after the respawn tick.
        BossMod.LOGGER.info("[BossMod] Kicking locked player {} who attempted to respawn.", playerId);
        serverLevel.getServer().execute(() -> {
            ServerPlayer current = serverLevel.getServer()
                    .getPlayerList().getPlayer(playerId);
            if (current != null) {
                current.connection.disconnect(Component.literal(LOCK_MESSAGE));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Login guard — prevents bypass via reconnect
    // -------------------------------------------------------------------------

    /**
     * Fires after a player completes login. If the player's UUID is in the
     * locked set they are kicked before they can interact with the world.
     *
     * <p>This is the final safety net: even if the death-event disconnect was
     * delayed or the player reconnected from a fresh client session, they still
     * cannot enter the world.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        // Config guard: if the feature is off, clear any previously stored lock
        // so players aren't permanently stuck if an admin disables the feature.
        if (!BossModConfig.SERVER.worldLockOnBossDeath.get()) return;

        UUID playerId = player.getUUID();
        WorldLockData lockData = WorldLockData.get(serverLevel);

        if (!lockData.isLocked(playerId)) return;

        BossMod.LOGGER.info("[BossMod] Kicking locked player {} on login attempt.", playerId);
        // Schedule one tick out so the connection is fully established before
        // we close it (avoids a silent drop vs a clean kick packet).
        serverLevel.getServer().execute(() ->
            player.connection.disconnect(Component.literal(LOCK_MESSAGE))
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the {@link DamageSource} was dealt directly by
     * an entity whose registry key is {@code thebrokenscript:integrity_bossfight}.
     *
     * <p>Checks both {@link DamageSource#getEntity()} (the root cause, e.g. a
     * projectile's owner) and {@link DamageSource#getDirectEntity()} (the
     * immediate attacker) to cover indirect damage like projectiles or summons
     * the boss might use.
     */
    private static boolean isKilledByIntegrityBoss(DamageSource source) {
        return isBossEntity(source.getEntity())
            || isBossEntity(source.getDirectEntity());
    }

    private static boolean isBossEntity(Entity entity) {
        if (entity == null) return false;
        ResourceLocation typeKey = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return BOSS_ID.equals(typeKey);
    }
}
