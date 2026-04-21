package ruc.db.schema;

import com.fasterxml.jackson.annotation.JsonFormat;
import ruc.db.LanguageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Types;
import java.util.ResourceBundle;

/**
 * @author wangqingshuai
 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum ColumnType {
    /* 定义类型的列，可根据配置文件将类型映射到这些类型*/
    INTEGER, VARCHAR, DECIMAL, BOOL, DATE, DATETIME;

    private static final Logger logger = LoggerFactory.getLogger(ColumnType.class);
    private static final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public static ColumnType getColumnType(int dataType) {
        return switch (dataType) {
            // 在 KingBase/JDBC 中，TINYINT 对应 java.sql.Types = -6，需要并入整数类型
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT, Types.BOOLEAN, Types.BIT -> INTEGER;
            case Types.VARCHAR, Types.CHAR -> VARCHAR;
            // 兼容 REAL 也归为数值小数类，便于后续统一生成
            case Types.FLOAT, Types.DOUBLE, Types.REAL, Types.DECIMAL, Types.NUMERIC -> DECIMAL;
            case Types.DATE -> DATE;
            case Types.TIMESTAMP, Types.TIME -> DATETIME;
            default -> {
                logger.error(rb.getString("unsupportedOperatorConversions"), dataType);
                throw new UnsupportedOperationException();
            }
        };
    }

    public boolean isHasCardinalityConstraint() {
        return this == INTEGER || this == VARCHAR;
    }
}
