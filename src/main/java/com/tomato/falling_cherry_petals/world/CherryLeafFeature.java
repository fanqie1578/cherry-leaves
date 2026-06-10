package com.tomato.falling_cherry_petals.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public class CherryLeafFeature extends Feature<NoneFeatureConfiguration> {
    public CherryLeafFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();

        boolean placed = false;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                double distSq = x * x + z * z;
                if (distSq > 10) continue;

                mutable.set(origin.getX() + x, origin.getY(), origin.getZ() + z);
                BlockPos groundPos = findGround(level, mutable, origin.getY());
                if (groundPos != null) {
                    BlockPos above = groundPos.above();
                    if (level.isEmptyBlock(above)) {
                        double chance = 0.35 + random.nextDouble() * 0.3;
                        if (random.nextDouble() < chance) {
                            int amount = 1 + random.nextInt(3); // 1-3 flowers per placement
                            BlockState petalState = Blocks.PINK_PETALS.defaultBlockState()
                                    .setValue(PinkPetalsBlock.AMOUNT, amount)
                                    .setValue(PinkPetalsBlock.FACING, Direction.from2DDataValue(random.nextInt(4)));
                            level.setBlock(above, petalState, 3);
                            placed = true;
                        }
                    }
                }
            }
        }

        return placed;
    }

    private static BlockPos findGround(WorldGenLevel level, BlockPos.MutableBlockPos pos, int startY) {
        for (int y = startY - 1; y > startY - 8; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }
}
