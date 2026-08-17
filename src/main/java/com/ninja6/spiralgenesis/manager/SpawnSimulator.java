package com.ninja6.spiralgenesis.manager;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs allocation repeatedly against the live world and reports what it cost.
 *
 * <p>Exists because allocation is otherwise only reachable by a player joining, which makes
 * the terrain rules impossible to exercise without a game client. Everything the safety
 * thresholds depend on — how much real terrain they reject, and which rule does the
 * rejecting — is measurable from the server console through this instead.
 *
 * <p>Allocations run one after another rather than concurrently. They are safe to overlap,
 * but serialising them bounds how much chunk generation is in flight at once, which matters
 * when a run of any size is started on a live server.
 */
public final class SpawnSimulator {

    private SpawnSimulator() {
        // Utility class
    }

    /**
     * Allocates {@code samples} spawns and reports the aggregate.
     *
     * <p>Indices come from a throwaway counter, never {@code DataStorage.reserveNextIndex}:
     * a simulation must not advance the live spiral progression, or running it would
     * permanently push real players outward.
     */
    public static CompletableFuture<Report> run(SpawnManager manager, int samples) {
        Report report = new Report(samples);
        AtomicInteger indices = new AtomicInteger();

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < samples; i++) {
            chain = chain.thenCompose(ignored ->
                    manager.allocateNextSafeSpawn(indices::getAndIncrement).thenAccept(report::record));
        }
        return chain.thenApply(ignored -> report);
    }

    /** Aggregate outcome of a simulation run. */
    public static final class Report {

        private final int samples;
        private final Map<RejectionReason, Integer> rejections = new EnumMap<>(RejectionReason.class);
        private int completed;
        private int cellsProbed;
        private int candidatesProbed;
        private int fallbacks;
        private int minSurfaceY = Integer.MAX_VALUE;
        private int maxSurfaceY = Integer.MIN_VALUE;

        Report(int samples) {
            this.samples = samples;
        }

        void record(SpawnManager.LocationResult result) {
            completed++;
            cellsProbed += result.cellsProbed();
            candidatesProbed += result.candidatesProbed();
            if (result.fallback()) {
                fallbacks++;
            }
            minSurfaceY = Math.min(minSurfaceY, result.surfaceY());
            maxSurfaceY = Math.max(maxSurfaceY, result.surfaceY());
            result.rejections().forEach((reason, count) -> rejections.merge(reason, count, Integer::sum));
        }

        public int samples() {
            return samples;
        }

        public int completed() {
            return completed;
        }

        public int cellsProbed() {
            return cellsProbed;
        }

        public int candidatesProbed() {
            return candidatesProbed;
        }

        public int fallbacks() {
            return fallbacks;
        }

        public int minSurfaceY() {
            return completed == 0 ? 0 : minSurfaceY;
        }

        public int maxSurfaceY() {
            return completed == 0 ? 0 : maxSurfaceY;
        }

        public Map<RejectionReason, Integer> rejections() {
            return Map.copyOf(rejections);
        }

        /**
         * Spiral indices consumed per allocation. 1.0 is perfect; a high value means the
         * safety rules are discarding whole cells and pushing players outward.
         */
        public double indicesPerSpawn() {
            return completed == 0 ? 0.0 : (double) cellsProbed / completed;
        }

        /**
         * Single-line machine-readable summary.
         *
         * <p>Formatted for {@code grep} in CI rather than for reading: the smoke test
         * asserts on these fields, so the key names are part of the contract.
         */
        public String toSummaryLine() {
            return String.format(
                    "SIMULATE samples=%d completed=%d indices=%d ratio=%.2f candidates=%d "
                            + "fallbacks=%d minY=%d maxY=%d",
                    samples, completed, cellsProbed, indicesPerSpawn(), candidatesProbed,
                    fallbacks, minSurfaceY(), maxSurfaceY());
        }

        /** Companion line breaking the rejections down by rule. */
        public String toRejectionLine() {
            if (rejections.isEmpty()) {
                return "SIMULATE rejections none";
            }
            StringBuilder sb = new StringBuilder("SIMULATE rejections");
            rejections.forEach((reason, count) -> sb.append(' ').append(reason).append('=').append(count));
            return sb.toString();
        }
    }
}
