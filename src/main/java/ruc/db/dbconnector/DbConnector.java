package ruc.db.dbconnector;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.LanguageManager;
import ruc.db.dbconnector.adapter.PgConnector;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnStatistics;
import ruc.db.schema.ColumnType;
import ruc.db.utils.DatabaseConnectorConfig;
import ruc.db.utils.exception.TouchstoneException;

/**
 * @author wangqingshuai 数据库驱动连接器
 */
public abstract class DbConnector {
    private final Logger logger = LoggerFactory.getLogger(DbConnector.class);
    private final HashMap<String, Integer> multiColNdvMap = new HashMap<>();
    private final int[] sqlInfoColumns;
    private final Connection conn;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    private static final List<Field> ALL_FIELDS = Arrays.stream(Types.class.getDeclaredFields()).filter(f -> Modifier.isStatic(f.getModifiers())).toList();
    protected final DatabaseConnectorConfig config;

    protected DbConnector(DatabaseConnectorConfig config, String dbType, String databaseConnectionConfig)
            throws TouchstoneException, SQLException {
        this.config = config;
        String url;
        if (config.getDatabaseName() != null) {
            url = String.format("jdbc:%s://%s:%s/%s?%s", dbType, config.getDatabaseIp(), config.getDatabasePort(),
                    config.getDatabaseName(), databaseConnectionConfig);
        } else {
            url = String.format("jdbc:%s://%s:%s/?%s", dbType, config.getDatabaseIp(), config.getDatabasePort(),
                    databaseConnectionConfig);
        }
        // 数据库的用户名与密码
        String user = config.getDatabaseUser();
        String pass = config.getDatabasePwd();
        conn = DriverManager.getConnection(url, user, pass);
        try (Statement stmt = conn.createStatement()) {
            for (String command : preExecutionCommands()) {
                stmt.execute(command);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new TouchstoneException(String.format("无法建立数据库连接,连接信息为: '%s'", url));
        }
        sqlInfoColumns = getSqlInfoColumns();
    }

    /**
     * 获取数据库连接配置
     * @return 数据库连接配置
     */
    protected DatabaseConnectorConfig getConfig() {
        return config;
    }

    /**
     * @return 获取查询计划的列索引
     */
    protected abstract int[] getSqlInfoColumns();

    protected abstract String getExplainFormat();

    /**
     * 获取表的外键信息
     * @param canonicalTableName 规范表名 (schema.table)
     * @return 外键映射: 本表列名 -> 引用表列名
     * @throws SQLException SQL异常
     */
    public Map<String, String> getForeignKeys(String canonicalTableName) throws SQLException {
        String[] schemaAndTable = canonicalTableName.split("\\.");
        Map<String, String> foreignKeys = new HashMap<>();
        DatabaseMetaData databaseMetaData = conn.getMetaData();
        ResultSet rs = databaseMetaData.getImportedKeys(null, schemaAndTable[0], schemaAndTable[1]);
        while (rs.next()) {
            String localColumnName = canonicalTableName + "." + rs.getString("FKCOLUMN_NAME");
            String refTableName = rs.getString("PKTABLE_SCHEM") + "." + rs.getString("PKTABLE_NAME");
            String refColumnName = refTableName + "." + rs.getString("PKCOLUMN_NAME");
            foreignKeys.put(localColumnName, refColumnName);
        }
        return foreignKeys;
    }

    /**
     * 获取数据库中所有表名
     * @return 所有表的规范名称列表 (schema.table)
     * @throws SQLException SQL异常
     */
    public List<String> getAllTableNames() throws SQLException {
        List<String> tableNames = new ArrayList<>();
        DatabaseMetaData databaseMetaData = conn.getMetaData();
        ResultSet rs = databaseMetaData.getTables(null, null, null, new String[]{"TABLE"});
        while (rs.next()) {
            String schemaName = rs.getString("TABLE_SCHEM");
            String tableName = rs.getString("TABLE_NAME");
            if (schemaName != null && tableName != null) {
                tableNames.add(schemaName + "." + tableName);
            }
        }
        return tableNames;
    }

    /**
     * 获取节点上查询计划的信息
     *
     * @param queryPlan 需要处理的查询计划
     * @return 返回格式化后的查询计划
     */
    protected abstract String[] formatQueryPlan(String[] queryPlan);

    protected abstract String[] preExecutionCommands();

    public List<String> getColumnMetadata(String canonicalTableName) throws SQLException, TouchstoneException {
        String[] schemaAndTable = canonicalTableName.split("\\.");
        logger.info("DEBUG: 传入的 tablename: {}, 分割结果: {}", canonicalTableName, java.util.Arrays.toString(schemaAndTable));

        List<String> columnNames = new ArrayList<>();
        DatabaseMetaData databaseMetaData = conn.getMetaData();
        logger.info("DEBUG: 成功获取 databasemetadata");
        logger.info("DEBUG: 开始获取列的定义");
        ResultSet rs = databaseMetaData.getColumns(null, schemaAndTable[0], schemaAndTable[1], null);

        while (rs.next()) {
            String canonicalColumnName = canonicalTableName + "." + rs.getString("COLUMN_NAME").trim();
            columnNames.add(canonicalColumnName);
            int jdbcType = rs.getInt("DATA_TYPE");

            String originalType = switch (jdbcType) {
                case Types.CHAR -> getTypeName(jdbcType) + "(" + rs.getInt("CHAR_OCTET_LENGTH") + ")";
                case Types.VARCHAR -> {
                    int charLength = rs.getInt("CHAR_OCTET_LENGTH");
                    if (charLength == Integer.MAX_VALUE) {
                        yield "TEXT";
                    } else {
                        yield getTypeName(jdbcType) + "(" + charLength + ")";
                    }
                }
                case Types.NUMERIC -> "DECIMAL" + "(" + rs.getInt("COLUMN_SIZE") + "," + rs.getInt("DECIMAL_DIGITS") + ")";
                default -> getTypeName(jdbcType);
            } + (rs.getInt("NULLABLE") == 0 ? " NOT NULL" : " DEFAULT NULL");
            if (this instanceof PgConnector && originalType.contains("DOUBLE")) {
                originalType = originalType.replace("DOUBLE", "DOUBLE PRECISION");
            }

            // 线程安全：所有对 ColumnManager 的读写都放到一个同步块里
            synchronized (ColumnManager.getInstance()) {
                Column existing = ColumnManager.getInstance().getColumn(canonicalColumnName);
                if (existing == null) {
                    Column newColumn = new Column(ColumnType.getColumnType(jdbcType));
                    newColumn.setOriginalType(originalType);
                    try {
                        ColumnManager.getInstance().addColumn(canonicalColumnName, newColumn);
                        if (ColumnType.getColumnType(jdbcType) == ColumnType.DECIMAL) {
                            ColumnManager.getInstance().setSpecialValue(canonicalColumnName, (int) Math.pow(10, rs.getInt("DECIMAL_DIGITS")));
                        }
                    } catch (TouchstoneException e) {
                        logger.error("添加列 {} 时出错: {}", canonicalColumnName, e.getMessage());
                    }
                } else {
                    existing.setOriginalType(originalType);
                    if (ColumnType.getColumnType(jdbcType) == ColumnType.DECIMAL) {
                        ColumnManager.getInstance().setSpecialValue(canonicalColumnName, (int) Math.pow(10, rs.getInt("DECIMAL_DIGITS")));
                    }
                }
            }
        }
        return columnNames;
    }

    private static String getTypeName(int type) {
        for (Field field : ALL_FIELDS) {
            try {
                if (field.getInt(null) == type) {
                    return field.getName();
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public String[] getDataRange(String canonicalTableName, List<String> canonicalColumnNames)
            throws SQLException, TouchstoneException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(String.format("select %s from %s", getColumnDistributionSql(canonicalColumnNames), canonicalTableName));
            rs.next();
            String[] infos = new String[rs.getMetaData().getColumnCount()];
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                try {
                    infos[i - 1] = rs.getString(i).trim().toLowerCase();
                } catch (NullPointerException e) {
                    logger.error(rb.getString("dataEmpty"));
                    infos[i - 1] = null;
                }
            }
            return infos;
        }
    }

    public List<ColumnStatistics> getColumnDataStatistics(String canonicalTableName, List<String> canonicalColumnNames)
            throws SQLException {
        // 创建列名到索引的映射
        Map<String, String> columnIndexMap = new HashMap<>();
        for (String canonicalColumnName : canonicalColumnNames) {
            String[] columnParts = canonicalColumnName.split("\\.");
            String columnName = columnParts[columnParts.length - 1];
            columnIndexMap.put(columnName, canonicalColumnName);
        }
        
        List<ColumnStatistics> statisticsList = new ArrayList<>();
        
        try (Statement stmt = conn.createStatement()) {
            String sql = getSingleColumnStatisticsSql(canonicalTableName);
            logger.info("获取单列统计信息的SQL: {}", sql);
            ResultSet rs = stmt.executeQuery(sql);
            
            while (rs.next()) {
                String attname = rs.getString("attname");
                if (attname != null && columnIndexMap.containsKey(attname)) {
                    String canonicalColumnName = columnIndexMap.get(attname);
                    
                    // 获取统计信息
                    String nullFrac = rs.getString("null_frac");
                    String nDistinct = rs.getString("n_distinct");
                    String avgWidth = rs.getString("avg_width");
                    String mostCommonVals = rs.getString("most_common_vals");
                    String mostCommonFreqs = rs.getString("most_common_freqs");
                    String histogramBounds = rs.getString("histogram_bounds");
                    
                    // 处理非null值：trim和格式化
                    if (nullFrac != null) nullFrac = nullFrac.trim().toLowerCase();
                    if (nDistinct != null) nDistinct = nDistinct.trim().toLowerCase();
                    if (avgWidth != null) avgWidth = avgWidth.trim().toLowerCase();
                    if (mostCommonVals != null) mostCommonVals = mostCommonVals.trim();
                    if (mostCommonFreqs != null) mostCommonFreqs = mostCommonFreqs.trim();
                    if (histogramBounds != null) histogramBounds = histogramBounds.trim();
                    
                    ColumnStatistics stats = new ColumnStatistics(
                        canonicalColumnName, nullFrac, nDistinct, avgWidth, 
                        mostCommonVals, mostCommonFreqs, histogramBounds
                    );
                    
                    statisticsList.add(stats);
                    logger.info("表 {} 的列 {} 的统计信息已提取", canonicalTableName, attname);
                }
            }
        }
        
        return statisticsList;
    }


    public void executeSql(String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    public List<String[]> explainQuery(Map.Entry<String, String> tableNameAndFilterInfo) throws SQLException {
        return explainQuery(String.format("SELECT COUNT(*) FROM %s WHERE %s;",
                tableNameAndFilterInfo.getKey(), tableNameAndFilterInfo.getValue()));
    }

    public List<String[]> explainQuery(String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String query = String.format(getExplainFormat(), sql);
            ResultSet rs = stmt.executeQuery(query);
            ArrayList<String[]> result = new ArrayList<>();
            while (rs.next()) {
                String[] infos = new String[sqlInfoColumns.length];
                for (int i = 0; i < sqlInfoColumns.length; i++) {
                    infos[i] = rs.getString(sqlInfoColumns[i]);
                }
                result.add(formatQueryPlan(infos));
            }
            return result;
        }
    }

    public int getMultiColNdv(String canonicalTableName, String columns) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(String.format("select count(*) from (select distinct %S from %S) as a", columns, canonicalTableName));
            rs.next();
            int result = rs.getInt("count");
            multiColNdvMap.put(String.format("%s.%s", canonicalTableName, columns), result);
            return result;
        }
    }

    public Map<String, Integer> getMultiColNdvMap() {
        return this.multiColNdvMap;
    }

    public int getTableSize(String canonicalTableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String countQuery = String.format("select count(*) as cnt from %s", canonicalTableName);
            ResultSet rs = stmt.executeQuery(countQuery);
            if (rs.next()) {
                return rs.getInt("cnt");
            }
            throw new SQLException(String.format("table'%s'的size为0", canonicalTableName));
        }
    }

    public int getRowsAfterFilter(String tableName, String filterInfo) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String countQuery = String.format("select count(*) as cntAfterFilter from %s where %s;", tableName, filterInfo);
            ResultSet rs = stmt.executeQuery(countQuery);
            if (rs.next()) {
                return rs.getInt("cntAfterFilter");
            }
            throw new SQLException(String.format("rows after filter: %s", tableName));
        }
    }

    /**
     * 获取col分布所需的查询SQL语句
     *
     * @param canonicalColumnNames 需要查询的col
     * @return SQL
     * @throws TouchstoneException 获取失败
     */
    private String getColumnDistributionSql(List<String> canonicalColumnNames) throws TouchstoneException {
        StringBuilder sql = new StringBuilder();
        for (String canonicalColumnName : canonicalColumnNames) {
            ColumnType type = ColumnManager.getInstance().getColumnType(canonicalColumnName);
            String[] canonicalColumnNameList = canonicalColumnName.split("\\.");
            canonicalColumnName = Arrays.stream(canonicalColumnNameList)
                    .map(s -> String.format("\"%s\"", s))
                    .collect(Collectors.joining("."));
            switch (type) {
                case DATE, DATETIME, DECIMAL ->
                        sql.append(String.format("min(%1$s), max(%1$s), ", canonicalColumnName));
                case INTEGER ->
                        sql.append(String.format("min(%1$s::int), max(%1$s::int), count(distinct %1$s::int),", canonicalColumnName));
                case VARCHAR ->
                        sql.append(String.format("avg(length(%1$s)), max(length(%1$s)), count(distinct %1$s),", canonicalColumnName));
                case BOOL -> sql.append(String.format("avg(%s)", canonicalColumnName));
                default -> throw new TouchstoneException("未匹配到的类型");
            }
            sql.append(String.format("sum(case when %s IS NULL then 1 else 0 end),", canonicalColumnName));
        }
        return sql.substring(0, sql.length() - 1);
    }


    /**
     * 获取单列统计信息，包括      
     *  • Fraction of NULL values
     *  • Number of distinct values
     *  • Average width in bytes
     *  • Equi-depth histogram
     *  • Most common values (MCVs) 和它们的频率 (MCFs)
     * @param canonicalTableName 需要查询的表
     * @return SQL
     */
    protected String getSingleColumnStatisticsSql(String canonicalTableName) {
        // 解析表名，只要表名部分，不含schema
        String[] tableParts = canonicalTableName.split("\\.");
        String tableName = tableParts.length == 2 ? tableParts[1] : canonicalTableName;
        return String.format(
                "SELECT attname, null_frac, n_distinct, avg_width, most_common_vals, most_common_freqs, histogram_bounds " +
                "FROM pg_stats WHERE tablename = '%s';",
                tableName
            );
    }


    public List<String> getPrimaryKey(String canonicalTableName) throws SQLException {
        String[] schemaAndTable = canonicalTableName.split("\\.");
        List<String> primaryKeys = new ArrayList<>();
        DatabaseMetaData databaseMetaData = conn.getMetaData();
        ResultSet rs = databaseMetaData.getPrimaryKeys(null, schemaAndTable[0], schemaAndTable[1]);
        while (rs.next()) {
            String canonicalColumnName = canonicalTableName + "." + rs.getString("COLUMN_NAME");
            primaryKeys.add(canonicalColumnName);
        }
        // 输出一下提取到的主键信息
        if (primaryKeys.isEmpty()) {
            logger.warn("表 {} 没有主键信息", canonicalTableName);
        } else {
            logger.info("表 {} 的主键为: {}", canonicalTableName, primaryKeys);
        }
        return primaryKeys;
    }

    /**
     * 处理列值，子类可以重写此方法以处理特定数据库的数据类型
     * @param value 原始值
     * @param dataType 数据类型
     * @return 处理后的值
     */
    protected String processColumnValue(String value, String dataType) {
        return value;
    }

    protected List<String> getMostCommonValues(ResultSet rs, String columnName) throws SQLException {
        List<String> mcv = new ArrayList<>();
        while (rs.next()) {
            String value = rs.getString(1);
            if (value != null) {
                // 获取数据类型
                String dataType = rs.getMetaData().getColumnTypeName(2);
                // 处理值
                value = processColumnValue(value, dataType);
                mcv.add(value);
            }
        }
        return mcv;
    }

    protected List<String> getHistogramBounds(ResultSet rs, String columnName) throws SQLException {
        List<String> bounds = new ArrayList<>();
        while (rs.next()) {
            String value = rs.getString(1);
            if (value != null) {
                // 获取数据类型
                String dataType = rs.getMetaData().getColumnTypeName(2);
                // 处理值
                value = processColumnValue(value, dataType);
                bounds.add(value);
            }
        }
        return bounds;
    }

    protected String getMinValue(ResultSet rs, String columnName) throws SQLException {
        if (rs.next()) {
            String value = rs.getString(1);
            if (value != null) {
                // 获取数据类型
                String dataType = rs.getMetaData().getColumnTypeName(2);
                // 处理值
                return processColumnValue(value, dataType);
            }
        }
        return null;
    }

    protected String getMaxValue(ResultSet rs, String columnName) throws SQLException {
        if (rs.next()) {
            String value = rs.getString(1);
            if (value != null) {
                // 获取数据类型
                String dataType = rs.getMetaData().getColumnTypeName(2);
                // 处理值
                return processColumnValue(value, dataType);
            }
        }
        return null;
    }

    /**
     * 获取数据库连接
     * @return 数据库连接对象
     */
    public Connection getConnection() {
        return conn;
    }

    /**
     * 获取数据库连接（兼容旧API）
     * @return 数据库连接对象
     */
    public Connection getConn() {
        return conn;
    }

    /**
     * 获取表中指定列的不重复值数量
     * @param canonicalTableName 规范表名 (schema.table)
     * @param columnName 列名
     * @return 不重复值数量
     * @throws SQLException SQL异常
     */
    public long getDistinctCount(String canonicalTableName, String columnName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String sql = String.format("SELECT COUNT(DISTINCT \"%s\") FROM %s", columnName, canonicalTableName);
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }

    /**
     * 检查列是否具有唯一性约束
     * @param canonicalTableName 规范表名 (schema.table)
     * @param columnName 列名
     * @return 是否唯一
     * @throws SQLException SQL异常
     */
    public boolean isColumnUnique(String canonicalTableName, String columnName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String sql = String.format(
                "SELECT COUNT(*) FROM (SELECT \"%s\", COUNT(*) FROM %s GROUP BY \"%s\" HAVING COUNT(*) > 1) AS duplicates",
                columnName, canonicalTableName, columnName
            );
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getLong(1) == 0; // 如果没有重复值，则唯一
            }
            return false;
        }
    }

    /**
     * 获取join基数统计
     * @param leftTable 左表名
     * @param leftColumn 左表列名
     * @param rightTable 右表名
     * @param rightColumn 右表列名
     * @param joinType join类型 (INNER, LEFT, RIGHT, ANTI, SEMI)
     * @return join结果行数
     * @throws SQLException SQL异常
     */
    public long getJoinCardinality(String leftTable, String leftColumn, String rightTable, String rightColumn, String joinType) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String sql;
            switch (joinType.toUpperCase()) {
                case "INNER":
                case "INNER_JOIN":
                    sql = String.format(
                        "SELECT COUNT(*) FROM %s l INNER JOIN %s r ON l.\"%s\" = r.\"%s\"",
                        leftTable, rightTable, leftColumn, rightColumn
                    );
                    break;
                case "LEFT":
                case "LEFT_JOIN":
                    sql = String.format(
                        "SELECT COUNT(*) FROM %s l LEFT JOIN %s r ON l.\"%s\" = r.\"%s\"",
                        leftTable, rightTable, leftColumn, rightColumn
                    );
                    break;
                case "ANTI":
                case "ANTI_JOIN":
                    sql = String.format(
                        "SELECT COUNT(*) FROM %s l WHERE NOT EXISTS (SELECT 1 FROM %s r WHERE l.\"%s\" = r.\"%s\")",
                        leftTable, rightTable, leftColumn, rightColumn
                    );
                    break;
                case "SEMI":
                case "SEMI_JOIN":
                    sql = String.format(
                        "SELECT COUNT(*) FROM %s l WHERE EXISTS (SELECT 1 FROM %s r WHERE l.\"%s\" = r.\"%s\")",
                        leftTable, rightTable, leftColumn, rightColumn
                    );
                    break;
                default:
                    throw new SQLException("不支持的join类型: " + joinType);
            }
            
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }

}