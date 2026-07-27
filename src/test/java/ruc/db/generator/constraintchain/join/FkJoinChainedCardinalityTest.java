package ruc.db.generator.constraintchain.join;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 多 JOIN 链上 {@code filterSize} 由前一节点返回值递推；此处用 {@link ConstraintChainFkJoinNode#computeJoinCardinalityTargetForCp}
 * 钉死夹逼语义（与 {@link FkGenerator#constructConstraintProblem} 中传播一致）。
 */
class FkJoinChainedCardinalityTest {

    @Test
    void chained_generic_targets_usePreviousFilterSize() {
        ConstraintChainFkJoinNode j1 = new ConstraintChainFkJoinNode("a.k1", "b.k1", 0, new BigDecimal("0.5"));
        j1.setJoinModel(JoinConstraintJoinModel.GENERIC);
        j1.setTargetJoinRows(500L);
        j1.setLeftInputRows(10_000L);
        j1.setRightInputRows(1000L);
        long afterFirst = j1.computeJoinCardinalityTargetForCp(10_000L);
        assertEquals(500L, afterFirst);

        ConstraintChainFkJoinNode j2 = new ConstraintChainFkJoinNode("a.k2", "c.k2", 0, new BigDecimal("0.5"));
        j2.setJoinModel(JoinConstraintJoinModel.GENERIC);
        j2.setTargetJoinRows(200L);
        j2.setLeftInputRows(500L);
        j2.setRightInputRows(500L);
        assertEquals(200L, j2.computeJoinCardinalityTargetForCp(afterFirst));
    }
}
