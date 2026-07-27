package ruc.db.generator.constraintchain;

import org.junit.jupiter.api.Test;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalFkNodeSelectionTest {

    @Test
    void genericFkJoinNodeIsNotPhysicalFkNode() {
        ConstraintChain chain = new ConstraintChain("public.child");
        ConstraintChainFkJoinNode generic = new ConstraintChainFkJoinNode(
                "public.child.code", "public.parent.non_pk_code", 0, BigDecimal.ONE);
        generic.setJoinModel(JoinConstraintJoinModel.GENERIC);
        chain.addNode(generic);

        assertTrue(chain.hasFkNode());
        assertFalse(chain.hasPhysicalFkNode());
        assertEquals(List.of(), chain.getPhysicalFkNodes());
        assertEquals(List.of(), chain.getInvolvedNodes(List.of("public.child.code")));
        assertEquals(List.of(generic), chain.getInvolvedJoinKeyNodes(List.of("public.child.code")));
    }

    @Test
    void pkFkAndLegacyFkJoinNodesRemainPhysicalFkNodes() {
        ConstraintChain chain = new ConstraintChain("public.child");
        ConstraintChainFkJoinNode pkFk = new ConstraintChainFkJoinNode(
                "public.child.parent_id", "public.parent.id", 0, BigDecimal.ONE);
        pkFk.setJoinModel(JoinConstraintJoinModel.PK_FK);
        ConstraintChainFkJoinNode legacy = new ConstraintChainFkJoinNode(
                "public.child.legacy_parent_id", "public.parent.id", 1, BigDecimal.ONE);

        chain.addNode(pkFk);
        chain.addNode(legacy);

        assertTrue(chain.hasPhysicalFkNode());
        assertEquals(List.of(pkFk, legacy), chain.getPhysicalFkNodes());
        assertEquals(List.of(pkFk), chain.getInvolvedNodes(List.of("public.child.parent_id")));
    }
}
