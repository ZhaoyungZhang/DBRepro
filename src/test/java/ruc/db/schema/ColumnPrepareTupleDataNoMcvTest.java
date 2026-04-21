package ruc.db.schema;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 有统计信息对象但无 MCV/直方图时，INTEGER 等类型原先用 null 表示“回退”，
 * 调用方曾未判空导致 NPE；此处校验 prepareTupleData 可走 distribution 回退。
 */
class ColumnPrepareTupleDataNoMcvTest {

    @Test
    void integerColumn_statisticsWithoutMcv_prepareTupleDataDoesNotNpe() {
        Column col = new Column(ColumnType.INTEGER);
        col.setMin(10);
        col.setSpecialValue(1);
        col.setRange(1000);
        col.setNullPercentage(BigDecimal.ZERO);
        col.init();

        EnhancedColumnStatistics stats = new EnhancedColumnStatistics();
        stats.setColumnName("public.t.x");
        stats.setDataType("int4");
        stats.setTableSize(1000);
        stats.setMostCommonValues(Collections.emptyList());
        stats.setHistogramBounds(Collections.emptyList());
        col.buildCDFFromStatistics(stats);

        assertDoesNotThrow(() -> col.prepareTupleData(100));
        assertNotNull(col.getColumnActualData());
        assertEquals(100, col.getColumnActualData().length);
    }
}
