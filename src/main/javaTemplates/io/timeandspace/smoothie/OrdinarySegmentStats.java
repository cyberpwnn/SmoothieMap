package io.timeandspace.smoothie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntToLongFunction;
import java.util.stream.IntStream;

import static io.timeandspace.smoothie.BitSetAndState.allocCapacity;
import static io.timeandspace.smoothie.HashTable.GROUP_SLOTS;
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_GROUPS;
import static io.timeandspace.smoothie.HashTable.HASH_TABLE_GROUPS_MASK;
import static io.timeandspace.smoothie.LongMath.percentOf;
import static io.timeandspace.smoothie.SmoothieMap.SEGMENT_MAX_ALLOC_CAPACITY;
import static java.util.Collections.singletonList;

/**
 * Stats of probing chain lengths for ordinary segments (that are, not
 * {@link io.timeandspace.smoothie.SmoothieMap.InflatedSegment}s).
 */
final class OrdinarySegmentStats {
    private static final int[] QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH =
            new int[HASH_TABLE_GROUPS];
    static {
        int groupIndex = 0;
        int step = 0;
        for (int chainLength = 0;
             chainLength < QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH.length;
             chainLength++) {
            QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH[groupIndex] = chainLength;
            step += 1; // [Quadratic probing]
            groupIndex = (groupIndex + step) % HASH_TABLE_GROUPS;
        }
    }

    private int numAggregatedSegments = 0;
    private long numAggregatedFullSlots = 0;
    private final long[] numAggregatedSegmentsPerAllocCapacity =
            new long[SEGMENT_MAX_ALLOC_CAPACITY + 1];
    private final long[] numSlotsPerCollisionChainGroupLengths =
            new long[SEGMENT_MAX_ALLOC_CAPACITY / GROUP_SLOTS];
    private final long[] numSlotsPerNumCollisionKeyComparisons =
            new long[SEGMENT_MAX_ALLOC_CAPACITY];
    private final long[] numSlotsPerDistancesToAllocIndexBoundary =
            new long[SEGMENT_MAX_ALLOC_CAPACITY];

    int getNumAggregatedSegments() {
        return numAggregatedSegments;
    }

    long getNumAggregatedFullSlots() {
        return numAggregatedFullSlots;
    }

    void aggregateFullSlot(long baseGroupIndex, long groupIndex, int numCollisionKeyComparisons
            /* if Interleaved segments */, int allocIndex, int allocIndexBoundaryForGroup
            /* endif */) {
        int quadraticProbingChainGroupIndex =
                (int) ((groupIndex - baseGroupIndex) & HASH_TABLE_GROUPS_MASK);
        int collisionChainGroupLength = QUADRATIC_PROBING_CHAIN_GROUP_INDEX_TO_CHAIN_LENGTH[
                quadraticProbingChainGroupIndex];
        numSlotsPerCollisionChainGroupLengths[collisionChainGroupLength]++;
        numSlotsPerNumCollisionKeyComparisons[numCollisionKeyComparisons]++;
        int distanceToAllocIndexBoundary;
        if (allocIndex >= allocIndexBoundaryForGroup) {
            distanceToAllocIndexBoundary = allocIndex - allocIndexBoundaryForGroup;
        } else {
            distanceToAllocIndexBoundary = allocIndexBoundaryForGroup - allocIndex - 1;
        }
        numSlotsPerDistancesToAllocIndexBoundary[distanceToAllocIndexBoundary]++;
        numAggregatedFullSlots++;
    }

    void incrementAggregatedSegments(long bitSetAndState) {
        numAggregatedSegments++;
        numAggregatedSegmentsPerAllocCapacity[allocCapacity(bitSetAndState)]++;
    }

    void add(OrdinarySegmentStats other) {
        numAggregatedSegments += other.numAggregatedSegments;
        addMetricArrays(
                numAggregatedSegmentsPerAllocCapacity, other.numAggregatedSegmentsPerAllocCapacity);
        numAggregatedFullSlots += other.numAggregatedFullSlots;
        addMetricArrays(
                numSlotsPerCollisionChainGroupLengths, other.numSlotsPerCollisionChainGroupLengths);
        addMetricArrays(
                numSlotsPerNumCollisionKeyComparisons, other.numSlotsPerNumCollisionKeyComparisons);
        addMetricArrays(numSlotsPerDistancesToAllocIndexBoundary,
                other.numSlotsPerDistancesToAllocIndexBoundary);
    }

    private static void addMetricArrays(long[] target, long[] source) {
        for (int i = 0; i < target.length; i++) {
            target[i] += source[i];
        }
    }

    @SuppressWarnings("AutoBoxing")
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Number of segments: %d%n", numAggregatedSegments));

        Count segments = new Count("segments",
                allocCapacity -> numAggregatedSegmentsPerAllocCapacity[allocCapacity]);
        appendNonZeroOrderedCountsWithPercentiles(
                sb, "segments with alloc capacity =", numAggregatedSegmentsPerAllocCapacity.length,
                singletonList(segments), allocCapacity -> {});

        double averageFullSlots = (double) numAggregatedFullSlots / (double) numAggregatedSegments;
        sb.append(String.format("Average full slots: %.2f%n", averageFullSlots));

        appendSlotMetricStats(
                sb, numSlotsPerCollisionChainGroupLengths, "collision chain group length");
        appendSlotMetricStats(
                sb, numSlotsPerNumCollisionKeyComparisons, "num collision key comparisons");
        appendSlotMetricStats(
                sb, numSlotsPerDistancesToAllocIndexBoundary, "distance to alloc index boundary");
        return sb.toString();
    }

    @SuppressWarnings("AutoBoxing")
    private void appendSlotMetricStats(
            StringBuilder sb, long[] numSlotsPerMetric, String metricName) {
        long totalMetricSum = 0;
        for (int metricValue = 0; metricValue < numSlotsPerMetric.length; metricValue++) {
            long numSlotsWithMetricValue = numSlotsPerMetric[metricValue];
            totalMetricSum += numSlotsWithMetricValue * (long) metricValue;
        }
        double averageMetricValue = (double) totalMetricSum / (double) numAggregatedFullSlots;
        sb.append(String.format("Average %s: %.2f%n", metricName, averageMetricValue));

        appendNonZeroOrderedCountsWithPercentiles(
                sb, metricName + " =", numSlotsPerMetric.length,
                singletonList(new Count("slots", metricValue -> numSlotsPerMetric[metricValue])),
                metricValue -> {});
    }

    static class Count {
        final String name;
        final IntToLongFunction countFunction;

        Count(String name, IntToLongFunction countFunction) {
            this.name = name;
            this.countFunction = countFunction;
        }
    }

    @SuppressWarnings("AutoBoxing")
    static void appendNonZeroOrderedCountsWithPercentiles(
            StringBuilder sb, String orderPrefix, int maxOrderExclusive,
            List<Count> counts, IntConsumer perOrderAction) {
        int maxOrderWidth = String.valueOf(maxOrderExclusive - 1).length();
        // Ensures all counts, and the subsequent percentile columns are aligned.
        String lineFormat = orderPrefix + " %" + maxOrderWidth + "d:";
        for (Count count : counts) {
            long maxCount = IntStream
                    .range(0, maxOrderExclusive).mapToLong(count.countFunction).max().orElse(0);
            int maxCountWidth = String.valueOf(maxCount).length();
            //noinspection StringConcatenationInLoop
            lineFormat += " %" + maxCountWidth + "d " + count.name + ", %6.2f%% %6.2f%%";
        }
        lineFormat += "%n";

        long[] totalCounts = counts
                .stream()
                .mapToLong(count ->
                        IntStream.range(0, maxOrderExclusive).mapToLong(count.countFunction).sum())
                .toArray();
        long[] currentAggregatedCounts = new long[counts.size()];
        for (int order = 0; order < maxOrderExclusive; order++) {
            int finalOrder = order;
            long[] countsForOrder = counts
                    .stream()
                    .mapToLong(count -> count.countFunction.applyAsLong(finalOrder))
                    .toArray();
            if (Arrays.stream(countsForOrder).allMatch(c -> c == 0)) {
                continue; // skip all-zero columns
            }
            Arrays.setAll(
                    currentAggregatedCounts, i -> currentAggregatedCounts[i] + countsForOrder[i]);

            List<Object> formatArguments = new ArrayList<>();
            formatArguments.add(order);
            for (int i = 0; i < counts.size(); i++) {
                double percentile = percentOf(countsForOrder[i], totalCounts[i]);
                double currentAggregatedPercentile =
                        percentOf(currentAggregatedCounts[i], totalCounts[i]);
                formatArguments.add(countsForOrder[i]);
                formatArguments.add(percentile);
                formatArguments.add(currentAggregatedPercentile);
            }
            sb.append(String.format(lineFormat, formatArguments.toArray()));

            perOrderAction.accept(order);
        }
    }
}