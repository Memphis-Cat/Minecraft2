package com.memphiscat.legacyculling.config;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class LegacyCullingConfig {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("legacy-culling.properties");

    public boolean enabled = true;
    public boolean minimalPerformanceOverlay = true;
    public boolean showStatistics = true;

    // 0 = vanilla compatibility, 1 = native renderer experimental.
    public int rendererBackend = 1;
    public boolean sectionVisibilityHierarchy = true;
    public int sectionGraphBuildsPerFrame = 2;

    // Stage two: vanilla-built solid meshes are repacked into region VBOs.
    public boolean nativeTerrainBatching = true;
    public int nativeRegionSizeChunks = 8;
    public int nativeRegionBuildsPerFrame = 2;
    public int nativeRegionCacheLimit = 128;

    public boolean entityCulling = true;
    public int entityCullingIntervalMs = 10;
    public int occlusionHideFrames = 3;
    public boolean smartEntityCulling = true;
    public boolean entityHierarchicalZ = true;
    public boolean blockEntityCulling = true;
    public boolean particleCulling = true;
    public boolean particleDensity = true;
    public boolean fogCulling = true;
    public boolean weatherCulling = true;
    public boolean leafFaceCulling = true;
    public boolean signTextCulling = true;
    public boolean decorationBackfaceCulling = true;
    public boolean shaderShadowSafety = true;

    public boolean dontCullEnderDragons = true;
    public boolean dontCullWithers = true;
    public boolean dontCullPlayerNametags = true;
    public boolean dontCullEntityNametags = true;
    public boolean dontCullArmorstandNametags = true;
    public boolean checkArmorstandRules = true;

    public boolean entityBackfaceCulling = false;
    public boolean playerBackfaceCulling = false;
    public boolean disableArmorstands = false;
    public boolean disableSemitransparentPlayers = false;
    public boolean disableEnchantmentBooks = false;
    public boolean disableItemFrames = false;
    public boolean disableMappedItemFrames = false;
    public boolean disableGroundedArrows = false;
    public boolean disableAttachedArrows = false;
    public boolean disableSkulls = false;
    public boolean disableFallingBlocks = false;
    public boolean disableNametagBoxes = false;
    public boolean unstackedItems = false;
    public boolean disableEndPortals = false;
    public boolean disableEnchantmentGlint = false;

    public boolean customEntityRenderDistance = false;
    public int tileEntityRenderDistance = 128;
    public int hostileEntityRenderDistance = 128;
    public int passiveEntityRenderDistance = 96;
    public int playerEntityRenderDistance = 192;
    public int globalEntityRenderDistance = 192;

    public boolean staticParticleColor = true;
    public boolean maxParticleLimit = true;
    public int maxParticles = 4000;
    public int particleMaxDistance = 96;
    public int particleCellLimit = 48;
    public boolean lowAnimationTick = true;
    public int animationTickRate = 500;

    public boolean limitChunkUpdates = true;
    public boolean adaptiveChunkLoading = true;
    public int chunkUpdateLimit = 120;
    public int chunkBurstLimit = 16;
    public int chunkTargetFps = 60;

    public boolean farTerrainLod = true;
    public int farTerrainDistance = 32;
    public int farTerrainSampleStep = 4;
    public int farTerrainCacheChunks = 1024;
    public int farTerrainBuildsPerFrame = 2;
    public int farTerrainRenderBudget = 384;
    public int farTerrainMinFps = 50;
    public boolean farTerrainShaderSafety = true;

    public boolean optimizedFontRenderer = true;
    public boolean cacheFontData = true;
    public boolean optimizedWorldSwapping = true;
    public boolean downscalePackImages = true;

    public int hierarchicalZCaptureInterval = 6;
    public int hierarchicalZMaxWidth = 320;

    public static LegacyCullingConfig load() {
        LegacyCullingConfig config = new LegacyCullingConfig();
        Properties properties = new Properties();
        if (Files.isRegularFile(PATH)) {
            try (InputStream input = Files.newInputStream(PATH)) {
                properties.load(input);
                config.read(properties);
            } catch (IOException exception) {
                LegacyCullingMod.LOGGER.warn("Could not read {}", PATH, exception);
            }
        }
        config.clamp();
        config.save();
        return config;
    }

    private void read(Properties properties) {
        for (Field field : getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String raw = properties.getProperty(field.getName());
            if (raw == null) continue;
            try {
                if (field.getType() == boolean.class) field.setBoolean(this, Boolean.parseBoolean(raw.trim()));
                else if (field.getType() == int.class) field.setInt(this, Integer.parseInt(raw.trim()));
            } catch (IllegalAccessException | NumberFormatException exception) {
                LegacyCullingMod.LOGGER.warn("Ignoring invalid setting {}={}", field.getName(), raw);
            }
        }
    }

    private void clamp() {
        rendererBackend = clamp(rendererBackend, 0, 1);
        sectionGraphBuildsPerFrame = clamp(sectionGraphBuildsPerFrame, 1, 16);
        nativeRegionSizeChunks = clamp(nativeRegionSizeChunks, 2, 16);
        nativeRegionBuildsPerFrame = clamp(nativeRegionBuildsPerFrame, 1, 16);
        nativeRegionCacheLimit = clamp(nativeRegionCacheLimit, 16, 512);
        entityCullingIntervalMs = clamp(entityCullingIntervalMs, 0, 1000);
        occlusionHideFrames = clamp(occlusionHideFrames, 2, 12);
        tileEntityRenderDistance = clamp(tileEntityRenderDistance, 16, 512);
        hostileEntityRenderDistance = clamp(hostileEntityRenderDistance, 16, 512);
        passiveEntityRenderDistance = clamp(passiveEntityRenderDistance, 16, 512);
        playerEntityRenderDistance = clamp(playerEntityRenderDistance, 16, 512);
        globalEntityRenderDistance = clamp(globalEntityRenderDistance, 16, 512);
        maxParticles = clamp(maxParticles, 64, 100000);
        particleMaxDistance = clamp(particleMaxDistance, 16, 256);
        particleCellLimit = clamp(particleCellLimit, 8, 512);
        animationTickRate = clamp(animationTickRate, 20, 1000);
        chunkUpdateLimit = clamp(chunkUpdateLimit, 1, 1000);
        chunkBurstLimit = clamp(chunkBurstLimit, 1, 128);
        chunkTargetFps = clamp(chunkTargetFps, 30, 240);
        farTerrainDistance = clamp(farTerrainDistance, 8, 64);
        farTerrainSampleStep = clamp(farTerrainSampleStep, 2, 8);
        farTerrainCacheChunks = clamp(farTerrainCacheChunks, 128, 4096);
        farTerrainBuildsPerFrame = clamp(farTerrainBuildsPerFrame, 1, 8);
        farTerrainRenderBudget = clamp(farTerrainRenderBudget, 32, 2048);
        farTerrainMinFps = clamp(farTerrainMinFps, 20, 240);
        hierarchicalZCaptureInterval = clamp(hierarchicalZCaptureInterval, 2, 30);
        hierarchicalZMaxWidth = clamp(hierarchicalZMaxWidth, 128, 1024);
    }

    public synchronized void save() {
        clamp();
        Properties properties = new Properties();
        for (Field field : getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                properties.setProperty(field.getName(), String.valueOf(field.get(this)));
            } catch (IllegalAccessException exception) {
                LegacyCullingMod.LOGGER.warn("Could not save setting {}", field.getName());
            }
        }
        try {
            Files.createDirectories(PATH.getParent());
            try (OutputStream output = Files.newOutputStream(PATH)) {
                properties.store(output, "Legacy Culling 1.8.9 settings");
            }
        } catch (IOException exception) {
            LegacyCullingMod.LOGGER.warn("Could not write {}", PATH, exception);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
