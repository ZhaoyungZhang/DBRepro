package ruc.db.generator;

import org.junit.jupiter.api.Test;
import ruc.db.schema.ColumnType;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleColumnPrimaryKeyValueFormatterTest {

    @Test
    void formatsIntegerPrimaryKeysAsUniqueOrdinals() {
        assertEquals("0", SingleColumnPrimaryKeyValueFormatter.format(ColumnType.INTEGER, "bigint", 0));
        assertEquals("42", SingleColumnPrimaryKeyValueFormatter.format(ColumnType.INTEGER, "integer", 42));
    }

    @Test
    void formatsDecimalPrimaryKeysAsIntegralUniqueValues() {
        assertEquals("7", SingleColumnPrimaryKeyValueFormatter.format(ColumnType.DECIMAL, "numeric(18,0)", 7));
    }

    @Test
    void formatsDateAndDatetimePrimaryKeysDeterministically() {
        assertEquals("1970-01-01", SingleColumnPrimaryKeyValueFormatter.format(ColumnType.DATE, "date", 0));
        assertEquals("1970-01-02", SingleColumnPrimaryKeyValueFormatter.format(ColumnType.DATE, "date", 1));
        assertEquals("1970-01-01 00:00:03",
                SingleColumnPrimaryKeyValueFormatter.format(ColumnType.DATETIME, "timestamp", 3));
    }

    @Test
    void formatsVarcharPrimaryKeysWithinDeclaredLength() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String value = SingleColumnPrimaryKeyValueFormatter.format(
                    ColumnType.VARCHAR,
                    "character varying(16 char)",
                    i);
            assertTrue(value.length() <= 16);
            values.add(value);
        }
        assertEquals(10_000, values.size());
    }

    @Test
    void rejectsVarcharPrimaryKeyWhenDeclaredLengthCannotHoldUniqueOrdinal() {
        assertThrows(IllegalArgumentException.class,
                () -> SingleColumnPrimaryKeyValueFormatter.format(ColumnType.VARCHAR, "varchar(1)", 36));
    }
}
