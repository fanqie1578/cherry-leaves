package com.tomato.falling_cherry_petals;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.*;

@Mod(FallingCherryPetalsMod.MODID)
public class FallingCherryPetalsMod {
    public static final String MODID = "falling_cherry_petals";
    public static final Logger LOGGER = LogUtils.getLogger();

    // ====== Tunable constants ======
    private static final int SCAN_CHUNKS_PER_TICK = 3;
    private static final int TREE_SCAN_HEIGHT = 14;
    private static final int CANOPY_SCAN_HEIGHT = 16;
    private static final int CANOPY_SCAN_RADIUS = 6;
    private static final int GROUND_START_OFFSET = 12;
    private static final int GROUND_SCAN_DEPTH = 25;
    private static final int PLAYER_TREE_MIN_LOGS = 4;
    private static final int PLAYER_TREE_MIN_LEAVES = 15;
    private static final int SATURATION_RESET_CYCLES = 30;
    private static final int GROWTH_CYCLE_CHANCE = 45;
    private static final double PETAL_SKIP_CHANCE = 0.6;
    private static final double PETAL_PLACE_CHANCE = 0.25;
    private static final double PETAL_GROW_CHANCE = 0.3;

    // ====== In-game config (changeable via /cherry-leaves config) ======
    public enum FlowerAreaMode {
        DYNAMIC_CANOPY, // 随树叶大小定 (use canopy scanning + inset)
        FIXED_4x4,      // 固定范围4x4
        CIRCULAR_RADIUS // 圆形半径范围
    }
    public static FlowerAreaMode flowerMode = FlowerAreaMode.DYNAMIC_CANOPY;
    public static int canopyInset = 1;       // 留空值 0-4 (仅 DYNAMIC_CANOPY 模式)
    public static int circularRadius = 3;    // 圆形半径 (用于 CIRCULAR_RADIUS 模式)

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, MODID);
    public static final DeferredHolder<Feature<?>, Feature<?>> CHERRY_LEAF_FEATURE = FEATURES.register("cherry_leaf_pile",
            com.tomato.falling_cherry_petals.world.CherryLeafFeature::new);

    // Track cherry trees per dimension: ground-level position under each tree
    private final Map<ServerLevel, Set<BlockPos>> trackedTreePositions = new HashMap<>();
    // Track saplings that haven't grown yet
    private final Map<ServerLevel, List<BlockPos>> trackedSaplings = new HashMap<>();
    // Set of loaded chunks per dimension (deduplicated)
    private final Map<ServerLevel, Set<ChunkPos>> loadedChunks = new HashMap<>();
    // Chunks we've already scanned for trees (cleared when all chunks re-scanned)
    private final Map<ServerLevel, Set<ChunkPos>> scannedChunks = new HashMap<>();
    // Queue of unscanned chunks to avoid iterating all loaded chunks every tick
    private final Map<ServerLevel, ArrayDeque<ChunkPos>> unscannedChunkQueue = new HashMap<>();
    // Trees whose flower area is fully saturated (periodically re-checked)
    private final Map<ServerLevel, Set<BlockPos>> saturatedTrees = new HashMap<>();
    // Counter to periodically refresh saturation cache
    private final Map<ServerLevel, Integer> saturationTimer = new HashMap<>();

    private static FallingCherryPetalsMod INSTANCE;

    public FallingCherryPetalsMod(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE = this;
        modEventBus.addListener(this::commonSetup);
        FEATURES.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // Register the /cherry-leaves command
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CherryLeavesCommand.register(event.getDispatcher());
    }

    public static void reload(ServerLevel level) {
        if (INSTANCE == null) return;
        INSTANCE.scannedChunks.remove(level);
        INSTANCE.saturatedTrees.remove(level);
        INSTANCE.saturationTimer.remove(level);
        // Repopulate unscanned chunk queue from loaded chunks
        ArrayDeque<ChunkPos> queue = INSTANCE.unscannedChunkQueue.computeIfAbsent(level, k -> new ArrayDeque<>());
        queue.clear();
        Set<ChunkPos> loaded = INSTANCE.loadedChunks.get(level);
        if (loaded != null) {
            queue.addAll(loaded);
        }
        // Force immediate regeneration
        INSTANCE.scanLoadedChunks(level);
        INSTANCE.growFlowersAroundTrees(level);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Falling Cherry Petals mod loaded!");
    }

    // ====== Track planted saplings & player-built cherry trees ======
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        // Track planted saplings
        if (event.getPlacedBlock().is(Blocks.CHERRY_SAPLING)) {
            trackedSaplings.computeIfAbsent(serverLevel, k -> new ArrayList<>())
                    .add(event.getPos().immutable());
        }

        // Detect player-built cherry trees: when a cherry log is placed, check if
        // it forms a valid tree (≥4 stacked logs + ≥15 nearby leaves)
        if (event.getPlacedBlock().is(Blocks.CHERRY_LOG)) {
            checkPlayerBuiltTree(serverLevel, event.getPos().immutable());
        }
    }

    // ====== Drop petals when cherry leaves are broken ======
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        // Don't spawn items if the event was cancelled by another mod
        if (event.isCanceled()) return;
        // Don't drop items in creative mode
        if (event.getPlayer() != null && event.getPlayer().isCreative()) return;

        BlockState state = event.getState();
        if (!state.is(Blocks.CHERRY_LEAVES)) return;

        double chance = Config.PETAL_DROP_CHANCE.get();
        if (chance <= 0) return;
        if (serverLevel.random.nextDouble() >= chance) return;

        int min = Config.PETAL_DROP_MIN.get();
        int max = Config.PETAL_DROP_MAX.get();
        int count = (min >= max) ? min : min + serverLevel.random.nextInt(max - min + 1);
        if (count <= 0) return;

        BlockPos pos = event.getPos();
        ItemStack petals = new ItemStack(Blocks.PINK_PETALS.asItem(), count);
        ItemEntity itemEntity = new ItemEntity(serverLevel,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, petals);
        serverLevel.addFreshEntity(itemEntity);
    }

    // ====== Track loaded chunks to scan for naturally generated trees ======
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        ChunkPos chunkPos = event.getChunk().getPos();
        loadedChunks.computeIfAbsent(serverLevel, k -> new HashSet<>())
                .add(chunkPos);
        unscannedChunkQueue.computeIfAbsent(serverLevel, k -> new ArrayDeque<>())
                .addLast(chunkPos);
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        ChunkPos chunkPos = event.getChunk().getPos();
        Set<ChunkPos> chunks = loadedChunks.get(serverLevel);
        if (chunks != null) {
            chunks.remove(chunkPos);
        }
        Set<ChunkPos> scanned = scannedChunks.get(serverLevel);
        if (scanned != null) {
            scanned.remove(chunkPos);
        }
        // Remove from queue
        ArrayDeque<ChunkPos> queue = unscannedChunkQueue.get(serverLevel);
        if (queue != null) {
            queue.remove(chunkPos);
        }
        // Also clean tracked tree positions in this chunk
        Set<BlockPos> trees = trackedTreePositions.get(serverLevel);
        if (trees != null) {
            int cx = chunkPos.x;
            int cz = chunkPos.z;
            trees.removeIf(pos -> pos.getX() >> 4 == cx && pos.getZ() >> 4 == cz);
        }
    }

    // ====== Main accumulation logic ======
    @SubscribeEvent
    public void onWorldUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        trackedTreePositions.remove(serverLevel);
        trackedSaplings.remove(serverLevel);
        loadedChunks.remove(serverLevel);
        scannedChunks.remove(serverLevel);
        unscannedChunkQueue.remove(serverLevel);
        saturatedTrees.remove(serverLevel);
        saturationTimer.remove(serverLevel);
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        long gameTime = level.getGameTime();
        if (gameTime % 20 != 0) return; // run once per second

        // Step 1: Check saplings that may have grown into trees
        checkSaplings(serverLevel);

        // Step 2: Process loaded chunks to find naturally generated trees
        scanLoadedChunks(serverLevel);

        // Step 3: Verify tracked trees still exist (remove if destroyed)
        verifyTrees(serverLevel);

        // Step 4: Grow flowers under all tracked trees (~45s average interval)
        if (serverLevel.random.nextInt(GROWTH_CYCLE_CHANCE) == 0) {
            growFlowersAroundTrees(serverLevel);
        }
    }

    private void checkSaplings(ServerLevel level) {
        List<BlockPos> saplings = trackedSaplings.get(level);
        if (saplings == null || saplings.isEmpty()) return;

        Set<BlockPos> trees = trackedTreePositions.computeIfAbsent(level, k -> new HashSet<>());
        Iterator<BlockPos> it = saplings.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            BlockState state = level.getBlockState(pos);

            if (state.is(Blocks.CHERRY_SAPLING)) continue; // still growing

            // Sapling is no longer here — check if it grew into a tree
            // (vanilla always replaces the sapling block with a log when it grows)
            if (state.is(Blocks.CHERRY_LEAVES) || state.is(Blocks.CHERRY_LOG)) {
                // Sapling grew into a tree! Find the ground below and track it
                BlockPos groundPos = findSurfaceAt(level, pos.getX(), pos.getZ(), pos.getY());
                if (groundPos != null) {
                    trees.add(groundPos);
                }
            }
            // In all cases (grew or was broken), stop tracking this sapling position
            it.remove();
        }
    }

    private void scanLoadedChunks(ServerLevel level) {
        Set<ChunkPos> chunks = loadedChunks.get(level);
        if (chunks == null || chunks.isEmpty()) return;

        Set<BlockPos> trees = trackedTreePositions.computeIfAbsent(level, k -> new HashSet<>());
        Set<ChunkPos> scanned = scannedChunks.computeIfAbsent(level, k -> new HashSet<>());
        ArrayDeque<ChunkPos> queue = unscannedChunkQueue.computeIfAbsent(level, k -> new ArrayDeque<>());

        // Refill queue when empty (all chunks have been scanned at least once)
        if (queue.isEmpty()) {
            if (scanned.size() >= chunks.size()) {
                scanned.clear(); // start new round of scanning
            }
            for (ChunkPos cp : chunks) {
                if (!scanned.contains(cp)) {
                    queue.addLast(cp);
                }
            }
        }

        // Scan up to 3 chunks per tick, dequeuing directly
        int processed = 0;
        while (processed < SCAN_CHUNKS_PER_TICK && !queue.isEmpty()) {
            ChunkPos chunkPos = queue.pollFirst();
            // Skip chunks that were unloaded or already scanned in this round
            if (!chunks.contains(chunkPos) || scanned.contains(chunkPos)) continue;
            scanChunkForTrees(level, chunkPos, trees);
            scanned.add(chunkPos);
            processed++;
        }
    }

    private static void scanChunkForTrees(ServerLevel level, ChunkPos chunkPos, Set<BlockPos> trees) {
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
        // Scan every 2 blocks in XZ, from surface upward for leaves
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                int worldX = chunkPos.getMinBlockX() + x;
                int worldZ = chunkPos.getMinBlockZ() + z;

                BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                        new BlockPos(worldX, 0, worldZ));
                // Quick check: the heightmap returns canopy top when directly under a tree,
                // so check the surface block itself first, then scan upward
                boolean hasLeaves = level.getBlockState(surface).is(Blocks.CHERRY_LEAVES);
                if (!hasLeaves) {
                    for (int dy = 1; dy <= TREE_SCAN_HEIGHT; dy++) {
                        scanPos.set(worldX, surface.getY() + dy, worldZ);
                        if (level.getBlockState(scanPos).is(Blocks.CHERRY_LEAVES)) {
                            hasLeaves = true;
                            break;
                        }
                    }
                }
                if (hasLeaves) {
                    // Find actual ground (heightmap may return top of trunk/canopy)
                    BlockPos ground = findSurfaceAt(level, worldX, worldZ, surface.getY());
                    if (ground != null) {
                        trees.add(ground);
                    }
                }
            }
        }
    }

    private void verifyTrees(ServerLevel level) {
        Set<BlockPos> trees = trackedTreePositions.get(level);
        if (trees == null || trees.isEmpty()) return;

        trees.removeIf(pos -> {
            BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
            for (int dy = 1; dy <= TREE_SCAN_HEIGHT; dy++) {
                scanPos.set(pos.getX(), pos.getY() + dy, pos.getZ());
                BlockState state = level.getBlockState(scanPos);
                if (state.is(Blocks.CHERRY_LEAVES) || state.is(Blocks.CHERRY_LOG)) {
                    return false; // tree still exists
                }
            }
            return true; // tree is gone, remove from tracking
        });
    }

    private void growFlowersAroundTrees(ServerLevel level) {
        Set<BlockPos> trees = trackedTreePositions.get(level);
        if (trees == null || trees.isEmpty()) return;

        var random = level.random;
        Set<BlockPos> saturated = saturatedTrees.computeIfAbsent(level, k -> new HashSet<>());

        // Periodically reset saturation cache (every ~30 growth cycles)
        // Each cycle fires with ~1/45 probability per second, so wall-clock time varies.
        int timer = saturationTimer.merge(level, 1, Integer::sum);
        if (timer > SATURATION_RESET_CYCLES) {
            saturated.clear();
            saturationTimer.put(level, 0);
        }

        for (BlockPos treeBase : trees) {
            if (saturated.contains(treeBase)) continue;
            growFlowersForTree(level, treeBase, trees, random, saturated);
        }
    }

    /**
     * Calculate flower area for a single tree and place/increment flowers.
     * Extracted for reuse by periodic growth and auto-adjust verification.
     */
    private void growFlowersForTree(ServerLevel level, BlockPos treeBase, Set<BlockPos> allTrees, RandomSource random, Set<BlockPos> saturated) {
        int hintY = treeBase.getY();

        // ====== Calculate flower area based on mode ======
        int flowerMinX, flowerMaxX, flowerMinZ, flowerMaxZ;

        if (flowerMode == FlowerAreaMode.DYNAMIC_CANOPY) {
            int[] canopyBounds = findCanopyBounds(level, treeBase, allTrees);
            if (canopyBounds == null) return;
            int inset = Math.max(0, Math.min(canopyInset, 4));
            flowerMinX = canopyBounds[0] + inset;
            flowerMaxX = canopyBounds[1] - inset;
            flowerMinZ = canopyBounds[2] + inset;
            flowerMaxZ = canopyBounds[3] - inset;
        } else if (flowerMode == FlowerAreaMode.FIXED_4x4) {
            flowerMinX = treeBase.getX() - 2;
            flowerMaxX = treeBase.getX() + 2;
            flowerMinZ = treeBase.getZ() - 2;
            flowerMaxZ = treeBase.getZ() + 2;
        } else if (flowerMode == FlowerAreaMode.CIRCULAR_RADIUS) {
            int r = circularRadius;
            flowerMinX = treeBase.getX() - r;
            flowerMaxX = treeBase.getX() + r;
            flowerMinZ = treeBase.getZ() - r;
            flowerMaxZ = treeBase.getZ() + r;
        } else {
            return;
        }

        if (flowerMinX > flowerMaxX || flowerMinZ > flowerMaxZ) return;

        // If fully saturated, cache and skip
        if (isAreaSaturated(level, hintY, flowerMinX, flowerMaxX, flowerMinZ, flowerMaxZ)) {
            saturated.add(treeBase);
            return;
        }

        // Place or grow flowers within the calculated area
        for (int fx = flowerMinX; fx <= flowerMaxX; fx++) {
            for (int fz = flowerMinZ; fz <= flowerMaxZ; fz++) {
                // Circular distance check for CIRCULAR_RADIUS mode
                if (flowerMode == FlowerAreaMode.CIRCULAR_RADIUS) {
                    double dx = fx - treeBase.getX();
                    double dz = fz - treeBase.getZ();
                    if (dx * dx + dz * dz > circularRadius * circularRadius) continue;
                }

                // 60% chance to skip for natural-looking gaps
                if (random.nextDouble() < PETAL_SKIP_CHANCE) continue;

                // Find the actual ground height at this specific position
                BlockPos surface = findSurfaceAt(level, fx, fz, hintY);
                if (surface == null) continue;

                BlockPos placePos = surface.above();
                BlockState stateAtPos = level.getBlockState(placePos);

                if (stateAtPos.isAir()) {
                    if (random.nextDouble() < PETAL_PLACE_CHANCE) {
                        int amount = 1 + random.nextInt(2); // 1-2 flowers
                        BlockState petalState = Blocks.PINK_PETALS.defaultBlockState()
                                .setValue(PinkPetalsBlock.AMOUNT, amount)
                                .setValue(PinkPetalsBlock.FACING,
                                        Direction.from2DDataValue(random.nextInt(4)));
                        level.setBlock(placePos, petalState, 3);
                    }
                } else if (stateAtPos.is(Blocks.PINK_PETALS)) {
                    int current = stateAtPos.getValue(PinkPetalsBlock.AMOUNT);
                    if (current >= 4) continue; // already maxed out
                    if (random.nextDouble() < PETAL_GROW_CHANCE) {
                        level.setBlock(placePos,
                                stateAtPos.setValue(PinkPetalsBlock.AMOUNT, current + 1),
                                3);
                    }
                }
            }
        }
    }

    /**
     * Scan the cherry tree canopy above the ground position to find its XZ extent.
     * Uses Voronoi partitioning: each leaf column is assigned to the nearest tree trunk,
     * so overlapping canopies in a forest are handled correctly.
     * Returns int[]{minX, maxX, minZ, maxZ} or null if no leaves found.
     */
    private static int[] findCanopyBounds(ServerLevel level, BlockPos treeBase, Set<BlockPos> allTrees) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        boolean found = false;

        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        for (int dx = -CANOPY_SCAN_RADIUS; dx <= CANOPY_SCAN_RADIUS; dx++) {
            for (int dz = -CANOPY_SCAN_RADIUS; dz <= CANOPY_SCAN_RADIUS; dz++) {
                int worldX = treeBase.getX() + dx;
                int worldZ = treeBase.getZ() + dz;

                // Check if this column has any cherry leaves
                boolean hasLeaves = false;
                for (int dy = 1; dy <= CANOPY_SCAN_HEIGHT; dy++) {
                    scanPos.set(worldX, treeBase.getY() + dy, worldZ);
                    if (level.getBlockState(scanPos).is(Blocks.CHERRY_LEAVES)) {
                        hasLeaves = true;
                        break;
                    }
                }
                if (!hasLeaves) continue;

                // Assign this leaf column to the nearest tree trunk (Voronoi)
                if (!isNearestTree(worldX, worldZ, treeBase, allTrees)) continue;

                minX = Math.min(minX, worldX);
                maxX = Math.max(maxX, worldX);
                minZ = Math.min(minZ, worldZ);
                maxZ = Math.max(maxZ, worldZ);
                found = true;
            }
        }

        return found ? new int[]{minX, maxX, minZ, maxZ} : null;
    }

    /**
     * Returns true if this tree is the nearest tracked tree to the given position.
     */
    private static boolean isNearestTree(int x, int z, BlockPos treeBase, Set<BlockPos> allTrees) {
        double myDistSq = (x - treeBase.getX()) * (x - treeBase.getX())
                + (z - treeBase.getZ()) * (z - treeBase.getZ());

        double nearestDistSq = Double.MAX_VALUE;
        for (BlockPos other : allTrees) {
            if (other.equals(treeBase)) continue; // skip self
            double dx = x - other.getX();
            double dz = z - other.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
            }
        }

        return myDistSq < nearestDistSq;
    }

    /**
     * Check if a placed cherry log is part of a player-built cherry tree.
     * Criteria: at least 4 vertically stacked logs and at least 15 cherry leaves nearby.
     */
    private void checkPlayerBuiltTree(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        // Find the bottom of the contiguous vertical log column
        int minY = pos.getY();
        for (int y = pos.getY() - 1; y > pos.getY() - 10; y--) {
            scanPos.set(pos.getX(), y, pos.getZ());
            if (!level.getBlockState(scanPos).is(Blocks.CHERRY_LOG)) break;
            minY = y;
        }

        // Count vertical logs from bottom up
        int logCount = 0;
        for (int y = minY; y < minY + 10; y++) {
            scanPos.set(pos.getX(), y, pos.getZ());
            if (!level.getBlockState(scanPos).is(Blocks.CHERRY_LOG)) break;
            logCount++;
        }

        if (logCount < PLAYER_TREE_MIN_LOGS) return;

        // Count cherry leaves in a circular volume around the log column
        int leafCount = 0;
        int x = pos.getX();
        int z = pos.getZ();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + dz * dz > 10) continue;
                for (int dy = 0; dy < logCount + 5; dy++) {
                    scanPos.set(x + dx, minY + dy, z + dz);
                    if (level.getBlockState(scanPos).is(Blocks.CHERRY_LEAVES)) {
                        leafCount++;
                        if (leafCount >= PLAYER_TREE_MIN_LEAVES) break;
                    }
                }
                if (leafCount >= PLAYER_TREE_MIN_LEAVES) break;
            }
            if (leafCount >= PLAYER_TREE_MIN_LEAVES) break;
        }

        if (leafCount < PLAYER_TREE_MIN_LEAVES) return;

        // Register this tree for flower generation
        BlockPos groundPos = findSurfaceAt(level, x, z, minY);
        if (groundPos != null) {
            trackedTreePositions.computeIfAbsent(level, k -> new HashSet<>()).add(groundPos);
        }
    }

    private static boolean isAreaSaturated(ServerLevel level, int hintY, int minX, int maxX, int minZ, int maxZ) {
        boolean anyFlowers = false;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos surface = findSurfaceAt(level, x, z, hintY);
                if (surface == null) continue;

                BlockPos checkPos = surface.above();
                BlockState state = level.getBlockState(checkPos);
                if (!state.is(Blocks.PINK_PETALS)) {
                    if (state.isAir()) return false; // empty spot → not saturated
                    continue;                       // non-air non-flower → skip
                }

                anyFlowers = true;
                if (state.getValue(PinkPetalsBlock.AMOUNT) < 4) return false; // partial → not saturated
            }
        }
        return anyFlowers; // all positions are full or blocked
    }

    /**
     * Find the ground surface at (x, z) by scanning downward around hintY.
     * Excludes cherry logs/leaves to avoid placing flowers on tree branches.
     * Returns null if water is found or no valid ground within range.
     */
    private static BlockPos findSurfaceAt(ServerLevel level, int x, int z, int hintY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, hintY + GROUND_START_OFFSET, z);
        for (int i = 0; i < GROUND_SCAN_DEPTH; i++) {
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos) && !state.is(Blocks.CHERRY_LOG) && !state.is(Blocks.CHERRY_LEAVES)) {
                // Found valid ground — check the space above isn't water
                BlockPos above = pos.above();
                if (!level.getBlockState(above).is(Blocks.WATER)) {
                    return pos.immutable();
                }
                // Waterlogged: continue scanning downward for dry ground
            }
            pos.move(Direction.DOWN);
        }
        return null;
    }

    // ====== Verify & Auto-Adjust Feature ======

    public static class TreeVerifyResult {
        public final BlockPos treePos;
        public final boolean exists;
        public final int totalPositions;
        public final int occupiedPositions;
        public final int saturatedPositions;
        public final double coveragePercent;
        public final double saturationPercent;
        public final List<String> issues;

        public TreeVerifyResult(BlockPos treePos, boolean exists, int totalPositions,
                                int occupiedPositions, int saturatedPositions,
                                double coveragePercent, double saturationPercent,
                                List<String> issues) {
            this.treePos = treePos;
            this.exists = exists;
            this.totalPositions = totalPositions;
            this.occupiedPositions = occupiedPositions;
            this.saturatedPositions = saturatedPositions;
            this.coveragePercent = coveragePercent;
            this.saturationPercent = saturationPercent;
            this.issues = issues;
        }
    }

    public static class VerifyReport {
        public final BlockPos center;
        public final int range;
        public final double threshold;
        public final List<TreeVerifyResult> results;
        public final List<String> suggestions;

        public VerifyReport(BlockPos center, int range, double threshold,
                            List<TreeVerifyResult> results, List<String> suggestions) {
            this.center = center;
            this.range = range;
            this.threshold = threshold;
            this.results = results;
            this.suggestions = suggestions;
        }

        public int totalTrees() { return results.size(); }

        public int treesWithIssues() {
            int count = 0;
            for (TreeVerifyResult r : results) {
                if (!r.issues.isEmpty()) count++;
            }
            return count;
        }

        public double averageCoverage() {
            if (results.isEmpty()) return 0;
            double sum = 0;
            int count = 0;
            for (TreeVerifyResult r : results) {
                if (r.exists && r.totalPositions > 0) {
                    sum += r.coveragePercent;
                    count++;
                }
            }
            return count > 0 ? sum / count : 0;
        }

        public double averageSaturation() {
            if (results.isEmpty()) return 0;
            double sum = 0;
            int count = 0;
            for (TreeVerifyResult r : results) {
                if (r.exists && r.totalPositions > 0) {
                    sum += r.saturationPercent;
                    count++;
                }
            }
            return count > 0 ? sum / count : 0;
        }

        public boolean needsAdjustment() {
            return treesWithIssues() > 0 || averageCoverage() < threshold;
        }

        public List<BlockPos> problemTreePositions() {
            List<BlockPos> problems = new ArrayList<>();
            for (TreeVerifyResult r : results) {
                if (!r.issues.isEmpty()) problems.add(r.treePos);
            }
            return problems;
        }

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("§6===== §e樱花树检查报告 §6=====\n");
            sb.append("§7范围中心: §f").append(center.toShortString())
                    .append(" §7| 检查范围: §f").append(range)
                    .append(" §7| 阈值: §f").append(String.format("%.0f%%", threshold)).append("\n");
            sb.append("§7检查树木: §f").append(totalTrees())
                    .append(" §7| 异常: §f").append(treesWithIssues()).append("\n");
            double avgCov = averageCoverage();
            sb.append("§7平均覆盖率: §f").append(String.format("%.1f%%", avgCov));
            if (avgCov < threshold) {
                sb.append(" §c低于阈值!").append("\n");
            } else {
                sb.append("\n");
            }
            sb.append("§7平均饱和度: §f").append(String.format("%.1f%%", averageSaturation())).append("\n");

            if (treesWithIssues() > 0) {
                sb.append("§6--- 异常详情 ---\n");
                int shown = 0;
                for (TreeVerifyResult r : results) {
                    if (r.issues.isEmpty()) continue;
                    if (shown >= 20) {
                        sb.append("§7...以及另外 §f").append(treesWithIssues() - shown).append(" §7棵异常树\n");
                        break;
                    }
                    shown++;
                    sb.append("§c✗ §f").append(r.treePos.toShortString());
                    if (!r.exists) {
                        sb.append(" §7[树木已消失]").append("\n");
                        continue;
                    }
                    sb.append(" §7覆盖率: §f").append(String.format("%.1f%%", r.coveragePercent));
                    if (r.totalPositions > 0) {
                        sb.append(" §7(").append(r.occupiedPositions).append("/").append(r.totalPositions).append(")");
                    }
                    sb.append("\n");
                    for (String issue : r.issues) {
                        sb.append("  §7- ").append(issue).append("\n");
                    }
                }
            }

            if (!suggestions.isEmpty()) {
                sb.append("§6--- 建议 ---\n");
                for (String s : suggestions) {
                    sb.append("§7- ").append(s).append("\n");
                }
            }
            sb.append("§6===============================");
            return sb.toString();
        }
    }

    /**
     * Verify flower generation for tracked trees within range of center.
     * Auto-adjusts if average coverage is below threshold.
     */
    public static VerifyReport verifyFlowerGeneration(ServerLevel level, BlockPos center, int range, double threshold) {
        if (INSTANCE == null) return null;
        return INSTANCE.verifyFlowerGenerationInternal(level, center, range, threshold);
    }

    private VerifyReport verifyFlowerGenerationInternal(ServerLevel level, BlockPos center, int range, double threshold) {
        Set<BlockPos> allTrees = trackedTreePositions.get(level);
        List<TreeVerifyResult> results = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (allTrees == null || allTrees.isEmpty()) {
            return new VerifyReport(center, range, threshold, results, suggestions);
        }

        int rangeSq = range * range;
        for (BlockPos treePos : allTrees) {
            double dx = treePos.getX() - center.getX();
            double dz = treePos.getZ() - center.getZ();
            if (dx * dx + dz * dz > rangeSq) continue;
            results.add(verifySingleTree(level, treePos, allTrees));
        }

        VerifyReport report = new VerifyReport(center, range, threshold, results, suggestions);

        if (report.needsAdjustment()) {
            autoAdjust(level, report);
        }

        return report;
    }

    private TreeVerifyResult verifySingleTree(ServerLevel level, BlockPos treePos, Set<BlockPos> allTrees) {
        List<String> issues = new ArrayList<>();
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        // Check tree existence
        boolean exists = false;
        for (int dy = 1; dy <= TREE_SCAN_HEIGHT; dy++) {
            scanPos.set(treePos.getX(), treePos.getY() + dy, treePos.getZ());
            BlockState state = level.getBlockState(scanPos);
            if (state.is(Blocks.CHERRY_LEAVES) || state.is(Blocks.CHERRY_LOG)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            return new TreeVerifyResult(treePos, false, 0, 0, 0, 0, 0, issues);
        }

        int hintY = treePos.getY();

        // Calculate flower area based on mode
        int flowerMinX, flowerMaxX, flowerMinZ, flowerMaxZ;

        if (flowerMode == FlowerAreaMode.DYNAMIC_CANOPY) {
            int[] canopyBounds = findCanopyBounds(level, treePos, allTrees);
            if (canopyBounds == null) {
                issues.add("未找到树冠 (树木可能太小)");
                return new TreeVerifyResult(treePos, true, 0, 0, 0, 0, 0, issues);
            }
            int inset = Math.max(0, Math.min(canopyInset, 4));
            flowerMinX = canopyBounds[0] + inset;
            flowerMaxX = canopyBounds[1] - inset;
            flowerMinZ = canopyBounds[2] + inset;
            flowerMaxZ = canopyBounds[3] - inset;

            int width = flowerMaxX - flowerMinX + 1;
            int depth = flowerMaxZ - flowerMinZ + 1;
            if (width < 2 || depth < 2) {
                issues.add("树冠范围过小 (" + width + "x" + depth + ")，考虑减小留空值");
            }
        } else if (flowerMode == FlowerAreaMode.FIXED_4x4) {
            flowerMinX = treePos.getX() - 2;
            flowerMaxX = treePos.getX() + 2;
            flowerMinZ = treePos.getZ() - 2;
            flowerMaxZ = treePos.getZ() + 2;
        } else if (flowerMode == FlowerAreaMode.CIRCULAR_RADIUS) {
            int r = circularRadius;
            flowerMinX = treePos.getX() - r;
            flowerMaxX = treePos.getX() + r;
            flowerMinZ = treePos.getZ() - r;
            flowerMaxZ = treePos.getZ() + r;
        } else {
            issues.add("未知的花簇生成模式");
            return new TreeVerifyResult(treePos, true, 0, 0, 0, 0, 0, issues);
        }

        if (flowerMinX > flowerMaxX || flowerMinZ > flowerMaxZ) {
            issues.add("花簇生成范围无效 (留空值过大?)");
            return new TreeVerifyResult(treePos, true, 0, 0, 0, 0, 0, issues);
        }

        // Scan all positions in flower area
        int totalPositions = 0;
        int occupiedPositions = 0;
        int saturatedPositions = 0;
        int blockedPositions = 0;

        for (int fx = flowerMinX; fx <= flowerMaxX; fx++) {
            for (int fz = flowerMinZ; fz <= flowerMaxZ; fz++) {
                if (flowerMode == FlowerAreaMode.CIRCULAR_RADIUS) {
                    double dx = fx - treePos.getX();
                    double dz = fz - treePos.getZ();
                    if (dx * dx + dz * dz > circularRadius * circularRadius) continue;
                }

                BlockPos surface = findSurfaceAt(level, fx, fz, hintY);
                if (surface == null) continue;

                totalPositions++;
                BlockPos above = surface.above();
                BlockState state = level.getBlockState(above);

                if (state.is(Blocks.PINK_PETALS)) {
                    occupiedPositions++;
                    if (state.getValue(PinkPetalsBlock.AMOUNT) >= 4) {
                        saturatedPositions++;
                    }
                } else if (!state.isAir()) {
                    blockedPositions++;
                }
            }
        }

        double coveragePercent = totalPositions > 0 ? (double) occupiedPositions / totalPositions * 100 : 0;
        double saturationPercent = totalPositions > 0 ? (double) saturatedPositions / totalPositions * 100 : 0;

        // Issue detection heuristics
        if (totalPositions == 0) {
            issues.add("未找到有效地面位置");
        } else {
            if (coveragePercent < 30) {
                issues.add(String.format("覆盖率过低 (%.1f%%)", coveragePercent));
            }
            if (blockedPositions > totalPositions * 0.5) {
                issues.add("大量位置被其他方块阻挡 (" + blockedPositions + "/" + totalPositions + ")");
            }
            if (occupiedPositions > 0 && saturatedPositions == 0 && occupiedPositions >= totalPositions * 0.8) {
                issues.add("花朵已存在但无法生长到最大值");
            }
        }

        return new TreeVerifyResult(treePos, true, totalPositions, occupiedPositions,
                saturatedPositions, coveragePercent, saturationPercent, issues);
    }

    private void autoAdjust(ServerLevel level, VerifyReport report) {
        Set<BlockPos> saturated = saturatedTrees.get(level);
        if (saturated != null) {
            saturated.removeAll(report.problemTreePositions());
        }

        saturationTimer.put(level, 0);

        Set<BlockPos> allTrees = trackedTreePositions.get(level);
        if (allTrees == null) return;

        // Re-scan chunks containing problem trees and force regrowth
        Set<ChunkPos> scanned = scannedChunks.get(level);
        for (BlockPos problemPos : report.problemTreePositions()) {
            ChunkPos chunkPos = new ChunkPos(problemPos.getX() >> 4, problemPos.getZ() >> 4);
            if (scanned != null) {
                scanned.remove(chunkPos);
            }
        }

        // Force immediate regrowth for problem trees
        for (TreeVerifyResult r : report.results) {
            if (!r.issues.isEmpty() && r.exists) {
                Set<BlockPos> sat = saturatedTrees.computeIfAbsent(level, k -> new HashSet<>());
                growFlowersForTree(level, r.treePos, allTrees, level.random, sat);
            }
        }

        // Generate suggestions
        if (flowerMode == FlowerAreaMode.DYNAMIC_CANOPY && canopyInset > 1) {
            report.suggestions.add("尝试减小留空值: /cherry-leaves config inset " + (canopyInset - 1));
        }
        if (flowerMode == FlowerAreaMode.DYNAMIC_CANOPY && canopyInset > 0) {
            report.suggestions.add("尝试将留空设为0: /cherry-leaves config inset 0");
        }
        if (flowerMode == FlowerAreaMode.FIXED_4x4) {
            report.suggestions.add("尝试切换为 DYNAMIC_CANOPY 模式以适配树冠大小");
        }
        if (flowerMode == FlowerAreaMode.CIRCULAR_RADIUS && circularRadius < 6) {
            report.suggestions.add("尝试增大半径: /cherry-leaves config radius " + (circularRadius + 1));
        }
        report.suggestions.add("尝试手动重置: /cherry-leaves reload");
    }
}
