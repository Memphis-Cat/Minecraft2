package com.memphiscat.legacyculling.performance;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.opengl.GL11;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * A deliberately small, cached height-and-color LOD. It only draws chunks that
 * the client has previously received, so it never invents server terrain. The
 * renderer suspends itself when FPS is below the configured floor.
 */
public final class LegacyFarTerrainRenderer {
    private static final LinkedHashMap<Long, FarChunk> CACHE =
            new LinkedHashMap<Long, FarChunk>(256, 0.75F, true);
    private static final LinkedHashSet<Long> DIRTY = new LinkedHashSet<Long>();
    private static ClientWorld lastWorld;
    private static int suspendedFrames;

    private LegacyFarTerrainRenderer() {
    }

    public static synchronized void onChunkState(int chunkX, int chunkZ, boolean loaded) {
        if (loaded) DIRTY.add(key(chunkX, chunkZ));
    }

    public static synchronized void markRegion(int minX, int minZ, int maxX, int maxZ) {
        int minChunkX = Math.floorDiv(Math.min(minX, maxX), 16);
        int maxChunkX = Math.floorDiv(Math.max(minX, maxX), 16);
        int minChunkZ = Math.floorDiv(Math.min(minZ, maxZ), 16);
        int maxChunkZ = Math.floorDiv(Math.max(minZ, maxZ), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                DIRTY.add(key(chunkX, chunkZ));
            }
        }
    }

    public static synchronized void clear() {
        for (FarChunk chunk : CACHE.values()) {
            if (chunk.listId != 0) GL11.glDeleteLists(chunk.listId, 1);
        }
        CACHE.clear();
        DIRTY.clear();
        lastWorld = null;
        suspendedFrames = 0;
        AdaptiveChunkScheduler.reset();
    }

    public static void render(Entity camera, float tickDelta) {
        if (camera == null || !LegacyCullingMod.CONFIG.enabled
                || !LegacyCullingMod.CONFIG.farTerrainLod) return;
        if (LegacyCullingMod.CONFIG.farTerrainShaderSafety && OptiFineCompat.shadersActive()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;

        synchronized (LegacyFarTerrainRenderer.class) {
            if (world != lastWorld) {
                clear();
                lastWorld = world;
            }
        }

        int fps = MinecraftClient.getCurrentFps();
        if (fps > 0 && fps < LegacyCullingMod.CONFIG.farTerrainMinFps) {
            suspendedFrames = 20;
            return;
        }
        if (suspendedFrames > 0) {
            suspendedFrames--;
            return;
        }

        processDirty(world, LegacyCullingMod.CONFIG.farTerrainBuildsPerFrame);

        double cameraX = camera.prevX + (camera.x - camera.prevX) * tickDelta;
        double cameraY = camera.prevY + (camera.y - camera.prevY) * tickDelta;
        double cameraZ = camera.prevZ + (camera.z - camera.prevZ) * tickDelta;
        int cameraChunkX = floorChunk(cameraX);
        int cameraChunkZ = floorChunk(cameraZ);
        int normalDistance = Math.max(2, client.options.viewDistance);
        int farDistance = Math.max(normalDistance + 2, LegacyCullingMod.CONFIG.farTerrainDistance);
        int maximum = LegacyCullingMod.CONFIG.farTerrainRenderBudget;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-cameraX, -cameraY, -cameraZ);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(true);

            int rendered = 0;
            synchronized (LegacyFarTerrainRenderer.class) {
                for (FarChunk chunk : CACHE.values()) {
                    int distance = Math.max(Math.abs(chunk.chunkX - cameraChunkX),
                            Math.abs(chunk.chunkZ - cameraChunkZ));
                    if (distance <= normalDistance + 1 || distance > farDistance) continue;
                    GL11.glCallList(chunk.listId);
                    if (++rendered >= maximum) break;
                }
            }
        } finally {
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static synchronized void processDirty(ClientWorld world, int maximum) {
        int built = 0;
        Iterator<Long> iterator = DIRTY.iterator();
        while (iterator.hasNext() && built < maximum) {
            long key = iterator.next();
            iterator.remove();
            rebuild(world, chunkX(key), chunkZ(key));
            built++;
        }
        trimCache();
    }

    private static void rebuild(ClientWorld world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunk(chunkX, chunkZ);
        if (chunk == null || chunk.isEmpty()) return;

        int step = normalizedStep(LegacyCullingMod.CONFIG.farTerrainSampleStep);
        int grid = 16 / step;
        int[] heights = new int[grid * grid];
        int[] colors = new int[grid * grid];

        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                int localX = Math.min(15, x * step + step / 2);
                int localZ = Math.min(15, z * step + step / 2);
                int y = Math.max(0, chunk.getHighestBlockY(localX, localZ) - 1);
                BlockPos pos = new BlockPos(chunkX * 16 + localX, y, chunkZ * 16 + localZ);
                BlockState state = chunk.method_9154(pos);
                Block block = state.getBlock();
                while (block == Blocks.AIR && y > 0) {
                    y--;
                    pos = new BlockPos(pos.getX(), y, pos.getZ());
                    state = chunk.method_9154(pos);
                    block = state.getBlock();
                }
                int index = z * grid + x;
                heights[index] = y + 1;
                colors[index] = terrainColor(world, pos, state, block);
            }
        }

        int listId = GL11.glGenLists(1);
        if (listId == 0) return;
        GL11.glNewList(listId, GL11.GL_COMPILE);
        GL11.glBegin(GL11.GL_QUADS);
        for (int z = 0; z < grid; z++) {
            for (int x = 0; x < grid; x++) {
                int index = z * grid + x;
                int y = heights[index];
                int left = heights[z * grid + Math.max(0, x - 1)];
                int right = heights[z * grid + Math.min(grid - 1, x + 1)];
                int north = heights[Math.max(0, z - 1) * grid + x];
                int south = heights[Math.min(grid - 1, z + 1) * grid + x];
                float shade = clamp(0.78F + (left - right + north - south) * 0.018F
                        + (y - 64) * 0.0012F, 0.50F, 1.0F);
                setColor(colors[index], shade);

                double x0 = chunkX * 16 + x * step;
                double x1 = x0 + step;
                double z0 = chunkZ * 16 + z * step;
                double z1 = z0 + step;
                double top = y + 0.03D;
                GL11.glVertex3d(x0, top, z0);
                GL11.glVertex3d(x0, top, z1);
                GL11.glVertex3d(x1, top, z1);
                GL11.glVertex3d(x1, top, z0);

                if (x + 1 < grid && right < y - 1) {
                    setColor(colors[index], shade * 0.72F);
                    GL11.glVertex3d(x1, right, z0);
                    GL11.glVertex3d(x1, right, z1);
                    GL11.glVertex3d(x1, top, z1);
                    GL11.glVertex3d(x1, top, z0);
                }
                if (z + 1 < grid && south < y - 1) {
                    setColor(colors[index], shade * 0.64F);
                    GL11.glVertex3d(x0, south, z1);
                    GL11.glVertex3d(x0, top, z1);
                    GL11.glVertex3d(x1, top, z1);
                    GL11.glVertex3d(x1, south, z1);
                }
            }
        }
        GL11.glEnd();
        GL11.glEndList();

        long key = key(chunkX, chunkZ);
        FarChunk previous = CACHE.put(key, new FarChunk(chunkX, chunkZ, listId));
        if (previous != null && previous.listId != 0) GL11.glDeleteLists(previous.listId, 1);
    }

    private static int terrainColor(ClientWorld world, BlockPos pos, BlockState state, Block block) {
        int color;
        try {
            color = block.getBlockColor(world, pos, 0);
        } catch (Throwable ignored) {
            color = block.getColor(state);
        }
        if (color != 0 && color != 0xFFFFFF) return color & 0xFFFFFF;

        Identifier identifier = Block.REGISTRY.getIdentifier(block);
        String id = identifier == null ? "" : identifier.toString();
        if (id.contains("grass") || id.contains("leaves") || id.contains("vine")) return 0x6A9345;
        if (id.contains("water")) return 0x3F76E4;
        if (id.contains("sand") || id.contains("sandstone")) return 0xD7C37A;
        if (id.contains("snow") || id.contains("quartz")) return 0xE8E8E2;
        if (id.contains("dirt") || id.contains("farmland")) return 0x866043;
        if (id.contains("log") || id.contains("wood") || id.contains("planks")) return 0x8A673E;
        if (id.contains("brick") || id.contains("netherrack")) return 0x82443B;
        if (id.contains("clay")) return 0x9EA7B2;
        if (id.contains("obsidian")) return 0x2D2438;
        if (id.contains("glass") || id.contains("ice")) return 0xAFCFD2;
        if (id.contains("stone") || id.contains("ore") || id.contains("gravel")) return 0x858585;
        return 0x777777;
    }

    private static void setColor(int color, float shade) {
        float red = ((color >> 16) & 255) / 255.0F * shade;
        float green = ((color >> 8) & 255) / 255.0F * shade;
        float blue = (color & 255) / 255.0F * shade;
        GL11.glColor3f(clamp(red, 0.0F, 1.0F), clamp(green, 0.0F, 1.0F),
                clamp(blue, 0.0F, 1.0F));
    }

    private static void trimCache() {
        int maximum = LegacyCullingMod.CONFIG.farTerrainCacheChunks;
        Iterator<Map.Entry<Long, FarChunk>> iterator = CACHE.entrySet().iterator();
        while (CACHE.size() > maximum && iterator.hasNext()) {
            FarChunk chunk = iterator.next().getValue();
            if (chunk.listId != 0) GL11.glDeleteLists(chunk.listId, 1);
            iterator.remove();
        }
    }

    private static int normalizedStep(int value) {
        if (value <= 2) return 2;
        if (value <= 4) return 4;
        return 8;
    }

    private static int floorChunk(double value) {
        return (int) Math.floor(value / 16.0D);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class FarChunk {
        final int chunkX;
        final int chunkZ;
        final int listId;

        FarChunk(int chunkX, int chunkZ, int listId) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.listId = listId;
        }
    }
}
