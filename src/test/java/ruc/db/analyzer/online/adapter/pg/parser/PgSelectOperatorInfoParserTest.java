package ruc.db.analyzer.online.adapter.pg.parser;

import ruc.db.generator.constraintchain.filter.LogicNode;
import java_cup.runtime.ComplexSymbolFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgSelectOperatorInfoParserTest {
    private final PgSelectOperatorInfoLexer lexer = new PgSelectOperatorInfoLexer(new StringReader(""));
    private PgSelectOperatorInfoParser parser;

    @BeforeEach
    void setUp() {
        parser = new PgSelectOperatorInfoParser(lexer, new ComplexSymbolFactory());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "(db1.table.col1 >= 2);" +
                    "col1 >= '2'",
            "((db1.table.col1 * (db1.table.col2 + 3.0)) >= 2);" +
                    "db1.table.col1 * db1.table.col2 + 3.0 >= 2",
            "((db1.table.col1 >= 2) or (db1.table.col4 < 3.0)); " +
                    "(col1 >= '2' or col4 < '3.0')",
            "((db1.table.col3) ~~ 'STRING');" +
                    "col3 like 'STRING'",
            "(db1.table.col3 = ANY ('{\"dasd\", dasd}'));" +
                    "col3 in ('dasd','dasd')",
            "(public.part.p_size = ANY ('{42,33,35,6,46,24,15,21}'::integer[]));" +
                    "p_size in ('42','33','35','6','46','24','15','21')",
            "((public.part.p_type)::text !~~ 'STANDARD ANODIZED%'::text);" +
                    "p_type not like 'STANDARD ANODIZED%'",
            "((public.part.p_type)::text = 'STANDARD BURNISHED NICKEL'::text);" +
                    "p_type = 'STANDARD BURNISHED NICKEL'",
            /* 执行计划里 is_special = false 常表现为 (NOT schema.table.is_special) */
            "((NOT db1.tablet.is_special) AND (db1.tablet.col1 >= 2));" +
                    "(is_special = 'false' and col1 >= '2')",
            /* Index Cond + Filter 合并并去掉 ::timestamp 后应能解析（等价于 32 类计划） */
            "(((db1.tbl.cons_type = 3) AND (db1.tbl.is_special = 1) AND (db1.tbl.ui_abnor_type = 4)) AND (((db1.tbl.data_date) >= '2024-06-02') AND ((db1.tbl.data_date) < '2024-06-03')));" +
                    "((cons_type = '3' and ui_abnor_type = '4' and is_special = '1') and (data_date >= '2024-06-02' and data_date < '2024-06-03'))"
    })
    void testPgParse(String input, String output) throws Exception {
        LogicNode node = parser.parseSelectOperatorInfo(input);
        assertEquals(output, node.toString().replaceAll(System.lineSeparator(), " "));
    }
}
