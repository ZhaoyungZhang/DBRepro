package ruc.db.dbconnector.adapter;

import ruc.db.dbconnector.DbConnector;
import ruc.db.utils.DatabaseConnectorConfig;
import ruc.db.utils.exception.TouchstoneException;

import java.sql.SQLException;
import java.sql.DriverManager;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.rsgen.PartitionTreeManager;
import ruc.db.rsgen.PartitionTreeNode;

/**
 * KingBase 数据库连接器
 * KingBase 是基于 PostgreSQL 的国产数据库，使用 KSQL 语法
 * 
 * @author RSGen Implementation
 */
public class KingBaseConnector extends DbConnector {

    private static final Logger logger = LoggerFactory.getLogger(KingBaseConnector.class);
    private static final String DB_DRIVER_TYPE = "kingbase8";
    private static final String JDBC_PROPERTY = "";
    private static final String DRIVER_CLASS = "com.kingbase8.Driver";

    // KingBase 系统 schema
    private static final Set<String> SYSTEM_SCHEMAS = new HashSet<String>() {{
        add("anon");
        add("dbms_sql");
        add("perf");
        add("pg_bitmapindex");
        add("pg_catalog");
        add("src_restrict");
        add("wmsys");
        add("xlog_record_read");
        add("information_schema");
        add("sys");
        add("sys_hm");
        add("sysmac");
        add("sysaudit");
        add("sys_catalog");
    }};

    // 系统表名模式
    private static final Pattern SYSTEM_TABLE_PATTERN = Pattern.compile("^(sys_|pg_|_|dual$|sys_stat_).*");

    static {
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("KingBase JDBC 驱动未找到: " + DRIVER_CLASS, e);
        }
    }

    public KingBaseConnector(DatabaseConnectorConfig config) throws TouchstoneException, SQLException {
        super(config, DB_DRIVER_TYPE, JDBC_PROPERTY);
    }

    @Override
    protected int[] getSqlInfoColumns() {
        // KingBase 使用与 PostgreSQL 相同的信息列
        return new int[]{1};
    }

    @Override
    protected String[] formatQueryPlan(String[] queryPlan) {
        // KingBase 查询计划格式与 PostgreSQL 相同
        return queryPlan;
    }

    @Override
    protected String[] preExecutionCommands() {
        // KingBase 支持 PostgreSQL 兼容的优化设置
        return new String[]{
            "SET max_parallel_workers_per_gather = 0;", 
            "SET join_collapse_limit = 1;",
            "SET enable_hashjoin = off;",
            "SET enable_mergejoin = off;"
        };
    }

    @Override
    protected String getExplainFormat() {
        // KingBase 支持 PostgreSQL 兼容的 EXPLAIN 格式
        return "EXPLAIN (ANALYZE, VERBOSE, FORMAT JSON, COSTS FALSE, TIMING FALSE) %s";
    }

    /**
     * 重写getTableSize方法，专门处理KingBase分区表的表大小获取
     * 根据表的类型使用不同的策略：
     * - 叶子表：使用COUNT(*)获取实际行数
     * - 根分区表和中间分区表：返回0（它们不存储实际数据）
     * - 普通表：先尝试pg_class，再回退到COUNT(*)
     */
    @Override
    public int getTableSize(String canonicalTableName) throws SQLException {
        // 获取表的类型
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        PartitionTreeNode.NodeType nodeType = treeManager.getTableType(canonicalTableName);
        
        if (nodeType != null) {
            switch (nodeType) {
                case ROOT:
                case INTERMEDIATE:
                    // 根分区表和中间分区表不存储实际数据，直接返回0
                    logger.debug("表 {} 是{}，返回大小0", canonicalTableName, 
                            nodeType == PartitionTreeNode.NodeType.ROOT ? "根分区表" : "中间分区表");
                    return 0;
                    
                case LEAF:
                    // 叶子表使用COUNT(*)获取准确的行数
                    return getTableSizeByCount(canonicalTableName);
                    
                case NORMAL:
                    // 普通表使用COUNT(*)获取准确的行数
                    return getTableSizeByCount(canonicalTableName);
                    
                default:
                    logger.warn("未知的表类型: {}, 表: {}, 使用COUNT(*)方法", nodeType, canonicalTableName);
                    return getTableSizeByCount(canonicalTableName);
            }
        } else {
            // 如果表类型未知，使用COUNT(*)方法
            logger.debug("表 {} 类型未知，使用COUNT(*)方法", canonicalTableName);
            return getTableSizeByCount(canonicalTableName);
        }
    }
    
    /**
     * 使用COUNT(*)方法获取表大小
     * 
     * @param canonicalTableName 表名
     * @return 表大小
     * @throws SQLException SQL异常
     */
    private int getTableSizeByCount(String canonicalTableName) throws SQLException {
        try (Statement stmt = getConn().createStatement()) {
            logger.debug("使用COUNT(*)方法获取表 {} 的大小", canonicalTableName);
            String countQuery = String.format("SELECT COUNT(*) as cnt FROM %s", canonicalTableName);
            ResultSet rs = stmt.executeQuery(countQuery);
            
            if (rs.next()) {
                int count = rs.getInt("cnt");
                logger.debug("表 {} 通过COUNT(*)获取的大小: {}", canonicalTableName, count);
                return count;
            }
            
            logger.warn("无法通过COUNT(*)获取表 {} 的大小，返回0", canonicalTableName);
            return 0;
            
        } catch (SQLException e) {
            logger.error("使用COUNT(*)获取表 {} 大小时发生错误: {}", canonicalTableName, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 使用混合策略获取表大小（先pg_class，后COUNT(*)）
     * 
     * @param canonicalTableName 表名
     * @return 表大小
     * @throws SQLException SQL异常
     */
    private int getTableSizeByMixedStrategy(String canonicalTableName) throws SQLException {
        try (Statement stmt = getConn().createStatement()) {
            // 解析schema和表名
            String[] parts = canonicalTableName.split("\\.");
            String schemaName = parts.length == 2 ? parts[0] : "public";
            String tableName = parts.length == 2 ? parts[1] : parts[0];
            
            // 首先尝试使用pg_class系统表获取表大小
            String pgClassQuery = String.format(
                "SELECT c.reltuples::bigint as row_count " +
                "FROM pg_class c " +
                "JOIN pg_namespace n ON c.relnamespace = n.oid " +
                "WHERE n.nspname = '%s' AND c.relname = '%s'",
                schemaName, tableName
            );
            
            logger.debug("尝试使用pg_class获取表 {} 的大小", canonicalTableName);
            ResultSet rs = stmt.executeQuery(pgClassQuery);
            
            if (rs.next()) {
                long rowCount = rs.getLong("row_count");
                logger.debug("表 {} 通过pg_class获取的大小: {}", canonicalTableName, rowCount);
                
                // 如果pg_class返回的行数大于0，使用这个值
                if (rowCount > 0) {
                    return (int) rowCount;
                }
            }
            
            // 如果pg_class方法失败或返回0，回退到COUNT(*)方法
            logger.debug("pg_class方法失败，回退到COUNT(*)方法获取表 {} 的大小", canonicalTableName);
            return getTableSizeByCount(canonicalTableName);
            
        } catch (SQLException e) {
            logger.error("获取表 {} 大小时发生错误: {}", canonicalTableName, e.getMessage());
            return 0;
        }
    }

    @Override
    protected String processColumnValue(String value, String dataType) {
        if (value == null) {
            return null;
        }

        // 对于 KingBase 的 date 类型，需要特殊处理
        if ("date".equalsIgnoreCase(dataType)) {
            return formatDateString(value);
        }

        return super.processColumnValue(value, dataType);
    }

    /**
     * 处理日期格式，移除时间部分
     * @param dateStr 原始日期字符串
     * @return 处理后的日期字符串
     */
    private String formatDateString(String dateStr) {
        if (dateStr == null) {
            return null;
        }

        // 去掉可能存在的引号
        dateStr = dateStr.replace("\"", "");
        
        // 如果包含空格（说明有时间部分），只保留日期部分
        if (dateStr.contains(" ")) {
            return dateStr.substring(0, dateStr.indexOf(" "));
        }
        
        return dateStr;
    }

    /**
     * 判断是否为系统表
     * @param tableName 表名
     * @return 是否为系统表
     */
    private boolean isSystemTable(String tableName) {
        return SYSTEM_TABLE_PATTERN.matcher(tableName).matches();
    }

    /**
     * 判断是否为系统 schema
     * @param schemaName schema 名称
     * @return 是否为系统 schema
     */
    private boolean isSystemSchema(String schemaName) {
        return schemaName != null && SYSTEM_SCHEMAS.contains(schemaName.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public List<String> getAllTableNames() throws SQLException {
        List<String> tableNames = new ArrayList<>();
        DatabaseMetaData metaData = getConnection().getMetaData();
        String[] schemas = getConfig().getSchemas();
        
        try (ResultSet tables = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                String tableName = tables.getString("TABLE_NAME");
                
                // 检查是否是系统 schema 或系统表
                if (!isSystemSchema(schema) && !isSystemTable(tableName)) {
                    for (String configSchema : schemas) {
                        if (configSchema.equalsIgnoreCase(schema)) {
                            tableNames.add(schema + "." + tableName);
                            logger.debug("发现表: {}.{}", schema, tableName);
                            break;
                        }
                    }
                }
            }
        }
        return tableNames;
    }
} 