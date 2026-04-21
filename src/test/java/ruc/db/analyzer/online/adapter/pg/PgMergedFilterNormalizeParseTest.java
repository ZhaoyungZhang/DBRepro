package ruc.db.analyzer.online.adapter.pg;

import ruc.db.analyzer.online.adapter.pg.parser.PgSelectOperatorInfoLexer;
import ruc.db.analyzer.online.adapter.pg.parser.PgSelectOperatorInfoParser;
import ruc.db.generator.constraintchain.filter.LogicNode;
import java_cup.runtime.ComplexSymbolFactory;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 归一化后与 CUP 解析器联调：覆盖 32_1 类合并 FILTER 字符串。 */
class PgMergedFilterNormalizeParseTest {

    @Test
    void kingbaseMergedFilterParsesAfterNormalizeTripleParenCompare() throws Exception {
        PgAnalyzer analyzer = new PgAnalyzer();
        String raw =
                "(((sgami_stat.a_lm_ui_out_det_hour.cons_type = 3) AND (sgami_stat.a_lm_ui_out_det_hour.is_special = 0) AND (sgami_stat.a_lm_ui_out_det_hour.ui_abnor_type = 4))) AND ((((sgami_stat.a_lm_ui_out_det_hour.data_date) >= '2024-06-02 00:00:00') AND ((sgami_stat.a_lm_ui_out_det_hour.data_date) < '2024-06-03 00:00:00')))";
        String normalized = analyzer.normalizeTripleParenCompare(raw);
        /* 与 combineScanFilterPredicates 一致：整段须包在一层 () 内，否则顶层 “P AND Q” 不符合 CUP 的 bool_expr。 */
        String toParse = "(" + normalized + ")";
        PgSelectOperatorInfoParser parser =
                new PgSelectOperatorInfoParser(
                        new PgSelectOperatorInfoLexer(new StringReader("")), new ComplexSymbolFactory());
        LogicNode node = parser.parseSelectOperatorInfo(toParse);
        assertNotNull(node);
    }
}
