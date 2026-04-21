package ruc.db.generator;

import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.generator.joininfo.JoinStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
