package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.visibility.VisibilityEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void sodiumculling$captureCamera(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
                                              LevelRenderState levelRenderState, CallbackInfo ci) {
        VisibilityEngine.beginFrame(camera, frustum);
    }

    @Inject(method = "extractEntity", at = @At("HEAD"), cancellable = true)
    private void sodiumculling$cullEntity(Entity entity, float partialTick,
                                           CallbackInfoReturnable<EntityRenderState> cir) {
        if (!VisibilityEngine.shouldCullEntity(entity)) {
            return;
        }

        EntityRenderState state = new EntityRenderState();
        state.entityType = EntityTypes.INTERACTION;
        state.x = Mth.lerp(partialTick, entity.xOld, entity.getX());
        state.y = Mth.lerp(partialTick, entity.yOld, entity.getY());
        state.z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        state.isInvisible = true;
        cir.setReturnValue(state);
    }
}
