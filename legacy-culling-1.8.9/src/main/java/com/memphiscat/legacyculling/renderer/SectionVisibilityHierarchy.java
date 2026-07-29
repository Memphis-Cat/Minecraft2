package com.memphiscat.legacyculling.renderer;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Conservative region/chunk/section visibility hierarchy.
 * Unknown or dirty sections are never rejected.
 */
public final class SectionVisibilityHierarchy {
    private static final int ALL_FACES = 0x3F;
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};
    private static final int[] OPPOSITE = {1, 0, 3, 2, 5, 4};

    private final Map<Long, Node> nodes = new HashMap<Long, Node>();
    private final LinkedHashSet<Long> dirty = new LinkedHashSet<Long>();
    private final Set<Long> reachable = new HashSet<Long>();
    private final Map<Long, RegionState> regions = new HashMap<Long, RegionState>();
    private World world;
    private long frame;
    private int cameraSectionX;
    private int cameraSectionY;
    private int cameraSectionZ;
    private int radius;

    public synchronized void reset() {
        nodes.clear();
        dirty.clear();
        reachable.clear();
        regions.clear();
        world = null;
        frame = 0L;
    }

    public synchronized void onChunkState(World currentWorld, int chunkX, int chunkZ, boolean loaded) {
        ensureWorld(currentWorld);
        for (int sectionY = 0; sectionY < 16; sectionY++) {
            long key = key(chunkX, sectionY, chunkZ);
            if (loaded) {
                dirty.add(key);
            } else {
                nodes.remove(key);
                dirty.remove(key);
                reachable.remove(key);
            }
        }
        invalidateAdjacentChunk(chunkX, chunkZ);
    }

    public synchronized void invalidateRegion(World currentWorld, int minX, int minY, int minZ,
                                              int maxX, int maxY, int maxZ) {
        ensureWorld(currentWorld);
        int minSectionX = floorDiv(Math.min(minX, maxX), 16) - 1;
        int maxSectionX = floorDiv(Math.max(minX, maxX), 16) + 1;
        int minSectionY = Math.max(0, floorDiv(Math.min(minY, maxY), 16) - 1);
        int maxSectionY = Math.min(15, floorDiv(Math.max(minY, maxY), 16) + 1);
        int minSectionZ = floorDiv(Math.min(minZ, maxZ), 16) - 1;
        int maxSectionZ = floorDiv(Math.max(minZ, maxZ), 16) + 1;
        for (int sx = minSectionX; sx <= maxSectionX; sx++) {
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                for (int sz = minSectionZ; sz <= maxSectionZ; sz++) dirty.add(key(sx, sy, sz));
            }
        }
    }

    public synchronized void beginFrame(World currentWorld, double cameraX, double cameraY, double cameraZ,
                                        int viewDistanceChunks, int buildBudget) {
        ensureWorld(currentWorld);
        frame++;
        cameraSectionX = floorDiv(floor(cameraX), 16);
        cameraSectionY = clamp(floorDiv(floor(cameraY), 16), 0, 15);
        cameraSectionZ = floorDiv(floor(cameraZ), 16);
        radius = Math.max(2, viewDistanceChunks + 1);
        processDirty(Math.max(1, buildBudget));
        rebuildReachability();
        pruneFarNodes();
    }

    public synchronized SectionVisibility visibility(Box box) {
        if (world == null || box == null) return SectionVisibility.UNKNOWN;
        int minX = floorDiv(floor(box.minX), 16);
        int maxX = floorDiv(floor(box.maxX), 16);
        int minY = clamp(floorDiv(floor(box.minY), 16), 0, 15);
        int maxY = clamp(floorDiv(floor(box.maxY), 16), 0, 15);
        int minZ = floorDiv(floor(box.minZ), 16);
        int maxZ = floorDiv(floor(box.maxZ), 16);
        boolean sawKnown = false;
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    long key = key(sx, sy, sz);
                    Node node = nodes.get(key);
                    if (node == null || node.dirty || !insideRadius(sx, sz)) return SectionVisibility.UNKNOWN;
                    sawKnown = true;
                    if (reachable.contains(key)) return SectionVisibility.VISIBLE;
                }
            }
        }
        return sawKnown ? SectionVisibility.BLOCKED : SectionVisibility.UNKNOWN;
    }

    public synchronized RegionState regionState(int regionX, int regionZ) {
        RegionState state = regions.get(regionKey(regionX, regionZ));
        return state == null ? RegionState.UNKNOWN : state;
    }

    private void ensureWorld(World currentWorld) {
        if (currentWorld != world) {
            reset();
            world = currentWorld;
        }
    }

    private void processDirty(int budget) {
        Iterator<Long> iterator = dirty.iterator();
        int rebuilt = 0;
        while (iterator.hasNext() && rebuilt < budget) {
            long key = iterator.next();
            iterator.remove();
            int sx = sectionX(key);
            int sy = sectionY(key);
            int sz = sectionZ(key);
            if (!insideRadius(sx, sz) || !OccluderRules.isChunkLoaded(world, sx << 4, sz << 4)) {
                nodes.remove(key);
                continue;
            }
            Node node = nodes.get(key);
            if (node == null) {
                node = new Node();
                nodes.put(key, node);
            }
            node.openFaces = computeOpenFaces(sx, sy, sz);
            node.dirty = false;
            node.builtFrame = frame;
            rebuilt++;
        }
        for (Long key : dirty) {
            Node node = nodes.get(key);
            if (node != null) node.dirty = true;
        }
    }

    private int computeOpenFaces(int sx, int sy, int sz) {
        int mask = 0;
        for (int face = 0; face < 6; face++) {
            int nx = sx + DX[face];
            int ny = sy + DY[face];
            int nz = sz + DZ[face];
            if (ny < 0 || ny > 15 || !OccluderRules.isChunkLoaded(world, nx << 4, nz << 4)) {
                mask |= 1 << face;
                continue;
            }
            if (faceHasOpening(sx, sy, sz, face)) mask |= 1 << face;
        }
        return mask;
    }

    private boolean faceHasOpening(int sx, int sy, int sz, int face) {
        int baseX = sx << 4;
        int baseY = sy << 4;
        int baseZ = sz << 4;
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                int x = baseX;
                int y = baseY;
                int z = baseZ;
                if (face == 0) { x += a; z += b; }
                else if (face == 1) { x += a; y += 15; z += b; }
                else if (face == 2) { x += a; y += b; }
                else if (face == 3) { x += a; y += b; z += 15; }
                else if (face == 4) { y += a; z += b; }
                else { x += 15; y += a; z += b; }
                int nx = x + DX[face];
                int ny = y + DY[face];
                int nz = z + DZ[face];
                if (!OccluderRules.isTrustedFullCube(world, new BlockPos(x, y, z))
                        && !OccluderRules.isTrustedFullCube(world, new BlockPos(nx, ny, nz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rebuildReachability() {
        reachable.clear();
        regions.clear();
        long start = key(cameraSectionX, cameraSectionY, cameraSectionZ);
        ArrayDeque<Long> queue = new ArrayDeque<Long>();
        queue.add(start);
        reachable.add(start);
        while (!queue.isEmpty()) {
            long current = queue.removeFirst();
            int sx = sectionX(current);
            int sy = sectionY(current);
            int sz = sectionZ(current);
            updateRegion(sx, sz, true);
            Node node = nodes.get(current);
            int open = node == null || node.dirty ? ALL_FACES : node.openFaces;
            for (int face = 0; face < 6; face++) {
                if ((open & (1 << face)) == 0) continue;
                int nx = sx + DX[face];
                int ny = sy + DY[face];
                int nz = sz + DZ[face];
                if (ny < 0 || ny > 15 || !insideRadius(nx, nz)) continue;
                long next = key(nx, ny, nz);
                Node neighbor = nodes.get(next);
                if (neighbor != null && !neighbor.dirty
                        && (neighbor.openFaces & (1 << OPPOSITE[face])) == 0) continue;
                if (reachable.add(next)) queue.addLast(next);
            }
        }
        for (Map.Entry<Long, Node> entry : nodes.entrySet()) {
            long key = entry.getKey();
            Node node = entry.getValue();
            if (!node.dirty && insideRadius(sectionX(key), sectionZ(key)) && !reachable.contains(key)) {
                updateRegion(sectionX(key), sectionZ(key), false);
            }
        }
    }

    private void updateRegion(int sectionX, int sectionZ, boolean visible) {
        long key = regionKey(floorDiv(sectionX, 8), floorDiv(sectionZ, 8));
        RegionState old = regions.get(key);
        if (visible) regions.put(key, RegionState.VISIBLE);
        else if (old == null) regions.put(key, RegionState.BLOCKED);
    }

    private void invalidateAdjacentChunk(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int sy = 0; sy < 16; sy++) dirty.add(key(chunkX + dx, sy, chunkZ + dz));
            }
        }
    }

    private void pruneFarNodes() {
        Iterator<Map.Entry<Long, Node>> iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getKey();
            if (Math.abs(sectionX(key) - cameraSectionX) > radius + 4
                    || Math.abs(sectionZ(key) - cameraSectionZ) > radius + 4) iterator.remove();
        }
    }

    private boolean insideRadius(int sx, int sz) {
        return Math.abs(sx - cameraSectionX) <= radius && Math.abs(sz - cameraSectionZ) <= radius;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static int sectionX(long key) { return signExtend((int) (key >> 38), 26); }
    private static int sectionY(long key) { return (int) (key & 0xFFFL); }
    private static int sectionZ(long key) { return signExtend((int) ((key >> 12) & 0x3FFFFFFL), 26); }
    private static long regionKey(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
    private static int signExtend(int value, int bits) { int shift = 32 - bits; return value << shift >> shift; }
    private static int floor(double value) { int i = (int) value; return value < i ? i - 1 : i; }
    private static int floorDiv(int value, int divisor) { int result = value / divisor; return (value ^ divisor) < 0 && result * divisor != value ? result - 1 : result; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class Node {
        int openFaces;
        boolean dirty = true;
        long builtFrame;
    }

    public enum RegionState {
        VISIBLE,
        BLOCKED,
        UNKNOWN
    }
}
