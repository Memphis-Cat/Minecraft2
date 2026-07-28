package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import com.memphiscat.sodiumculling.visibility.VisibilityEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation sodiumculling$cullWeatherColumn(ClientLevel level, BlockPos pos) {
        if (!SodiumCullingClient.CONFIG.weatherCulling) {
            return level.getPrecipitationAt(pos);
        }
        int bottom = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        AABB column = new AABB(pos.getX(), bottom, pos.getZ(), pos.getX() + 1.0D,
                level.getMaxY() + 1.0D, pos.getZ() + 1.0D);
        if (!VisibilityEngine.isColumnVisible(column)) {
            return Biome.Precipitation.NONE;
        }
        return level.getPrecipitationAt(pos);
    }
}
