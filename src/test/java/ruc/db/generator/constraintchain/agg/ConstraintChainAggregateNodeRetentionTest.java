package ruc.db.generator.constraintchain.agg;

import org.junit.jupiter.api.Test;
import ruc.db.generator.constraintchain.ConstraintChain;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstraintChainAggregateNodeRetentionTest {

    @Test
    void singleColumnGroupKeyAggregateIsKeptAsDistinctConstraint() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                List.of("sgami_arch.a_mgt_org_childs.child_mgt_org_code"),
                new BigDecimal("0.999229"),
                1_297L,
                1_296L);

        assertFalse(node.removeAgg());
        assertTrue(node.isSingleGroupKeyDistinctConstraint());
    }

    @Test
    void aggregateWithoutGroupKeyIsStillRemoved() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(null, BigDecimal.ONE, 100L, 1L);

        assertTrue(node.removeAgg());
        assertFalse(node.isSingleGroupKeyDistinctConstraint());
    }

    @Test
    void multiColumnGroupKeyIsNotTreatedAsFirstPassDistinctConstraint() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                List.of("public.t.a", "public.t.b"),
                new BigDecimal("0.5"),
                100L,
                50L);

        assertTrue(node.removeAgg());
        assertFalse(node.isSingleGroupKeyDistinctConstraint());
    }

    @Test
    void joinCanContinueAfterAggregateOnlyWhenLocalJoinKeyIsTheSingleGroupKey() {
        ConstraintChain chain = new ConstraintChain("sgami_arch.a_mgt_org_childs");
        chain.addNode(new ConstraintChainAggregateNode(
                List.of("sgami_arch.a_mgt_org_childs.child_mgt_org_code"),
                new BigDecimal("0.999229"),
                1_297L,
                1_296L));

        assertTrue(chain.canContinueJoinAfterAggregateOnLocalKey(
                "sgami_arch.a_mgt_org_childs.child_mgt_org_code"));
        assertFalse(chain.canContinueJoinAfterAggregateOnLocalKey(
                "sgami_arch.a_mgt_org_childs.mgt_org_code"));
    }

    @Test
    void recoveredHavingAggregateCanPreserveDistinctStatsWithoutBlockingLaterJoins() {
        ConstraintChain chain = new ConstraintChain("sgami_support.s_obj_group_members");
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                List.of("sgami_support.s_obj_group_members.members_id"),
                new BigDecimal("0.2"),
                1_000L,
                200L);
        node.setAllowsPostAggregateJoins(true);
        chain.addNode(node);

        assertTrue(chain.canContinueJoinAfterAggregateOnLocalKey(
                "sgami_support.s_obj_group_members.group_no"));
    }
}
