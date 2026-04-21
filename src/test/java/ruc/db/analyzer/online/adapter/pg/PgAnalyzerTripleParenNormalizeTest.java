package ruc.db.analyzer.online.adapter.pg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kingbase/合并计划里常见的多层括号日期比较 + 前置 AND 组。 */
class PgAnalyzerTripleParenNormalizeTest {

    @Test
    void tripleParenBeforeCompareBecomesDouble() {
        PgAnalyzer a = new PgAnalyzer();
        String in =
                "(((sgami_stat.a_lm_ui_out_det_hour.data_date)) >= '2024-06-02 00:00:00') AND (((sgami_stat.a_lm_ui_out_det_hour.data_date)) < '2024-06-03 00:00:00')";
        String out = a.normalizeTripleParenCompare(in);
        assertEquals(
                "((sgami_stat.a_lm_ui_out_det_hour.data_date) >= '2024-06-02 00:00:00') AND ((sgami_stat.a_lm_ui_out_det_hour.data_date) < '2024-06-03 00:00:00')",
                out);
    }

    @Test
    void quadParenBeforeCompareBecomesDouble() {
        PgAnalyzer a = new PgAnalyzer();
        String in =
                "((((sgami_stat.a_lm_ui_out_det_hour.data_date) >= '2024-06-02 00:00:00') AND (((sgami_stat.a_lm_ui_out_det_hour.data_date) < '2024-06-03 00:00:00')";
        String out = a.normalizeTripleParenCompare(in);
        assertEquals(
                "((sgami_stat.a_lm_ui_out_det_hour.data_date) >= '2024-06-02 00:00:00') AND ((sgami_stat.a_lm_ui_out_det_hour.data_date) < '2024-06-03 00:00:00')",
                out);
    }

    @Test
    void tripleWrappedThreeIntEqAndChainBecomesDoubleWrapped() {
        PgAnalyzer a = new PgAnalyzer();
        String in =
                "(((sgami_stat.a_lm_ui_out_det_hour.cons_type = 3) AND (sgami_stat.a_lm_ui_out_det_hour.is_special = 0) AND (sgami_stat.a_lm_ui_out_det_hour.ui_abnor_type = 4)))";
        String out = a.normalizeTripleParenCompare(in);
        assertEquals(
                "((sgami_stat.a_lm_ui_out_det_hour.cons_type = 3) AND (sgami_stat.a_lm_ui_out_det_hour.is_special = 0) AND (sgami_stat.a_lm_ui_out_det_hour.ui_abnor_type = 4))",
                out);
    }

    @Test
    void fullMergedFilterShapeStaysParenBalanced() {
        PgAnalyzer a = new PgAnalyzer();
        String in =
                "(((sgami_stat.a_lm_ui_out_det_hour.cons_type = 3) AND (sgami_stat.a_lm_ui_out_det_hour.is_special = 0) AND (sgami_stat.a_lm_ui_out_det_hour.ui_abnor_type = 4))) AND ((((sgami_stat.a_lm_ui_out_det_hour.data_date) >= '2024-06-02 00:00:00') AND ((sgami_stat.a_lm_ui_out_det_hour.data_date) < '2024-06-03 00:00:00')))";
        String out = a.normalizeTripleParenCompare(in);
        int depth = 0;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            assertTrue(depth >= 0, "negative paren depth at " + i);
        }
        assertEquals(0, depth, "unbalanced after normalize: " + out);
    }
}
