package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.performance.AdaptiveChunkScheduler;
import com.memphiscat.legacyculling.visibility.CullingStats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererChunkUpdateMixin {
    @Redirect(method = "updateChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/chunk/ChunkBuilder;send(Lnet/minecraft/client/world/BuiltChunk;)Z"))
    private boolean legacyculling$scheduleChunkUpdate(ChunkBuilder builder, BuiltChunk chunk) {
        if (!AdaptiveChunkScheduler.tryAcquire()) return false;
        boolean accepted = builder.send(chunk);
        if (accepted) CullingStats.chunkUpdate();
        return accepted;
    }
}
