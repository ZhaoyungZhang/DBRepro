package ruc.db.generator;

import com.google.ortools.sat.CpModel;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.generator.joininfo.JoinStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJoinPfConstraintsTest {

    @Test
    void appendTo_skipsSingleBucket_redundantWithMainJoinCardinality() {
        ConstructCpModel cp = new ConstructCpModel();
        Map<JoinStatus, Long> hist = new LinkedHashMap<>();
        hist.put(new JoinStatus(new boolean[]{true}), 3L);
        hist.put(new JoinStatus(new boolean[]{false}), 2L);
        cp.initModel(hist, 1, 50);

        ConstraintChainFkJoinNode fk = new ConstraintChainFkJoinNode("t.a", "t.b", 0, BigDecimal.ONE);
        fk.setJoinModel(JoinConstraintJoinModel.GENERIC);
        fk.setTargetJoinRows(10L);
        fk.setRightInputRows(100L);
        fk.setGenericBucketWeights(new long[]{1L});

        assertDoesNotThrow(() -> GenericJoinPfConstraints.appendTo(cp, List.of(List.of(fk)), 1000L));
        long[][] sol = cp.solve();
        assertTrue(sol != null && sol.length >= 1);
    }

    @Test
    void appendTo_addsFeasiblePfLayer() {
        ConstructCpModel cp = new ConstructCpModel();
        Map<JoinStatus, Long> hist = new LinkedHashMap<>();
        hist.put(new JoinStatus(new boolean[]{true}), 3L);
        hist.put(new JoinStatus(new boolean[]{false}), 2L);
        cp.initModel(hist, 1, 50);

        ConstraintChainFkJoinNode fk = new ConstraintChainFkJoinNode("t.a", "t.b", 0, BigDecimal.ONE);
        fk.setJoinModel(JoinConstraintJoinModel.GENERIC);
        fk.setTargetJoinRows(10L);
        fk.setRightInputRows(100L);
        fk.setGenericBucketWeights(new long[]{2, 3});

        List<List<ConstraintChainNode>> chains = List.of(List.of(fk));
        GenericJoinPfConstraints.appendTo(cp, chains, 1000L);

        long[][] sol = cp.solve();
        assertTrue(sol != null && sol.length >= 1);
    }

    @Test
    void appendTo_doesNotAddDisconnectedPfBucketVariables() throws Exception {
        ConstructCpModel cp = new ConstructCpModel();
        Map<JoinStatus, Long> hist = new LinkedHashMap<>();
        hist.put(new JoinStatus(new boolean[]{true}), 524_303L);
        hist.put(new JoinStatus(new boolean[]{false}), 1_975_370L);
        cp.initModel(hist, 2, 2_499_673);
        int baseVars = variableCount(cp);

        ConstraintChainFkJoinNode fk = new ConstraintChainFkJoinNode("fact.mgt_org_code", "org.child_mgt_org_code", 0, BigDecimal.ONE);
        fk.setJoinModel(JoinConstraintJoinModel.GENERIC);
        fk.setTargetJoinRows(32_825_545L);
        fk.setLeftInputRows(1_296L);
        fk.setRightInputRows(32_825_588L);
        fk.setLocalInputRows(32_825_588L);
        fk.setGenericBucketWeights(new long[]{
                1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1
        });

        GenericJoinPfConstraints.appendTo(cp, List.of(List.of(fk)), 156_499_673L, Map.of(fk, 524_303L));

        assertEquals(baseVars, variableCount(cp));
    }

    private static int variableCount(ConstructCpModel cp) throws Exception {
        Field field = ConstructCpModel.class.getDeclaredField("model");
        field.setAccessible(true);
        CpModel model = (CpModel) field.get(cp);
        return model.model().getVariablesCount();
    }
}
