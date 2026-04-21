package ruc.db.generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Join 键上的频次统计：GROUP BY 语义 + Top-N + Others，供 GENERIC 的 {@code genericBucketWeights}。
 */
public final class JoinKeyHistogram {

    private JoinKeyHistogram() {
    }

    /**
     * 对一批键值做频次聚合，产出至多 {@code maxBuckets} 个非负权重（桶内行数/频次）。
     * 若 {@code maxBuckets >= 2} 且不同键个数超过容量，则前 {@code maxBuckets - 1} 个高频键各一桶，其余并入 Others。
     * 若不同键个数不超过 {@code maxBuckets}，则每桶一个键频，无单独 Others。
     * <p>
     * 忽略 {@link Long#MIN_VALUE}（常表示 NULL/占位）。
     */
    public static long[] topNOthersWeights(long[] keys, int maxBuckets) {
        if (maxBuckets < 1) {
            maxBuckets = 1;
        }
        Map<Long, Long> freq = new HashMap<>();
        if (keys != null) {
            for (long k : keys) {
                if (k == Long.MIN_VALUE) {
                    continue;
                }
                freq.merge(k, 1L, Long::sum);
            }
        }
        if (freq.isEmpty()) {
            return new long[]{1L};
        }
        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(freq.entrySet());
        sorted.sort(Comparator.<Map.Entry<Long, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparingLong(Map.Entry::getKey));

        int distinct = sorted.size();
        if (maxBuckets == 1 || distinct == 1) {
            long sum = 0L;
            for (long c : freq.values()) {
                sum += c;
            }
            return new long[]{Math.max(1L, sum)};
        }

        if (distinct <= maxBuckets) {
            long[] w = new long[distinct];
            for (int i = 0; i < distinct; i++) {
                w[i] = Math.max(1L, sorted.get(i).getValue());
            }
            return w;
        }

        long[] w = new long[maxBuckets];
        long others = 0L;
        for (int i = 0; i < maxBuckets - 1; i++) {
            w[i] = Math.max(1L, sorted.get(i).getValue());
        }
        for (int i = maxBuckets - 1; i < distinct; i++) {
            others += sorted.get(i).getValue();
        }
        w[maxBuckets - 1] = Math.max(1L, others);
        return w;
    }

    /**
     * 将本批键频次合并到累加器中（跨批 GROUP BY 语义）。
     */
    public static void mergeInto(Map<Long, Long> accumulator, long[] batchKeys) {
        if (accumulator == null || batchKeys == null) {
            return;
        }
        for (long k : batchKeys) {
            if (k == Long.MIN_VALUE) {
                continue;
            }
            accumulator.merge(k, 1L, Long::sum);
        }
    }

    /**
     * 由累加后的频次图生成与 {@link #topNOthersWeights(long[], int)} 相同规则的权重向量。
     */
    public static long[] topNOthersWeightsFromCounts(Map<Long, Long> freq, int maxBuckets) {
        if (freq == null || freq.isEmpty()) {
            return new long[]{1L};
        }
        if (maxBuckets < 1) {
            maxBuckets = 1;
        }
        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(freq.entrySet());
        sorted.sort(Comparator.<Map.Entry<Long, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparingLong(Map.Entry::getKey));
        int distinct = sorted.size();
        if (maxBuckets == 1 || distinct == 1) {
            long sum = 0L;
            for (long c : freq.values()) {
                sum += c;
            }
            return new long[]{Math.max(1L, sum)};
        }
        if (distinct <= maxBuckets) {
            long[] w = new long[distinct];
            for (int i = 0; i < distinct; i++) {
                w[i] = Math.max(1L, sorted.get(i).getValue());
            }
            return w;
        }
        long[] w = new long[maxBuckets];
        long others = 0L;
        for (int i = 0; i < maxBuckets - 1; i++) {
            w[i] = Math.max(1L, sorted.get(i).getValue());
        }
        for (int i = maxBuckets - 1; i < distinct; i++) {
            others += sorted.get(i).getValue();
        }
        w[maxBuckets - 1] = Math.max(1L, others);
        return w;
    }
}
