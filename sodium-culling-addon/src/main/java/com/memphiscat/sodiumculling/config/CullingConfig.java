package com.memphiscat.sodiumculling.config;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CullingConfig {
    public boolean entityCulling = true;
    public boolean blockEntityCulling = true;
    public boolean particleCulling = true;
    public boolean fogCulling = true;
    public boolean leafCulling = true;
    public boolean weatherCulling = true;
    public boolean signTextCulling = true;
    public boolean animationCulling = true;
    public int entityMaxDistance = 192;
    public int blockEntityMaxDistance = 160;
    public int particleMaxDistance = 96;
    public int particleCellLimit = 48;

    public static CullingConfig load() {
        CullingConfig config = new CullingConfig();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("sodium-culling.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
                config.read(properties);
            } catch (IOException ex) {
                SodiumCullingClient.LOGGER.warn("Could not read {}", path, ex);
            }
        }
        config.write(path);
        return config;
    }

    private void read(Properties p) {
        entityCulling = bool(p, "entityCulling", entityCulling);
        blockEntityCulling = bool(p, "blockEntityCulling", blockEntityCulling);
        particleCulling = bool(p, "particleCulling", particleCulling);
        fogCulling = bool(p, "fogCulling", fogCulling);
        leafCulling = bool(p, "leafCulling", leafCulling);
        weatherCulling = bool(p, "weatherCulling", weatherCulling);
        signTextCulling = bool(p, "signTextCulling", signTextCulling);
        animationCulling = bool(p, "animationCulling", animationCulling);
        entityMaxDistance = integer(p, "entityMaxDistance", entityMaxDistance, 32, 512);
        blockEntityMaxDistance = integer(p, "blockEntityMaxDistance", blockEntityMaxDistance, 32, 512);
        particleMaxDistance = integer(p, "particleMaxDistance", particleMaxDistance, 16, 256);
        particleCellLimit = integer(p, "particleCellLimit", particleCellLimit, 8, 512);
    }

    private void write(Path path) {
        Properties p = new Properties();
        p.setProperty("entityCulling", Boolean.toString(entityCulling));
        p.setProperty("blockEntityCulling", Boolean.toString(blockEntityCulling));
        p.setProperty("particleCulling", Boolean.toString(particleCulling));
        p.setProperty("fogCulling", Boolean.toString(fogCulling));
        p.setProperty("leafCulling", Boolean.toString(leafCulling));
        p.setProperty("weatherCulling", Boolean.toString(weatherCulling));
        p.setProperty("signTextCulling", Boolean.toString(signTextCulling));
        p.setProperty("animationCulling", Boolean.toString(animationCulling));
        p.setProperty("entityMaxDistance", Integer.toString(entityMaxDistance));
        p.setProperty("blockEntityMaxDistance", Integer.toString(blockEntityMaxDistance));
        p.setProperty("particleMaxDistance", Integer.toString(particleMaxDistance));
        p.setProperty("particleCellLimit", Integer.toString(particleCellLimit));
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                p.store(out, "Sodium Culling Addon - conservative defaults");
            }
        } catch (IOException ex) {
            SodiumCullingClient.LOGGER.warn("Could not write {}", path, ex);
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int integer(Properties p, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
