package ruc.db.generator.constraintchain.agg;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AggregateValueModel {
    private AggregateValueModel() {
    }

    public static Map<String, Long> countByGroup(Object[] groupKeyValues) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (groupKeyValues == null) {
            return counts;
        }
        for (Object value : groupKeyValues) {
            if (value == null) {
                continue;
            }
            counts.merge(String.valueOf(value), 1L, Long::sum);
        }
        return counts;
    }

    public static long sumCountForInnerJoin(Object[] probeValues, Map<String, Long> countByGroup) {
        if (probeValues == null || countByGroup == null || countByGroup.isEmpty()) {
            return 0L;
        }
        long out = 0L;
        for (Object value : probeValues) {
            if (value == null) {
                continue;
            }
            out += countByGroup.getOrDefault(String.valueOf(value), 0L);
        }
        return out;
    }

    public static long sumCoalescedCountForLeftJoin(Object[] probeValues, Map<String, Long> countByGroup, long defaultValue) {
        if (probeValues == null) {
            return 0L;
        }
        long fallback = Math.max(0L, defaultValue);
        long out = 0L;
        for (Object value : probeValues) {
            if (value == null || countByGroup == null || countByGroup.isEmpty()) {
                out += fallback;
                continue;
            }
            out += Math.max(fallback, countByGroup.getOrDefault(String.valueOf(value), 0L));
        }
        return out;
    }

    public static BigDecimal averageCountPerGroup(long inputRows, long outputGroups, int scale) {
        if (inputRows < 0 || outputGroups <= 0) {
            return BigDecimal.ZERO.setScale(Math.max(0, scale), RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(inputRows)
                .divide(BigDecimal.valueOf(outputGroups), Math.max(0, scale), RoundingMode.HALF_UP);
    }
}
