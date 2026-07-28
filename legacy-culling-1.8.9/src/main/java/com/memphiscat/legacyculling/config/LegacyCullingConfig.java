package com.memphiscat.legacyculling.config;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class LegacyCullingConfig {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("legacy-culling.properties");

    public boolean enabled = true;
    public boolean showStatistics = true;
    public boolean entityOcclusion = true;
    public boolean blockEntityOcclusion = true;
    public boolean particleCulling = true;
    public boolean particleDensity = true;
    public boolean fogCulling = true;
    public boolean leafFaceCulling = true;
    public boolean signTextCulling = true;
    public boolean decorationBackfaceCulling = true;
    public boolean hierarchicalZCulling = true;
    public boolean shaderShadowSafety = true;

    public int particleMaxDistance = 96;
    public int particleCellLimit = 48;
    public int rayCacheTicks = 3;
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
        config.save();
        return config;
    }

    private void read(Properties properties) {
        enabled = bool(properties, "enabled", enabled);
        showStatistics = bool(properties, "showStatistics", showStatistics);
        entityOcclusion = bool(properties, "entityOcclusion", entityOcclusion);
        blockEntityOcclusion = bool(properties, "blockEntityOcclusion", blockEntityOcclusion);
        particleCulling = bool(properties, "particleCulling", particleCulling);
        particleDensity = bool(properties, "particleDensity", particleDensity);
        fogCulling = bool(properties, "fogCulling", fogCulling);
        leafFaceCulling = bool(properties, "leafFaceCulling", leafFaceCulling);
        signTextCulling = bool(properties, "signTextCulling", signTextCulling);
        decorationBackfaceCulling = bool(properties, "decorationBackfaceCulling", decorationBackfaceCulling);
        hierarchicalZCulling = bool(properties, "hierarchicalZCulling", hierarchicalZCulling);
        shaderShadowSafety = bool(properties, "shaderShadowSafety", shaderShadowSafety);
        particleMaxDistance = integer(properties, "particleMaxDistance", particleMaxDistance, 16, 256);
        particleCellLimit = integer(properties, "particleCellLimit", particleCellLimit, 8, 512);
        rayCacheTicks = integer(properties, "rayCacheTicks", rayCacheTicks, 1, 10);
        hierarchicalZCaptureInterval = integer(properties, "hierarchicalZCaptureInterval", hierarchicalZCaptureInterval, 2, 30);
        hierarchicalZMaxWidth = integer(properties, "hierarchicalZMaxWidth", hierarchicalZMaxWidth, 128, 1024);
    }

    public synchronized void save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("showStatistics", Boolean.toString(showStatistics));
        properties.setProperty("entityOcclusion", Boolean.toString(entityOcclusion));
        properties.setProperty("blockEntityOcclusion", Boolean.toString(blockEntityOcclusion));
        properties.setProperty("particleCulling", Boolean.toString(particleCulling));
        properties.setProperty("particleDensity", Boolean.toString(particleDensity));
        properties.setProperty("fogCulling", Boolean.toString(fogCulling));
        properties.setProperty("leafFaceCulling", Boolean.toString(leafFaceCulling));
        properties.setProperty("signTextCulling", Boolean.toString(signTextCulling));
        properties.setProperty("decorationBackfaceCulling", Boolean.toString(decorationBackfaceCulling));
        properties.setProperty("hierarchicalZCulling", Boolean.toString(hierarchicalZCulling));
        properties.setProperty("shaderShadowSafety", Boolean.toString(shaderShadowSafety));
        properties.setProperty("particleMaxDistance", Integer.toString(particleMaxDistance));
        properties.setProperty("particleCellLimit", Integer.toString(particleCellLimit));
        properties.setProperty("rayCacheTicks", Integer.toString(rayCacheTicks));
        properties.setProperty("hierarchicalZCaptureInterval", Integer.toString(hierarchicalZCaptureInterval));
        properties.setProperty("hierarchicalZMaxWidth", Integer.toString(hierarchicalZMaxWidth));
        try {
            Files.createDirectories(PATH.getParent());
            try (OutputStream output = Files.newOutputStream(PATH)) {
                properties.store(output, "Legacy Culling 1.8.9 settings");
            }
        } catch (IOException exception) {
            LegacyCullingMod.LOGGER.warn("Could not write {}", PATH, exception);
        }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int integer(Properties properties, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
