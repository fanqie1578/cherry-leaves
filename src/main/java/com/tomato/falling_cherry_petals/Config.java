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

    // Flower area settings (persisted across restarts)
    public static final ModConfigSpec.EnumValue<FallingCherryPetalsMod.FlowerAreaMode> FLOWER_MODE = BUILDER
            .comment("Flower generation mode: DYNAMIC_CANOPY, FIXED_4x4, or CIRCULAR_RADIUS",
                    "Default: DYNAMIC_CANOPY")
            .defineEnum("flowerMode", FallingCherryPetalsMod.FlowerAreaMode.DYNAMIC_CANOPY);

    public static final ModConfigSpec.IntValue CANOPY_INSET = BUILDER
            .comment("Canopy inset (empty margin) used only in DYNAMIC_CANOPY mode (0-4)",
                    "Default: 1")
            .defineInRange("canopyInset", 1, 0, 4);

    public static final ModConfigSpec.IntValue CIRCULAR_RADIUS = BUILDER
            .comment("Radius used only in CIRCULAR_RADIUS mode (2-6)",
                    "Default: 3")
            .defineInRange("circularRadius", 3, 2, 6);

    static final ModConfigSpec SPEC = BUILDER.build();
}
