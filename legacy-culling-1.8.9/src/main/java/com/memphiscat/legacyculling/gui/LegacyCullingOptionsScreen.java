package com.memphiscat.legacyculling.gui;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class LegacyCullingOptionsScreen extends Screen {
    private static final int PAGE_SIZE = 12;
    private static final String[] FIELD_ORDER = {
            "enabled", "minimalPerformanceOverlay", "showStatistics", "entityCulling",
            "entityCullingIntervalMs", "occlusionHideFrames", "smartEntityCulling",
            "dontCullEnderDragons", "dontCullWithers", "dontCullPlayerNametags", "dontCullEntityNametags",
            "dontCullArmorstandNametags", "checkArmorstandRules", "entityBackfaceCulling", "playerBackfaceCulling",
            "disableArmorstands", "disableSemitransparentPlayers", "disableItemFrames", "disableMappedItemFrames",
            "disableGroundedArrows", "disableAttachedArrows", "disableFallingBlocks", "disableSkulls",
            "disableEnchantmentBooks", "disableEndPortals", "disableEnchantmentGlint", "disableNametagBoxes",
            "unstackedItems", "customEntityRenderDistance", "tileEntityRenderDistance", "hostileEntityRenderDistance",
            "passiveEntityRenderDistance", "playerEntityRenderDistance", "globalEntityRenderDistance",
            "blockEntityCulling", "particleCulling", "particleDensity", "maxParticleLimit", "maxParticles",
            "particleMaxDistance", "particleCellLimit", "staticParticleColor", "fogCulling", "weatherCulling",
            "leafFaceCulling", "signTextCulling", "decorationBackfaceCulling", "entityHierarchicalZ",
            "hierarchicalZCaptureInterval", "hierarchicalZMaxWidth", "shaderShadowSafety",
            "limitChunkUpdates", "adaptiveChunkLoading", "chunkUpdateLimit", "chunkBurstLimit", "chunkTargetFps",
            "farTerrainLod", "farTerrainDistance", "farTerrainSampleStep", "farTerrainCacheChunks",
            "farTerrainBuildsPerFrame", "farTerrainRenderBudget", "farTerrainMinFps", "farTerrainShaderSafety",
            "lowAnimationTick", "animationTickRate", "optimizedFontRenderer", "cacheFontData",
            "optimizedWorldSwapping", "downscalePackImages"
    };

    private final Screen parent;
    private final List<Option> options = new ArrayList<Option>();
    private int page;

    public LegacyCullingOptionsScreen(Screen parent) {
        this.parent = parent;
        for (String name : FIELD_ORDER) {
            try {
                Field field = LegacyCullingMod.CONFIG.getClass().getField(name);
                options.add(new Option(field));
            } catch (NoSuchFieldException ignored) {
            }
        }
    }

    @Override
    public void init() {
        buttons.clear();
        int start = page * PAGE_SIZE;
        int end = Math.min(options.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int local = index - start;
            int column = local & 1;
            int row = local >> 1;
            int x = width / 2 - 205 + column * 210;
            int y = 42 + row * 24;
            buttons.add(new ButtonWidget(1000 + index, x, y, 200, 20, options.get(index).label()));
        }
        buttons.add(new ButtonWidget(1, width / 2 - 155, height - 28, 100, 20, "Previous"));
        buttons.add(new ButtonWidget(2, width / 2 - 50, height - 28, 100, 20, "Done"));
        buttons.add(new ButtonWidget(3, width / 2 + 55, height - 28, 100, 20, "Next"));
        buttons.get(buttons.size() - 3).active = page > 0;
        buttons.get(buttons.size() - 1).active = (page + 1) * PAGE_SIZE < options.size();
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (!button.active) return;
        if (button.id == 1) {
            page--;
            init();
        } else if (button.id == 2) {
            LegacyCullingMod.CONFIG.save();
            client.setScreen(parent);
        } else if (button.id == 3) {
            page++;
            init();
        } else if (button.id >= 1000) {
            int index = button.id - 1000;
            if (index >= 0 && index < options.size()) {
                options.get(index).cycle();
                LegacyCullingMod.CONFIG.save();
                button.message = options.get(index).label();
            }
        }
    }

    @Override
    protected void keyPressed(char character, int keyCode) {
        if (keyCode == 1) {
            LegacyCullingMod.CONFIG.save();
            client.setScreen(parent);
        } else {
            super.keyPressed(character, keyCode);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        renderBackground();
        drawCenteredString(textRenderer, "Legacy Culling 1.8.9", width / 2, 12, 0xFFFFFF);
        drawCenteredString(textRenderer, "Page " + (page + 1) + " / " + ((options.size() + PAGE_SIZE - 1) / PAGE_SIZE),
                width / 2, 25, 0xA0A0A0);
        super.render(mouseX, mouseY, tickDelta);
    }

    private static final class Option {
        private final Field field;

        Option(Field field) {
            this.field = field;
        }

        String label() {
            try {
                return humanize(field.getName()) + ": " + field.get(LegacyCullingMod.CONFIG);
            } catch (IllegalAccessException exception) {
                return humanize(field.getName()) + ": error";
            }
        }

        void cycle() {
            try {
                if (field.getType() == boolean.class) {
                    field.setBoolean(LegacyCullingMod.CONFIG, !field.getBoolean(LegacyCullingMod.CONFIG));
                    return;
                }
                int value = field.getInt(LegacyCullingMod.CONFIG);
                int step = step(field.getName());
                int min = minimum(field.getName());
                int max = maximum(field.getName());
                value += step;
                if (value > max) value = min;
                field.setInt(LegacyCullingMod.CONFIG, value);
            } catch (IllegalAccessException ignored) {
            }
        }

        private static String humanize(String value) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (i > 0 && Character.isUpperCase(c)) result.append(' ');
                result.append(i == 0 ? Character.toUpperCase(c) : c);
            }
            return result.toString();
        }

        private static int step(String name) {
            if (name.equals("farTerrainDistance")) return 4;
            if (name.equals("farTerrainSampleStep")) return 2;
            if (name.equals("farTerrainCacheChunks")) return 256;
            if (name.equals("farTerrainRenderBudget")) return 64;
            if (name.equals("farTerrainMinFps") || name.equals("chunkTargetFps")) return 10;
            if (name.equals("chunkBurstLimit")) return 4;
            if (name.contains("Distance") || name.contains("Width")) return 16;
            if (name.equals("maxParticles")) return 500;
            if (name.equals("particleCellLimit")) return 8;
            if (name.equals("animationTickRate")) return 100;
            if (name.equals("chunkUpdateLimit")) return 20;
            if (name.equals("entityCullingIntervalMs")) return 5;
            return 1;
        }

        private static int minimum(String name) {
            if (name.equals("entityCullingIntervalMs")) return 0;
            if (name.equals("occlusionHideFrames")) return 2;
            if (name.equals("maxParticles")) return 500;
            if (name.equals("particleCellLimit")) return 8;
            if (name.equals("animationTickRate")) return 100;
            if (name.equals("chunkUpdateLimit")) return 20;
            if (name.equals("chunkBurstLimit")) return 4;
            if (name.equals("chunkTargetFps") || name.equals("farTerrainMinFps")) return 30;
            if (name.equals("farTerrainDistance")) return 8;
            if (name.equals("farTerrainSampleStep")) return 2;
            if (name.equals("farTerrainCacheChunks")) return 128;
            if (name.equals("farTerrainBuildsPerFrame")) return 1;
            if (name.equals("farTerrainRenderBudget")) return 32;
            if (name.equals("hierarchicalZCaptureInterval")) return 2;
            if (name.contains("Distance")) return 16;
            if (name.contains("Width")) return 128;
            return 1;
        }

        private static int maximum(String name) {
            if (name.equals("entityCullingIntervalMs")) return 250;
            if (name.equals("occlusionHideFrames")) return 12;
            if (name.equals("maxParticles")) return 20000;
            if (name.equals("particleCellLimit")) return 256;
            if (name.equals("animationTickRate")) return 1000;
            if (name.equals("chunkUpdateLimit")) return 1000;
            if (name.equals("chunkBurstLimit")) return 128;
            if (name.equals("chunkTargetFps") || name.equals("farTerrainMinFps")) return 240;
            if (name.equals("farTerrainDistance")) return 64;
            if (name.equals("farTerrainSampleStep")) return 8;
            if (name.equals("farTerrainCacheChunks")) return 4096;
            if (name.equals("farTerrainBuildsPerFrame")) return 8;
            if (name.equals("farTerrainRenderBudget")) return 2048;
            if (name.equals("hierarchicalZCaptureInterval")) return 30;
            if (name.contains("Distance")) return 512;
            if (name.contains("Width")) return 1024;
            return 1000;
        }
    }
}
