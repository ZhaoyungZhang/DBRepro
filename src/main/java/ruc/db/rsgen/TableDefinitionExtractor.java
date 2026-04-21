package ruc.db.rsgen;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从数据库中提取表的真实定义（列、分区、索引）。
 * 兼容 PostgreSQL / KingbaseES 等基于 PG 目录的实现。
 */
public class TableDefinitionExtractor {
    private static final Logger logger = LoggerFactory.getLogger(TableDefinitionExtractor.class);
    
    private final Connection connection;
    
    public TableDefinitionExtractor(Connection connection) {
        this.connection = connection;
    }

    /** 分区子表：{@code CREATE TABLE ... PARTITION OF parent} + bound 子句。 */
    public static class PartitionChildDefinition {
        private final String qualifiedName;
        /** 形如 {@code FOR VALUES FROM (...) TO (...)} */
        private final String partitionBoundClause;

        public PartitionChildDefinition(String qualifiedName, String partitionBoundClause) {
            this.qualifiedName = qualifiedName;
            this.partitionBoundClause = partitionBoundClause;
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public String getPartitionBoundClause() {
            return partitionBoundClause;
        }
    }
    
    /**
     * 表定义信息
     */
    public static class TableDefinition {
        private String tableName;
        private String schemaName;
        private List<ColumnDefinition> columns;
        private List<IndexDefinition> indexes;
        /** {@code p} = 分区父表 */
        private char relKind = 'r';
        /** 目录返回的 {@code PARTITION BY RANGE (...)} 整段；非分区表为 null */
        private String partitionByClause;
        private final List<PartitionChildDefinition> partitionChildren = new ArrayList<>();
        
        public TableDefinition(String schemaName, String tableName) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.columns = new ArrayList<>();
            this.indexes = new ArrayList<>();
        }
        
        // Getters and setters
        public String getTableName() { return tableName; }
        public String getSchemaName() { return schemaName; }
        public List<ColumnDefinition> getColumns() { return columns; }
        public List<IndexDefinition> getIndexes() { return indexes; }

        public char getRelKind() {
            return relKind;
        }

        public void setRelKind(char relKind) {
            this.relKind = relKind;
        }

        public boolean isPartitionedParent() {
            return relKind == 'p';
        }

        public String getPartitionByClause() {
            return partitionByClause;
        }

        public void setPartitionByClause(String partitionByClause) {
            this.partitionByClause = partitionByClause;
        }

        public List<PartitionChildDefinition> getPartitionChildren() {
            return partitionChildren;
        }
        
        public void addColumn(ColumnDefinition column) {
            this.columns.add(column);
        }
        
        public void addIndex(IndexDefinition index) {
            this.indexes.add(index);
        }
    }
    
    /**
     * 列定义信息
     */
    public static class ColumnDefinition {
        private String columnName;
        private String dataType;
        private boolean isNullable;
        private String defaultValue;
        
        public ColumnDefinition(String columnName, String dataType, boolean isNullable, String defaultValue) {
            this.columnName = columnName;
            this.dataType = dataType;
            this.isNullable = isNullable;
            this.defaultValue = defaultValue;
        }
        
        // Getters
        public String getColumnName() { return columnName; }
        public String getDataType() { return dataType; }
        public boolean isNullable() { return isNullable; }
        public String getDefaultValue() { return defaultValue; }
    }
    
    /**
     * 索引定义信息
     */
    public static class IndexDefinition {
        private String indexName;
        private String indexType;
        private List<String> columns;
        private boolean isUnique;
        /** 索引所在 schema（物理索引 rel 的 namespace） */
        private String indexSchemaName;
        /** {@link #pgCreateSql} 非空时优先按原文生成，保留 DESC / fillfactor / 部分索引等 */
        private String pgCreateSql;
        private boolean primaryKey;

        public IndexDefinition(String indexName, String indexType, List<String> columns, boolean isUnique) {
            this.indexName = indexName;
            this.indexType = indexType;
            this.columns = columns;
            this.isUnique = isUnique;
        }

        public static IndexDefinition fromPgCatalog(
                String indexSchemaName,
                String indexRelName,
                String pgCreateSql,
                boolean isUnique,
                boolean primaryKey,
                List<String> columns) {
            IndexDefinition d = new IndexDefinition(indexRelName, isUnique ? "UNIQUE" : "INDEX", columns, isUnique);
            d.indexSchemaName = indexSchemaName;
            d.pgCreateSql = pgCreateSql;
            d.primaryKey = primaryKey;
            return d;
        }
        
        // Getters
        public String getIndexName() { return indexName; }
        public String getIndexType() { return indexType; }
        public List<String> getColumns() { return columns; }
        public boolean isUnique() { return isUnique; }

        public String getIndexSchemaName() {
            return indexSchemaName;
        }

        public String getPgCreateSql() {
            return pgCreateSql;
        }

        public boolean isPrimaryKey() {
            return primaryKey;
        }

        public String qualifiedIndexNameForDrop() {
            if (indexSchemaName != null && !indexSchemaName.isEmpty()) {
                return quoteIdent(indexSchemaName) + "." + quoteIdent(indexName);
            }
            return quoteIdent(indexName);
        }

        private static String quoteIdent(String ident) {
            if (ident == null) {
                return "";
            }
            return "\"" + ident.replace("\"", "\"\"") + "\"";
        }
    }
    
    /**
     * 从schema.json中提取所有表名
     */
    public Set<String> extractTableNamesFromSchema(Map<String, Object> schemaData) {
        Set<String> tableNames = new HashSet<>();
        
        for (String tableKey : schemaData.keySet()) {
            tableNames.add(tableKey);
        }
        
        return tableNames;
    }
    
    /**
     * 获取表的完整定义
     */
    public TableDefinition getTableDefinition(String schemaName, String tableName) throws SQLException {
        logger.info("获取表定义: {}.{}", schemaName, tableName);
        
        TableDefinition tableDef = new TableDefinition(schemaName, tableName);
        
        loadRelKind(tableDef);
        // 获取列定义
        getColumnDefinitions(tableDef);
        // 分区父表：分区键与子分区
        if (tableDef.isPartitionedParent()) {
            loadPartitionMetadata(tableDef);
        }
        // 获取索引定义（含 pg_get_indexdef 原文）
        getIndexDefinitions(tableDef);
        
        return tableDef;
    }

    private void loadRelKind(TableDefinition tableDef) throws SQLException {
        String sql = """
            SELECT c.relkind
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableDef.getSchemaName());
            stmt.setString(2, tableDef.getTableName());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String k = rs.getString("relkind");
                    if (k != null && !k.isEmpty()) {
                        tableDef.setRelKind(k.charAt(0));
                    }
                }
            }
        }
    }

    /**
     * 读取 {@code PARTITION BY ...} 及子分区 {@code FOR VALUES ...}（声明式分区）。
     */
    private void loadPartitionMetadata(TableDefinition tableDef) throws SQLException {
        Long parentOid = lookupTableOid(tableDef.getSchemaName(), tableDef.getTableName());
        if (parentOid == null) {
            return;
        }
        String partKey = null;
        try (PreparedStatement stmt = connection.prepareStatement("SELECT pg_get_partkeydef(?)")) {
            stmt.setLong(1, parentOid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    partKey = rs.getString(1);
                }
            }
        } catch (SQLException e) {
            logger.warn("pg_get_partkeydef 不可用，分区表将按普通表 DDL 降级: {}", e.getMessage());
        }
        if (partKey != null && !partKey.isBlank()) {
            tableDef.setPartitionByClause(partKey.trim());
        }
        String childSql = """
            SELECT quote_ident(sn.nspname) || '.' || quote_ident(ch.relname) AS fqname,
                   COALESCE(pg_get_partbounddef(ch.oid), pg_get_expr(ch.relpartbound, ch.oid)) AS bound
            FROM pg_inherits inh
            JOIN pg_class ch ON ch.oid = inh.inhrelid
            JOIN pg_namespace sn ON sn.oid = ch.relnamespace
            WHERE inh.inhparent = ?
            ORDER BY fqname
            """;
        try (PreparedStatement stmt = connection.prepareStatement(childSql)) {
            stmt.setLong(1, parentOid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String fq = rs.getString("fqname");
                    String bound = rs.getString("bound");
                    if (fq == null || bound == null || bound.isBlank()) {
                        continue;
                    }
                    tableDef.getPartitionChildren().add(new PartitionChildDefinition(fq, bound.trim()));
                }
            }
        } catch (SQLException e) {
            logger.warn("读取子分区失败（可改为仅建父表）: {}", e.getMessage());
        }
    }

    private Long lookupTableOid(String schemaName, String tableName) throws SQLException {
        String sql = """
            SELECT c.oid
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? AND c.relname = ?
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("oid");
                }
            }
        }
        return null;
    }
    
    /**
     * 获取列定义
     */
    private void getColumnDefinitions(TableDefinition tableDef) throws SQLException {
        String sql = """
            SELECT 
                column_name,
                data_type,
                CASE 
                    WHEN character_maximum_length IS NOT NULL 
                    THEN data_type || '(' || character_maximum_length || ')'
                    WHEN numeric_precision IS NOT NULL AND numeric_scale IS NOT NULL 
                    THEN data_type || '(' || numeric_precision || ',' || numeric_scale || ')'
                    WHEN numeric_precision IS NOT NULL 
                    THEN data_type || '(' || numeric_precision || ')'
                    ELSE data_type
                END as full_data_type,
                is_nullable,
                column_default
            FROM information_schema.columns 
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """;
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableDef.getSchemaName());
            stmt.setString(2, tableDef.getTableName());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString("column_name");
                    String dataType = rs.getString("full_data_type");
                    boolean isNullable = "YES".equals(rs.getString("is_nullable"));
                    String defaultValue = rs.getString("column_default");
                    
                    ColumnDefinition column = new ColumnDefinition(columnName, dataType, isNullable, defaultValue);
                    tableDef.addColumn(column);
                    
                    logger.debug("列: {} {} {}", columnName, dataType, isNullable ? "NULL" : "NOT NULL");
                }
            }
        }
    }
    
    /**
     * 使用系统目录 + {@code pg_get_indexdef} 获取索引（含主键、DESC、WITH、部分索引等）。
     */
    private void getIndexDefinitions(TableDefinition tableDef) throws SQLException {
        Long tableOid = lookupTableOid(tableDef.getSchemaName(), tableDef.getTableName());
        if (tableOid == null) {
            return;
        }
        String sql = """
            SELECT inm.nspname AS idx_schema,
                   ic.relname AS idx_name,
                   pg_get_indexdef(i.indexrelid) AS idx_def,
                   i.indisunique,
                   i.indisprimary
            FROM pg_index i
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_namespace inm ON inm.oid = ic.relnamespace
            WHERE i.indrelid = ?
            ORDER BY i.indisprimary DESC, ic.relname
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, tableOid);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String idxSchema = rs.getString("idx_schema");
                    String idxName = rs.getString("idx_name");
                    String idxDef = rs.getString("idx_def");
                    boolean isUnique = rs.getBoolean("indisunique");
                    boolean isPk = rs.getBoolean("indisprimary");
                    if (idxDef == null || idxDef.isBlank()) {
                        continue;
                    }
                    List<String> cols = parseIndexColumnsFromDef(idxDef);
                    tableDef.addIndex(IndexDefinition.fromPgCatalog(
                            idxSchema, idxName, idxDef.trim(), isUnique, isPk, cols));
                    logger.debug("索引: {} {}", idxName, idxDef);
                }
            }
        } catch (SQLException e) {
            logger.warn("pg_get_indexdef 路径失败，回退 pg_indexes: {}", e.getMessage());
            getIndexDefinitionsFromPgIndexesView(tableDef);
        }
    }

    private void getIndexDefinitionsFromPgIndexesView(TableDefinition tableDef) throws SQLException {
        String sql = """
            SELECT i.indexname, i.indexdef,
                   CASE WHEN i.indexdef ILIKE '%UNIQUE%' THEN true ELSE false END AS is_unique
            FROM pg_indexes i
            WHERE i.schemaname = ? AND i.tablename = ?
            ORDER BY i.indexname
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, tableDef.getSchemaName());
            stmt.setString(2, tableDef.getTableName());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("indexname");
                    String indexDef = rs.getString("indexdef");
                    boolean isUnique = rs.getBoolean("is_unique");
                    if (indexDef == null || indexDef.isBlank()) {
                        continue;
                    }
                    boolean isPk = indexName.toLowerCase(Locale.ROOT).endsWith("_pkey")
                            || (indexName.toLowerCase(Locale.ROOT).startsWith("pk_") && isUnique);
                    List<String> cols = parseIndexColumnsFromDef(indexDef);
                    tableDef.addIndex(IndexDefinition.fromPgCatalog(
                            tableDef.getSchemaName(),
                            indexName,
                            indexDef.trim(),
                            isUnique,
                            isPk,
                            cols));
                }
            }
        }
    }

    private static List<String> parseIndexColumnsFromDef(String idxDef) {
        Matcher matcher = Pattern.compile(
                        "ON\\s+.+?\\s+USING\\s+\\w+\\s*\\(([^)]+)\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(idxDef);
        if (!matcher.find()) {
            matcher = Pattern.compile("ON\\s+.+?\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(idxDef);
            if (!matcher.find()) {
                return List.of();
            }
        }
        String columnsStr = matcher.group(1);
        String[] parts = columnsStr.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim().replaceAll("^\"|\"$", "").replaceAll("\\s+(ASC|DESC)\\s*$", "").trim();
            int sp = t.lastIndexOf('.');
            if (sp >= 0) {
                t = t.substring(sp + 1);
            }
            if (!t.isEmpty()) {
                out.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
    
    /**
     * 获取所有表的定义
     */
    public Map<String, TableDefinition> getAllTableDefinitions(Map<String, Object> schemaData) throws SQLException {
        Map<String, TableDefinition> tableDefinitions = new HashMap<>();
        
        Set<String> tableNames = extractTableNamesFromSchema(schemaData);
        
        for (String tableKey : tableNames) {
            String[] parts = tableKey.split("\\.");
            if (parts.length == 2) {
                String schemaName = parts[0];
                String tableName = parts[1];
                
                try {
                    TableDefinition tableDef = getTableDefinition(schemaName, tableName);
                    tableDefinitions.put(tableKey, tableDef);
                } catch (SQLException e) {
                    logger.warn("无法获取表 {}.{} 的定义: {}", schemaName, tableName, e.getMessage());
                }
            }
        }
        
        return tableDefinitions;
    }
} 