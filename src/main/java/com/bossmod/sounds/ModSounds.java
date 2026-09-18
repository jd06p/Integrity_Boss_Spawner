package com.bossmod.sounds;

import com.bossmod.BossMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BossMod.MOD_ID);

    public static final RegistryObject<SoundEvent> SAM1 = register("sam1");
    public static final RegistryObject<SoundEvent> SAM2 = register("sam2");
    public static final RegistryObject<SoundEvent> SAM3 = register("sam3");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(BossMod.MOD_ID, name)));
    }
}
