package ruc.db.generator.constraintchain.join;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstraintChainFkJoinNodeGenericCardinalityTest {

    @Test
    void genericUsesTargetClampedByInputs() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, new BigDecimal("0.3"));
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(500L);
        n.setLeftInputRows(100L);
        n.setRightInputRows(200L);
        assertEquals(100L, n.computeJoinCardinalityTargetForCp(999L));
    }

    @Test
    void pkFkUsesProbabilityTimesFilter() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, new BigDecimal("0.25"));
        n.setJoinModel(JoinConstraintJoinModel.PK_FK);
        n.setTargetJoinRows(999L);
        assertEquals(25L, n.computeJoinCardinalityTargetForCp(100L));
    }
}
