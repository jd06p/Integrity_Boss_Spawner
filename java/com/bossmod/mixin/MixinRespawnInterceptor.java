package com.bossmod.mixin;

import com.bossmod.config.BossModConfig;
import com.bossmod.worldlock.WorldLockData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the client-sent respawn packet
 * ({@code ServerboundClientCommandPacket} with action {@code PERFORM_RESPAWN})
 * before the server begins the respawn sequence.
 *
 * <h3>Why a mixin here?</h3>
 * Forge's {@code PlayerRespawnEvent} fires <em>after</em> the player has
 * already been teleported back into the world — meaning even a disconnect in
 * that event allows a single tick of world interaction.  Injecting at
 * {@code handleClientCommand} at {@code HEAD} lets us abort the respawn
 * entirely and kick the player while they are still on the death screen,
 * before any world state is mutated.
 *
 * <h3>Injection point</h3>
 * {@code ServerboundClientCommandPacket.Action.PERFORM_RESPAWN} is handled
 * inside {@code handleClientCommand}.  We inject at {@code HEAD} and cancel
 * ({@code ci.cancel()}) when the player is locked, preventing the rest of the
 * method — and therefore the entire respawn sequence — from running.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinRespawnInterceptor {

    @Shadow
    public ServerPlayer player;

    /**
     * Cancels the respawn packet handling for locked players.
     *
     * <p>The method processes all {@code ServerboundClientCommandPacket}
     * actions (not just respawn); we guard with an explicit action check so
     * only the PERFORM_RESPAWN branch is affected.
     */
    @Inject(
        method = "handleClientCommand",
        at = @At("HEAD"),
        cancellable = true
    )
    private void bossmod$interceptRespawn(
            ServerboundClientCommandPacket packet,
            CallbackInfo ci
    ) {
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) return;
        if (!BossModConfig.SERVER.worldLockOnBossDeath.get()) return;
        if (!(player.level() instanceof ServerLevel serverLevel)) return;

        WorldLockData lockData = WorldLockData.get(serverLevel);
        if (!lockData.isLocked(player.getUUID())) return;

        // Player is locked — cancel the packet and disconnect them cleanly.
        ci.cancel();
        player.connection.disconnect(Component.literal("You will become one of us"));
    }
}
