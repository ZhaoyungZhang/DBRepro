package ruc.db.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirage {@link ruc.db.generator.DataGenerator} 文本行导出约定。
 * 字段分隔符使用 {@code |}，避免 varchar 中逗号（如 {@code 198.0,235.4}）破坏列对齐。
 */
public final class DataExportConstants {

    private static final Pattern CHAR_FAMILY_MAX_LEN = Pattern.compile(
            "(?i)(character\\s+varying|varchar|character|char|bpchar)\\s*\\(\\s*(\\d+)\\s*\\)");

    private DataExportConstants() {
    }

    /** 单行内字段之间的分隔符（与 COPY TEXT 的 DELIMITER 一致） */
    public static final String FIELD_DELIMITER = "|";

    public static final char FIELD_DELIMITER_CHAR = '|';

    /**
     * 保证字段串中不含分隔符，避免误分列。对 varchar 输出在写出前调用。
     */
    public static String stripFieldDelimiterFromText(String value) {
        if (value == null || value.isEmpty() || !value.contains("|")) {
            return value;
        }
        return value.replace('|', '_');
    }

    /**
     * 从 DDL 类型串解析 {@code varchar(n)} / {@code char(n)} / {@code bpchar(n)} 的长度上限；无则 {@code null}。
     * 与 {@link ruc.db.schema.ColumnManager} 中逻辑保持一致。
     */
    public static Integer parseCharFamilyMaxLength(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return null;
        }
        Matcher m = CHAR_FAMILY_MAX_LEN.matcher(sqlType);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 按列最大字符数截断导出串（与 PG {@code varchar(n)} 常见语义一致，按 Java UTF-16 码元计数）。
     * {@code maxChars <= 0} 表示未声明上限（如 text），不截断。
     */
    public static String truncateToMaxChars(String value, int maxChars) {
        if (value == null || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
