package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.visibility.LegacyVisibilityEngine;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Unique
    private boolean legacyculling$restoreCull;

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void legacyculling$testVisibility(Entity entity, CameraView cameraView,
                                              double cameraX, double cameraY, double cameraZ,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (LegacyVisibilityEngine.shouldCullEntity(entity, cameraX, cameraY, cameraZ)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "method_6913", at = @At("HEAD"))
    private void legacyculling$enableBackfaceCulling(Entity entity, double x, double y, double z,
                                                      float yaw, float tickDelta, boolean hitbox,
                                                      CallbackInfoReturnable<Boolean> cir) {
        boolean enabled = entity instanceof PlayerEntity
                ? LegacyCullingMod.CONFIG.playerBackfaceCulling
                : LegacyCullingMod.CONFIG.entityBackfaceCulling;
        if (!LegacyCullingMod.CONFIG.enabled || !enabled) return;
        legacyculling$restoreCull = !GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if (legacyculling$restoreCull) GlStateManager.enableCull();
    }

    @Inject(method = "method_6913", at = @At("RETURN"))
    private void legacyculling$restoreBackfaceCulling(Entity entity, double x, double y, double z,
                                                       float yaw, float tickDelta, boolean hitbox,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (legacyculling$restoreCull) {
            GlStateManager.disableCull();
            legacyculling$restoreCull = false;
        }
    }
}
