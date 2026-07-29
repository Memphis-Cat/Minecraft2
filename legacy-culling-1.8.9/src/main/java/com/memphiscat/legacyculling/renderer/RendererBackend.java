package com.memphiscat.legacyculling.renderer;

public enum RendererBackend {
    VANILLA_COMPATIBILITY(0),
    NATIVE_LEGACY(1);

    private final int id;

    RendererBackend(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static RendererBackend fromId(int id) {
        return id == 1 ? NATIVE_LEGACY : VANILLA_COMPATIBILITY;
    }
}
