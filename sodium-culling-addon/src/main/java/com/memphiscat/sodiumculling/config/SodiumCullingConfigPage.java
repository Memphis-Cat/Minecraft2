package com.memphiscat.sodiumculling.config;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class SodiumCullingConfigPage implements ConfigEntryPoint {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SodiumCullingClient.MOD_ID, path);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        CullingConfig config = SodiumCullingClient.CONFIG;
        StorageEventHandler storage = config::save;

        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.literal("Culling Addon"));

        OptionGroupBuilder general = builder.createOptionGroup()
                .setName(Component.literal("Status and diagnostics"))
                .addOption(builder.createBooleanOption(id("enabled"))
                        .setName(Component.literal("Culling Addon Enabled"))
                        .setTooltip(Component.literal("Master switch. The page itself confirms that the addon loaded through Sodium's Config API."))
                        .setImpact(OptionImpact.HIGH)
                        .setStorageHandler(storage)
                        .setBinding(value -> config.enabled = value, () -> config.enabled)
                        .setDefaultValue(true))
                .addOption(builder.createBooleanOption(id("show_statistics"))
                        .setName(Component.literal("Show Live Statistics in F3"))
                        .setTooltip(Component.literal("Adds cumulative entity, block-entity, particle, weather, leaf-face, sign, HZB and ray-test counters to the F3 screen."))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(storage)
                        .setBinding(value -> config.showStatistics = value, () -> config.showStatistics)
                        .setDefaultValue(true));
        page.addOptionGroup(general);

        OptionGroupBuilder systems = builder.createOptionGroup()
                .setName(Component.literal("Culling systems"))
                .addOption(booleanOption(builder, storage, "entity_culling", "Entity and Animation Culling",
                        "Skips entity render-state extraction, model animation and submission when the entity is outside distance/frustum limits, back-facing when applicable, or fully occluded.",
                        value -> config.entityCulling = value, () -> config.entityCulling, OptionImpact.HIGH))
                .addOption(booleanOption(builder, storage, "block_entity_culling", "Block Entity Culling",
                        "Culls chests, signs, item-display block entities and other non-global block entities. Iris shadow extraction is preserved separately.",
                        value -> config.blockEntityCulling = value, () -> config.blockEntityCulling, OptionImpact.HIGH))
                .addOption(booleanOption(builder, storage, "particle_culling", "Particle Culling and Density Limit",
                        "Rejects distant particle bursts and skips particles outside the camera or behind solid geometry.",
                        value -> config.particleCulling = value, () -> config.particleCulling, OptionImpact.HIGH))
                .addOption(booleanOption(builder, storage, "hierarchical_z", "Hierarchical-Z Occlusion",
                        "Uses asynchronous previous-frame depth readback and a conservative depth pyramid. It remains active with Iris and never blocks waiting for the GPU.",
                        value -> config.hierarchicalZCulling = value, () -> config.hierarchicalZCulling, OptionImpact.HIGH))
                .addOption(booleanOption(builder, storage, "fog_culling", "Fog-Distance Culling",
                        "Uses Minecraft's current camera fog end distance, including the render state produced while Iris is loaded.",
                        value -> config.fogCulling = value, () -> config.fogCulling, OptionImpact.MEDIUM))
                .addOption(booleanOption(builder, storage, "weather_culling", "Weather Column Culling",
                        "Skips rain and snow columns outside the frustum or beyond the active fog distance.",
                        value -> config.weatherCulling = value, () -> config.weatherCulling, OptionImpact.MEDIUM))
                .addOption(booleanOption(builder, storage, "sign_text_culling", "Sign Text Side Culling",
                        "Only extracts the sign-text side that can face the camera, with an edge-on safety overlap.",
                        value -> config.signTextCulling = value, () -> config.signTextCulling, OptionImpact.MEDIUM))
                .addOption(builder.createBooleanOption(id("leaf_culling"))
                        .setName(Component.literal("Internal Leaf Face Culling"))
                        .setTooltip(Component.literal("Removes faces shared by adjacent leaf blocks. Applying this option rebuilds chunk meshes."))
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(storage)
                        .setBinding(value -> config.leafCulling = value, () -> config.leafCulling)
                        .setDefaultValue(true));
        page.addOptionGroup(systems);

        OptionGroupBuilder limits = builder.createOptionGroup()
                .setName(Component.literal("Limits and HZB scheduling"))
                .addOption(integerOption(builder, storage, "entity_distance", "Maximum Entity Distance",
                        "Upper distance limit before render-distance and fog limits are applied.", 32, 512, 16,
                        value -> config.entityMaxDistance = value, () -> config.entityMaxDistance, " blocks"))
                .addOption(integerOption(builder, storage, "block_entity_distance", "Maximum Block Entity Distance",
                        "Upper distance limit for non-global block entities.", 32, 512, 16,
                        value -> config.blockEntityMaxDistance = value, () -> config.blockEntityMaxDistance, " blocks"))
                .addOption(integerOption(builder, storage, "particle_distance", "Maximum Particle Distance",
                        "Particles beyond this distance are rejected or skipped.", 16, 256, 8,
                        value -> config.particleMaxDistance = value, () -> config.particleMaxDistance, " blocks"))
                .addOption(integerOption(builder, storage, "particle_cell_limit", "Particle Burst Limit per 4x4x4 Cell",
                        "Maximum particles admitted in one spatial cell during a game tick.", 8, 512, 8,
                        value -> config.particleCellLimit = value, () -> config.particleCellLimit, " particles"))
                .addOption(integerOption(builder, storage, "hzb_interval", "HZB Capture Interval",
                        "Schedules one asynchronous depth readback every selected number of rendered frames. The renderer never waits for it.", 2, 30, 1,
                        value -> config.hierarchicalZCaptureInterval = value, () -> config.hierarchicalZCaptureInterval, " frames"))
                .addOption(integerOption(builder, storage, "hzb_width", "HZB CPU Pyramid Width",
                        "Maximum width of the conservative CPU depth pyramid after asynchronous readback.", 128, 1024, 64,
                        value -> config.hierarchicalZMaxWidth = value, () -> config.hierarchicalZMaxWidth, " px"));
        page.addOptionGroup(limits);

        builder.registerOwnModOptions()
                .setName("Sodium Culling Addon")
                .addPage(page);
    }

    private static net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder booleanOption(
            ConfigBuilder builder, StorageEventHandler storage, String id, String name, String tooltip,
            Consumer<Boolean> setter, Supplier<Boolean> getter, OptionImpact impact) {
        return builder.createBooleanOption(id(id))
                .setName(Component.literal(name))
                .setTooltip(Component.literal(tooltip))
                .setImpact(impact)
                .setStorageHandler(storage)
                .setBinding(setter, getter)
                .setDefaultValue(true);
    }

    private static IntegerOptionBuilder integerOption(
            ConfigBuilder builder, StorageEventHandler storage, String id, String name, String tooltip,
            int min, int max, int step, IntConsumer setter, IntSupplier getter, String suffix) {
        return builder.createIntegerOption(id(id))
                .setName(Component.literal(name))
                .setTooltip(Component.literal(tooltip))
                .setImpact(OptionImpact.VARIES)
                .setStorageHandler(storage)
                .setBinding(setter::accept, getter::getAsInt)
                .setDefaultValue(getter.getAsInt())
                .setRange(min, max, step)
                .setValueFormatter(value -> Component.literal(value + suffix));
    }
}
