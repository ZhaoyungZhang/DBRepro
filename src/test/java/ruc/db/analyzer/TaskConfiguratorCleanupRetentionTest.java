package ruc.db.analyzer;

import org.junit.jupiter.api.Test;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskConfiguratorCleanupRetentionTest {

    @Test
    void cleanupKeepsSingleColumnDistinctAggregateNodes() {
        ConstraintChain chain = new ConstraintChain("sg_mis.s_obj_group_members");
        chain.addNode(new ConstraintChainAggregateNode(
                List.of("sg_mis.s_obj_group_members.members_id"),
                new BigDecimal("0.25"),
                1_000L,
                200L));

        Map<String, List<ConstraintChain>> input = new LinkedHashMap<>();
        input.put("q5_1.sql", new LinkedList<>(List.of(chain)));

        Map<String, List<ConstraintChain>> cleaned = new TaskConfigurator().checkQueryConstraintChains(input);

        assertEquals(1, cleaned.get("q5_1.sql").size());
        assertEquals(1, cleaned.get("q5_1.sql").getFirst().getNodes().size());
    }

    @Test
    void cleanupKeepsSyntheticDistinctAggregateNodes() {
        ConstraintChain chain = new ConstraintChain("sgami_arch.a_arch_meter_full_info");
        chain.addNode(new ConstraintChainAggregateNode(
                List.of("sgami_arch.a_arch_meter_full_info.mgt_org_code"),
                new BigDecimal("0.5"),
                200L,
                101L));

        Map<String, List<ConstraintChain>> input = new LinkedHashMap<>();
        input.put("q5_2.sql", new LinkedList<>(List.of(chain)));

        Map<String, List<ConstraintChain>> cleaned = new TaskConfigurator().checkQueryConstraintChains(input);

        assertEquals(1, cleaned.get("q5_2.sql").size());
        assertEquals(1, cleaned.get("q5_2.sql").getFirst().getNodes().size());
    }
}
