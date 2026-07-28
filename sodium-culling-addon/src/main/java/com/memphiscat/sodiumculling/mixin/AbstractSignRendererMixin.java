package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import com.memphiscat.sodiumculling.visibility.VisibilityEngine;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractSignRenderer.class)
public abstract class AbstractSignRendererMixin {
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/SignBlockEntity;getFrontText()Lnet/minecraft/world/level/block/entity/SignText;"))
    private SignText sodiumculling$cullFrontText(SignBlockEntity sign) {
        return shouldRenderSide(sign, true) ? sign.getFrontText() : null;
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/SignBlockEntity;getBackText()Lnet/minecraft/world/level/block/entity/SignText;"))
    private SignText sodiumculling$cullBackText(SignBlockEntity sign) {
        return shouldRenderSide(sign, false) ? sign.getBackText() : null;
    }

    private static boolean shouldRenderSide(SignBlockEntity sign, boolean front) {
        if (!SodiumCullingClient.CONFIG.enabled || !SodiumCullingClient.CONFIG.signTextCulling) {
            return true;
        }

        BlockState state = sign.getBlockState();
        Direction facing;
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        } else if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            int rotation = state.getValue(BlockStateProperties.ROTATION_16);
            facing = Direction.fromYRot(rotation * 22.5D);
        } else {
            return true;
        }

        Vec3 normal = new Vec3(facing.getStepX(), 0.0D, facing.getStepZ());
        BlockPos pos = sign.getBlockPos();
        Vec3 center = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.75D, pos.getZ() + 0.5D);
        return VisibilityEngine.isTextSideVisible(normal, center, front);
    }
}
