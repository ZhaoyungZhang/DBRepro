package ruc.db.generator.constraintchain.join;

import com.fasterxml.jackson.databind.ObjectMapper;
import ruc.db.generator.constraintchain.ConstraintChainNodeType;
import ruc.db.utils.CommonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死 {@link ConstraintNodeJoinType} 在基数/distinct/JoinStatus 上的语义，
 * 为 OUTER / SEMI / ANTI 与文档中「输出行 vs 匹配行」讨论提供回归锚点。
 */
class ConstraintChainFkJoinNodeJoinTypeSemanticsTest {

    @ParameterizedTest
    @EnumSource(ConstraintNodeJoinType.class)
    void antiFlag_onlyPlainAnti(ConstraintNodeJoinType t) {
        boolean expectAnti = t == ConstraintNodeJoinType.ANTI_JOIN;
        assertEquals(expectAnti, t.isAnti(), "isAnti: " + t);
    }

    @ParameterizedTest
    @EnumSource(ConstraintNodeJoinType.class)
    void semiFlag_coversSemiAndAntiSemi(ConstraintNodeJoinType t) {
        boolean expectSemi = t == ConstraintNodeJoinType.SEMI_JOIN || t == ConstraintNodeJoinType.ANTI_SEMI_JOIN;
        assertEquals(expectSemi, t.isSemi(), "isSemi: " + t);
    }

    @Test
    void hasCardinalityConstraint_outerSemiAntiSemi_notInnerNorPlainAnti() {
        assertFalse(ConstraintNodeJoinType.INNER_JOIN.hasCardinalityConstraint());
        assertFalse(ConstraintNodeJoinType.ANTI_JOIN.hasCardinalityConstraint());
        assertTrue(ConstraintNodeJoinType.OUTER_JOIN.hasCardinalityConstraint());
        assertTrue(ConstraintNodeJoinType.SEMI_JOIN.hasCardinalityConstraint());
        assertTrue(ConstraintNodeJoinType.ANTI_SEMI_JOIN.hasCardinalityConstraint());
    }

    @Test
    void jacksonRoundTrip_preservesJoinType_semiOuterAnti() throws Exception {
        ObjectMapper mapper = CommonUtils.MAPPER;
        for (ConstraintNodeJoinType joinType : new ConstraintNodeJoinType[]{
                ConstraintNodeJoinType.SEMI_JOIN,
                ConstraintNodeJoinType.OUTER_JOIN,
                ConstraintNodeJoinType.ANTI_JOIN
        }) {
            ConstraintChainFkJoinNode node = new ConstraintChainFkJoinNode("s.a", "s.b", 0, BigDecimal.ONE);
            node.setType(joinType);
            node.setJoinModel(JoinConstraintJoinModel.GENERIC);
            node.setTargetJoinRows(77L);

            String json = mapper.writeValueAsString(node);
            ConstraintChainFkJoinNode back = mapper.readValue(json, ConstraintChainFkJoinNode.class);

            assertEquals(ConstraintChainNodeType.FK_JOIN, back.getConstraintChainNodeType());
            assertEquals(joinType, back.getType());
            assertEquals(77L, back.getTargetJoinRows());
        }
    }

    /**
     * {@code targetJoinRows} 在 CP 侧默认按「计划节点 Actual」理解，对 GENERIC 会夹在左右输入与 filterSize 内；
     * 与 join 类型正交，但文档中 LEFT 的「输出 vs 匹配」口径需在 prepare 写入时区分。
     */
    @Test
    void genericTargetRows_clampedSameForOuterAndInner() {
        ConstraintChainFkJoinNode outer = new ConstraintChainFkJoinNode("a.k", "b.k", 0, new BigDecimal("0.5"));
        outer.setJoinModel(JoinConstraintJoinModel.GENERIC);
        outer.setType(ConstraintNodeJoinType.OUTER_JOIN);
        outer.setTargetJoinRows(500L);
        outer.setLeftInputRows(100L);
        outer.setRightInputRows(200L);
        assertEquals(100L, outer.computeJoinCardinalityTargetForCp(999L));

        ConstraintChainFkJoinNode inner = new ConstraintChainFkJoinNode("a.k", "b.k", 0, new BigDecimal("0.5"));
        inner.setJoinModel(JoinConstraintJoinModel.GENERIC);
        inner.setType(ConstraintNodeJoinType.INNER_JOIN);
        inner.setTargetJoinRows(500L);
        inner.setLeftInputRows(100L);
        inner.setRightInputRows(200L);
        assertEquals(100L, inner.computeJoinCardinalityTargetForCp(999L));
    }
}
