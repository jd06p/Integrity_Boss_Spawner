package com.bossmod.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class BossModConfig {

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        ForgeConfigSpec.Builder serverBuilder = new ForgeConfigSpec.Builder();
        SERVER = new Server(serverBuilder);
        SERVER_SPEC = serverBuilder.build();
    }

    public static class Server {

        public final ForgeConfigSpec.IntValue spawnDay;
        public final ForgeConfigSpec.BooleanValue worldLockOnBossDeath;

        public Server(ForgeConfigSpec.Builder builder) {
            builder.comment("Integrity Spawner Settings <o>")
                   .push("boss_spawn");

            spawnDay = builder
                    .comment(
                        "The in-game day on which Integrity will be summoned.",
                        "Integrity spawns once per world. Changing this value after Integrity has already",
                        "spawned will NOT trigger a second spawn unless you reset the world data.",
                        "Range: 1 – 666  |  Default: 66"
                    )
                    .defineInRange("spawnDay", 66, 1, 666);

            builder.pop();

            builder.comment("Ban on death")
                   .push("world_lock");

            worldLockOnBossDeath = builder
                    .comment(
                        "When true, any player killed by Integrity is",
                        "permanently locked out of that world.",
                        "and cannot respawn or rejoin.",
                        "Do not die.",
                        "Default: true"
                    )
                    .define("ban_on_death", true);

            builder.pop();
        }
    }
}
