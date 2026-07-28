package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.visibility.CullingStats;
import net.minecraft.block.BaseLeavesBlock;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseLeavesBlock.class)
public abstract class BaseLeavesBlockMixin {
    @Inject(method = "isSideInvisible", at = @At("HEAD"), cancellable = true)
    private void legacyculling$hideInternalLeafFace(BlockView world, BlockPos adjacentPos, Direction side,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.leafFaceCulling) return;
        Block adjacent = world.getBlockState(adjacentPos).getBlock();
        if (adjacent instanceof BaseLeavesBlock) {
            CullingStats.leafFace();
            cir.setReturnValue(false);
        }
    }
}
