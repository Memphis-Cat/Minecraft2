package com.memphiscat.legacyculling.visibility;

import java.util.concurrent.atomic.AtomicLong;

public final class CullingStats {
    private static final AtomicLong ENTITIES = new AtomicLong();
    private static final AtomicLong BLOCK_ENTITIES = new AtomicLong();
    private static final AtomicLong PARTICLES = new AtomicLong();
    private static final AtomicLong PARTICLE_LIMIT = new AtomicLong();
    private static final AtomicLong LEAF_FACES = new AtomicLong();
    private static final AtomicLong SIGN_TEXT = new AtomicLong();
    private static final AtomicLong DISABLED_RENDERERS = new AtomicLong();
    private static final AtomicLong RAY_TESTS = new AtomicLong();
    private static final AtomicLong HZB_HITS = new AtomicLong();
    private static final AtomicLong HZB_CAPTURES = new AtomicLong();
    private static final AtomicLong CHUNK_UPDATES = new AtomicLong();

    private CullingStats() {
    }

    public static void entity() { ENTITIES.incrementAndGet(); }
    public static void blockEntity() { BLOCK_ENTITIES.incrementAndGet(); }
    public static void particle() { PARTICLES.incrementAndGet(); }
    public static void particleLimit() { PARTICLE_LIMIT.incrementAndGet(); }
    public static void leafFace() { LEAF_FACES.incrementAndGet(); }
    public static void signText() { SIGN_TEXT.incrementAndGet(); }
    public static void disabledRenderer() { DISABLED_RENDERERS.incrementAndGet(); }
    public static void rayTest() { RAY_TESTS.incrementAndGet(); }
    public static void hzbHit() { HZB_HITS.incrementAndGet(); }
    public static void hzbCapture() { HZB_CAPTURES.incrementAndGet(); }
    public static void chunkUpdate() { CHUNK_UPDATES.incrementAndGet(); }

    public static Snapshot snapshot() {
        return new Snapshot(ENTITIES.get(), BLOCK_ENTITIES.get(), PARTICLES.get(), PARTICLE_LIMIT.get(),
                LEAF_FACES.get(), SIGN_TEXT.get(), DISABLED_RENDERERS.get(), RAY_TESTS.get(),
                HZB_HITS.get(), HZB_CAPTURES.get(), CHUNK_UPDATES.get());
    }

    public static final class Snapshot {
        public final long entities;
        public final long blockEntities;
        public final long particles;
        public final long particleLimit;
        public final long leafFaces;
        public final long signText;
        public final long disabledRenderers;
        public final long rayTests;
        public final long hzbHits;
        public final long hzbCaptures;
        public final long chunkUpdates;

        private Snapshot(long entities, long blockEntities, long particles, long particleLimit,
                         long leafFaces, long signText, long disabledRenderers, long rayTests,
                         long hzbHits, long hzbCaptures, long chunkUpdates) {
            this.entities = entities;
            this.blockEntities = blockEntities;
            this.particles = particles;
            this.particleLimit = particleLimit;
            this.leafFaces = leafFaces;
            this.signText = signText;
            this.disabledRenderers = disabledRenderers;
            this.rayTests = rayTests;
            this.hzbHits = hzbHits;
            this.hzbCaptures = hzbCaptures;
            this.chunkUpdates = chunkUpdates;
        }
    }
}
