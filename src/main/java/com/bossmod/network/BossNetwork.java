package com.bossmod.network;

import com.bossmod.BossMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class BossNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BossMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        CHANNEL.registerMessage(
                0,
                IntegrityFxPacket.class,
                IntegrityFxPacket::encode,
                IntegrityFxPacket::new,
                IntegrityFxPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }
}