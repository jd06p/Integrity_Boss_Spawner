package com.bossmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client-bound trigger sent by the server for the fake technical-error
 * sequences. Carries only a primitive {@code kind} selector; the common side
 * contains no client classes, so a dedicated server can send it freely. The
 * client-only work is deferred through DistExecutor and is never resolved on a
 * dedicated server.
 */
public class IntegrityFxPacket {

    /** Width of the "KIND_*" field, shared by server (sender) and client (trigger). */
    public static final int KIND_BOSS_SPAWN = 0;
    /** Half-health event: "YOU WILL REGRET THIS" alert + windowed mode. */
    public static final int KIND_HALF_HEALTH = 1;
    /** End of the half-health event: "THEY CAN'T BE HELPED" alert + windowed mode. */
    public static final int KIND_FINAL = 2;
    /** Boss death: windowed mode + the sequential 12-alert death sequence. */
    public static final int KIND_DEATH = 3;

    private final int kind;

    public IntegrityFxPacket() {
        this.kind = KIND_BOSS_SPAWN;
    }

    public IntegrityFxPacket(int kind) {
        this.kind = kind;
    }

    public IntegrityFxPacket(FriendlyByteBuf buf) {
        this.kind = buf.readInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(this.kind);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            if (ctx.getDirection().getReceptionSide().isClient()) {
                int kind = this.kind;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> com.bossmod.client.IntegrityWindowFx.trigger(kind));
            }
        });
        ctx.setPacketHandled(true);
    }
}