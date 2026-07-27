package ruc.db.analyzer.online;

import ruc.db.analyzer.online.adapter.pg.PgAnalyzer;
import ruc.db.analyzer.online.node.AggNode;
import ruc.db.analyzer.online.node.FilterNode;
import ruc.db.analyzer.online.node.JoinNode;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.LogicNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintNodeJoinType;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.utils.exception.TouchstoneException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerSkipAndJoinInferenceTest {

    @Test
    void canUseTableSizeForSkipRatio_falseWhenTableNameNull() {
        JoinNode join = new JoinNode("j1", 10L, "Hash Cond: (a.x = b.y)", false, false, BigDecimal.ZERO);
        join.setTableName(null);
        assertFalse(QueryAnalyzer.canUseTableSizeForSkipRatio(join));
    }

    @Test
    void canUseTableSizeForSkipRatio_falseWhenTableNameBlank() {
        JoinNode join = new JoinNode("j1", 10L, "Hash Cond:", false, false, BigDecimal.ZERO);
        join.setTableName("  ");
        assertFalse(QueryAnalyzer.canUseTableSizeForSkipRatio(join));
    }

    @Test
    void canUseTableSizeForSkipRatio_trueWhenTableNameSet() {
        JoinNode join = new JoinNode("j1", 10L, "Hash Cond:", false, false, BigDecimal.ZERO);
        join.setTableName("public.lineitem");
        assertTrue(QueryAnalyzer.canUseTableSizeForSkipRatio(join));
    }

    @Test
    void shouldUsePkJoinBranch_nonUniqueInferredPkSide_returnsFalseWithoutThrow() throws TouchstoneException, SQLException {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String ta = "jcc.nta" + u;
        String tb = "jcc.ntb" + u;
        String ca = ta + ".kx";
        String cb = tb + ".ky";

        TableManager tm = TableManager.getInstance();
        tm.addSchema(ta, new Table(List.of(ca), 1000L));
        tm.addSchema(tb, new Table(List.of(cb), 500L));

        Column colA = new Column(ColumnType.INTEGER);
        colA.setRange(100);
        Column colB = new Column(ColumnType.INTEGER);
        colB.setRange(50);
        ColumnManager.getInstance().addColumn(ca, colA);
        ColumnManager.getInstance().addColumn(cb, colB);

        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        assertFalse(qa.shouldUsePkJoinBranch(ta, "kx", tb, "ky"));
    }

    @Test
    void shouldUsePkJoinBranch_equalNdvAndEqualTableSize_degradesToFkBranchWithoutThrow() throws TouchstoneException, SQLException {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String ta = "jcc.eqta" + u;
        String tb = "jcc.eqtb" + u;
        String ca = ta + ".kx";
        String cb = tb + ".ky";

        TableManager tm = TableManager.getInstance();
        tm.addSchema(ta, new Table(List.of(ca), 500L));
        tm.addSchema(tb, new Table(List.of(cb), 500L));

        Column colA = new Column(ColumnType.INTEGER);
        colA.setRange(100);
        Column colB = new Column(ColumnType.INTEGER);
        colB.setRange(100);
        ColumnManager.getInstance().addColumn(ca, colA);
        ColumnManager.getInstance().addColumn(cb, colB);

        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        assertFalse(qa.shouldUsePkJoinBranch(ta, "kx", tb, "ky"));
    }

    @Test
    void shouldApplySetPrimaryKeysFromPkJoinKey_falseWhenCompositePkPartialJoin() throws Exception {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String t = "jcc.ntpk" + u;
        String c1 = t + ".c1";
        String c2 = t + ".c2";
        Table tab = new Table(List.of(c1, c2, t + ".c3"), 100L);
        tab.setPrimaryKeys(new ArrayList<>(Arrays.asList(c1, c2)));
        TableManager.getInstance().addSchema(t, tab);

        assertFalse(QueryAnalyzer.shouldApplySetPrimaryKeysFromPkJoinKey(t, "c1"));
        assertTrue(QueryAnalyzer.shouldApplySetPrimaryKeysFromPkJoinKey(t, "c1,c2"));
    }

    @Test
    void refJoinKeyIsExactlyTablePrimaryKey_trueOnlyForFullPkMatch() throws TouchstoneException {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String t = "jcc.trpk" + u;
        String pk1 = t + ".tid";
        String pk2 = t + ".mid";
        String addr = t + ".addr";
        Table tab = new Table(List.of(pk1, pk2, addr), 100L);
        tab.setPrimaryKeys(new ArrayList<>(Arrays.asList(pk1, pk2)));
        TableManager.getInstance().addSchema(t, tab);

        assertFalse(QueryAnalyzer.refJoinKeyIsExactlyTablePrimaryKey(t, "addr"));
        assertFalse(QueryAnalyzer.refJoinKeyIsExactlyTablePrimaryKey(t, "tid"));
        assertTrue(QueryAnalyzer.refJoinKeyIsExactlyTablePrimaryKey(t, "tid,mid"));
        assertTrue(QueryAnalyzer.refJoinKeyIsExactlyTablePrimaryKey(t, "mid,tid"));
    }

    @Test
    void analyzeJoinNode_skipsDerivedTableJoinWhenAliasCannotResolve() throws Exception {
        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        JoinNode join = new JoinNode(
                "j-derived",
                123L,
                "Hash Cond: (sgami_support.s_meter_label_result.dev_id = mr_agg.meter_id)",
                false,
                false,
                BigDecimal.ONE);
        ConstraintChain chain = new ConstraintChain("sgami_support.s_meter_label_result");

        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeJoinNode", JoinNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        Object rows = assertDoesNotThrow(() -> method.invoke(qa, join, chain, 1_000L));
        assertEquals(123L, rows);
    }

    @Test
    void retainOnlyTableOperations_dropsPureCrossTableJoinPredicate() throws Exception {
        PgAnalyzer analyzer = new PgAnalyzer();
        LogicNode node = analyzer.analyzeSelectOperator(
                "(sgami_arch.a_arch_meter_full_info.inst_id = sg_mis.dev_inst_rmv_wk_rec.inst_id)");

        assertFalse(node.retainOnlyTableOperations("sgami_arch.a_arch_meter_full_info"));
    }

    @Test
    void retainOnlyTableOperations_keepsLocalPredicateAndDropsJoinPredicate() throws Exception {
        PgAnalyzer analyzer = new PgAnalyzer();
        LogicNode node = analyzer.analyzeSelectOperator("""
                ((sgami_arch.a_arch_meter_full_info.inst_id = sg_mis.dev_inst_rmv_wk_rec.inst_id)
                 AND (sgami_arch.a_arch_meter_full_info.dev_cls = '01'::text))
                """);

        assertTrue(node.retainOnlyTableOperations("sgami_arch.a_arch_meter_full_info"));
        assertEquals("dev_cls = '01'", node.toString().replaceAll(System.lineSeparator(), " "));
    }

    @Test
    void analyzeJoinNode_prefersRecordedJoinInputRowsWhenPartitionLeafRowsAreZero() throws Exception {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String localTable = "jcc.q14_local_" + u;
        String refTable = "jcc.q14_ref_" + u;
        String refLeafTable = refTable + "_pmin";
        String localCol = localTable + ".inst_id";
        String refCol = refTable + ".inst_id";
        String refPkExtra = refTable + ".part_id";

        Table local = new Table(List.of(localCol), 76_950L);
        Table ref = new Table(List.of(refCol, refPkExtra), 460_4064L);
        ref.setPrimaryKeys(new ArrayList<>(List.of(refCol, refPkExtra)));
        TableManager.getInstance().addSchema(localTable, local);
        TableManager.getInstance().addSchema(refTable, ref);

        Column localColumn = new Column(ColumnType.INTEGER);
        localColumn.setRange(1_297);
        Column refColumn = new Column(ColumnType.INTEGER);
        refColumn.setRange(12_437_700);
        ColumnManager.getInstance().addColumn(localCol, localColumn);
        ColumnManager.getInstance().addColumn(refCol, refColumn);

        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        JoinNode join = new JoinNode(
                "j-q14",
                76_966L,
                "Index Cond: (" + refCol + " = " + localCol + ")",
                false,
                false,
                new BigDecimal("0.9997920728"));
        FilterNode left = new FilterNode("left", 76_950L, null);
        left.setTableName(localTable);
        FilterNode right = new FilterNode("right", 0L, null);
        right.setTableName(refLeafTable);
        join.setLeftNode(left);
        join.setRightNode(right);
        join.setLeftInputRows(76_950L);
        join.setRightInputRows(76_950L);

        ConstraintChain chain = new ConstraintChain(localTable);
        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeJoinNode", JoinNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        Object rows = method.invoke(qa, join, chain, 76_950L);

        assertEquals(76_966L, rows);
        assertEquals(1, chain.getNodes().size());
        ConstraintChainFkJoinNode fk = (ConstraintChainFkJoinNode) chain.getNodes().getFirst();
        assertEquals(JoinConstraintJoinModel.GENERIC, fk.getJoinModel());
        assertEquals(ConstraintNodeJoinType.OUTER_JOIN, fk.getType());
        assertEquals(76_950L, fk.getLeftInputRows());
        assertEquals(76_950L, fk.getRightInputRows());
        assertEquals(76_950L, fk.getLocalInputRows());
        assertEquals(76_950L, fk.getRefInputRows());
    }

    @Test
    void analyzeSelectNode_skipsSubPlanAlternativePlaceholder() throws Exception {
        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        FilterNode filter = new FilterNode(
                "q10-filter",
                2_188_869L,
                "(alternatives: SubPlan 5 or hashed SubPlan 6)");
        filter.setTableName("sg_mis.meter_cntr_dev_run");
        ConstraintChain chain = new ConstraintChain("sg_mis.meter_cntr_dev_run");

        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeSelectNode", ruc.db.analyzer.online.node.ExecutionNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        Object rows = method.invoke(qa, filter, chain, 3_476_000L);

        assertEquals(2_188_869L, rows);
        assertTrue(chain.getNodes().isEmpty());
        assertTrue(QueryAnalyzer.isUnmodeledSubPlanAlternative(filter.getInfo()));
    }

    @Test
    void analyzeJoinNode_buildsSemiJoinForRecoveredQ10ExistsConstraint() throws Exception {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String localTable = "sg_mis.meter_cntr_dev_run_" + u;
        String refTable = "sgami_arch.a_mgt_org_childs_" + u;
        String localCol = localTable + ".mgt_org_code";
        String refCol = refTable + ".child_mgt_org_code";
        String refFilterCol = refTable + ".mgt_org_code";

        TableManager.getInstance().addSchema(localTable, new Table(List.of(localCol), 3_469_640L));
        Table ref = new Table(List.of(refCol, refFilterCol), 202L);
        ref.setPrimaryKeys(new ArrayList<>(List.of(refFilterCol, refCol)));
        TableManager.getInstance().addSchema(refTable, ref);

        Column localColumn = new Column(ColumnType.VARCHAR);
        localColumn.setRange(2_000);
        Column refColumn = new Column(ColumnType.VARCHAR);
        refColumn.setRange(202);
        Column refFilter = new Column(ColumnType.VARCHAR);
        refFilter.setRange(1);
        ColumnManager.getInstance().addColumn(localCol, localColumn);
        ColumnManager.getInstance().addColumn(refCol, refColumn);
        ColumnManager.getInstance().addColumn(refFilterCol, refFilter);

        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        JoinNode join = new JoinNode(
                "j-q10-semi",
                2_188_869L,
                "Index Cond: (" + refCol + " = " + localCol + ")",
                false,
                true,
                BigDecimal.ZERO);
        FilterNode left = new FilterNode("left", 3_469_640L, null);
        left.setTableName(localTable);
        FilterNode right = new FilterNode("right", 202L, refFilterCol + " = '51401'");
        right.setTableName(refTable);
        join.setLeftNode(left);
        join.setRightNode(right);
        join.setLeftInputRows(3_469_640L);
        join.setRightInputRows(202L);

        ConstraintChain chain = new ConstraintChain(localTable);
        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeJoinNode", JoinNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        Object rows = method.invoke(qa, join, chain, 3_469_640L);

        assertEquals(2_188_869L, rows);
        assertEquals(1, chain.getNodes().size());
        ConstraintChainFkJoinNode fk = (ConstraintChainFkJoinNode) chain.getNodes().getFirst();
        assertEquals(ConstraintNodeJoinType.SEMI_JOIN, fk.getType());
        assertEquals(JoinConstraintJoinModel.GENERIC, fk.getJoinModel());
        assertEquals(2_188_869L, fk.getTargetJoinRows());
        assertEquals(3_469_640L, fk.getLocalInputRows());
        assertEquals(202L, fk.getRefInputRows());
    }

    @Test
    void analyzeJoinNode_retainsQ10ParentOrgNestedLoopJoin() throws Exception {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String localTable = "sg_mis.meter_cntr_dev_run_" + u;
        String refTable = "sgami_arch.a_mgt_org_parents_" + u;
        String localCol = localTable + ".mgt_org_code";
        String refCol = refTable + ".mgt_org_code";
        String refPkExtra = refTable + ".org_level";

        TableManager.getInstance().addSchema(localTable, new Table(List.of(localCol), 2_188_869L));
        Table ref = new Table(List.of(refCol, refPkExtra), 101L);
        ref.setPrimaryKeys(new ArrayList<>(List.of(refCol, refPkExtra)));
        TableManager.getInstance().addSchema(refTable, ref);
        TableManager.getInstance().setForeignKeys(localTable, "mgt_org_code", refTable, "mgt_org_code");

        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        JoinNode join = new JoinNode(
                "j-q10-po",
                101L,
                "Index Cond: (" + refCol + " = " + localCol + ")",
                false,
                false,
                BigDecimal.ZERO);
        FilterNode left = new FilterNode("left", 2_188_869L, null);
        left.setTableName(localTable);
        FilterNode right = new FilterNode("right", 101L, null);
        right.setTableName(refTable);
        join.setLeftNode(left);
        join.setRightNode(right);
        join.setLeftInputRows(2_188_869L);
        join.setRightInputRows(101L);

        ConstraintChain chain = new ConstraintChain(localTable);
        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeJoinNode", JoinNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        Object rows = method.invoke(qa, join, chain, 2_188_869L);

        assertEquals(101L, rows);
        assertEquals(1, chain.getNodes().size());
        ConstraintChainFkJoinNode fk = (ConstraintChainFkJoinNode) chain.getNodes().getFirst();
        assertEquals(localTable + ".mgt_org_code", fk.getLocalCols());
        assertEquals(refTable + ".mgt_org_code", fk.getRefCols());
        assertEquals(101L, fk.getTargetJoinRows());
        assertEquals(2_188_869L, fk.getLocalInputRows());
        assertEquals(101L, fk.getRefInputRows());
    }

    @Test
    void analyzeJoinInfo_preservesCompositeJoinColumnsForQ5SecondBranch() {
        PgAnalyzer analyzer = new PgAnalyzer();
        String[] resolved = new String[4];

        double probability = analyzer.analyzeJoinInfo(
                "Hash Cond: ((sg_mis.elec_cons_cust.cust_id = sgami_arch.a_arch_meter_full_info.cust_id) " +
                        "AND (sg_mis.elec_cons_cust.mgt_org_code = sgami_arch.a_arch_meter_full_info.mgt_org_code))",
                resolved);

        assertEquals(1.0, probability);
        assertEquals("sg_mis.elec_cons_cust", resolved[0]);
        assertEquals("cust_id,mgt_org_code", resolved[1]);
        assertEquals("sgami_arch.a_arch_meter_full_info", resolved[2]);
        assertEquals("cust_id,mgt_org_code", resolved[3]);
    }

    @Test
    void analyzeAggregateNode_preservesRepeatedMembersHavingAggregate() throws Exception {
        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        AggNode agg = new AggNode("q5-members-agg", 250, "sgami_support.s_obj_group_members.members_id");
        agg.setTableName("sgami_support.s_obj_group_members");
        FilterNode having = new FilterNode("having", 200, "(count(*) > 1)");
        agg.setAggFilter(having);
        agg.setAggregateFilterKind(AggNode.AggregateFilterKind.COUNT_GT_LITERAL);
        ConstraintChain chain = new ConstraintChain("sgami_support.s_obj_group_members");

        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeAggregateNode", AggNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        Object rows = method.invoke(qa, agg, chain, 1_000L);

        assertEquals(250L, rows);
        assertEquals(1, chain.getNodes().size());
        ConstraintChainAggregateNode node = (ConstraintChainAggregateNode) chain.getNodes().getFirst();
        assertNotNull(node.getGroupKey());
        assertEquals(List.of("sgami_support.s_obj_group_members.members_id"), node.getGroupKey());
        assertEquals(1_000L, node.getInputRows());
        assertEquals(200L, node.getOutputRows());
        assertNull(node.getAggFilter());
    }

    @Test
    void analyzeAggregateNode_preservesDistinctExpansionAggregate() throws Exception {
        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        AggNode agg =
                new AggNode("q5-distinct-agg", 101, "sgami_arch.a_arch_meter_full_info.mgt_org_code");
        agg.setTableName("sgami_arch.a_arch_meter_full_info");
        ConstraintChain chain = new ConstraintChain("sgami_arch.a_arch_meter_full_info");

        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeAggregateNode", AggNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        Object rows = method.invoke(qa, agg, chain, 12_345L);

        assertEquals(101L, rows);
        assertEquals(1, chain.getNodes().size());
        ConstraintChainAggregateNode node = (ConstraintChainAggregateNode) chain.getNodes().getFirst();
        assertEquals(List.of("sgami_arch.a_arch_meter_full_info.mgt_org_code"), node.getGroupKey());
        assertEquals(12_345L, node.getInputRows());
        assertEquals(101L, node.getOutputRows());
    }

    @Test
    void referencesOtherTables_detectsCanonicalCrossTablePredicate() {
        assertTrue(QueryAnalyzer.referencesOtherTables(
                "(sgami_support.s_obj_group.group_no = sgami_support.s_obj_group_members.group_no)",
                "sgami_support.s_obj_group"));
        assertFalse(QueryAnalyzer.referencesOtherTables(
                "(sgami_arch.a_mgt_org_childs.mgt_org_code = '51401')",
                "sgami_arch.a_mgt_org_childs"));
    }

    @Test
    void analyzeSelectInfo_preRecoversBareSingleTableCastEqualityWithoutParserNoise() throws Exception {
        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        Method method = QueryAnalyzer.class.getDeclaredMethod("analyzeSelectInfo", String.class, String.class);
        method.setAccessible(true);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        LogicNode node;
        try {
            System.setErr(new PrintStream(capturedErr));
            node = (LogicNode) method.invoke(
                    qa,
                    "sgami_arch.a_mgt_org_childs.mgt_org_code = '51401'::text",
                    "sgami_arch.a_mgt_org_childs");
        } finally {
            System.setErr(originalErr);
        }

        assertTrue(node.retainOnlyTableOperations("sgami_arch.a_mgt_org_childs"));
        assertEquals("mgt_org_code = '51401'", node.toString().replaceAll(System.lineSeparator(), " "));
        String errOutput = capturedErr.toString();
        assertFalse(errOutput.contains("Syntax error for input symbol"));
        assertFalse(errOutput.contains("Couldn't repair and continue parse"));
    }

    @Test
    void analyzeSelectNode_mixedCrossTableIndexCondRecoversLocalFilterWithoutParserNoise() throws Exception {
        QueryAnalyzer qa = new QueryAnalyzer(new PgAnalyzer(), null);
        FilterNode filter = new FilterNode(
                "q5-amoc-filter",
                202L,
                "(((sgami_arch.a_mgt_org_childs.mgt_org_code)::text = '51401'::text) " +
                        "AND ((sgami_arch.a_mgt_org_childs.child_mgt_org_code)::text = " +
                        "(sgami_arch.a_arch_meter_full_info.mgt_org_code)::text))");
        filter.setTableName("sgami_arch.a_mgt_org_childs");
        filter.setIndexScan(true);
        ConstraintChain chain = new ConstraintChain("sgami_arch.a_mgt_org_childs");

        Method method = QueryAnalyzer.class.getDeclaredMethod(
                "analyzeSelectNode", ruc.db.analyzer.online.node.ExecutionNode.class, ConstraintChain.class, long.class);
        method.setAccessible(true);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(capturedErr));
            Object rows = method.invoke(qa, filter, chain, 202L);
            assertEquals(202L, rows);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, chain.getNodes().size());
        ConstraintChainFilterNode filterNode = (ConstraintChainFilterNode) chain.getNodes().getFirst();
        assertTrue(filterNode.toString().contains("mgt_org_code = '51401'"));
        String errOutput = capturedErr.toString();
        assertFalse(errOutput.contains("Syntax error for input symbol"));
        assertFalse(errOutput.contains("Couldn't repair and continue parse"));
        assertFalse(errOutput.contains("expected token classes are [LPAREN]"));
    }
}
