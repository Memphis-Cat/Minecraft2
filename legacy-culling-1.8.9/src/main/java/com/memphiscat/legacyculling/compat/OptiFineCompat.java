package com.memphiscat.legacyculling.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class OptiFineCompat {
    private static boolean initialized;
    private static Method configIsShaders;
    private static Field shaderPackLoaded;

    private OptiFineCompat() {
    }

    public static boolean shadersActive() {
        initialize();
        try {
            if (configIsShaders != null) {
                Object value = configIsShaders.invoke(null);
                if (Boolean.TRUE.equals(value)) return true;
            }
            if (shaderPackLoaded != null) {
                return shaderPackLoaded.getBoolean(null);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> config = Class.forName("Config");
            configIsShaders = config.getDeclaredMethod("isShaders");
            configIsShaders.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            shaderPackLoaded = shaders.getDeclaredField("shaderPackLoaded");
            shaderPackLoaded.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
