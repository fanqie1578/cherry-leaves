package com.tomato.falling_cherry_petals;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Petal drop settings
    public static final ModConfigSpec.DoubleValue PETAL_DROP_CHANCE = BUILDER
            .comment("Chance for cherry leaves to drop petals when broken (0.0 = never, 1.0 = always)",
                    "Default: 1.0 (always drop)")
            .defineInRange("petalDropChance", 1.0, 0.0, 1.0);

    public static final ModConfigSpec.IntValue PETAL_DROP_MIN = BUILDER
            .comment("Minimum number of petals dropped from cherry leaves",
                    "Default: 1")
            .defineInRange("petalDropMin", 1, 0, 4);

    public static final ModConfigSpec.IntValue PETAL_DROP_MAX = BUILDER
            .comment("Maximum number of petals dropped from cherry leaves",
                    "Default: 2")
            .defineInRange("petalDropMax", 2, 0, 4);

    // Visual settings
    public static final ModConfigSpec.BooleanValue ENABLE_PARTICLE_EFFECTS = BUILDER
            .comment("Whether to show particle effects when petals fall",
                    "Default: true")
            .define("enableParticleEffects", true);

    public static final ModConfigSpec.BooleanValue ENABLE_SOUND_EFFECTS = BUILDER
            .comment("Whether to play sound effects when interacting with petals",
                    "Default: true")
            .define("enableSoundEffects", true);

    static final ModConfigSpec SPEC = BUILDER.build();
}
