package com.memphiscat.legacyculling;

import com.memphiscat.legacyculling.config.LegacyCullingConfig;
import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LegacyCullingMod implements ModInitializer {
    public static final String MOD_ID = "legacyculling";
    public static final Logger LOGGER = LogManager.getLogger("Legacy Culling");
    public static final LegacyCullingConfig CONFIG = LegacyCullingConfig.load();

    @Override
    public void onInitialize() {
        String message = "Legacy Culling initialized for Minecraft 1.8.9";
        LOGGER.info(message);
        System.out.println("[LegacyCulling] INIT_OK " + message);
    }
}
