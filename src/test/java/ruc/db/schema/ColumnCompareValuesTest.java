package ruc.db.schema;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnCompareValuesTest {

    @Test
    void varcharComparisonUsesLexicographicOrderWithoutDateCoercion() throws Exception {
        Column column = new Column(ColumnType.VARCHAR);

        assertEquals(0, compare(column, "1876811692544357989", "1876811692544357989"));
        assertTrue(compare(column, "A", "B") < 0);
    }

    @Test
    void dateComparisonKeepsDateSemantics() throws Exception {
        Column column = new Column(ColumnType.DATE);

        assertTrue(compare(column, "2026-06-09", "2026-06-08") > 0);
    }

    private static int compare(Column column, Object left, Object right) throws Exception {
        Method method = Column.class.getDeclaredMethod("compareValues", Object.class, Object.class);
        method.setAccessible(true);
        return (int) method.invoke(column, left, right);
    }
}
