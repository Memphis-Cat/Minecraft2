package com.memphiscat.legacyculling.renderer;

import com.memphiscat.legacyculling.LegacyCullingMod;
import org.lwjgl.opengl.GLContext;

/**
 * Immutable capability snapshot for the experimental native renderer.
 * The backend must fall back before allocating renderer resources when a
 * required feature is unavailable.
 */
public final class NativeRendererCapabilities {
    public final boolean bufferObjects;
    public final boolean vertexArrayObjects;
    public final boolean syncObjects;
    public final boolean computeShaders;
    public final boolean shaderStorageBuffers;
    public final boolean multiDrawIndirect;

    private NativeRendererCapabilities(boolean bufferObjects,
                                       boolean vertexArrayObjects,
                                       boolean syncObjects,
                                       boolean computeShaders,
                                       boolean shaderStorageBuffers,
                                       boolean multiDrawIndirect) {
        this.bufferObjects = bufferObjects;
        this.vertexArrayObjects = vertexArrayObjects;
        this.syncObjects = syncObjects;
        this.computeShaders = computeShaders;
        this.shaderStorageBuffers = shaderStorageBuffers;
        this.multiDrawIndirect = multiDrawIndirect;
    }

    public static NativeRendererCapabilities probe() {
        try {
            org.lwjgl.opengl.ContextCapabilities caps = GLContext.getCapabilities();
            return new NativeRendererCapabilities(
                    caps.OpenGL15,
                    caps.OpenGL30,
                    caps.OpenGL32,
                    caps.OpenGL43 || caps.GL_ARB_compute_shader,
                    caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object,
                    caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect);
        } catch (Throwable throwable) {
            LegacyCullingMod.LOGGER.warn("Could not query native renderer OpenGL capabilities", throwable);
            return new NativeRendererCapabilities(false, false, false, false, false, false);
        }
    }

    public boolean supportsBaseBackend() {
        return bufferObjects && vertexArrayObjects && syncObjects;
    }

    public boolean supportsGpuDrivenBackend() {
        return supportsBaseBackend() && computeShaders && shaderStorageBuffers && multiDrawIndirect;
    }
}
