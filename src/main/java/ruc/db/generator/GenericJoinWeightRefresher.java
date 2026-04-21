package ruc.db.generator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;

/**
 * 跨批用已生成的 FK/join 键列值刷新 {@link ConstraintChainFkJoinNode#setGenericBucketWeights(long[])}。
 */
public final class GenericJoinWeightRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(GenericJoinWeightRefresher.class);

    private GenericJoinWeightRefresher() {
    }

    /**
     * 将本批 {@code fkCol2Values} 合并进累加器，并为每条链上的 GENERIC 节点写回 Top-N+Others 权重。
     *
     * @param accByLocalCol 键为 join 本地列规范名（与 {@code fkCol2Values} 的 key 一致）
     */
    public static void mergeBatchAndUpdateNodes(
            Map<String, Map<Long, Long>> accByLocalCol,
            List<ConstraintChain> chainsForTable,
            Map<String, long[]> fkCol2Values,
            int maxHistogramBuckets,
            boolean enabled) {
        if (!enabled || chainsForTable == null || fkCol2Values == null || maxHistogramBuckets < 1) {
            return;
        }
        for (ConstraintChain chain : chainsForTable) {
            for (ConstraintChainNode n : chain.getNodes()) {
                if (!(n instanceof ConstraintChainFkJoinNode fk)) {
                    continue;
                }
                if (fk.getJoinModel() != JoinConstraintJoinModel.GENERIC) {
                    continue;
                }
                if (fk.getTargetJoinRows() == null) {
                    continue;
                }
                String local = fk.getLocalCols();
                if (local == null || local.contains(",")) {
                    continue;
                }
                long[] batch = fkCol2Values.get(local);
                if (batch == null || batch.length == 0) {
                    continue;
                }
                Map<Long, Long> acc = accByLocalCol.computeIfAbsent(local, k -> new HashMap<>());
                int before = acc.size();
                JoinKeyHistogram.mergeInto(acc, batch);
                long[] w = JoinKeyHistogram.topNOthersWeightsFromCounts(acc, maxHistogramBuckets);
                fk.setGenericBucketWeights(w);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("GENERIC histogram refresh localCol={} accDistinctKeys={}->{} buckets={}",
                            local, before, acc.size(), w.length);
                }
            }
        }
    }
}
