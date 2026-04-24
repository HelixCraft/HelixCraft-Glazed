package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HoleTunnelStairsESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHParams  = settings.createGroup("Hole Parameters");
    private final SettingGroup sgTParams  = settings.createGroup("Tunnel Parameters");
    private final SettingGroup sgSParams  = settings.createGroup("Stairs Parameters");
    private final SettingGroup sgCParams  = settings.createGroup("Covered Hole Parameters");
    private final SettingGroup sgRender   = settings.createGroup("Rendering");

    private final Setting<DetectionMode> detectionMode = sgGeneral.add(new EnumSetting.Builder<DetectionMode>()
        .name("Detection Mode")
        .description("Choose what to detect: holes, tunnels, stairs, or all.")
        .defaultValue(DetectionMode.ALL)
        .build()
    );
    private final Setting<Integer> maxChunks = sgGeneral.add(new IntSetting.Builder()
        .name("Chunks to process/tick")
        .description("Amount of chunks to process per tick.")
        .defaultValue(10).min(1).sliderRange(1, 100)
        .build()
    );
    private final Setting<Boolean> airBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("Detect only Air blocks as passable.")
        .description("Only marks tunnels/holes if their blocks are air rather than merely passable.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> undergroundUpdateThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("Underground Update Threshold")
        .description("Amount of incoming block updates below Y=0 required to trigger an underground rescan of that chunk.")
        .defaultValue(250).min(10).sliderMax(2000)
        .build()
    );
    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("Detection Y Minimum Offset")
        .description("Scans blocks at or above this many blocks from the minimum build limit.")
        .min(0).sliderRange(0, 319).defaultValue(0)
        .build()
    );
    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("Detection Y Maximum Offset")
        .description("Scans blocks at or below this many blocks from the maximum build limit.")
        .min(0).sliderRange(0, 319).defaultValue(0)
        .build()
    );

    private final Setting<Integer> minHoleDepth = sgHParams.add(new IntSetting.Builder()
        .name("Min Hole Depth")
        .description("Minimum depth for a hole to be detected.")
        .defaultValue(4).min(1).sliderMax(20)
        .build()
    );

    private final Setting<Integer> minTunnelLength = sgTParams.add(new IntSetting.Builder()
        .name("Min Tunnel Length")
        .description("Minimum length for a tunnel to be detected.")
        .defaultValue(6).min(1).sliderMax(20)
        .build()
    );
    private final Setting<Integer> minTunnelHeight = sgTParams.add(new IntSetting.Builder()
        .name("Min Tunnel Height")
        .description("Minimum height of tunnels to detect.")
        .defaultValue(1).min(1).sliderMax(10)
        .build()
    );
    private final Setting<Integer> maxTunnelHeight = sgTParams.add(new IntSetting.Builder()
        .name("Max Tunnel Height")
        .description("Maximum height of tunnels to detect.")
        .defaultValue(3).min(2).sliderMax(10)
        .build()
    );
    private final Setting<Integer> minTunnelWidth = sgTParams.add(new IntSetting.Builder()
        .name("Min Tunnel Width")
        .description("Minimum width of straight tunnels to detect.")
        .defaultValue(1).min(1).sliderMax(10)
        .build()
    );
    private final Setting<Integer> maxTunnelWidth = sgTParams.add(new IntSetting.Builder()
        .name("Max Tunnel Width")
        .description("Maximum width of straight tunnels to detect.")
        .defaultValue(3).min(1).sliderMax(10)
        .build()
    );
    private final Setting<Boolean> diagonals = sgTParams.add(new BoolSetting.Builder()
        .name("Detect Diagonal Tunnels.")
        .description("Detects diagonal tunnels when tunnels are selected.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> minDiagonalLength = sgTParams.add(new IntSetting.Builder()
        .name("Min Diagonal Tunnel Length")
        .description("Minimum length for diagonal tunnels to be detected.")
        .defaultValue(6).min(1).sliderMax(20)
        .visible(diagonals::get)
        .build()
    );
    private final Setting<Integer> minDiagonalWidth = sgTParams.add(new IntSetting.Builder()
        .name("Min Diagonal Tunnel Width")
        .description("Minimum width for diagonal tunnels to be detected.")
        .defaultValue(1).min(1).sliderMax(10)
        .visible(diagonals::get)
        .build()
    );
    private final Setting<Integer> maxDiagonalWidth = sgTParams.add(new IntSetting.Builder()
        .name("Max Diagonal Tunnel Width")
        .description("Maximum width for diagonal tunnels to be detected.")
        .defaultValue(3).min(1).sliderMax(10)
        .visible(diagonals::get)
        .build()
    );

    private final Setting<Integer> minStaircaseLength = sgSParams.add(new IntSetting.Builder()
        .name("Min Staircase Length")
        .description("Minimum length for a staircase to be detected.")
        .defaultValue(3).min(1).sliderMax(20)
        .build()
    );
    private final Setting<Integer> minStaircaseHeight = sgSParams.add(new IntSetting.Builder()
        .name("Min Staircase Height")
        .description("Minimum height of the staircase to be detected.")
        .defaultValue(3).min(2).sliderMax(10)
        .build()
    );
    private final Setting<Integer> maxStaircaseHeight = sgSParams.add(new IntSetting.Builder()
        .name("Max Staircase Height")
        .description("Maximum height of the staircase to be detected.")
        .defaultValue(5).min(2).sliderMax(10)
        .build()
    );

    private final Setting<Boolean> detectCoveredHoles = sgCParams.add(new BoolSetting.Builder()
        .name("detect-covered-holes")
        .description("Detects and highlights holes that are covered by solid blocks.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> chatNotifications = sgCParams.add(new BoolSetting.Builder()
        .name("chat-notifications")
        .description("Send chat messages when covered holes are found.")
        .defaultValue(true)
        .visible(detectCoveredHoles::get)
        .build()
    );
    private final Setting<Boolean> onlyPlayerCovered = sgCParams.add(new BoolSetting.Builder()
        .name("only-player-covered")
        .description("Only detect holes that appear to be intentionally covered (e.g., by common building blocks).")
        .defaultValue(true)
        .visible(detectCoveredHoles::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> holeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("1x1-hole-line-color").defaultValue(new SettingColor(255, 0, 0, 95)).build());
    private final Setting<SettingColor> holeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("1x1-hole-side-color").defaultValue(new SettingColor(255, 0, 0, 30)).build());
    private final Setting<SettingColor> hole3x1LineColor = sgRender.add(new ColorSetting.Builder()
        .name("3x1-hole-line-color").defaultValue(new SettingColor(255, 165, 0, 95)).build());
    private final Setting<SettingColor> hole3x1SideColor = sgRender.add(new ColorSetting.Builder()
        .name("3x1-hole-side-color").defaultValue(new SettingColor(255, 165, 0, 30)).build());
    private final Setting<SettingColor> tunnelLineColor = sgRender.add(new ColorSetting.Builder()
        .name("tunnel-line-color").defaultValue(new SettingColor(0, 0, 255, 95)).build());
    private final Setting<SettingColor> tunnelSideColor = sgRender.add(new ColorSetting.Builder()
        .name("tunnel-side-color").defaultValue(new SettingColor(0, 0, 255, 30)).build());
    private final Setting<SettingColor> staircaseLineColor = sgRender.add(new ColorSetting.Builder()
        .name("staircase-line-color").defaultValue(new SettingColor(255, 0, 255, 95)).build());
    private final Setting<SettingColor> staircaseSideColor = sgRender.add(new ColorSetting.Builder()
        .name("staircase-side-color").defaultValue(new SettingColor(255, 0, 255, 30)).build());
    private final Setting<SettingColor> coveredHoleLineColor = sgCParams.add(new ColorSetting.Builder()
        .name("covered-hole-line-color").defaultValue(new SettingColor(255, 165, 0, 255))
        .visible(detectCoveredHoles::get).build());
    private final Setting<SettingColor> coveredHoleSideColor = sgCParams.add(new ColorSetting.Builder()
        .name("covered-hole-side-color").defaultValue(new SettingColor(255, 165, 0, 50))
        .visible(detectCoveredHoles::get).build());

    private static final Direction[] DIRECTIONS = { Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH };
    private static final Direction[] CANONICAL_TUNNEL_DIRS = { Direction.EAST, Direction.SOUTH };

    // ── Chunk tracking ────────────────────────────────────────────────────────
    private final Long2ObjectMap<TChunk> chunks     = new Long2ObjectOpenHashMap<>();
    private final Queue<Chunk>           chunkQueue = new LinkedList<>();

    // ── Detection result stores ───────────────────────────────────────────────
    private final Set<Box> holes      = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Box> staircases = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Box> holes3x1   = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<Box, CoveredHoleInfo> coveredHoles = new ConcurrentHashMap<>();
    private final Set<Box> notifiedHoles = ConcurrentHashMap.newKeySet();

    // Tunnels: hash → Box.  Using ConcurrentHashMap lets us call merge() to
    // *atomically extend* an existing box when a new underground chunk reveals
    // that the tunnel continues further — no delete-and-re-add, no flash.
    // Diagonal tunnel boxes are stored here too via putIfAbsent (they don't extend).
    private final ConcurrentHashMap<Long, Box> tunnelBoxMap = new ConcurrentHashMap<>();

    // NEW: Separate storage for diagonal tunnels with neighbor info
    private final ConcurrentHashMap<Long, DiagonalTunnelBox> diagonalTunnelBoxMap = new ConcurrentHashMap<>();

    private final Set<Long> holeHashes      = ConcurrentHashMap.newKeySet();
    private final Set<Long> hole3x1Hashes   = ConcurrentHashMap.newKeySet();
    private final Set<Long> staircaseHashes = ConcurrentHashMap.newKeySet();

    private final ThreadLocal<BitSet> visitedBlocksLocal = ThreadLocal.withInitial(BitSet::new);

    // ── Underground rescan state ──────────────────────────────────────────────
    //
    // Two sources of "this chunk needs rescanning":
    //
    //   A) ChunkDataS2CPacket for a KNOWN chunk: the server re-sent full chunk
    //      data for a chunk we already scanned (underground reveal).  We add it
    //      directly to chunksNeedingRescan — no threshold, immediate next tick.
    //
    //   B) BlockUpdateS2CPacket for y < 0: the server sends individual block
    //      updates underground (also part of the reveal mechanism).  We batch
    //      these via a per-chunk counter; only when enough accumulate for one
    //      chunk do we add it to chunksNeedingRescan.  This avoids rescanning
    //      a chunk for every single block packet.
    //
    // Crucially we ONLY ever rescan the specific chunk(s) that received new data
    // — never their neighbours.  Neighbour tunnels are extended via tunnelBoxMap
    // .merge() (see checkTunnelOptimized) without needing a neighbour rescan.
    private final Set<Long>        chunksNeedingRescan        = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> undergroundUpdateCounts  = new ConcurrentHashMap<>();

    // ── Block-state caches (for covered-hole detection) ───────────────────────
    private final Map<BlockPos, Boolean>    solidBlockCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, BlockState> blockStateCache = new ConcurrentHashMap<>();

    // =========================================================================
    public HoleTunnelStairsESP() {
        super(GlazedAddon.esp, "hole-tunnel-stair-esp", "Finds and highlights holes, tunnels, and staircases.");
    }

    public Set<Box> getHoles() { return new HashSet<>(holes); }

    @Override
    public void onDeactivate() { clearAll(); }

    private void clearAll() {
        synchronized (chunks) { chunks.clear(); }
        chunkQueue.clear();
        holes.clear(); staircases.clear(); holes3x1.clear();
        coveredHoles.clear(); notifiedHoles.clear();
        tunnelBoxMap.clear();
        diagonalTunnelBoxMap.clear();
        holeHashes.clear(); hole3x1Hashes.clear(); staircaseHashes.clear();
        chunksNeedingRescan.clear();
        undergroundUpdateCounts.clear();
        solidBlockCache.clear();
        blockStateCache.clear();
    }

    // =========================================================================
    //  PACKET HANDLING
    // =========================================================================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            long key = ChunkPos.toLong(packet.getChunkX(), packet.getChunkZ());
            synchronized (chunks) {
                // Only react when we already processed this chunk.
                // Genuinely new chunks are handled via the normal onTick queue path.
                if (chunks.containsKey(key)) {
                    // Server re-sent data for a known chunk (underground reveal).
                    // Schedule an immediate targeted rescan next tick.
                    chunksNeedingRescan.add(key);
                }
            }

        } else if (event.packet instanceof BlockUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            if (pos.getY() < 0) {
                long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
                // Count underground block updates per chunk.  Trigger a rescan
                // only when the count crosses the configured threshold.
                int count = undergroundUpdateCounts.merge(key, 1, Integer::sum);
                if (count >= undergroundUpdateThreshold.get()) {
                    undergroundUpdateCounts.remove(key);
                    synchronized (chunks) {
                        if (chunks.containsKey(key)) {
                            chunksNeedingRescan.add(key);
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    //  TARGETED RESCAN  (only the specific chunk(s) that received new data)
    // =========================================================================

    /**
     * Processes chunks that received new underground block data.
     *
     * <p><b>Design rationale — no neighbour expansion:</b><br>
     * The old code expanded each affected chunk to a 3×3 neighbourhood, which
     * caused already-scanned neighbour chunks to lose their boxes and briefly
     * disappear (the "flash" bug).
     *
     * <p>With the new {@code tunnelBoxMap.merge()} design we don't need to touch
     * neighbours at all.  When the newly-scanned chunk B discovers a tunnel whose
     * canonical start is in neighbour chunk A, the forward walk reads world blocks
     * freely (no chunk boundary limit) and computes the full new end-position.
     * {@code merge} then atomically <em>extends</em> the existing box A had
     * registered — the old box is replaced in one step, so there is no frame
     * where it is absent.
     *
     * <p><b>What we remove before rescanning:</b>
     * <ul>
     *   <li>Hole/staircase boxes whose bounds fall inside the rescan chunk column —
     *       these are per-column structures and must be rediscovered from scratch.</li>
     *   <li>Tunnel boxes that <em>intersect</em> the rescan chunk column — they will
     *       be re-measured (and potentially extended) during the rescan.  The
     *       {@code merge} call will silently no-op if the new box is not larger.</li>
     * </ul>
     */
    private void processRescans() {
        if (chunksNeedingRescan.isEmpty()) return;

        // Snapshot and drain atomically.
        Set<Long> toProcess = new HashSet<>(chunksNeedingRescan);
        chunksNeedingRescan.removeAll(toProcess);

        // Remove hole / staircase boxes that belong to the affected chunks.
        removeBoxesInChunks(holes,      holeHashes,      toProcess);
        removeBoxesInChunks(holes3x1,   hole3x1Hashes,   toProcess);
        removeBoxesInChunks(staircases, staircaseHashes, toProcess);

        // Remove covered-hole entries for affected chunks (null-safe — no hash set needed).
        coveredHoles.keySet().removeIf(b -> intersectsChunk(b, toProcess));

        // Remove tunnel boxes that touch the affected chunk columns so they will
        // be re-measured (and extended if necessary) during the upcoming rescan.
        // tunnelBoxMap keys are the dedup hashes; removing via values() removes
        // the map entry entirely, so the hash is also freed.
        tunnelBoxMap.values().removeIf(b -> intersectsChunk(b, toProcess));
        
        // NEW: Remove diagonal tunnel boxes in affected chunks
        diagonalTunnelBoxMap.values().removeIf(dtb -> intersectsChunk(dtb.box, toProcess));

        // Remove from the chunks map.  On the next onTick pass the chunks will be
        // absent and therefore re-queued for a fresh searchChunk call.
        synchronized (chunks) {
            chunks.keySet().removeAll(toProcess);
        }
    }

    /**
     * Removes boxes from {@code boxes} that intersect any chunk in {@code chunkKeys},
     * and removes the corresponding entry from {@code hashes} (if non-null).
     */
    private void removeBoxesInChunks(Set<Box> boxes, Set<Long> hashes, Set<Long> chunkKeys) {
        boxes.removeIf(b -> {
            if (!intersectsChunk(b, chunkKeys)) return false;
            if (hashes != null)
                hashes.remove(BlockPos.asLong((int) b.minX, (int) b.minY, (int) b.minZ));
            return true;
        });
    }

    /**
     * Returns true if box {@code b} overlaps with any chunk column in {@code chunkKeys}.
     */
    private boolean intersectsChunk(Box b, Set<Long> chunkKeys) {
        int minCx = ((int) Math.floor(b.minX)) >> 4;
        int maxCx = ((int) Math.floor(b.maxX - 0.001)) >> 4;
        int minCz = ((int) Math.floor(b.minZ)) >> 4;
        int maxCz = ((int) Math.floor(b.maxZ - 0.001)) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++)
            for (int cz = minCz; cz <= maxCz; cz++)
                if (chunkKeys.contains(ChunkPos.toLong(cx, cz))) return true;
        return false;
    }

    // =========================================================================
    //  TICK / RENDER
    // =========================================================================

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Process any chunks that received new underground data first.
        processRescans();

        synchronized (chunks) {
            for (TChunk tChunk : chunks.values()) tChunk.marked = false;
            for (Chunk chunk : Utils.chunks(true)) {
                long key = ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z);
                if (chunks.containsKey(key)) chunks.get(key).marked = true;
                else if (!chunkQueue.contains(chunk)) chunkQueue.add(chunk);
            }
            processChunkQueue();
            chunks.values().removeIf(tChunk -> !tChunk.marked);
        }
        removeBoxesOutsideRenderDistance();

        // Periodically trim the block-state caches to avoid unbounded growth.
        if (solidBlockCache.size() > 10_000) solidBlockCache.clear();
        if (blockStateCache.size() > 10_000) blockStateCache.clear();
    }

    private void removeBoxesOutsideRenderDistance() {
        Set<WorldChunk> chunkSet = new HashSet<>();
        for (Chunk chunk : Utils.chunks(true))
            if (chunk instanceof WorldChunk wc) chunkSet.add(wc);
        removeBoxesOutside(holes,                chunkSet);
        removeBoxesOutside(staircases,           chunkSet);
        removeBoxesOutside(holes3x1,             chunkSet);
        removeBoxesOutside(coveredHoles.keySet(),chunkSet);
        tunnelBoxMap.values().removeIf(b -> {
            BlockPos center = new BlockPos(
                (int) Math.floor((b.minX + b.maxX) / 2),
                (int) Math.floor((b.minY + b.maxY) / 2),
                (int) Math.floor((b.minZ + b.maxZ) / 2));
            return !chunkSet.contains(mc.world.getChunk(center));
        });
        // NEW: Remove diagonal tunnels outside render distance
        diagonalTunnelBoxMap.values().removeIf(dtb -> {
            BlockPos center = new BlockPos(
                (int) Math.floor((dtb.box.minX + dtb.box.maxX) / 2),
                (int) Math.floor((dtb.box.minY + dtb.box.maxY) / 2),
                (int) Math.floor((dtb.box.minZ + dtb.box.maxZ) / 2));
            return !chunkSet.contains(mc.world.getChunk(center));
        });
    }

    private void removeBoxesOutside(Set<Box> boxSet, Set<WorldChunk> worldChunks) {
        boxSet.removeIf(box -> {
            BlockPos center = new BlockPos(
                (int) Math.floor(box.getCenter().getX()),
                (int) Math.floor(box.getCenter().getY()),
                (int) Math.floor(box.getCenter().getZ()));
            return !worldChunks.contains(mc.world.getChunk(center));
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        switch (detectionMode.get()) {
            case ALL -> {
                renderHoles(event.renderer); renderTunnels(event.renderer);
                renderStaircases(event.renderer); render3x1Holes(event.renderer);
                if (detectCoveredHoles.get()) renderCoveredHoles(event.renderer);
            }
            case HOLES_AND_TUNNELS -> {
                renderHoles(event.renderer); renderTunnels(event.renderer);
                render3x1Holes(event.renderer);
                if (detectCoveredHoles.get()) renderCoveredHoles(event.renderer);
            }
            case HOLES_AND_STAIRCASES -> {
                renderHoles(event.renderer); renderStaircases(event.renderer);
                render3x1Holes(event.renderer);
                if (detectCoveredHoles.get()) renderCoveredHoles(event.renderer);
            }
            case TUNNELS_AND_STAIRCASES -> { renderTunnels(event.renderer); renderStaircases(event.renderer); }
            case HOLES -> {
                renderHoles(event.renderer); render3x1Holes(event.renderer);
                if (detectCoveredHoles.get()) renderCoveredHoles(event.renderer);
            }
            case TUNNELS    -> renderTunnels(event.renderer);
            case STAIRCASES -> renderStaircases(event.renderer);
            case HOLES_3X1_AND_TUNNELS -> {
                renderHoles(event.renderer); render3x1Holes(event.renderer);
                renderTunnels(event.renderer);
                if (detectCoveredHoles.get()) renderCoveredHoles(event.renderer);
            }
            default -> {
                renderHoles(event.renderer); renderTunnels(event.renderer);
                renderStaircases(event.renderer); render3x1Holes(event.renderer);
                if (detectCoveredHoles.get()) renderCoveredHoles(event.renderer);
            }
        }
    }

    private void renderHoles(Renderer3D r) {
        for (Box b : holes) {
            if (detectCoveredHoles.get() && coveredHoles.containsKey(b)) continue;
            r.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, holeSideColor.get(), holeLineColor.get(), shapeMode.get(), 0);
        }
    }
    private void render3x1Holes(Renderer3D r) {
        for (Box b : holes3x1) {
            if (detectCoveredHoles.get() && coveredHoles.containsKey(b)) continue;
            r.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, hole3x1SideColor.get(), hole3x1LineColor.get(), shapeMode.get(), 0);
        }
    }
    
    private void renderTunnels(Renderer3D r) {
        // Render straight tunnels (existing behavior)
        for (Box b : tunnelBoxMap.values())
            r.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, tunnelSideColor.get(), tunnelLineColor.get(), shapeMode.get(), 0);
        
        // NEW: Render diagonal tunnels with neighbor-culling
        renderDiagonalTunnelsOptimized(r);
    }
    
    /**
     * Renders diagonal tunnels using neighbor-culling to avoid overlapping faces.
     * Only renders faces that are not shared with adjacent tunnel segments.
     */
    private void renderDiagonalTunnelsOptimized(Renderer3D r) {
        SettingColor sideColor = tunnelSideColor.get();
        SettingColor lineColor = tunnelLineColor.get();
        ShapeMode mode = shapeMode.get();
        
        for (DiagonalTunnelBox dtb : diagonalTunnelBoxMap.values()) {
            Box b = dtb.box;
            double x1 = b.minX, y1 = b.minY, z1 = b.minZ;
            double x2 = b.maxX, y2 = b.maxY, z2 = b.maxZ;
            
            // Determine which faces to render based on neighbors
            boolean renderNegX = !dtb.hasNegX;
            boolean renderPosX = !dtb.hasPosX;
            boolean renderNegY = !dtb.hasNegY;
            boolean renderPosY = !dtb.hasPosY;
            boolean renderNegZ = !dtb.hasNegZ;
            boolean renderPosZ = !dtb.hasPosZ;
            
            // Render sides (quads) - only if no neighbor on that side
            if (mode.lines() || mode.sides()) {
                // Bottom face (Y-)
                if (renderNegY && mode.sides()) {
                    r.quadHorizontal(x1, y1, z1, x2, z2, sideColor);
                }
                // Top face (Y+)
                if (renderPosY && mode.sides()) {
                    r.quadHorizontal(x1, y2, z1, x2, z2, sideColor);
                }
                // North face (Z-)
                if (renderNegZ && mode.sides()) {
                    r.quadVertical(x1, y1, z1, x2, y2, z1, sideColor);
                }
                // South face (Z+)
                if (renderPosZ && mode.sides()) {
                    r.quadVertical(x1, y1, z2, x2, y2, z2, sideColor);
                }
                // West face (X-)
                if (renderNegX && mode.sides()) {
                    r.quadVertical(x1, y1, z1, x1, y2, z2, sideColor);
                }
                // East face (X+)
                if (renderPosX && mode.sides()) {
                    r.quadVertical(x2, y1, z1, x2, y2, z2, sideColor);
                }
            }
            
            // Render edges (lines) - only if at least one adjacent face is exposed
            if (mode.lines()) {
                // Bottom edges
                if (renderNegY || renderNegZ) r.line(x1, y1, z1, x2, y1, z1, lineColor); // North
                if (renderNegY || renderPosZ) r.line(x1, y1, z2, x2, y1, z2, lineColor); // South  
                if (renderNegY || renderNegX) r.line(x1, y1, z1, x1, y1, z2, lineColor); // West
                if (renderNegY || renderPosX) r.line(x2, y1, z1, x2, y1, z2, lineColor); // East
                
                // Top edges
                if (renderPosY || renderNegZ) r.line(x1, y2, z1, x2, y2, z1, lineColor); // North
                if (renderPosY || renderPosZ) r.line(x1, y2, z2, x2, y2, z2, lineColor); // South
                if (renderPosY || renderNegX) r.line(x1, y2, z1, x1, y2, z2, lineColor); // West
                if (renderPosY || renderPosX) r.line(x2, y2, z1, x2, y2, z2, lineColor); // East
                
                // Vertical edges
                if (renderNegX || renderNegZ) r.line(x1, y1, z1, x1, y2, z1, lineColor); // NW
                if (renderPosX || renderNegZ) r.line(x2, y1, z1, x2, y2, z1, lineColor); // NE
                if (renderNegX || renderPosZ) r.line(x1, y1, z2, x1, y2, z2, lineColor); // SW
                if (renderPosX || renderPosZ) r.line(x2, y1, z2, x2, y2, z2, lineColor); // SE
            }
        }
    }
    
    private void renderStaircases(Renderer3D r) {
        for (Box b : staircases)
            r.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, staircaseSideColor.get(), staircaseLineColor.get(), shapeMode.get(), 0);
    }
    private void renderCoveredHoles(Renderer3D r) {
        for (Map.Entry<Box, CoveredHoleInfo> entry : coveredHoles.entrySet()) {
            Box hole = entry.getKey();
            CoveredHoleInfo info = entry.getValue();
            r.box(hole.minX, hole.minY, hole.minZ, hole.maxX, hole.maxY, hole.maxZ,
                coveredHoleSideColor.get(), coveredHoleLineColor.get(), shapeMode.get(), 0);
            r.box(info.coverPos.getX(), info.coverPos.getY(), info.coverPos.getZ(),
                info.coverPos.getX() + 1, info.coverPos.getY() + 1, info.coverPos.getZ() + 1,
                coveredHoleSideColor.get(), coveredHoleLineColor.get(), shapeMode.get(), 0);
        }
    }

    // =========================================================================
    //  CHUNK PROCESSING
    // =========================================================================

    private void processChunkQueue() {
        int processed = 0;
        while (!chunkQueue.isEmpty() && processed < maxChunks.get()) {
            Chunk chunk = chunkQueue.poll();
            if (chunk != null) {
                TChunk tChunk = new TChunk(chunk.getPos().x, chunk.getPos().z);
                chunks.put(tChunk.getKey(), tChunk);
                MeteorExecutor.execute(() -> searchChunk(chunk, tChunk));
                processed++;
            }
        }
    }

    private void searchChunk(Chunk chunk, TChunk tChunk) {
        int Ymin = mc.world.getBottomY()       + minY.get();
        int Ymax = mc.world.getTopYInclusive() - maxY.get();

        BitSet visited = visitedBlocksLocal.get();
        visited.clear();

        BlockPos.Mutable pos      = new BlockPos.Mutable();
        BlockPos.Mutable floorPos = new BlockPos.Mutable();

        for (int ySection = 0; ySection < chunk.getSectionArray().length; ySection++) {
            ChunkSection section = chunk.getSectionArray()[ySection];
            if (section == null || section.isEmpty()) continue;
            int sectionBaseY = mc.world.getBottomY() + (ySection * 16);

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        int currentY = sectionBaseY + y;
                        if (currentY <= Ymin || currentY >= Ymax) continue;
                        if (visited.get(getLocalIndex(x, currentY, z, Ymin))) continue;

                        pos.set(chunk.getPos().getStartX() + x, currentY, chunk.getPos().getStartZ() + z);
                        if (!isPassableBlock(pos)) continue;

                        floorPos.set(pos).move(Direction.DOWN);
                        boolean hasSolidFloor = !isPassableBlock(floorPos);
                        DetectionMode mode = detectionMode.get();

                        if (mode == DetectionMode.ALL || mode == DetectionMode.HOLES
                            || mode == DetectionMode.HOLES_AND_TUNNELS
                            || mode == DetectionMode.HOLES_AND_STAIRCASES
                            || mode == DetectionMode.HOLES_3X1_AND_TUNNELS) {
                            findAndAddHole(pos, visited, Ymin);
                            findAndAdd3x1Hole(pos, visited, Ymin);
                        }
                        if (hasSolidFloor) {
                            if (mode == DetectionMode.ALL || mode == DetectionMode.TUNNELS
                                || mode == DetectionMode.HOLES_AND_TUNNELS
                                || mode == DetectionMode.TUNNELS_AND_STAIRCASES
                                || mode == DetectionMode.HOLES_3X1_AND_TUNNELS) {
                                checkTunnelOptimized(pos, visited, Ymin);
                                if (diagonals.get()) checkDiagonalTunnel(pos, visited, Ymin);
                            }
                            if (mode == DetectionMode.ALL || mode == DetectionMode.STAIRCASES
                                || mode == DetectionMode.HOLES_AND_STAIRCASES
                                || mode == DetectionMode.TUNNELS_AND_STAIRCASES) {
                                checkStaircaseOptimized(pos, visited, Ymin);
                            }
                        }
                    }
                }
            }
        }
        
        // NEW: After all diagonal tunnels are detected, compute neighbor relationships
        computeDiagonalTunnelNeighbors();
    }

    private int getLocalIndex(int x, int y, int z, int yMin) {
        return (x & 15) | ((z & 15) << 4) | ((y - yMin) << 8);
    }

    // =========================================================================
    //  HOLE DETECTION
    // =========================================================================

    private void findAndAddHole(BlockPos pos, BitSet visited, int yMin) {
        if (!isValidHoleSection(pos)) return;
        BlockPos.Mutable cur = pos.mutableCopy();
        int depth = 0;
        while (isValidHoleSection(cur)) {
            visited.set(getLocalIndex(cur.getX() & 15, cur.getY(), cur.getZ() & 15, yMin));
            cur.move(Direction.UP);
            depth++;
        }
        if (depth >= minHoleDepth.get()) {
            long hash = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
            if (holeHashes.add(hash)) {
                Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, cur.getY(), pos.getZ() + 1);
                holes.add(box);
                if (detectCoveredHoles.get()) checkCoveredHole(box);
            }
        }
    }

    private void findAndAdd3x1Hole(BlockPos pos, BitSet visited, int yMin) {
        if (isValid3x1HoleSectionX(pos)) {
            BlockPos.Mutable cur = pos.mutableCopy();
            int depth = 0;
            while (isValid3x1HoleSectionX(cur)) {
                mark3x1Visited(cur, Direction.EAST, visited, yMin);
                cur.move(Direction.UP);
                depth++;
            }
            if (depth >= minHoleDepth.get()) {
                long hash = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
                if (hole3x1Hashes.add(hash)) {
                    Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 3, cur.getY(), pos.getZ() + 1);
                    holes3x1.add(box);
                    if (detectCoveredHoles.get()) checkCoveredHole(box);
                }
            }
        }
        if (isValid3x1HoleSectionZ(pos)) {
            BlockPos.Mutable cur = pos.mutableCopy();
            int depth = 0;
            while (isValid3x1HoleSectionZ(cur)) {
                mark3x1Visited(cur, Direction.SOUTH, visited, yMin);
                cur.move(Direction.UP);
                depth++;
            }
            if (depth >= minHoleDepth.get()) {
                long hash = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
                if (hole3x1Hashes.add(hash)) {
                    Box box = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, cur.getY(), pos.getZ() + 3);
                    holes3x1.add(box);
                    if (detectCoveredHoles.get()) checkCoveredHole(box);
                }
            }
        }
    }

    private void mark3x1Visited(BlockPos pos, Direction widthDir, BitSet visited, int yMin) {
        BlockPos.Mutable m = pos.mutableCopy();
        for (int i = 0; i < 3; i++) {
            visited.set(getLocalIndex(m.getX() & 15, m.getY(), m.getZ() & 15, yMin));
            m.move(widthDir);
        }
    }

    // =========================================================================
    //  TUNNEL DETECTION
    // =========================================================================

    private boolean isValidTunnelCrossSection(BlockPos pos, Direction lengthDir, int width, int refHeight) {
        Direction widthDir     = (lengthDir.getAxis() == Direction.Axis.X) ? Direction.SOUTH : Direction.EAST;
        Direction antiWidthDir = widthDir.getOpposite();

        if (!isPassableBlock(pos))                            return false;
        if (isPassableBlock(pos.down()))                      return false;
        if (isPassableBlock(pos.offset(antiWidthDir)))        return false;
        if (getTunnelHeight(pos) != refHeight)                return false;
        if (isPassableBlock(pos.up(refHeight)))               return false;

        for (int w = 1; w < width; w++) {
            BlockPos wPos = pos.offset(widthDir, w);
            if (!isPassableBlock(wPos))             return false;
            if (isPassableBlock(wPos.down()))        return false;
            if (getTunnelHeight(wPos) != refHeight)  return false;
            if (isPassableBlock(wPos.up(refHeight))) return false;
        }
        return !isPassableBlock(pos.offset(widthDir, width));
    }

    /**
     * Detects straight tunnels and registers them in {@code tunnelBoxMap}.
     *
     * <h3>Tunnel extension via merge()</h3>
     * When an underground chunk is rescanned and a tunnel turns out to extend
     * further than the previously registered box, {@code tunnelBoxMap.merge()}
     * atomically replaces the old (shorter) box with the new (longer) one.
     * Because the map entry is updated in a single atomic step the render thread
     * always sees a valid box — there is no frame where the box is absent
     * (no "flash").
     *
     * <p>The merge function only grows the box along the tunnel's length axis
     * (min/max on X and Z); the Y dimensions are kept from the first registration
     * because they reflect the actual ceiling height measured at scan time.
     */
    private void checkTunnelOptimized(BlockPos startPos, BitSet visited, int yMin) {
        int chunkX = startPos.getX() >> 4;
        int chunkZ = startPos.getZ() >> 4;

        for (Direction dir : CANONICAL_TUNNEL_DIRS) {
            Direction widthDir     = (dir.getAxis() == Direction.Axis.X) ? Direction.SOUTH : Direction.EAST;
            Direction antiWidthDir = widthDir.getOpposite();

            // Fast pre-check: canonical left-edge guard.
            if (isPassableBlock(startPos.offset(antiWidthDir))) continue;

            // Measure width.
            int width = 0;
            while (width < maxTunnelWidth.get() && isPassableBlock(startPos.offset(widthDir, width))) width++;
            if (width < minTunnelWidth.get() || width > maxTunnelWidth.get()) continue;

            // Reference height — passed into every cross-section call so height is
            // re-used without recomputation and enforces uniformity along the tunnel.
            int refHeight = getTunnelHeight(startPos);
            if (refHeight < minTunnelHeight.get() || refHeight > maxTunnelHeight.get()) continue;

            if (!isValidTunnelCrossSection(startPos, dir, width, refHeight)) continue;

            // Pass 1: walk backward to canonical start (same for all chunks touching this tunnel).
            BlockPos.Mutable canonicalStart = startPos.mutableCopy();
            {
                BlockPos.Mutable probe = startPos.mutableCopy();
                probe.move(dir.getOpposite());
                while (isValidTunnelCrossSection(probe, dir, width, refHeight)) {
                    canonicalStart.set(probe);
                    probe.move(dir.getOpposite());
                }
            }

            // Dedup hash encodes position + axis (sign bit distinguishes X vs Z tunnels
            // that share the same corner block).
            long hash = BlockPos.asLong(canonicalStart.getX(), canonicalStart.getY(), canonicalStart.getZ());
            if (dir.getAxis() == Direction.Axis.Z) hash ^= Long.MIN_VALUE;

            // Pass 2: walk forward — mark visited blocks in this chunk, count steps,
            // record end position.
            BlockPos.Mutable scanPos  = canonicalStart.mutableCopy();
            BlockPos.Mutable lastValid = canonicalStart.mutableCopy();
            int stepCount = 0;

            while (isValidTunnelCrossSection(scanPos, dir, width, refHeight)) {
                for (int w = 0; w < width; w++) {
                    int wx = scanPos.getX() + widthDir.getOffsetX() * w;
                    int wz = scanPos.getZ() + widthDir.getOffsetZ() * w;
                    if ((wx >> 4) == chunkX && (wz >> 4) == chunkZ)
                        visited.set(getLocalIndex(wx & 15, scanPos.getY(), wz & 15, yMin));
                }
                lastValid.set(scanPos);
                scanPos.move(dir);
                stepCount++;
            }

            if (stepCount < minTunnelLength.get()) continue;

            int x1 = canonicalStart.getX(), y1 = canonicalStart.getY(), z1 = canonicalStart.getZ();
            int x2, z2;
            if (dir.getAxis() == Direction.Axis.X) { x2 = lastValid.getX() + 1; z2 = z1 + width; }
            else                                    { x2 = x1 + width;           z2 = lastValid.getZ() + 1; }

            final Box newBox = new Box(x1, y1, z1, x2, y1 + refHeight, z2);

            // Atomically register or extend the box.  If a shorter box was registered
            // by an earlier chunk scan, merge() replaces it with the larger one.
            // The render thread sees a valid box at all times (no flash).
            tunnelBoxMap.merge(hash, newBox, (oldBox, nb) -> new Box(
                Math.min(oldBox.minX, nb.minX), oldBox.minY, Math.min(oldBox.minZ, nb.minZ),
                Math.max(oldBox.maxX, nb.maxX), oldBox.maxY, Math.max(oldBox.maxZ, nb.maxZ)
            ));
        }
    }

    // =========================================================================
    //  STAIRCASE DETECTION
    // =========================================================================

    private void checkStaircaseOptimized(BlockPos pos, BitSet visited, int yMin) {
        for (Direction dir : DIRECTIONS) {
            BlockPos.Mutable cur = pos.mutableCopy();
            int stepCount = 0;
            List<Box> potential = new ArrayList<>();

            while (isStaircaseSection(cur, dir)) {
                int height = getStaircaseHeight(cur);
                potential.add(new Box(cur.getX(), cur.getY(), cur.getZ(), cur.getX() + 1, cur.getY() + height, cur.getZ() + 1));
                visited.set(getLocalIndex(cur.getX() & 15, cur.getY(), cur.getZ() & 15, yMin));
                cur.move(dir);
                cur.move(Direction.UP);
                stepCount++;
            }
            if (stepCount >= minStaircaseLength.get()) {
                for (Box b : potential) {
                    long hash = BlockPos.asLong((int) b.minX, (int) b.minY, (int) b.minZ);
                    if (staircaseHashes.add(hash)) staircases.add(b);
                }
            }
        }
    }

    // =========================================================================
    //  DIAGONAL TUNNEL DETECTION
    // =========================================================================

    /**
     * NEW: Data class for diagonal tunnel boxes with neighbor information.
     * Stores which sides have adjacent tunnel segments for culling.
     */
    private static class DiagonalTunnelBox {
        public final Box box;
        public final int x, y, z;      // Grid position
        public final int width, height;
        public final Direction dir;    // Primary direction
        public final boolean turnRight;
        
        // Neighbor flags - true if another tunnel segment is adjacent on this side
        public boolean hasNegX, hasPosX, hasNegY, hasPosY, hasNegZ, hasPosZ;
        
        public DiagonalTunnelBox(Box box, int x, int y, int z, int width, int height, 
                                  Direction dir, boolean turnRight) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
            this.dir = dir;
            this.turnRight = turnRight;
        }
        
        /**
         * Computes hash based on grid position for neighbor lookup.
         */
        public long getHash() {
            return BlockPos.asLong(x, y, z);
        }
    }

    private void checkDiagonalTunnel(BlockPos pos, BitSet visited, int yMin) {
        for (Direction dir : DIRECTIONS) {
            for (int w = minDiagonalWidth.get(); w <= maxDiagonalWidth.get(); w++) {
                BlockPos.Mutable cur = pos.mutableCopy();
                int stepCount = 0;
                List<DiagonalTunnelBox> potential = new ArrayList<>();
                Direction checkDir = dir;
                boolean turnRight  = true;

                while (isDiagonalTunnelSection(cur, checkDir)) {
                    int height = getTunnelHeight(cur);
                    BlockPos.Mutable fill = cur.mutableCopy();
                    
                    for (int k = 0; k < w; k++) {
                        // Create box for this segment
                        Box box = new Box(fill.getX(), fill.getY(), fill.getZ(),
                                          fill.getX() + 1, fill.getY() + height, fill.getZ() + 1);
                        
                        // Store grid position for neighbor detection
                        int gx = fill.getX();
                        int gy = fill.getY();
                        int gz = fill.getZ();
                        
                        DiagonalTunnelBox dtb = new DiagonalTunnelBox(box, gx, gy, gz, w, height, checkDir, turnRight);
                        potential.add(dtb);
                        
                        visited.set(getLocalIndex(fill.getX() & 15, fill.getY(), fill.getZ() & 15, yMin));
                        fill.move(turnRight ? checkDir.rotateYClockwise() : checkDir.rotateYCounterclockwise());
                    }
                    
                    if (turnRight) { 
                        checkDir = checkDir.rotateYClockwise();        
                        cur.move(checkDir, w); 
                        turnRight = false; 
                    } else {           
                        checkDir = checkDir.rotateYCounterclockwise(); 
                        cur.move(checkDir, w); 
                        turnRight = true;  
                    }
                    stepCount++;
                }
                
                if (stepCount >= minDiagonalLength.get()) {
                    for (DiagonalTunnelBox dtb : potential) {
                        long hash = dtb.getHash();
                        // Use merge to handle overlapping segments from different scan passes
                        diagonalTunnelBoxMap.merge(hash, dtb, (old, neu) -> {
                            // Keep the larger one if they overlap, or combine them
                            if ((neu.box.maxX - neu.box.minX) * (neu.box.maxY - neu.box.minY) * (neu.box.maxZ - neu.box.minZ) >
                                (old.box.maxX - old.box.minX) * (old.box.maxY - old.box.minY) * (old.box.maxZ - old.box.minZ)) {
                                return neu;
                            }
                            return old;
                        });
                    }
                }
            }
        }
    }
    
    /**
     * NEW: Computes neighbor relationships for all diagonal tunnel boxes.
     * This is called after all diagonal tunnels are detected in a chunk.
     */
    private void computeDiagonalTunnelNeighbors() {
        // For each diagonal tunnel box, check all 6 neighbors
        for (DiagonalTunnelBox dtb : diagonalTunnelBoxMap.values()) {
            // Check X neighbors
            long negXHash = BlockPos.asLong(dtb.x - 1, dtb.y, dtb.z);
            long posXHash = BlockPos.asLong(dtb.x + 1, dtb.y, dtb.z);
            dtb.hasNegX = diagonalTunnelBoxMap.containsKey(negXHash);
            dtb.hasPosX = diagonalTunnelBoxMap.containsKey(posXHash);
            
            // Check Y neighbors
            long negYHash = BlockPos.asLong(dtb.x, dtb.y - 1, dtb.z);
            long posYHash = BlockPos.asLong(dtb.x, dtb.y + 1, dtb.z);
            dtb.hasNegY = diagonalTunnelBoxMap.containsKey(negYHash);
            dtb.hasPosY = diagonalTunnelBoxMap.containsKey(posYHash);
            
            // Check Z neighbors
            long negZHash = BlockPos.asLong(dtb.x, dtb.y, dtb.z - 1);
            long posZHash = BlockPos.asLong(dtb.x, dtb.y, dtb.z + 1);
            dtb.hasNegZ = diagonalTunnelBoxMap.containsKey(negZHash);
            dtb.hasPosZ = diagonalTunnelBoxMap.containsKey(posZHash);
        }
    }

    // =========================================================================
    //  COVERED HOLE LOGIC
    // =========================================================================

    private static class CoveredHoleInfo {
        public final BlockPos coverPos;
        public final Box holeBox;
        public CoveredHoleInfo(BlockPos coverPos, Box holeBox) {
            this.coverPos = coverPos; this.holeBox = holeBox;
        }
    }

    private void checkCoveredHole(Box holeBox) {
        if (!detectCoveredHoles.get()) return;
        BlockPos topPos = new BlockPos((int) holeBox.minX, (int) holeBox.maxY, (int) holeBox.minZ);
        if (isSolidBlockCached(topPos)) {
            boolean isPlayerCovered = !onlyPlayerCovered.get() || isLikelyPlayerCovered(topPos, holeBox);
            if (isPlayerCovered) {
                CoveredHoleInfo info = new CoveredHoleInfo(topPos, holeBox);
                coveredHoles.put(holeBox, info);
                if (chatNotifications.get() && notifiedHoles.add(holeBox)) {
                    int depth = (int) (holeBox.maxY - holeBox.minY);
                    info(String.format("Covered Hole found at %s (depth: %d)", topPos.toShortString(), depth));
                }
            }
        }
    }

    private boolean isLikelyPlayerCovered(BlockPos coverPos, Box hole) {
        BlockState coverBlock = getBlockStateCached(coverPos);
        if (coverBlock == null) return false;
        if (isCommonBuildingBlock(coverBlock)) return true;
        int matchingBlocks = 0;
        for (BlockPos p : new BlockPos[]{ coverPos.north(), coverPos.south(), coverPos.east(), coverPos.west() }) {
            BlockState state = getBlockStateCached(p);
            if (state != null && state.getBlock() == coverBlock.getBlock()) matchingBlocks++;
        }
        return matchingBlocks < 2;
    }

    private boolean isCommonBuildingBlock(BlockState state) {
        if (state == null) return false;
        String name = state.getBlock().getTranslationKey().toLowerCase();
        return name.contains("cobblestone") || name.contains("stone_brick") || name.contains("plank")
            || name.contains("log") || name.contains("wool") || name.contains("concrete")
            || name.contains("terracotta") || name.contains("glass");
    }

    private boolean isSolidBlockCached(BlockPos pos) {
        if (mc.world == null) return false;
        return solidBlockCache.computeIfAbsent(pos, p -> {
            try { BlockState s = mc.world.getBlockState(p); return s != null && s.isSolidBlock(mc.world, p); }
            catch (Exception e) { return false; }
        });
    }

    private BlockState getBlockStateCached(BlockPos pos) {
        if (mc.world == null) return null;
        return blockStateCache.computeIfAbsent(pos, p -> {
            try { return mc.world.getBlockState(p); }
            catch (Exception e) { return null; }
        });
    }

    // =========================================================================
    //  HELPER METHODS
    // =========================================================================

    private int getTunnelHeight(BlockPos pos) {
        int h = 0;
        while (h < maxTunnelHeight.get() + 1 && isPassableBlock(pos.up(h))) h++;
        return h;
    }

    private int getStaircaseHeight(BlockPos pos) {
        int h = 0;
        while (h < maxStaircaseHeight.get() && isPassableBlock(pos.up(h))) h++;
        return h;
    }

    private boolean isValidHoleSection(BlockPos pos) {
        return isPassableBlock(pos)
            && !isPassableBlock(pos.north()) && !isPassableBlock(pos.south())
            && !isPassableBlock(pos.east())  && !isPassableBlock(pos.west());
    }

    private boolean isValid3x1HoleSectionX(BlockPos pos) {
        return isPassableBlock(pos) && isPassableBlock(pos.east()) && isPassableBlock(pos.east(2))
            && !isPassableBlock(pos.north()) && !isPassableBlock(pos.south())
            && !isPassableBlock(pos.east(3)) && !isPassableBlock(pos.west())
            && !isPassableBlock(pos.east().north())   && !isPassableBlock(pos.east().south())
            && !isPassableBlock(pos.east(2).north())  && !isPassableBlock(pos.east(2).south());
    }

    private boolean isValid3x1HoleSectionZ(BlockPos pos) {
        return isPassableBlock(pos) && isPassableBlock(pos.south()) && isPassableBlock(pos.south(2))
            && !isPassableBlock(pos.east()) && !isPassableBlock(pos.west())
            && !isPassableBlock(pos.south(3)) && !isPassableBlock(pos.north())
            && !isPassableBlock(pos.south().east())   && !isPassableBlock(pos.south().west())
            && !isPassableBlock(pos.south(2).east())  && !isPassableBlock(pos.south(2).west());
    }

    private boolean isStaircaseSection(BlockPos pos, Direction dir) {
        int height = getStaircaseHeight(pos);
        if (height < minStaircaseHeight.get() || height > maxStaircaseHeight.get()) return false;
        if (isPassableBlock(pos.down()) || isPassableBlock(pos.up(height))) return false;
        Direction[] perp = (dir.getAxis() == Direction.Axis.X)
            ? new Direction[]{ Direction.NORTH, Direction.SOUTH }
            : new Direction[]{ Direction.EAST,  Direction.WEST  };
        for (Direction p : perp)
            for (int i = 0; i < height; i++)
                if (isPassableBlock(pos.up(i).offset(p))) return false;
        return true;
    }

    private boolean isDiagonalTunnelSection(BlockPos pos, Direction dir) {
        int height = getTunnelHeight(pos);
        if (height < minTunnelHeight.get() || height > maxTunnelHeight.get()) return false;
        if (isPassableBlock(pos.down()) || isPassableBlock(pos.up(height))) return false;
        for (int i = 0; i < height; i++)
            if (isPassableBlock(pos.up(i).offset(dir))) return false;
        return true;
    }

    private boolean isPassableBlock(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (airBlocks.get()) return state.isAir();
        VoxelShape shape = state.getCollisionShape(mc.world, pos);
        return shape.isEmpty() || !VoxelShapes.fullCube().equals(shape);
    }

    // =========================================================================
    //  ENUMS / INNER CLASSES
    // =========================================================================

    public enum DetectionMode {
        ALL, HOLES_AND_TUNNELS, HOLES_AND_STAIRCASES, TUNNELS_AND_STAIRCASES,
        HOLES, TUNNELS, STAIRCASES, HOLES_3X1_AND_TUNNELS
    }

    private static class TChunk {
        private final int x, z;
        public boolean marked;
        public TChunk(int x, int z) { this.x = x; this.z = z; this.marked = true; }
        public long getKey() { return ChunkPos.toLong(x, z); }
    }
}