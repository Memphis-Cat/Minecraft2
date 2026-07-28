package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.visibility.CullingStats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererChunkUpdateMixin {
    @Unique
    private long legacyculling$chunkWindowStart;
    @Unique
    private int legacyculling$chunkUpdates;

    @Redirect(method = "updateChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/chunk/ChunkBuilder;send(Lnet/minecraft/client/world/BuiltChunk;)Z"))
    private boolean legacyculling$limitChunkUpdates(ChunkBuilder builder, BuiltChunk chunk) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.limitChunkUpdates) {
            return builder.send(chunk);
        }

        long now = System.currentTimeMillis();
        if (now - legacyculling$chunkWindowStart >= 1000L) {
            legacyculling$chunkWindowStart = now;
            legacyculling$chunkUpdates = 0;
        }
        if (legacyculling$chunkUpdates >= LegacyCullingMod.CONFIG.chunkUpdateLimit) {
            return false;
        }
        boolean accepted = builder.send(chunk);
        if (accepted) {
            legacyculling$chunkUpdates++;
            CullingStats.chunkUpdate();
        }
        return accepted;
    }
}
