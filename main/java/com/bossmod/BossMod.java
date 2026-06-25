package com.bossmod;

import com.bossmod.config.BossModConfig;
import com.bossmod.events.BossSpawnHandler;
import com.bossmod.sounds.ModSounds;
import com.bossmod.worldlock.WorldLockHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BossMod.MOD_ID)
public class BossMod {

    public static final String MOD_ID = "integrity_boss_spawn";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public BossMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModSounds.SOUND_EVENTS.register(modEventBus);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BossModConfig.SERVER_SPEC, "integrity_boss_spawn-server.toml");

        MinecraftForge.EVENT_BUS.register(new BossSpawnHandler());
        MinecraftForge.EVENT_BUS.register(new WorldLockHandler());

        LOGGER.info("[{}] Initialized. Boss will spawn on day {} (configurable).",
                MOD_ID, BossModConfig.SERVER.spawnDay.get());
    }
}
