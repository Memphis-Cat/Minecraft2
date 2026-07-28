package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LeavesBlock.class, priority = 1210)
public abstract class LeavesBlockMixin {
    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void sodiumculling$skipInternalLeafFace(BlockState state, BlockState adjacent,
                                                     Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (SodiumCullingClient.CONFIG.leafCulling && adjacent.getBlock() instanceof LeavesBlock) {
            cir.setReturnValue(true);
        }
    }
}
