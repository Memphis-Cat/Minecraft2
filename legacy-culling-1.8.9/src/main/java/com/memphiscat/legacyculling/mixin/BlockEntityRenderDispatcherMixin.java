package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.renderer.NativeRendererCoordinator;
import com.memphiscat.legacyculling.visibility.LegacyVisibilityEngine;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V", at = @At("HEAD"), cancellable = true)
    private void legacyculling$cullBlockEntity(BlockEntity blockEntity, float tickDelta, int destroyStage,
                                                CallbackInfo ci) {
        if (blockEntity != null) {
            BlockPos pos = blockEntity.getPos();
            if (NativeRendererCoordinator.definitelyReachable(
                    new Box(pos, pos.add(1, 1, 1)).expand(0.20D, 0.70D, 0.20D))) return;
        }
        if (LegacyVisibilityEngine.shouldCullBlockEntity(blockEntity)) ci.cancel();
    }
}
