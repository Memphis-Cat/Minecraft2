package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.visibility.LegacyHzbFastPath;
import com.memphiscat.legacyculling.visibility.LegacyVisibilityEngine;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "renderEntity(Lnet/minecraft/block/entity/BlockEntity;FI)V", at = @At("HEAD"), cancellable = true)
    private void legacyculling$cullBlockEntity(BlockEntity blockEntity, float tickDelta, int destroyStage,
                                                CallbackInfo ci) {
        if (LegacyHzbFastPath.shouldCullBlockEntity(blockEntity)
                || LegacyVisibilityEngine.shouldCullBlockEntity(blockEntity)) {
            ci.cancel();
        }
    }
}
