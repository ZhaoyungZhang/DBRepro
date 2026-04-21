package ruc.db.generator;

import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJoinWeightRefresherTest {

    @Test
    void mergeBatch_secondCall_increasesBucketsOrWeights() {
        Map<String, Map<Long, Long>> acc = new HashMap<>();
        String local = "public.fact.fk_col";
        ConstraintChainFkJoinNode fk = new ConstraintChainFkJoinNode(local, "public.dim.id", 0, BigDecimal.ONE);
        fk.setJoinModel(JoinConstraintJoinModel.GENERIC);
        fk.setTargetJoinRows(100L);
        fk.setGenericBucketWeights(new long[]{1L});
        ConstraintChain chain = new ConstraintChain();
        chain.setTableName("public.fact");
        chain.addNode(fk);

        Map<String, long[]> batch1 = Map.of(local, new long[]{1, 1, 2, 2, 3});
        GenericJoinWeightRefresher.mergeBatchAndUpdateNodes(acc, List.of(chain), batch1, 4, true);
        long[] w1 = fk.getGenericBucketWeights();
        assertTrue(w1.length >= 1);

        Map<String, long[]> batch2 = Map.of(local, new long[]{3, 3, 3, 10, 10});
        GenericJoinWeightRefresher.mergeBatchAndUpdateNodes(acc, List.of(chain), batch2, 4, true);
        long[] w2 = fk.getGenericBucketWeights();
        long s1 = 0, s2 = 0;
        for (long x : w1) {
            s1 += x;
        }
        for (long x : w2) {
            s2 += x;
        }
        assertEquals(5L, s1);
        assertEquals(10L, s2);
    }
}
