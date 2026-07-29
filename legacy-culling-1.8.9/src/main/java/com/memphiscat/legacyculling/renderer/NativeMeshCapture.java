package com.memphiscat.legacyculling.renderer;

import net.minecraft.client.render.VertexBuffer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Captures vanilla's already-compiled block vertex stream before it is uploaded to a section VBO. */
public final class NativeMeshCapture {
    public static final int BLOCK_VERTEX_SIZE = 28;

    private static final Map<VertexBuffer, CapturedMesh> MESHES =
            Collections.synchronizedMap(new WeakHashMap<VertexBuffer, CapturedMesh>());
    private static final ThreadLocal<Boolean> INTERNAL_UPLOAD = new ThreadLocal<Boolean>();
    private static long nextGeneration = 1L;

    private NativeMeshCapture() {
    }

    public static void capture(VertexBuffer buffer, ByteBuffer source) {
        if (buffer == null || source == null || Boolean.TRUE.equals(INTERNAL_UPLOAD.get())) return;

        ByteBuffer duplicate = source.duplicate();
        int limit = duplicate.limit();
        if (limit <= 0 || limit % BLOCK_VERTEX_SIZE != 0) return;

        duplicate.position(0);
        byte[] bytes = new byte[limit];
        duplicate.get(bytes);

        synchronized (MESHES) {
            MESHES.put(buffer, new CapturedMesh(bytes, nextGeneration++));
        }
    }

    public static CapturedMesh get(VertexBuffer buffer) {
        return buffer == null ? null : MESHES.get(buffer);
    }

    public static void remove(VertexBuffer buffer) {
        if (buffer != null) MESHES.remove(buffer);
    }

    public static void clear() {
        MESHES.clear();
    }

    public static void beginInternalUpload() {
        INTERNAL_UPLOAD.set(Boolean.TRUE);
    }

    public static void endInternalUpload() {
        INTERNAL_UPLOAD.remove();
    }

    public static final class CapturedMesh {
        public final byte[] bytes;
        public final int vertexCount;
        public final long generation;

        private CapturedMesh(byte[] bytes, long generation) {
            this.bytes = bytes;
            this.vertexCount = bytes.length / BLOCK_VERTEX_SIZE;
            this.generation = generation;
        }
    }
}
