package ruc.db.generator;

import java.util.Set;

/**
 * 缩小场景下对生成 FK 与参照键集合做 INNER 语义计数，用于回归「与计划同量级」而非精确 SQL 重放。
 */
public final class GenericJoinSanityChecker {

    private GenericJoinSanityChecker() {
    }

    /** 统计 {@code fkValues} 中落在 {@code refKeys} 内的非 MIN 值个数（近似 INNER 命中行数）。 */
    public static long countFksInReferenceSet(long[] fkValues, Set<Long> refKeys) {
        if (fkValues == null || refKeys == null || refKeys.isEmpty()) {
            return 0L;
        }
        long c = 0L;
        for (long v : fkValues) {
            if (v != Long.MIN_VALUE && refKeys.contains(v)) {
                c++;
            }
        }
        return c;
    }

    /** 与计划目标比较是否在相对容差内（与 {@link ConstructCpModel#addJoinCardinalityConstraint(long)} 约 8% 对齐）。 */
    public static boolean withinJoinCardinalityTolerance(long actual, long target) {
        if (target <= 0) {
            return actual == 0L;
        }
        long tol = Math.max(1L, (long) (target * 0.08));
        return actual >= target - tol && actual <= target + tol;
    }
}
