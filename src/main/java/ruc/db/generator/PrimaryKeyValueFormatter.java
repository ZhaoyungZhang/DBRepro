package ruc.db.generator;

import ruc.db.schema.Column;
import ruc.db.schema.ColumnType;
import ruc.db.utils.DataExportConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class PrimaryKeyValueFormatter {
    private static final LocalDate DATE_EPOCH = LocalDate.of(1970, 1, 1);
    private static final LocalDateTime DATETIME_EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PrimaryKeyValueFormatter() {
    }

    static String format(Column column, long ordinal) {
        return format(column, ordinal, 0);
    }

    static String format(Column column, long ordinal, int componentIndex) {
        if (column == null) {
            return Long.toString(Math.max(0L, ordinal));
        }
        return format(column.getColumnType(), column.getOriginalType(), ordinal, componentIndex);
    }

    static String format(ColumnType columnType, String originalType, long ordinal, int componentIndex) {
        long normalized = Math.max(0L, ordinal);
        if (columnType == null) {
            return Long.toString(normalized);
        }
        return switch (columnType) {
            case INTEGER -> Long.toString(normalized);
            case DECIMAL -> Long.toString(normalized);
            case DATE -> DATE_EPOCH.plusDays(normalized).toString();
            case DATETIME -> DATETIME_EPOCH.plusSeconds(normalized).format(DATETIME_FORMATTER);
            case BOOL -> {
                if (normalized > 1L) {
                    throw new IllegalArgumentException("boolean primary key component cannot encode ordinal " + ordinal);
                }
                yield normalized == 0L ? "false" : "true";
            }
            case VARCHAR -> formatVarchar(originalType, normalized, componentIndex);
        };
    }

    private static String formatVarchar(String originalType, long ordinal, int componentIndex) {
        String value = Long.toUnsignedString(ordinal, 36).toUpperCase(Locale.ROOT);
        if (componentIndex > 0) {
            value = Long.toUnsignedString(componentIndex, 36).toUpperCase(Locale.ROOT) + value;
        }
        Integer maxLength = DataExportConstants.parseCharFamilyMaxLength(originalType);
        if (maxLength != null && maxLength > 0 && value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "varchar primary key component cannot encode ordinal " + ordinal
                            + " within declared length " + maxLength);
        }
        return DataExportConstants.stripFieldDelimiterFromText(value);
    }
}
