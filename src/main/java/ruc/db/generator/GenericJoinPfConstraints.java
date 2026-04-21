package ruc.db.generator;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;

import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;

/**
 * GENERIC 等值连接：在已有 filter×PK 状态 CP 上追加「按桶 PF」子约束（阶段 1：与 vars 解耦）。
 */
public final class GenericJoinPfConstraints {

    private static final AtomicInteger NAME_SEQ = new AtomicInteger();

    private GenericJoinPfConstraints() {
    }

    /**
     * 对链上每个带 {@link ConstraintChainFkJoinNode#getGenericBucketWeights()} 的 GENERIC 节点，
     * 添加 \(\sum_k w_k PF_k \approx n_{jcc}\) 及 \(\sum_k PF_k \le\) 右表容量上界。
     */
    public static void appendTo(ConstructCpModel cpModel, List<List<ConstraintChainNode>> chainNodesList, long defaultRightCap) {
        if (cpModel == null || chainNodesList == null) {
            return;
        }
        int seq = NAME_SEQ.incrementAndGet();
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
                IntVar[] pf = cpModel.newAuxiliaryIntVars("gpf-" + seq + "-" + nodeIdx + "-", w.length, cap);
                cpModel.addWeightedJoinCardinalityConstraint(pf, w, fk.getTargetJoinRows());
                int sumUb = (int) Math.min(cap, Integer.MAX_VALUE / 2 - 1);
                cpModel.addLinearConstraint(LinearExpr.sum(pf), 0, sumUb);
                nodeIdx++;
            }
        }
    }
}
