package com.memphiscat.sodiumculling.visibility;

import java.util.concurrent.atomic.AtomicLong;

public final class CullingStats {
    private static final AtomicLong ENTITIES = new AtomicLong();
    private static final AtomicLong BLOCK_ENTITIES = new AtomicLong();
    private static final AtomicLong PARTICLES = new AtomicLong();
    private static final AtomicLong PARTICLE_ADMISSION = new AtomicLong();
    private static final AtomicLong WEATHER_COLUMNS = new AtomicLong();
    private static final AtomicLong LEAF_FACES = new AtomicLong();
    private static final AtomicLong SIGN_SIDES = new AtomicLong();
    private static final AtomicLong HZB_CAPTURES = new AtomicLong();
    private static final AtomicLong HZB_HITS = new AtomicLong();
    private static final AtomicLong RAY_TESTS = new AtomicLong();

    private CullingStats() {
    }

    public static void entity() { ENTITIES.incrementAndGet(); }
    public static void blockEntity() { BLOCK_ENTITIES.incrementAndGet(); }
    public static void particle() { PARTICLES.incrementAndGet(); }
    public static void particleAdmission() { PARTICLE_ADMISSION.incrementAndGet(); }
    public static void weatherColumn() { WEATHER_COLUMNS.incrementAndGet(); }
    public static void leafFace() { LEAF_FACES.incrementAndGet(); }
    public static void signSide() { SIGN_SIDES.incrementAndGet(); }
    public static void hzbCapture() { HZB_CAPTURES.incrementAndGet(); }
    public static void hzbHit() { HZB_HITS.incrementAndGet(); }
    public static void rayTest() { RAY_TESTS.incrementAndGet(); }

    public static Snapshot snapshot() {
        return new Snapshot(
                ENTITIES.get(), BLOCK_ENTITIES.get(), PARTICLES.get(), PARTICLE_ADMISSION.get(),
                WEATHER_COLUMNS.get(), LEAF_FACES.get(), SIGN_SIDES.get(), HZB_CAPTURES.get(),
                HZB_HITS.get(), RAY_TESTS.get()
        );
    }

    public record Snapshot(long entities, long blockEntities, long particles, long particleAdmission,
                           long weatherColumns, long leafFaces, long signSides, long hzbCaptures,
                           long hzbHits, long rayTests) {
    }
}
