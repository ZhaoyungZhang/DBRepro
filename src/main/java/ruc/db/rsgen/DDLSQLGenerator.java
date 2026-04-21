package ruc.db.rsgen;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ruc.db.schema.TableManager;
import ruc.db.utils.DataExportConstants;

/**
 * SQL和DDL文件生成器
 * 负责生成数据库创建脚本、索引脚本和数据导入脚本
 * 
 * @author RSGen Implementation
 */
public class DDLSQLGenerator {
    private static final Logger logger = LoggerFactory.getLogger(DDLSQLGenerator.class);

    /**
     * {@code rsgen}：与 {@link ruc.db.rsgen.DataFileWriter} 一致，按 schema 中 canonical 顺序，字段分隔符 {@code |}。<br>
     * {@code mirage_generate}：与 {@link ruc.db.generator.DataGenerator} 写出一致——主键列（schema.json 中 primaryKeys 顺序）+
     * 外键列（与 {@link java.util.TreeMap} 键序一致的字典序）+ 非键属性列（canonical 顺序去掉 PK/FK），字段分隔符 {@code |}。<br>
     * 此布局下 {@code CREATE TABLE} 的列顺序与上述一致，便于无显式列清单的导入或与导出文件逐列对照。<br>
     * 启动 JVM 时传入：{@code -Dmirage.ddl.importData.layout=mirage_generate}
     */
    public static final String IMPORT_DATA_LAYOUT_PROPERTY = "mirage.ddl.importData.layout";

    /**
     * 获取完整的表名（包含schema）
     * 
     * @param tableName 原始表名（格式：schema.table）
     * @return 完整的表名（schema.table），如果没有schema则返回表名
     */
    private String getFullTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return tableName;
        }
        
        // 如果已经包含schema，直接返回小写形式
        if (tableName.contains(".")) {
            return tableName.toLowerCase();
        }
        
        // 如果不包含schema，返回原始表名的小写形式
        return tableName.toLowerCase();
    }
    
    /**
     * 获取简单表名（不包含schema）
     * 
     * @param tableName 原始表名（格式：schema.table）
     * @return 简单表名（table）
     */
    private String getSimpleTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return tableName;
        }
        
        if (tableName.contains(".")) {
            return tableName.split("\\.")[1].toLowerCase();
        }
        
        return tableName.toLowerCase();
    }

    /**
     * 提取表名中的schema名称
     * 
     * @param tableName 表名（格式：schema.table）
     * @return schema名称，如果没有则返回null
     */
    private String extractSchemaName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return null;
        }
        
        if (tableName.contains(".")) {
            return tableName.split("\\.")[0];
        }
        
        return null;
    }
    
    /**
     * 获取所有需要创建的schema名称
     * 
     * @param enhancedStats 增强统计信息
     * @return 需要创建的schema名称集合
     */
    private Set<String> extractRequiredSchemas(Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats) {
        Set<String> schemas = new HashSet<>();
        
        for (String tableName : enhancedStats.keySet()) {
            String schemaName = extractSchemaName(tableName);
            if (schemaName != null && !schemaName.equalsIgnoreCase("public")) {
                // 只添加非public的schema，因为public是默认存在的
                schemas.add(schemaName.toLowerCase());
            }
        }
        
        return schemas;
    }

    /**
     * 生成所有DDL文件
     * 
     * @param inputDir 输入目录（包含schema.json和enhanced_column_statistics.json）
     * @param outputDir 输出目录
     * @param connection 数据库连接（用于获取真实表定义）
     * @throws IOException 文件操作异常
     */
    public void generateAllDDLFiles(String inputDir, String outputDir, Connection connection) throws IOException {
        generateAllDDLFiles(inputDir, outputDir, connection, null);
    }

    /**
     * 生成所有DDL文件（使用优化的拓扑排序）
     * 
     * @param inputDir 输入目录（包含schema.json和enhanced_column_statistics.json）
     * @param outputDir 输出目录
     * @param optimizedTopologicalOrder 优化的拓扑排序（来自数据生成阶段）
     * @throws IOException 文件操作异常
     */
    public void generateAllDDLFiles(String inputDir, String outputDir, List<String> optimizedTopologicalOrder) throws IOException {
        generateAllDDLFiles(inputDir, outputDir, null, optimizedTopologicalOrder);
    }

    /**
     * 生成所有DDL文件（内部实现）
     * 
     * @param inputDir 输入目录（包含schema.json和enhanced_column_statistics.json）
     * @param outputDir 输出目录
     * @param connection 数据库连接（用于获取真实表定义）
     * @param optimizedTopologicalOrder 优化的拓扑排序（可为null）
     * @throws IOException 文件操作异常
     */
    private void generateAllDDLFiles(String inputDir, String outputDir, Connection connection, List<String> optimizedTopologicalOrder) throws IOException {
        // 创建create_sql目录
        File createSqlDir = new File(outputDir, "create_sql");
        if (!createSqlDir.exists()) {
            createSqlDir.mkdirs();
        }
        
        // 加载schema.json数据
        ObjectMapper objectMapper = new ObjectMapper();
        File schemaFile = new File(inputDir, "schema.json");
        Map<String, Object> schemaData = null;
        if (schemaFile.exists()) {
            try {
            schemaData = objectMapper.readValue(schemaFile,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                logger.info("成功加载schema.json文件");
            } catch (Exception e) {
                logger.warn("加载schema.json文件失败：{}，将使用默认配置", e.getMessage());
                schemaData = new HashMap<>();
            }
        } else {
            logger.warn("schema.json文件不存在，将使用默认配置");
            schemaData = new HashMap<>();
        }
        
        // 获取真实的表定义
        Map<String, TableDefinitionExtractor.TableDefinition> tableDefinitions = new HashMap<>();
        if (connection != null) {
            try {
                TableDefinitionExtractor extractor = new TableDefinitionExtractor(connection);
                tableDefinitions = extractor.getAllTableDefinitions(schemaData);
                logger.info("成功获取{}个表的真实定义", tableDefinitions.size());
            } catch (Exception e) {
                logger.warn("获取表定义失败：{}，将使用默认配置", e.getMessage());
            }
        } else {
            logger.info("未提供数据库连接，将使用推测的表定义");
            // 如果没有数据库连接，使用原来的推测方法
            tableDefinitions = createTableDefinitionsFromStats(inputDir, schemaData);
        }
        
        // 生成CreateSchema.sql
        generateCreateSchemaSQL(createSqlDir, tableDefinitions, schemaData, outputDir, optimizedTopologicalOrder);
        
        // 加载增强统计信息
        File statsFile = new File(inputDir, "enhanced_column_statistics.json");
        Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats = new HashMap<>();
        if (statsFile.exists()) {
            try {
                enhancedStats = objectMapper.readValue(statsFile, 
                    objectMapper.getTypeFactory().constructMapType(
                        Map.class, String.class, EnhancedStatsExtractor.EnhancedTableStatistics.class));
                logger.info("成功加载增强统计信息，包含{}个表", enhancedStats.size());
            } catch (Exception e) {
                logger.warn("加载增强统计信息失败：{}", e.getMessage());
            }
        } else {
            logger.warn("enhanced_column_statistics.json文件不存在");
        }
        
        // 生成CreateIndex.sql  
        generateCreateIndexSQL(createSqlDir, tableDefinitions, schemaData, outputDir);
        
        // 生成importData.sql
        generateImportDataSQL(createSqlDir, enhancedStats, schemaData, optimizedTopologicalOrder);
        
        logger.info("成功生成所有DDL和SQL文件到: {}", createSqlDir.getAbsolutePath());
    }
    
    /**
     * 生成CreateSchema.sql文件
     */
    public void generateCreateSchemaSQL(File createSqlDir, 
            Map<String, TableDefinitionExtractor.TableDefinition> tableDefinitions,
            Map<String, Object> schemaData, String outputDir) throws IOException {
        generateCreateSchemaSQL(createSqlDir, tableDefinitions, schemaData, outputDir, null);
    }
    
    /**
     * 生成CreateSchema.sql文件
     */
    public void generateCreateSchemaSQL(File createSqlDir, 
            Map<String, TableDefinitionExtractor.TableDefinition> tableDefinitions,
            Map<String, Object> schemaData, String outputDir, List<String> optimizedTopologicalOrder) throws IOException {
        File createSchemaFile = new File(createSqlDir, "CreateSchema.sql");
        
        // 获取拓扑排序顺序
        List<String> generationOrder = optimizedTopologicalOrder != null ? optimizedTopologicalOrder : getTopologicalOrder(schemaData);
        if (generationOrder.isEmpty()) {
            generationOrder = new ArrayList<>(tableDefinitions.keySet());
        }
        
        // 过滤出有定义的表（避免处理中间分区表）
        List<String> tablesWithDefinitions = new ArrayList<>();
        for (String tableName : generationOrder) {
            if (tableDefinitions.containsKey(tableName)) {
                tablesWithDefinitions.add(tableName);
            } else {
                // 检查表类型并记录详细的跳过原因
                String skipReason = determineSkipReason(tableName, tableDefinitions);
                logger.info("跳过没有定义的表: {} - 原因: {}", tableName, skipReason);
            }
        }
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(createSchemaFile))) {
            // 从输出目录路径提取数据库名（最后一节）
            String databaseName = new File(outputDir).getName().toLowerCase();
            if (databaseName.isEmpty()) {
                databaseName = "mirage"; // 默认数据库名
            }
            
            writer.write("-- 创建数据库模式\n");
            writer.write(String.format("CREATE DATABASE %s;\n", databaseName));
            writer.write(String.format("\\c %s;\n\n", databaseName));
            
            // 提取所有需要创建的schema
            Set<String> requiredSchemas = extractRequiredSchemasFromTableDefinitions(tableDefinitions);
            
            // 创建schema语句
            if (!requiredSchemas.isEmpty()) {
                writer.write("-- 创建schema\n");
                for (String schemaName : requiredSchemas) {
                    writer.write(String.format("CREATE SCHEMA IF NOT EXISTS %s;\n", schemaName));
                }
                writer.write("\n");
            }
            
            // 按拓扑排序顺序创建表
            for (String tableName : tablesWithDefinitions) {
                TableDefinitionExtractor.TableDefinition tableDef = tableDefinitions.get(tableName);
                
                // 生成CREATE TABLE语句（列顺序对齐 schema.json canonicalColumnNames；支持分区父表+子分区）
                writer.write(generateCreateTableSQL(tableDef, tableName, schemaData));
                writer.newLine();
                }
        }
        
        logger.info("生成创建表SQL文件: {}", createSchemaFile.getAbsolutePath());
    }
    
    /**
     * 生成CreateIndex.sql文件（带schemaData参数）
     */
    public void generateCreateIndexSQL(File createSqlDir, 
            Map<String, TableDefinitionExtractor.TableDefinition> tableDefinitions,
            Map<String, Object> schemaData, String outputDir) throws IOException {
        File createIndexFile = new File(createSqlDir, "CreateIndex.sql");
        
        // 从输出目录路径提取数据库名（最后一节）
        String databaseName = new File(outputDir).getName().toLowerCase();
        if (databaseName.isEmpty()) {
            databaseName = "mirage"; // 默认数据库名
        }
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(createIndexFile))) {
            writer.write(String.format("\\c %s;\n\n", databaseName));
            
            // 主键：若已从源库 pg_get_indexdef 带出主键索引，则不再用 schema.json 重复 ADD PRIMARY KEY
            for (Map.Entry<String, TableDefinitionExtractor.TableDefinition> entry : tableDefinitions.entrySet()) {
                String tableName = entry.getKey();
                TableDefinitionExtractor.TableDefinition tableDef = entry.getValue();
                
                String fullTableName = getFullTableName(tableName);
                
                boolean dbHasPk = tableDef.getIndexes().stream()
                        .anyMatch(TableDefinitionExtractor.IndexDefinition::isPrimaryKey);
                @SuppressWarnings("unchecked")
                Map<String, Object> tableInfo = schemaData != null ? (Map<String, Object>) schemaData.get(tableName) : null;
                if (!dbHasPk && tableInfo != null) {
                    @SuppressWarnings("unchecked")
                    List<String> primaryKeys = (List<String>) tableInfo.get("primaryKeys");
                    if (primaryKeys != null && !primaryKeys.isEmpty()) {
                        List<String> pkColumns = new ArrayList<>();
                        for (String pkCol : primaryKeys) {
                            String columnName = pkCol.substring(pkCol.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                            pkColumns.add("\"" + columnName + "\"");
                        }
                        writer.write(String.format("ALTER TABLE %s ADD PRIMARY KEY (%s);\n", 
                                fullTableName, String.join(", ", pkColumns)));
                    }
                }
            }
            
            writer.write("\n");
            
            // 然后添加外键约束
            for (Map.Entry<String, Object> entry : schemaData.entrySet()) {
                String tableName = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> tableInfo = (Map<String, Object>) entry.getValue();
                
                @SuppressWarnings("unchecked")
                Map<String, String> foreignKeys = (Map<String, String>) tableInfo.get("foreignKeys");
                if (foreignKeys != null && !foreignKeys.isEmpty()) {
                    String fullTableName = getFullTableName(tableName);
                    
                    for (Map.Entry<String, String> fkEntry : foreignKeys.entrySet()) {
                        String fkColumn = fkEntry.getKey();
                        String refColumn = fkEntry.getValue();
                        
                        String fkColName = fkColumn.substring(fkColumn.lastIndexOf('.') + 1).toLowerCase();
                        String refTableName = refColumn.substring(0, refColumn.lastIndexOf('.'));
                        String refColName = refColumn.substring(refColumn.lastIndexOf('.') + 1).toLowerCase();
                        
                        writer.write(String.format("ALTER TABLE %s ADD CONSTRAINT fk_%s_%s " +
                                "FOREIGN KEY (\"%s\") REFERENCES %s (\"%s\");\n",
                                fullTableName, tableName.replace('.', '_'), fkColName,
                                fkColName, refTableName, refColName));
                    }
                }
            }
            
            writer.write("\n");
            
            // 索引：优先使用源库 pg_get_indexdef 原文（含 DESC、WITH(fillfactor)、部分索引等）
            for (Map.Entry<String, TableDefinitionExtractor.TableDefinition> entry : tableDefinitions.entrySet()) {
                String tableName = entry.getKey();
                TableDefinitionExtractor.TableDefinition tableDef = entry.getValue();
                
                String fullTableName = getFullTableName(tableName);
                
                for (TableDefinitionExtractor.IndexDefinition index : tableDef.getIndexes()) {
                    if (index.getPgCreateSql() != null && !index.getPgCreateSql().isBlank()) {
                        writer.write("DROP INDEX IF EXISTS " + index.qualifiedIndexNameForDrop() + ";\n");
                        String stmt = index.getPgCreateSql().trim();
                        if (!stmt.endsWith(";")) {
                            stmt = stmt + ";";
                        }
                        writer.write(stmt + "\n");
                    } else if (!index.isUnique()) {
                        List<String> indexColumns = index.getColumns().stream()
                            .map(col -> "\"" + col + "\"")
                            .toList();
                        writer.write(String.format("CREATE INDEX \"%s\" ON %s (%s);\n",
                                index.getIndexName().replace("\"", "\"\""), fullTableName, String.join(", ", indexColumns)));
                    } else {
                        List<String> indexColumns = index.getColumns().stream()
                                .map(col -> "\"" + col + "\"")
                                .toList();
                        writer.write(String.format("CREATE UNIQUE INDEX \"%s\" ON %s (%s);\n",
                                index.getIndexName().replace("\"", "\"\""), fullTableName, String.join(", ", indexColumns)));
                    }
                }
            }
        }
        
        logger.info("生成创建索引SQL文件: {}", createIndexFile.getAbsolutePath());
    }
    
    /**
     * 生成importData.sql文件
     * 
     * @param createSqlDir 输出目录
     * @param enhancedStats 增强统计信息
     * @param schemaData schema数据
     * @throws IOException 文件操作异常
     */
    public void generateImportDataSQL(File createSqlDir, 
            Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats,
            Map<String, Object> schemaData) throws IOException {
        generateImportDataSQL(createSqlDir, enhancedStats, schemaData, null);
    }

    /**
     * 生成importData.sql文件（使用优化的拓扑排序）
     * 
     * @param createSqlDir 输出目录
     * @param enhancedStats 增强统计信息
     * @param schemaData schema数据
     * @param optimizedTopologicalOrder 优化的拓扑排序
     * @throws IOException 文件操作异常
     */
    public void generateImportDataSQL(File createSqlDir, 
            Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats,
            Map<String, Object> schemaData, List<String> optimizedTopologicalOrder) throws IOException {
        File importDataFile = new File(createSqlDir, "importData.sql");
        
        // 加载分区关系信息
        try {
            PartitionTableManager.getInstance().loadFromFile(createSqlDir.getParentFile().getAbsolutePath());
        } catch (IOException e) {
            logger.info("未找到分区关系文件，将按普通表处理: {}", e.getMessage());
        }
        
        // 使用传入的schemaData进行拓扑排序
        List<String> generationOrder = optimizedTopologicalOrder != null ? optimizedTopologicalOrder : getTopologicalOrder(schemaData);
        if (generationOrder.isEmpty()) {
            generationOrder = new ArrayList<>(enhancedStats.keySet());
        }
        
        // 过滤出有统计信息的表（避免处理中间分区表）
        List<String> tablesWithStats = new ArrayList<>();
        for (String tableName : generationOrder) {
            if (enhancedStats.containsKey(tableName)) {
                tablesWithStats.add(tableName);
            } else {
                // 检查表类型并记录详细的跳过原因
                String skipReason = determineSkipReasonForStats(tableName, enhancedStats);
                logger.info("跳过没有统计信息的表: {} - 原因: {}", tableName, skipReason);
            }
        }
        
        // 添加不在优化排序中但有统计信息的表（主要是分区子表）
        for (String tableName : enhancedStats.keySet()) {
            if (!tablesWithStats.contains(tableName)) {
                tablesWithStats.add(tableName);
                logger.debug("添加不在优化排序中的表: {}", tableName);
            }
        }
        
        logger.info("Import SQL生成顺序: {}", generationOrder);
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(importDataFile))) {
            // 从输出目录路径提取数据库名（最后一节）
            String databaseName = new File(createSqlDir.getParentFile().getAbsolutePath()).getName().toLowerCase();
            if (databaseName.isEmpty()) {
                databaseName = "mirage"; // 默认数据库名
            }
            
            writer.write(String.format("\\c %s;\n\n", databaseName));
            String layout = System.getProperty(IMPORT_DATA_LAYOUT_PROPERTY, "rsgen").trim().toLowerCase(Locale.ROOT);
            if ("mirage_generate".equals(layout)) {
                writer.write("-- importData 列顺序/分隔符对齐 ruc.db.generator.DataGenerator（-D" + IMPORT_DATA_LAYOUT_PROPERTY + "=mirage_generate）\n");
                writer.write("-- 行格式: primaryKeys顺序 + foreignKeys键字典序 + 其余属性列(canonical顺序去PK/FK); DELIMITER '|'\n\n");
            } else {
                writer.write("-- 默认对齐 RSGen DataFileWriter：显式列时用 canonicalColumnNames 顺序；DELIMITER '|'\n\n");
            }
            
            // 按拓扑排序顺序导入数据
            for (String tableName : tablesWithStats) {
                EnhancedStatsExtractor.EnhancedTableStatistics tableStats = enhancedStats.get(tableName);
                
                String simpleTableName = getSimpleTableName(tableName);
                String absoluteDataPath = new File(createSqlDir.getParentFile(), "data/" + simpleTableName + ".tbl").getAbsolutePath();
                
                // 检查是否为子表
                if (PartitionTableManager.getInstance().isChildTable(tableName)) {
                    // 如果是子表，生成导入到父表的COPY命令
                    String parentTable = PartitionTableManager.getInstance().getParentTable(tableName);
                    String fullParentTableName = getFullTableName(parentTable);
                    
                    String copyLine = formatCopyFromLine(
                            fullParentTableName, absoluteDataPath, tableName, schemaData);
                    writer.write(copyLine);
                    
                    logger.info("子表 {} 的数据将导入到父表 {}", tableName, parentTable);
                } else if (PartitionTableManager.getInstance().isPartitionTable(tableName)) {
                    // 如果是分区表父表，跳过COPY命令生成（因为父表没有数据文件）
                    Set<String> childTables = PartitionTableManager.getInstance().getChildTables(tableName);
                    logger.info("跳过分区表父表 {} 的COPY命令，数据通过子表导入: {}", tableName, childTables);
                } else {
                    // 普通表的处理
                    String fullTableName = getFullTableName(tableName);
                    String copyLine = formatCopyFromLine(
                            fullTableName, absoluteDataPath, tableName, schemaData);
                    writer.write(copyLine);
                }
            }
        }
        
        logger.info("生成导入数据SQL文件: {}", importDataFile.getAbsolutePath());
    }

    /**
     * 从统计信息推测表定义（当没有数据库连接时使用）
     */
    private Map<String, TableDefinitionExtractor.TableDefinition> createTableDefinitionsFromStats(String inputDir, Map<String, Object> schemaData) throws IOException {
        Map<String, TableDefinitionExtractor.TableDefinition> tableDefinitions = new HashMap<>();
        
        // 加载增强统计信息
        ObjectMapper objectMapper = new ObjectMapper();
        File statsFile = new File(inputDir, "enhanced_column_statistics.json");
        Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats = 
            objectMapper.readValue(statsFile, objectMapper.getTypeFactory().constructMapType(
                Map.class, String.class, EnhancedStatsExtractor.EnhancedTableStatistics.class));
        
        for (Map.Entry<String, EnhancedStatsExtractor.EnhancedTableStatistics> entry : enhancedStats.entrySet()) {
            String tableName = entry.getKey();
            EnhancedStatsExtractor.EnhancedTableStatistics tableStats = entry.getValue();
            
            String[] parts = tableName.split("\\.");
            if (parts.length == 2) {
                String schemaName = parts[0];
                String simpleTableName = parts[1];
                
                TableDefinitionExtractor.TableDefinition tableDef = new TableDefinitionExtractor.TableDefinition(schemaName, simpleTableName);
                
                // 从schema.json获取列顺序
                List<String> columnOrder = getColumnOrderFromSchema(tableName, schemaData);
                
                for (String canonicalColumnName : columnOrder) {
                    EnhancedStatsExtractor.EnhancedColumnStatistics colStats = tableStats.getColumns().get(canonicalColumnName);
                    if (colStats != null) {
                        String columnName = canonicalColumnName.split("\\.")[2].toLowerCase();
                        String dataType = mapDataTypeToSQL(colStats.getDataType());
                        boolean isNullable = colStats.getNullFraction() > 0;
                        
                        TableDefinitionExtractor.ColumnDefinition column = 
                            new TableDefinitionExtractor.ColumnDefinition(columnName, dataType, isNullable, null);
                        tableDef.addColumn(column);
                    }
                }
                
                tableDefinitions.put(tableName, tableDef);
            }
        }
        
        return tableDefinitions;
    }

    /**
     * 从schema数据获取拓扑排序顺序
     * 
     * @param schemaData schema数据
     * @return 拓扑排序后的表名列表
     */
    private List<String> getTopologicalOrder(Map<String, Object> schemaData) {
        if (schemaData == null) {
            return new ArrayList<>();
        }
        
        // 构建依赖关系图
        Map<String, Set<String>> dependencyGraph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Map<String, Integer>> tableDependencyCount = new HashMap<>(); // 记录表A依赖表B的次数
        
        // 初始化
        for (String tableName : schemaData.keySet()) {
            dependencyGraph.put(tableName, new HashSet<>());
            inDegree.put(tableName, 0);
            tableDependencyCount.put(tableName, new HashMap<>());
        }
        
        // 构建依赖关系
        for (String tableName : schemaData.keySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tableInfo = (Map<String, Object>) schemaData.get(tableName);
            @SuppressWarnings("unchecked")
            Map<String, String> foreignKeys = (Map<String, String>) tableInfo.get("foreignKeys");
            
            if (foreignKeys != null) {
                for (String referencedColumn : foreignKeys.values()) {
                    String referencedTable = referencedColumn.substring(0, referencedColumn.lastIndexOf('.'));
                    dependencyGraph.get(tableName).add(referencedTable);
                    
                    // 记录表级依赖次数
                    Map<String, Integer> depCount = tableDependencyCount.get(tableName);
                    depCount.put(referencedTable, depCount.getOrDefault(referencedTable, 0) + 1);
                    
                    inDegree.put(tableName, inDegree.get(tableName) + 1);
                }
            }
        }
        
        // logger.debug("依赖关系图构建完成，入度信息: {}", inDegree);
        // logger.debug("表依赖次数: {}", tableDependencyCount);
        
        // Kahn算法进行拓扑排序
        Queue<String> queue = new LinkedList<>();
        List<String> result = new ArrayList<>();
        
        // 找到所有入度为0的表
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
                // logger.debug("入度为0的表: {}", entry.getKey());
            }
        }
        
        // 拓扑排序
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);
            // logger.debug("处理表: {}", current);
            
            // 减少依赖于当前表的其他表的入度
            for (String tableName : schemaData.keySet()) {
                if (dependencyGraph.get(tableName).contains(current)) {
                    // 获取该表对当前表的依赖次数
                    int dependencyCount = tableDependencyCount.get(tableName).get(current);
                    int newInDegree = inDegree.get(tableName) - dependencyCount;
                    inDegree.put(tableName, newInDegree);
                    // logger.debug("表 {} 的入度减少 {}，现在为: {}", tableName, dependencyCount, newInDegree);
                    
                    if (newInDegree == 0) {
                        queue.offer(tableName);
                        // logger.debug("表 {} 入度变为0，加入队列", tableName);
                    }
                }
            }
        }
        
        // logger.debug("拓扑排序完成，顺序: {}", result);
        if (result.size() != schemaData.size()) {
            logger.warn("拓扑排序未包含所有表！期望: {}, 实际: {}", schemaData.keySet(), result);
        }
        
        return result;
    }
    
    /**
     * 从schema.json获取列顺序
     * 
     * @param tableName 表名
     * @param schemaData schema数据
     * @return 列名列表
     */
    private List<String> getColumnOrderFromSchema(String tableName, Map<String, Object> schemaData) {
        if (schemaData == null || !schemaData.containsKey(tableName)) {
            return new ArrayList<>();
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> tableInfo = (Map<String, Object>) schemaData.get(tableName);
        @SuppressWarnings("unchecked")
        List<String> canonicalColumnNames = (List<String>) tableInfo.get("canonicalColumnNames");
        
        return canonicalColumnNames != null ? canonicalColumnNames : new ArrayList<>();
    }

    /**
     * 映射数据类型到SQL标准类型
     * 
     * @param originalType 原始数据类型
     * @return SQL标准类型
     */
    private String mapDataTypeToSQL(String originalType) {
        if (originalType == null) return "TEXT";
        
        String type = originalType.toLowerCase();
        if (type.contains("int") || type.contains("serial")) {
            return "INTEGER";
        } else if (type.contains("varchar") || type.contains("char")) {
            return "VARCHAR(255)";
        } else if (type.contains("text")) {
            return "TEXT";
        } else if (type.contains("numeric") || type.contains("decimal")) {
            return "DECIMAL(10,2)";
        } else if (type.contains("date") && !type.contains("time")) {
            return "DATE";
        } else if (type.contains("timestamp") || type.contains("datetime")) {
            return "TIMESTAMP";
        } else if (type.contains("bool")) {
            return "BOOLEAN";
        } else {
            return "TEXT";
        }
    }

    /**
     * 从表定义中提取需要的schema
     */
    private Set<String> extractRequiredSchemasFromTableDefinitions(
            Map<String, TableDefinitionExtractor.TableDefinition> tableDefinitions) {
        Set<String> schemas = new HashSet<>();
        
        for (TableDefinitionExtractor.TableDefinition tableDef : tableDefinitions.values()) {
            String schemaName = tableDef.getSchemaName();
            if (schemaName != null && !schemaName.equalsIgnoreCase("public")) {
                schemas.add(schemaName);
            }
        }
        
        return schemas;
    }

    /**
     * 生成 CREATE TABLE。默认列顺序与 schema.json {@code canonicalColumnNames} 一致（RSGen {@code |} 导出）；<br>
     * {@code -Dmirage.ddl.importData.layout=mirage_generate} 时与 {@link ruc.db.generator.DataGenerator} 一致：PK + FK + 非键属性。
     */
    private String generateCreateTableSQL(
            TableDefinitionExtractor.TableDefinition tableDef,
            String tableKey,
            Map<String, Object> schemaData) {
        String fullTableName = getFullTableName(tableKey);
        
        List<TableDefinitionExtractor.ColumnDefinition> ordered =
                getOrderedColumnsForDDL(tableDef, tableKey, schemaData);
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("DROP TABLE IF EXISTS %s CASCADE;\n", fullTableName));
        sb.append(String.format("CREATE TABLE %s (\n", fullTableName));
        
        List<String> columnDefinitions = new ArrayList<>();
        for (TableDefinitionExtractor.ColumnDefinition column : ordered) {
            String colName = column.getColumnName().toLowerCase(Locale.ROOT);
            String columnDef = String.format("    \"%s\" %s",
                    colName.replace("\"", "\"\""),
                    column.getDataType());
            
            if (!column.isNullable()) {
                columnDef += " NOT NULL";
            }
            
            if (column.getDefaultValue() != null) {
                columnDef += " DEFAULT " + column.getDefaultValue();
            }
            
            columnDefinitions.add(columnDef);
        }
        
        sb.append(String.join(",\n", columnDefinitions));
        if (tableDef.isPartitionedParent() && tableDef.getPartitionByClause() != null
                && !tableDef.getPartitionByClause().isBlank()) {
            sb.append("\n) ").append(tableDef.getPartitionByClause()).append(";\n");
            for (TableDefinitionExtractor.PartitionChildDefinition ch : tableDef.getPartitionChildren()) {
                sb.append("CREATE TABLE ").append(ch.getQualifiedName())
                        .append(" PARTITION OF ").append(fullTableName).append(" ")
                        .append(ch.getPartitionBoundClause()).append(";\n");
            }
        } else {
            sb.append("\n);\n");
        }
        
        return sb.toString();
    }

    /**
     * 与 {@link #buildCopyColumnsMirageDataGenerator} / {@link ruc.db.generator.DataGenerator} 相同的物理列顺序：<br>
     * primaryKeys 顺序 → foreignKeys 键名字典序 → canonical 中去掉 PK/FK 的余下列。
     *
     * @return 小写短列名（不含引号）
     */
    @SuppressWarnings("unchecked")
    private List<String> mirageGenerateColumnShortNamesOrder(String tableKey, Map<String, Object> schemaData) {
        List<String> out = new ArrayList<>();
        if (schemaData == null || tableKey == null) {
            return out;
        }
        Map<String, Object> tableInfo = (Map<String, Object>) schemaData.get(tableKey);
        if (tableInfo == null) {
            return out;
        }
        Set<String> pkCanonical = new HashSet<>();
        List<String> pkList = (List<String>) tableInfo.get("primaryKeys");
        if (pkList != null) {
            for (String pk : pkList) {
                if (pk == null || !pk.contains(".")) {
                    continue;
                }
                pkCanonical.add(pk);
                out.add(pk.substring(pk.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
            }
        }
        Map<String, String> foreignKeys = (Map<String, String>) tableInfo.get("foreignKeys");
        List<String> fkKeys = new ArrayList<>();
        if (foreignKeys != null) {
            fkKeys.addAll(foreignKeys.keySet());
            Collections.sort(fkKeys);
        }
        Set<String> fkCanonical = new HashSet<>(fkKeys);
        for (String fk : fkKeys) {
            if (fk == null || !fk.contains(".")) {
                continue;
            }
            out.add(fk.substring(fk.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        }
        List<String> canonical = (List<String>) tableInfo.get("canonicalColumnNames");
        if (canonical != null) {
            for (String col : canonical) {
                if (col == null || !col.contains(".")) {
                    continue;
                }
                if (pkCanonical.contains(col) || fkCanonical.contains(col)) {
                    continue;
                }
                out.add(col.substring(col.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * 按布局选择列顺序：{@code mirage_generate} 时与 DataGenerator 导出一致（PK+FK+属性）；否则按 canonicalColumnNames，其余列按库顺序追加。
     */
    private List<TableDefinitionExtractor.ColumnDefinition> getOrderedColumnsForDDL(
            TableDefinitionExtractor.TableDefinition tableDef,
            String tableKey,
            Map<String, Object> schemaData) {
        Map<String, TableDefinitionExtractor.ColumnDefinition> byShort = new LinkedHashMap<>();
        for (TableDefinitionExtractor.ColumnDefinition c : tableDef.getColumns()) {
            byShort.put(c.getColumnName().toLowerCase(Locale.ROOT), c);
        }
        String layout = System.getProperty(IMPORT_DATA_LAYOUT_PROPERTY, "rsgen").trim().toLowerCase(Locale.ROOT);
        if ("mirage_generate".equals(layout)) {
            List<TableDefinitionExtractor.ColumnDefinition> out = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (String shortName : mirageGenerateColumnShortNamesOrder(tableKey, schemaData)) {
                TableDefinitionExtractor.ColumnDefinition cd = byShort.get(shortName);
                if (cd != null) {
                    out.add(cd);
                    used.add(shortName);
                }
            }
            for (TableDefinitionExtractor.ColumnDefinition c : tableDef.getColumns()) {
                String sn = c.getColumnName().toLowerCase(Locale.ROOT);
                if (!used.contains(sn)) {
                    out.add(c);
                }
            }
            return out;
        }
        List<TableDefinitionExtractor.ColumnDefinition> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<String> canonical = getColumnOrderFromSchema(tableKey, schemaData);
        for (String full : canonical) {
            if (full == null || !full.contains(".")) {
                continue;
            }
            String shortName = full.substring(full.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            TableDefinitionExtractor.ColumnDefinition cd = byShort.get(shortName);
            if (cd != null) {
                out.add(cd);
                used.add(shortName);
            }
        }
        for (TableDefinitionExtractor.ColumnDefinition c : tableDef.getColumns()) {
            String sn = c.getColumnName().toLowerCase(Locale.ROOT);
            if (!used.contains(sn)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 构造 \\COPY 列清单（与 .tbl 列顺序一致）；若无 canonical 信息则返回空列表表示不写显式列。
     */
    private List<String> buildCopyColumnSimpleNames(String tableKey, Map<String, Object> schemaData) {
        String layout = System.getProperty(IMPORT_DATA_LAYOUT_PROPERTY, "rsgen").trim().toLowerCase(Locale.ROOT);
        if ("mirage_generate".equals(layout)) {
            return buildCopyColumnsMirageDataGenerator(tableKey, schemaData);
        }
        List<String> canonical = getColumnOrderFromSchema(tableKey, schemaData);
        if (canonical.isEmpty()) {
            return List.of();
        }
        List<String> cols = new ArrayList<>();
        for (String full : canonical) {
            if (full == null || !full.contains(".")) {
                continue;
            }
            String shortName = full.substring(full.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            cols.add("\"" + shortName.replace("\"", "\"\"") + "\"");
        }
        return cols;
    }

    /**
     * 与 {@link ruc.db.generator.DataGenerator#call} 写出逻辑一致：fkCol2Values 为 {@link java.util.TreeMap}，迭代值为字典序；
     * 属性段为 {@link ruc.db.schema.Table#getAttributeColumnNames()} 顺序。
     */
    private List<String> buildCopyColumnsMirageDataGenerator(String tableKey, Map<String, Object> schemaData) {
        List<String> quoted = new ArrayList<>();
        for (String sn : mirageGenerateColumnShortNamesOrder(tableKey, schemaData)) {
            quoted.add("\"" + sn.replace("\"", "\"\"") + "\"");
        }
        return quoted;
    }

    private String resolveSchemaKeyForCopy(String tableName, Map<String, Object> schemaData) {
        if (schemaData != null && schemaData.containsKey(tableName)) {
            return tableName;
        }
        try {
            if (PartitionTableManager.getInstance().isChildTable(tableName)) {
                String parent = PartitionTableManager.getInstance().getParentTable(tableName);
                if (parent != null && schemaData != null && schemaData.containsKey(parent)) {
                    return parent;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return tableName;
    }

    private String formatCopyFromLine(
            String fullQualifiedTable,
            String absoluteDataPath,
            String statsTableKey,
            Map<String, Object> schemaData) {
        String schemaKey = resolveSchemaKeyForCopy(statsTableKey, schemaData);
        List<String> cols = buildCopyColumnSimpleNames(schemaKey, schemaData);
        String delimiter = DataExportConstants.FIELD_DELIMITER;
        if (cols.isEmpty()) {
            return String.format("\\COPY %s FROM '%s' WITH (FORMAT TEXT, DELIMITER '%s', NULL '\\N');\n",
                    fullQualifiedTable, absoluteDataPath, delimiter);
        }
        return String.format("\\COPY %s (%s) FROM '%s' WITH (FORMAT TEXT, DELIMITER '%s', NULL '\\N');\n",
                fullQualifiedTable, String.join(", ", cols), absoluteDataPath, delimiter);
    }
    
    /**
     * 确定表被跳过的原因
     * 
     * @param tableName 表名
     * @param tableDefinitions 表定义映射
     * @return 跳过原因
     */
    private String determineSkipReason(String tableName, Map<String, TableDefinitionExtractor.TableDefinition> tableDefinitions) {
        try {
            // 检查是否为分区表
            if (PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                return "根分区表（父表）- 数据通过子表生成";
            } else if (PartitionTableManager.getInstance().isChildTable(tableName)) {
                return "分区子表 - 数据直接生成";
            } else if (PartitionTableManager.getInstance().isPartitionTable(tableName)) {
                return "中间分区表 - 数据通过叶子表生成";
            } else {
                return "普通表 - 缺少DDL定义";
            }
        } catch (Exception e) {
            return "无法确定表类型 - " + e.getMessage();
        }
    }
    
    /**
     * 确定表被跳过的原因（统计信息版本）
     * 
     * @param tableName 表名
     * @param enhancedStats 增强统计信息映射
     * @return 跳过原因
     */
    private String determineSkipReasonForStats(String tableName, Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats) {
        try {
            // 检查是否为分区表
            if (PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                return "根分区表（父表）- 统计信息在子表中";
            } else if (PartitionTableManager.getInstance().isChildTable(tableName)) {
                return "分区子表 - 缺少统计信息";
            } else if (PartitionTableManager.getInstance().isPartitionTable(tableName)) {
                return "中间分区表 - 统计信息在叶子表中";
            } else {
                return "普通表 - 缺少统计信息";
            }
        } catch (Exception e) {
            return "无法确定表类型 - " + e.getMessage();
        }
    }
}
