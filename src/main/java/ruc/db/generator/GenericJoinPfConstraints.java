package ruc.db.generator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;

/**
 * GENERIC 等值连接：对「按桶 PF」信息做可达性预检。
 *
 * <p>当前 PF bucket 与 filter×PK 状态变量尚未建立映射。把解耦的 bucket 变量加入 CP-SAT
 * 不会约束实际 FK 分配，反而会把大批量生成推入独立的大整数搜索空间。这里先保留
 * target/capacity 预检，等 bucket 与状态变量能绑定后再恢复真正的 CP 约束。</p>
 */
public final class GenericJoinPfConstraints {

    private static final Logger LOG = LoggerFactory.getLogger(GenericJoinPfConstraints.class);

    private GenericJoinPfConstraints() {
    }

    /**
     * 对链上每个带 {@link ConstraintChainFkJoinNode#getGenericBucketWeights()} 的 GENERIC 节点，
     * 添加 \(\sum_k w_k PF_k \approx n_{jcc}\) 及 \(\sum_k PF_k \le\) 右表容量上界。
     */
    public static void appendTo(ConstructCpModel cpModel, List<List<ConstraintChainNode>> chainNodesList, long defaultRightCap) {
        appendTo(cpModel, chainNodesList, defaultRightCap, Map.of());
    }

    public static void appendTo(ConstructCpModel cpModel, List<List<ConstraintChainNode>> chainNodesList,
                                long defaultRightCap, Map<ConstraintChainFkJoinNode, Long> inputRowsByNode) {
        if (cpModel == null || chainNodesList == null) {
            return;
        }
        int nodeIdx = 0;
        for (List<ConstraintChainNode> nodes : chainNodesList) {
            for (ConstraintChainNode n : nodes) {
                if (!(n instanceof ConstraintChainFkJoinNode fk)) {
                    continue;
                }
                if (fk.getJoinModel() != JoinConstraintJoinModel.GENERIC) {
                    continue;
                }
                long[] w = fk.getGenericBucketWeights();
                if (w == null || w.length == 0 || fk.getTargetJoinRows() == null) {
                    continue;
                }
                // 单桶与主 vars 上 join 基数约束信息重复，且易与 ± 容差叠加；多桶统计路径再启用 PF 子块
                if (w.length == 1) {
                    continue;
                }
                long cap = fk.getRightInputRows() != null ? fk.getRightInputRows() : defaultRightCap;
                cap = Math.max(1L, cap);
                long target = computeBatchTarget(fk, inputRowsByNode);
                long maxWeight = 0L;
                for (long weight : w) {
                    maxWeight = Math.max(maxWeight, weight);
                }
                long tolerance = Math.max(1L, (long) (target * 0.08));
                long maxReachable = saturatedMultiply(cap, Math.max(1L, maxWeight));
                if (target - tolerance > maxReachable) {
                    throw new IllegalStateException(String.format(
                            "GENERIC PF bucket constraint is infeasible before CP solve: target=%d, tolerance=%d, reachableUpperBound=%d, cap=%d, maxWeight=%d, localCols=%s, refCols=%s",
                            target, tolerance, maxReachable, cap, maxWeight, fk.getLocalCols(), fk.getRefCols()));
                }
                LOG.debug("Skip disconnected GENERIC PF CP variables after feasibility precheck: buckets={}, target={}, cap={}, localCols={}, refCols={}",
                        w.length, target, cap, fk.getLocalCols(), fk.getRefCols());
                nodeIdx++;
            }
        }
    }
    private static long computeBatchTarget(ConstraintChainFkJoinNode fk, Map<ConstraintChainFkJoinNode, Long> inputRowsByNode) {
        Long inputRows = inputRowsByNode == null ? null : inputRowsByNode.get(fk);
        if (inputRows != null && inputRows >= 0) {
            return fk.computeJoinCardinalityTargetForCp(inputRows);
        }
        return fk.getTargetJoinRows();
    }

    private static long saturatedMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0L;
        }
        BigDecimal product = BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(b));
        if (product.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        return product.setScale(0, RoundingMode.DOWN).longValue();
    }

}
