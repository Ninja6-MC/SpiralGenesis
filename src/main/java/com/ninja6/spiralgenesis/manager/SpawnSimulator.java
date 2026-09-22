package com.ninja6.spiralgenesis.manager;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
     * permanently push real players outward. That counter starts at zero, so a run scans the
     * origin however far the live spiral has grown, which is why it goes through
     * {@link SpawnManager#simulateNextSafeSpawn}: what a run concludes about its own index
     * range must not be applied to the live one, in either direction.
     */
    public static CompletableFuture<Report> run(SpawnManager manager, int samples) {
        Report report = new Report(samples);
        AtomicInteger indices = new AtomicInteger();

        AtomicInteger current = new AtomicInteger();

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 1; i <= samples; i++) {
            int sample = i;
            chain = chain.thenCompose(ignored -> {
                if (report.failure() != null) {
                    return CompletableFuture.completedFuture(null);
                }
                current.set(sample);
                try {
                    return sample(manager, indices, report, sample);
                } catch (Throwable e) {
                    report.fail(sample, unwrap(e));
                    return CompletableFuture.completedFuture(null);
                }
            });
        }
        // The last line of defence rather than the expected path: sample() records its own
        // failures, so a chain that fails anyway is charged to the sample it was running,
        // and the report still goes out with it.
        return chain.handle((ignored, error) -> {
            if (error != null && report.failure() == null) {
                report.fail(Math.max(1, current.get()), unwrap(error));
            }
            return report;
        });
    }

    /**
     * Runs one sample and records whatever it came to, so that no single sample can take
     * the report down with it.
     *
     * <p>A sample that gives up against the border is an outcome, not an error: it is
     * counted and the run carries on, because a border that misses the origin can still
     * fit cells further out, and how many samples fit is what the operator is asking.
     * Anything else that fails a sample ends the run there, since a scheduler that has
     * gone away fails every sample after it the same way, and the report is delivered
     * with the failure attached rather than instead of it.
     */
    private static CompletableFuture<Void> sample(SpawnManager manager, AtomicInteger indices,
                                                  Report report, int sample) {
        // Samples run one at a time, so nothing else draws from the counter in between.
        int firstIndex = indices.get();
        CompletableFuture<SpawnManager.AllocationOutcome> pending;
        try {
            pending = manager.simulateNextSafeSpawn(indices::getAndIncrement);
            if (pending == null) {
                throw new IllegalStateException("Sample " + sample + " returned no allocation");
            }
        } catch (Throwable e) {
            pending = CompletableFuture.failedFuture(e);
        }
        return pending.handle((outcome, error) -> {
            // Guarded as a whole: a throw escaping this handler would fail the chain, and a
            // failed chain is what used to discard the report.
            try {
                if (error != null) {
                    report.fail(sample, unwrap(error));
                } else if (outcome == null) {
                    report.fail(sample, new IllegalStateException(
                            "Sample " + sample + " completed with neither a result nor an error"));
                } else {
                    switch (outcome) {
                        case SpawnManager.LocationResult found -> report.record(found);
                        case SpawnManager.BorderExhausted ignored ->
                                report.recordExhausted(sample, firstIndex);
                    }
                }
            } catch (Throwable e) {
                report.fail(sample, e);
            }
            return null;
        });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
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
        private int exhausted;
        private int firstExhaustedSample;
        private int firstExhaustedIndex = -1;
        private Throwable failure;
        private int failedSample;

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

        /**
         * Counts a sample whose scan found no plot inside the world border.
         *
         * <p>Its indices stay out of {@link #cellsProbed()}: that figure is what placed
         * spawns cost, and a scan walking its whole budget outside the border would inflate
         * the ratio CI guards with something that is not a terrain rule.
         */
        void recordExhausted(int sample, int firstIndex) {
            if (exhausted == 0) {
                firstExhaustedSample = sample;
                firstExhaustedIndex = firstIndex;
            }
            exhausted++;
        }

        void fail(int sample, Throwable cause) {
            failure = cause;
            failedSample = sample;
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

        /** Samples whose scan found no plot inside the world border. */
        public int borderExhausted() {
            return exhausted;
        }

        /** 1-based number of the first sample that exhausted, or 0 if none did. */
        public int firstExhaustedSample() {
            return firstExhaustedSample;
        }

        /**
         * Spiral index the first exhausted sample started scanning from, or -1 if none did:
         * where, counting from the origin, the spiral stopped fitting inside the border.
         */
        public int firstExhaustedIndex() {
            return firstExhaustedIndex;
        }

        /** What ended the run early, or {@code null} if every sample ran. */
        public Throwable failure() {
            return failure;
        }

        /**
         * What ended the run early, as one line for a chat reply, or {@code null} if every
         * sample ran. The failure's message, or its simple class name when it has no
         * message, so the reply never reads "null".
         */
        public String failureSummary() {
            if (failure == null) {
                return null;
            }
            String message = failure.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            // An anonymous class has an empty simple name; its full name at least says where.
            String type = failure.getClass().getSimpleName();
            return type.isEmpty() ? failure.getClass().getName() : type;
        }

        /** 1-based number of the sample that failed, or 0 if none did. */
        public int failedSample() {
            return failedSample;
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
            // Locale.ROOT is load-bearing, not defensive: CI parses `ratio=` out of this
            // line with awk. Under a comma-decimal default locale the value would render as
            // "3,50", which awk coerces to 3, so a real regression just above the threshold
            // would slip through the guard silently.
            return String.format(Locale.ROOT,
                    "SIMULATE samples=%d completed=%d indices=%d ratio=%.2f candidates=%d "
                            + "fallbacks=%d minY=%d maxY=%d exhausted=%d",
                    samples, completed, cellsProbed, indicesPerSpawn(), candidatesProbed,
                    fallbacks, minSurfaceY(), maxSurfaceY(), exhausted);
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
