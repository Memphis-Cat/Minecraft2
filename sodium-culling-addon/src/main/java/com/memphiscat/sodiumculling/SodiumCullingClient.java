package com.memphiscat.sodiumculling;

import com.memphiscat.sodiumculling.config.CullingConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SodiumCullingClient implements ClientModInitializer {
    public static final String MOD_ID = "sodiumculling";
    public static final String VERSION = "0.2.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static CullingConfig CONFIG;

    @Override
    public void onInitializeClient() {
        CONFIG = CullingConfig.load();
        LOGGER.info("Sodium Culling Addon {} initialized; all configured culling systems are active", VERSION);
    }
}
