package com.memphiscat.legacyculling.renderer;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repackages vanilla-compiled SOLID section meshes into 8x8-chunk region VBOs.
 * Any missing or stale input causes the entire layer to remain on vanilla for that frame.
 */
public final class NativeRegionTerrainRenderer {
    private static final float CHUNK_SCALE = 1.000001F;
    private static final Map<Long, RegionBatch> BATCHES = new HashMap<Long, RegionBatch>();
    private static long frame;

    private NativeRegionTerrainRenderer() {
    }

    public static boolean tryRenderSolid(List<BuiltChunk> chunks, double viewX, double viewY, double viewZ) {
        if (!NativeRendererCoordinator.terrainEnabled()) return false;
        if (chunks == null) return false;
        if (chunks.isEmpty()) return true;

        try {
            frame++;
            int regionChunks = LegacyCullingMod.CONFIG.nativeRegionSizeChunks;
            int regionBlocks = regionChunks << 4;
            Map<Long, PendingRegion> pending = collect(chunks, regionChunks, regionBlocks);
            if (pending == null) return false;

            int buildsRemaining = LegacyCullingMod.CONFIG.nativeRegionBuildsPerFrame;
            List<RegionBatch> ready = new ArrayList<RegionBatch>(pending.size());
            for (PendingRegion region : pending.values()) {
                RegionBatch batch = BATCHES.get(region.key);
                if (batch == null || !batch.matches(region)) {
                    if (buildsRemaining <= 0) return false;
                    if (batch == null) {
                        batch = new RegionBatch(region.key, region.originX, region.originZ);
                        BATCHES.put(region.key, batch);
                    }
                    batch.rebuild(region);
                    buildsRemaining--;
                }
                batch.lastUsedFrame = frame;
                ready.add(batch);
            }

            for (RegionBatch batch : ready) {
                batch.render(viewX, viewY, viewZ);
            }
            prune();
            return true;
        } catch (RuntimeException exception) {
            NativeRendererCoordinator.failTerrain("solid region batching", exception);
            return false;
        }
    }

    public static void reset() {
        for (RegionBatch batch : BATCHES.values()) batch.delete();
        BATCHES.clear();
        frame = 0L;
    }

    private static Map<Long, PendingRegion> collect(List<BuiltChunk> chunks, int regionChunks, int regionBlocks) {
        LinkedHashMap<Long, PendingRegion> pending = new LinkedHashMap<Long, PendingRegion>();
        int solidOrdinal = RenderLayer.SOLID.ordinal();

        for (BuiltChunk chunk : chunks) {
            VertexBuffer sectionBuffer = chunk.method_10165(solidOrdinal);
            NativeMeshCapture.CapturedMesh mesh = NativeMeshCapture.get(sectionBuffer);
            if (mesh == null || mesh.vertexCount <= 0) return null;

            BlockPos pos = chunk.getPos();
            int chunkX = floorDiv(pos.getX(), 16);
            int chunkZ = floorDiv(pos.getZ(), 16);
            int regionX = floorDiv(chunkX, regionChunks);
            int regionZ = floorDiv(chunkZ, regionChunks);
            long key = key(regionX, regionZ);

            PendingRegion region = pending.get(key);
            if (region == null) {
                region = new PendingRegion(key, regionX * regionBlocks, regionZ * regionBlocks);
                pending.put(key, region);
            }
            region.add(pos, mesh);
        }
        return pending;
    }

    private static void prune() {
        int limit = LegacyCullingMod.CONFIG.nativeRegionCacheLimit;
        Iterator<Map.Entry<Long, RegionBatch>> iterator = BATCHES.entrySet().iterator();
        while (iterator.hasNext()) {
            RegionBatch batch = iterator.next().getValue();
            if (frame - batch.lastUsedFrame > 240L) {
                batch.delete();
                iterator.remove();
            }
        }
        while (BATCHES.size() > limit) {
            Long oldestKey = null;
            long oldestFrame = Long.MAX_VALUE;
            for (Map.Entry<Long, RegionBatch> entry : BATCHES.entrySet()) {
                if (entry.getValue().lastUsedFrame < oldestFrame) {
                    oldestFrame = entry.getValue().lastUsedFrame;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) break;
            RegionBatch removed = BATCHES.remove(oldestKey);
            if (removed != null) removed.delete();
        }
    }

    private static void setupBlockPointers() {
        GL11.glVertexPointer(3, GL11.GL_FLOAT, NativeMeshCapture.BLOCK_VERTEX_SIZE, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, NativeMeshCapture.BLOCK_VERTEX_SIZE, 12L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, NativeMeshCapture.BLOCK_VERTEX_SIZE, 16L);
        GLX.gl13ClientActiveTexture(GLX.lightmapTextureUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, NativeMeshCapture.BLOCK_VERTEX_SIZE, 24L);
        GLX.gl13ClientActiveTexture(GLX.textureUnit);
    }

    private static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static int floorDiv(int value, int divisor) {
        int result = value / divisor;
        return (value ^ divisor) < 0 && result * divisor != value ? result - 1 : result;
    }

    private static final class PendingRegion {
        final long key;
        final int originX;
        final int originZ;
        final List<SectionMesh> sections = new ArrayList<SectionMesh>();
        long signature = 0xcbf29ce484222325L;
        int totalBytes;
        int vertexCount;

        PendingRegion(long key, int originX, int originZ) {
            this.key = key;
            this.originX = originX;
            this.originZ = originZ;
        }

        void add(BlockPos pos, NativeMeshCapture.CapturedMesh mesh) {
            sections.add(new SectionMesh(pos, mesh));
            totalBytes += mesh.bytes.length;
            vertexCount += mesh.vertexCount;
            signature = mix(signature, pos.getX());
            signature = mix(signature, pos.getY());
            signature = mix(signature, pos.getZ());
            signature = mix(signature, mesh.generation);
            signature = mix(signature, mesh.bytes.length);
        }

        private static long mix(long hash, long value) {
            hash ^= value;
            return hash * 0x100000001b3L;
        }
    }

    private static final class SectionMesh {
        final BlockPos pos;
        final NativeMeshCapture.CapturedMesh mesh;

        SectionMesh(BlockPos pos, NativeMeshCapture.CapturedMesh mesh) {
            this.pos = pos;
            this.mesh = mesh;
        }
    }

    private static final class RegionBatch {
        final long key;
        final int originX;
        final int originZ;
        VertexBuffer buffer;
        long signature;
        int totalBytes;
        int sectionCount;
        int vertexCount;
        long lastUsedFrame;

        RegionBatch(long key, int originX, int originZ) {
            this.key = key;
            this.originX = originX;
            this.originZ = originZ;
        }

        boolean matches(PendingRegion pending) {
            return buffer != null
                    && signature == pending.signature
                    && totalBytes == pending.totalBytes
                    && sectionCount == pending.sections.size()
                    && vertexCount == pending.vertexCount;
        }

        void rebuild(PendingRegion pending) {
            ByteBuffer output = BufferUtils.createByteBuffer(pending.totalBytes).order(ByteOrder.nativeOrder());
            for (SectionMesh section : pending.sections) appendSection(output, section);
            output.flip();

            VertexBuffer replacement = new VertexBuffer(VertexFormats.BLOCK);
            NativeMeshCapture.beginInternalUpload();
            try {
                replacement.data(output);
            } finally {
                NativeMeshCapture.endInternalUpload();
            }

            if (buffer != null) buffer.delete();
            buffer = replacement;
            signature = pending.signature;
            totalBytes = pending.totalBytes;
            sectionCount = pending.sections.size();
            vertexCount = pending.vertexCount;
        }

        private void appendSection(ByteBuffer output, SectionMesh section) {
            byte[] bytes = section.mesh.bytes;
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            int vertexSize = NativeMeshCapture.BLOCK_VERTEX_SIZE;
            for (int offset = 0; offset < bytes.length; offset += vertexSize) {
                int destination = output.position();
                output.put(bytes, offset, vertexSize);

                float x = input.getFloat(offset);
                float y = input.getFloat(offset + 4);
                float z = input.getFloat(offset + 8);
                float rebasedX = scale(x) + section.pos.getX() - originX;
                float rebasedY = scale(y) + section.pos.getY();
                float rebasedZ = scale(z) + section.pos.getZ() - originZ;
                output.putFloat(destination, rebasedX);
                output.putFloat(destination + 4, rebasedY);
                output.putFloat(destination + 8, rebasedZ);
            }
        }

        private static float scale(float coordinate) {
            return (coordinate - 8.0F) * CHUNK_SCALE + 8.0F;
        }

        void render(double viewX, double viewY, double viewZ) {
            GlStateManager.pushMatrix();
            GlStateManager.translate((float) (originX - viewX), (float) -viewY, (float) (originZ - viewZ));
            buffer.bind();
            setupBlockPointers();
            buffer.draw(GL11.GL_QUADS);
            GlStateManager.popMatrix();
        }

        void delete() {
            if (buffer != null) {
                buffer.delete();
                buffer = null;
            }
        }
    }
}
