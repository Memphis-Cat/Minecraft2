package com.memphiscat.legacyculling.mixin;

import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(AbstractChunkRenderManager.class)
public interface AbstractChunkRenderManagerAccessor {
    @Accessor("helpers")
    List<BuiltChunk> legacyculling$getHelpers();

    @Accessor("field_10667")
    boolean legacyculling$isActive();

    @Accessor("viewX")
    double legacyculling$getViewX();

    @Accessor("viewY")
    double legacyculling$getViewY();

    @Accessor("viewZ")
    double legacyculling$getViewZ();
}
