package ruc.db.generator.constraintchain.join;

import org.junit.jupiter.api.Test;
import ruc.db.generator.ConstructCpModel;
import ruc.db.generator.joininfo.JoinStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConstraintChainFkJoinNodeGenericCardinalityTest {

    @Test
    void genericUsesTargetScaledToCurrentBatch() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, new BigDecimal("0.2260560027"));
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(9_141_228L);
        n.setLeftInputRows(40_437_891L);
        n.setRightInputRows(202L);
        assertEquals(1_582_392L, n.computeJoinCardinalityTargetForCp(7_000_000L));
    }

    @Test
    void genericScalesByLocalInputRowsWhenPlanLeftSideIsReferenceTable() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode(
                "sgami_support.s_meter_label_result.mgt_org_code",
                "sgami_arch.a_mgt_org_childs.child_mgt_org_code",
                0,
                BigDecimal.ONE);
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(32_825_545L);
        n.setLeftInputRows(1_296L);
        n.setRightInputRows(32_825_588L);
        n.setLocalInputRows(32_825_588L);
        n.setRefInputRows(1_296L);

        assertEquals(209_749L, n.computeJoinCardinalityTargetForCp(209_749L));
    }

    @Test
    void genericFailFilterUsesFailFilterProbabilityNotMainTargetRows() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode(
                "sgami_support.s_meter_label_result.mgt_org_code",
                "sgami_arch.a_mgt_org_childs.child_mgt_org_code",
                0,
                BigDecimal.ONE);
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(32_825_545L);
        n.setLeftInputRows(1_296L);
        n.setRightInputRows(32_825_588L);
        n.setLocalInputRows(32_825_588L);
        n.setRefInputRows(1_296L);
        n.setProbabilityWithFailFilter(BigDecimal.ZERO);

        assertEquals(0L, n.computeFailFilterJoinCardinalityTargetForCp(5_531_760L));
    }

    @Test
    void failFilterTargetUsesFailFilterProbabilityForAllJoinModels() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, BigDecimal.ONE);
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(10_000L);
        n.setLocalInputRows(10_000L);
        n.setProbabilityWithFailFilter(new BigDecimal("0.25"));

        assertEquals(25L, n.computeFailFilterJoinCardinalityTargetForCp(100L));
    }

    @Test
    void genericFallsBackToLeftInputRowsForLegacyConstraintJson() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, BigDecimal.ONE);
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(9_141_228L);
        n.setLeftInputRows(40_437_891L);

        assertEquals(1_582_392L, n.computeJoinCardinalityTargetForCp(7_000_000L));
    }

    @Test
    void genericOuterJoinWithTargetEqualLeftInputKeepsWholeBatchFeasible() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, BigDecimal.ONE);
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setType(ConstraintNodeJoinType.OUTER_JOIN);
        n.setTargetJoinRows(9_141_228L);
        n.setLeftInputRows(9_141_228L);
        assertEquals(7_000_000L, n.computeJoinCardinalityTargetForCp(7_000_000L));
    }

    @Test
    void pkFkUsesProbabilityTimesFilter() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, new BigDecimal("0.25"));
        n.setJoinModel(JoinConstraintJoinModel.PK_FK);
        n.setTargetJoinRows(999L);
        assertEquals(25L, n.computeJoinCardinalityTargetForCp(100L));
    }

    @Test
    void zeroFailFilterIndexJoinTargetIsTight() throws Exception {
        ConstructCpModel cp = new ConstructCpModel();
        Map<JoinStatus, Long> hist = new LinkedHashMap<>();
        hist.put(new JoinStatus(new boolean[]{false}), 1L);
        cp.initModel(hist, 2, 1);

        cp.addJoinCardinalityValidVar(0, 0);
        cp.addJoinCardinalityConstraint(1L, 1L);

        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("a.k", "b.k", 0, BigDecimal.ONE);
        n.setProbabilityWithFailFilter(BigDecimal.ZERO);
        n.initJoinResultStatus(new JoinStatus[][]{
                {new JoinStatus(new boolean[]{true})},
                {new JoinStatus(new boolean[]{false})}
        });
        boolean[][] canBeInput = new boolean[][]{{false, false}};

        Method method = ConstraintChainFkJoinNode.class.getDeclaredMethod(
                "addIndexJoinCardinalityConstraint",
                ConstructCpModel.class,
                long.class,
                boolean[][].class);
        method.setAccessible(true);
        method.invoke(n, cp, 100L, canBeInput);

        assertThrows(UnsupportedOperationException.class, cp::solve);
    }

    @Test
    void genericSemiJoinDoesNotForceExactDistinctTargetIntoCp() {
        ConstructCpModel cp = new ConstructCpModel();
        Map<JoinStatus, Long> hist = new LinkedHashMap<>();
        hist.put(new JoinStatus(new boolean[]{true}), 10L);
        cp.initModel(hist, 1, 10);
        cp.initDistinctModel(0, 2L, 10L);

        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode("fact.mgt_org_code", "org.child_mgt_org_code", 0, new BigDecimal("0.5"));
        n.setType(ConstraintNodeJoinType.SEMI_JOIN);
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(6L);
        n.setLocalInputRows(10L);
        n.setRightInputRows(2L);
        n.setRefInputRows(2L);
        n.initJoinResultStatus(new JoinStatus[][]{
                {new JoinStatus(new boolean[]{true})}
        });

        boolean[][] canBeInput = new boolean[][]{{true}};
        n.addJoinDistinctConstraint(cp, 10L, canBeInput);
        cp.applyFKShareConstraint(0, Map.of(new ArrayList<>(List.of(0)), 2L));

        assertDoesNotThrow(cp::solve);
    }
}
