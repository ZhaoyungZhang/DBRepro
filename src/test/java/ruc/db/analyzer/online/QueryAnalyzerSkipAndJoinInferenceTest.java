package ruc.db.analyzer.online;

import ruc.db.analyzer.online.adapter.pg.PgAnalyzer;
import ruc.db.analyzer.online.node.JoinNode;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.utils.exception.TouchstoneException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
