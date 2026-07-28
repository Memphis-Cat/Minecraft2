package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.visibility.VisibilityEngine;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private void sodiumculling$cullBlockEntity(BlockEntity blockEntity, float partialTick,
                                                ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
                                                boolean globallyRendered,
                                                CallbackInfoReturnable<BlockEntityRenderState> cir) {
        if (globallyRendered) {
            return;
        }
        BlockEntityRenderer<?> renderer = getRenderer(blockEntity);
        if (renderer == null || renderer.shouldRenderOffScreen()) {
            return;
        }
        if (VisibilityEngine.shouldCullBlockEntity(blockEntity)) {
            cir.setReturnValue(null);
        }
    }

    @Shadow
    public abstract <E extends BlockEntity> BlockEntityRenderer<E> getRenderer(E blockEntity);
}
