package ruc.db.generator;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinKeyHistogramTest {

    @Test
    void topN_emptyKeys_returnsSingletonOne() {
        assertArrayEquals(new long[]{1L}, JoinKeyHistogram.topNOthersWeights(new long[0], 8));
        assertArrayEquals(new long[]{1L}, JoinKeyHistogram.topNOthersWeights(new long[]{Long.MIN_VALUE}, 8));
    }

    @Test
    void topN_singleDistinct_oneBucket() {
        assertArrayEquals(new long[]{5L}, JoinKeyHistogram.topNOthersWeights(new long[]{1, 1, 1, 1, 1}, 8));
    }

    @Test
    void topN_twoDistinct_fitsInMaxBuckets() {
        long[] w = JoinKeyHistogram.topNOthersWeights(new long[]{1, 1, 2, 2, 2, 2}, 8);
        assertEquals(2, w.length);
        assertEquals(6L, w[0] + w[1]);
    }

    @Test
    void topN_manyDistinct_othersBucket() {
        long[] keys = new long[100];
        for (int i = 0; i < 100; i++) {
            keys[i] = i;
        }
        long[] w = JoinKeyHistogram.topNOthersWeights(keys, 4);
        assertEquals(4, w.length);
        long sum = 0L;
        for (long x : w) {
            sum += x;
        }
        assertEquals(100L, sum);
    }

    @Test
    void mergeInto_accumulates() {
        Map<Long, Long> acc = new HashMap<>();
        JoinKeyHistogram.mergeInto(acc, new long[]{10L, 20L, 10L});
        JoinKeyHistogram.mergeInto(acc, new long[]{20L, Long.MIN_VALUE});
        assertEquals(2L, acc.get(10L));
        assertEquals(2L, acc.get(20L));
    }

    @Test
    void topNOthersWeightsFromCounts_matchesExpandedKeys() {
        Map<Long, Long> m = new HashMap<>();
        m.put(1L, 3L);
        m.put(2L, 2L);
        long[] fromMap = JoinKeyHistogram.topNOthersWeightsFromCounts(m, 8);
        long[] fromKeys = JoinKeyHistogram.topNOthersWeights(new long[]{1, 1, 1, 2, 2}, 8);
        assertArrayEquals(fromKeys, fromMap);
    }
}
