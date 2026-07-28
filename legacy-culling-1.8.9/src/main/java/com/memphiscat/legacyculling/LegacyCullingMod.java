package com.memphiscat.legacyculling;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LegacyCullingMod implements ModInitializer {
    public static final String MOD_ID = "legacyculling";
    public static final Logger LOGGER = LogManager.getLogger("Legacy Culling");

    @Override
    public void onInitialize() {
        LOGGER.info("Legacy Culling initialized for Minecraft 1.8.9");
    }
}
