package ruc.db.generator.constraintchain.join;

import com.fasterxml.jackson.databind.ObjectMapper;
import ruc.db.generator.constraintchain.ConstraintChainNodeType;
import ruc.db.utils.CommonUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConstraintChainFkJoinNodeMetadataTest {

    @Test
    void jacksonRoundTrip_preservesJoinModelAndTargetRows() throws Exception {
        ConstraintChainFkJoinNode node = new ConstraintChainFkJoinNode("a.x", "b.y", 1, new BigDecimal("0.5"));
        node.setJoinModel(JoinConstraintJoinModel.GENERIC);
        node.setTargetJoinRows(999L);
        node.setLeftInputRows(100L);
        node.setRightInputRows(200L);
        node.setGenericBucketWeights(new long[]{1, 2, 3});
        node.setGenericAntiDomainOffset(42L);

        ObjectMapper mapper = CommonUtils.MAPPER;
        String json = mapper.writeValueAsString(node);
        ConstraintChainFkJoinNode back = mapper.readValue(json, ConstraintChainFkJoinNode.class);

        assertEquals(ConstraintChainNodeType.FK_JOIN, back.getConstraintChainNodeType());
        assertEquals(JoinConstraintJoinModel.GENERIC, back.getJoinModel());
        assertEquals(999L, back.getTargetJoinRows());
        assertEquals(100L, back.getLeftInputRows());
        assertEquals(200L, back.getRightInputRows());
        assertNotNull(back.getProbability());
        assertEquals(3, back.getGenericBucketWeights().length);
        assertEquals(2L, back.getGenericBucketWeights()[1]);
        assertEquals(42L, back.getGenericAntiDomainOffset());
    }
}
