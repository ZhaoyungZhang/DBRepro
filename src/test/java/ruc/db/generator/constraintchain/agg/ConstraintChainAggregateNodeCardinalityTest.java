package ruc.db.generator.constraintchain.agg;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstraintChainAggregateNodeCardinalityTest {

    @Test
    void q11OrgChildAggregateTargetUsesExplicitInputAndOutputRows() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                List.of("sgami_arch.a_mgt_org_childs.child_mgt_org_code"),
                new BigDecimal("0.999229"),
                1_297L,
                1_296L);

        assertEquals(1_296L, node.computeDistinctTargetForCp(1_297L));
    }

    @Test
    void q11MeterRunAggregateTargetUsesExplicitInputAndOutputRows() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                List.of("sg_mis.meter_run.meter_id"),
                new BigDecimal("0.999558"),
                18_435_547L,
                18_427_403L);

        assertEquals(18_427_403L, node.computeDistinctTargetForCp(18_435_547L));
    }

    @Test
    void oldJsonWithoutExplicitRowsFallsBackToAggregateProbability() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                List.of("public.t.k"),
                new BigDecimal("0.25"));

        assertEquals(25L, node.computeDistinctTargetForCp(100L));
    }

    @Test
    void aggregateDistinctTargetIsClampedToCurrentFilterSize() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                List.of("public.t.k"),
                BigDecimal.ONE,
                10L,
                20L);

        assertEquals(10L, node.computeDistinctTargetForCp(10L));
    }
}
