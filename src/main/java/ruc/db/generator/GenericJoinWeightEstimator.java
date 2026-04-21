package ruc.db.generator;

import java.util.Arrays;

import ruc.db.schema.ColumnManager;

/**
 * GENERIC 等值连接：由左表 join 键 NDV 估计桶数，生成用于
 * {@link ConstraintChainFkJoinNode#setGenericBucketWeights(long[])} 的权重向量（当前阶段为均匀权重，后续可换为真实频次）。
 */
public final class GenericJoinWeightEstimator {

    public static final int DEFAULT_MAX_BUCKETS = 64;

    private GenericJoinWeightEstimator() {
    }

    /**
     * @param localCanonicalCol 规范列名 {@code schema.table.column}，复合列（含逗号）时退回单桶
     * @param leftInputRows     计划左输入行数，可为 null
     * @param maxBuckets        桶数上限
     * @return 长度 {@code K=min(maxBuckets, max(1,ndv))} 的权重数组，当前均为 1（表示 K 个 PF 桶占位，与
     *         {@link GenericJoinPfConstraints} 中 \(\sum w_k PF_k\) 一致且 \(w_k=1\) 时 \(\sum PF_k \approx n_{jcc}\)）
     */
    public static long[] estimateUniformBucketWeights(String localCanonicalCol, Long leftInputRows, int maxBuckets) {
        if (localCanonicalCol == null || localCanonicalCol.contains(",")) {
            return new long[]{1L};
        }
        int ndv = Math.max(1, ColumnManager.getInstance().getNdv(localCanonicalCol));
        int k = Math.min(Math.max(1, maxBuckets), ndv);
        if (leftInputRows != null && leftInputRows > 0) {
            k = Math.min(k, (int) Math.min(leftInputRows, Integer.MAX_VALUE / 4));
            k = Math.max(1, k);
        }
        long[] w = new long[k];
        Arrays.fill(w, 1L);
        return w;
    }
}
