package com.tomato.falling_cherry_petals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import static net.minecraft.commands.Commands.*;

public class CherryLeavesCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("cherry-leaves")
                .then(literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(literal("config")
                        .requires(src -> src.hasPermission(2))
                        // /cherry-leaves config mode <dynamic|fixed|circular>
                        .then(literal("mode")
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("dynamic");
                                            builder.suggest("fixed");
                                            builder.suggest("circular");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String mode = StringArgumentType.getString(ctx, "mode");
                                            return setMode(ctx.getSource(), mode);
                                        })))
                        // /cherry-leaves config inset <0-4>
                        .then(literal("inset")
                                .then(argument("value", IntegerArgumentType.integer(0, 4))
                                        .executes(ctx -> {
                                            int value = IntegerArgumentType.getInteger(ctx, "value");
                                            return setInset(ctx.getSource(), value);
                                        })))
                        // /cherry-leaves config radius <2-6>
                        .then(literal("radius")
                                .then(argument("value", IntegerArgumentType.integer(2, 6))
                                        .executes(ctx -> {
                                            int value = IntegerArgumentType.getInteger(ctx, "value");
                                            return setRadius(ctx.getSource(), value);
                                        })))
                        // /cherry-leaves config (show current)
                        .executes(ctx -> showConfig(ctx.getSource())))
                // /cherry-leaves verify [range] [threshold]
                .then(literal("verify")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> verify(ctx.getSource(), 32, 70))
                        .then(argument("range", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> verify(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "range"), 70))
                                .then(argument("threshold", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> verify(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "range"),
                                                IntegerArgumentType.getInteger(ctx, "threshold")))))));
    }

    private static int reload(CommandSourceStack source) {
        if (source.getLevel() instanceof ServerLevel serverLevel) {
            FallingCherryPetalsMod.reload(serverLevel);
            source.sendSuccess(() -> Component.literal("§a✔ 缓存已清空，花簇正在重新生成..."), true);
            return 1;
        }
        return 0;
    }

    private static int setMode(CommandSourceStack source, String mode) {
        FallingCherryPetalsMod.FlowerAreaMode newMode;
        String displayName;

        switch (mode.toLowerCase()) {
            case "dynamic" -> {
                newMode = FallingCherryPetalsMod.FlowerAreaMode.DYNAMIC_CANOPY;
                displayName = "随树叶大小定 (DYNAMIC_CANOPY)";
            }
            case "fixed" -> {
                newMode = FallingCherryPetalsMod.FlowerAreaMode.FIXED_4x4;
                displayName = "固定范围4x4 (FIXED_4x4)";
            }
            case "circular" -> {
                newMode = FallingCherryPetalsMod.FlowerAreaMode.CIRCULAR_RADIUS;
                displayName = "圆形半径范围 (CIRCULAR_RADIUS)";
            }
            default -> {
                source.sendFailure(Component.literal("§c未知模式！可用: dynamic, fixed, circular"));
                return 0;
            }
        }

        FallingCherryPetalsMod.flowerMode = newMode;
        source.sendSuccess(() -> Component.literal("§a✔ 花簇生成模式已切换为: §e" + displayName), true);
        return 1;
    }

    private static int setInset(CommandSourceStack source, int value) {
        FallingCherryPetalsMod.canopyInset = value;
        source.sendSuccess(() -> Component.literal("§a✔ 樱花留空值已设为: §e" + value + " 格"), true);
        return 1;
    }

    private static int setRadius(CommandSourceStack source, int value) {
        FallingCherryPetalsMod.circularRadius = value;
        source.sendSuccess(() -> Component.literal("§a✔ 圆形半径已设为: §e" + value + " 格"), true);
        return 1;
    }

    private static int showConfig(CommandSourceStack source) {
        String modeDisplay;
        switch (FallingCherryPetalsMod.flowerMode) {
            case DYNAMIC_CANOPY -> modeDisplay = "随树叶大小定 (留空=" + FallingCherryPetalsMod.canopyInset + ")";
            case FIXED_4x4 -> modeDisplay = "固定范围4x4";
            case CIRCULAR_RADIUS -> modeDisplay = "圆形半径 (半径=" + FallingCherryPetalsMod.circularRadius + ")";
            default -> modeDisplay = "未知";
        }

        String info = """
                §6===== §eCherry Leaves Config §6=====
                §7花簇生成模式: §f%s
                §7樱花留空值:   §f%d (仅 DYNAMIC_CANOPY 模式生效)
                §7圆形半径:     §f%d (仅 CIRCULAR_RADIUS 模式生效)
                §6===============================
                """.formatted(modeDisplay, FallingCherryPetalsMod.canopyInset, FallingCherryPetalsMod.circularRadius);

        source.sendSuccess(() -> Component.literal(info), false);
        return 1;
    }

    private static int verify(CommandSourceStack source, int range, int threshold) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("§c该命令只能在服务器世界使用"));
            return 0;
        }

        BlockPos center;
        if (source.getEntity() != null) {
            center = BlockPos.containing(source.getPosition());
        } else {
            center = serverLevel.getSharedSpawnPos();
        }

        source.sendSuccess(() -> Component.literal("§7正在检查 " + range + " 格范围内的樱花树花簇生成情况..."), false);

        FallingCherryPetalsMod.VerifyReport report = FallingCherryPetalsMod.verifyFlowerGeneration(serverLevel, center, range, threshold);
        if (report == null) {
            source.sendFailure(Component.literal("§c模组尚未初始化完成，请稍后再试"));
            return 0;
        }

        if (report.totalTrees() == 0) {
            source.sendSuccess(() -> Component.literal("§e范围内未找到已跟踪的樱花树，请确认树木已被识别"), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(report.format()), false);

        if (report.needsAdjustment()) {
            source.sendSuccess(() -> Component.literal("§a✔ 已自动调整异常树木的花簇生成"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§a✔ 所有树木花簇生成正常"), false);
        }

        return 1;
    }
}
