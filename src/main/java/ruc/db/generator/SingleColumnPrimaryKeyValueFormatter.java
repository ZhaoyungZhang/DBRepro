package ruc.db.generator;

import ruc.db.schema.Column;
import ruc.db.schema.ColumnType;
final class SingleColumnPrimaryKeyValueFormatter {
    private SingleColumnPrimaryKeyValueFormatter() {
    }

    static String format(Column column, long ordinal) {
        return PrimaryKeyValueFormatter.format(column, ordinal);
    }

    static String format(ColumnType columnType, String originalType, long ordinal) {
        return PrimaryKeyValueFormatter.format(columnType, originalType, ordinal, 0);
    }
}
