package ruc.db.rsgen;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.sql.Connection;
import java.sql.DriverManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ruc.db.dbconnector.DbConnector;
import ruc.db.dbconnector.adapter.PgConnector;
import ruc.db.dbconnector.adapter.KingBaseConnector;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.LanguageManager;
import ruc.db.utils.DatabaseConnectorConfig;
import ruc.db.utils.ConfigManager;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * RSGen主命令行接口
 * 完整的RSGen数据生成流程控制器
 * 
 * 使用方法：
 * 1. 提取统计信息：java -jar mirage.jar rsgen extract -h localhost -p 5432 -d tpch -u
 * postgres -w password -o ./output [-j 4]
 * 2. 生成数据：java -jar mirage.jar rsgen generate -i ./output -o ./data -s 1000000
 * 3. 完整流程：java -jar mirage.jar rsgen all -h localhost -p 5432 -d tpch -u
 * postgres -w password -o ./output -s 1000000
 * 
 * @author RSGen Implementation
 */
@Command(name = "rsgen", description = "RSGen - Reversing Statistics for Scalable Test Database Generation", mixinStandardHelpOptions = true, subcommands = {
        RSGenMainCLI.ExtractCommand.class,
        RSGenMainCLI.GenerateCommand.class,
        RSGenMainCLI.AllCommand.class,
        RSGenMainCLI.MineMultiColCommand.class,
        RSGenMainCLI.ConfigCommand.class,
        RSGenMainCLI.DDLCommand.class
})
public class RSGenMainCLI implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(RSGenMainCLI.class);
    private static final LanguageManager LM = LanguageManager.getInstance();

    /**
     * 计时工具类
     */
    private static class TimingInfo {
        private final long startTime;
        private long totalExtractionTime = 0;
        private long totalGenerationTime = 0;
        private long schemaExtractionTime = 0;
        private long statisticsExtractionTime = 0;
        private final StringBuilder detailedReport = new StringBuilder();
        private final DecimalFormat timeFormat = new DecimalFormat("#,##0.000");

        public TimingInfo() {
            this.startTime = System.currentTimeMillis();
        }

        public void recordStatisticsExtraction(long time) {
            this.statisticsExtractionTime = time;
            detailedReport
                    .append(String.format("  - Statistics Extraction: %s seconds\n", timeFormat.format(time / 1000.0)));
        }

        public void recordExtractionEnd() {
            this.totalExtractionTime = schemaExtractionTime + statisticsExtractionTime;
        }

        public void recordTableGeneration(String tableName, long time, long rowCount) {
            detailedReport.append(String.format("  - Table %s: %s seconds (%,d rows)\n",
                    tableName, timeFormat.format(time / 1000.0), rowCount));
        }

        public void printFinalReport() {
            long totalTime = System.currentTimeMillis() - startTime;

            System.out.println("\n" + "=".repeat(80));
            System.out.println("RSGen Performance Report");
            System.out.println("=".repeat(80));
            System.out
                    .println(String.format("Total Execution Time: %s seconds", timeFormat.format(totalTime / 1000.0)));
            System.out.println();

            System.out.println("Phase Details:");
            System.out.println(String.format("1. Statistics Extraction: %s seconds (%.1f%%)",
                    timeFormat.format(totalExtractionTime / 1000.0),
                    (totalExtractionTime * 100.0) / totalTime));
            System.out.print(detailedReport.toString());

            System.out.println(String.format("2. Data Generation: %s seconds (%.1f%%)",
                    timeFormat.format(totalGenerationTime / 1000.0),
                    (totalGenerationTime * 100.0) / totalTime));

            System.out.println("\n" + "=".repeat(80));

            // 同时写入日志
            logger.info(LM.formatBilingual("MainCli.RsgenDoneTiming",
                    timeFormat.format(totalTime / 1000.0),
                    timeFormat.format(totalExtractionTime / 1000.0),
                    timeFormat.format(totalGenerationTime / 1000.0)));
        }

        public void setTotalGenerationTime(long time) {
            this.totalGenerationTime = time;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RSGenMainCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        System.out.println("RSGen - Reversing Statistics for Scalable Test Database Generation");
        System.out.println("请使用子命令：extract, generate, 或 all");
        System.out.println("使用 --help 查看详细帮助信息");
        return 0;
    }

    /**
     * 提取统计信息命令
     */
    @Command(name = "extract", description = "从数据库提取统计信息")
    static class ExtractCommand implements Callable<Integer> {
        @Option(names = { "-h", "--host" }, description = "数据库主机", defaultValue = "localhost")
        String host;

        @Option(names = { "-p", "--port" }, description = "数据库端口", defaultValue = "5432")
        String port;

        @Option(names = { "-d", "--database" }, description = "数据库名称", required = true)
        String database;

        @Option(names = { "-u", "--username" }, description = "用户名", required = true)
        String username;

        @Option(names = { "-w", "--password" }, description = "密码", required = true)
        String password;

        @Option(names = { "-o", "--output" }, description = "输出目录", required = true)
        String outputDir;

        @Option(names = { "-r", "--range-mode" }, description = "范围获取模式: histogram (默认) 或 direct-query", defaultValue = "histogram")
        String rangeMode;

        @Option(names = { "--sql-dir" }, description = "SQL 工作集目录：仅提取其中 .sql 涉及的表（表名需与库中 schema.table 一致）")
        String sqlDir;

        @Option(names = { "--default-schema" }, description = "SQL 中未写 schema 的表默认补此 schema", defaultValue = "public")
        String defaultSchema;

        @Option(names = { "--partition-mode" }, description = "分区统计: full=检测分区树、叶子+普通表抽列; off=不检测分区、父表/整表一层抽列", defaultValue = "full")
        String partitionMode;

        @Option(names = { "-j", "--extract-workers" }, description = "统计提取阶段并行线程数（与 -w 密码无关）", defaultValue = "4")
        int extractWorkers;

        @Override
        public Integer call() {
            TimingInfo timing = new TimingInfo();

            try {
                int workers = Math.max(1, extractWorkers);
                logger.info(LM.formatBilingual("MainCli.StartExtractStats"));
                logger.info(LM.formatBilingual("MainCli.ConnectionParamsWithWorkers", host, port, database, username, workers));

                // 确保输出目录存在
                File outputDirectory = new File(outputDir);
                if (!outputDirectory.exists()) {
                    outputDirectory.mkdirs();
                }

                // 创建数据库连接
                DatabaseConnectorConfig config = new DatabaseConnectorConfig(host, port, username, password, database);
                DbConnector dbConnector = new PgConnector(config);

                try {
                    // 提取增强的统计信息
                    long extractStart = System.currentTimeMillis();
                    EnhancedStatsExtractor enhancedExtractor = new EnhancedStatsExtractor();
                    
                    // 设置范围获取模式
                    if ("direct-query".equals(rangeMode)) {
                        enhancedExtractor.setRangeExtractionMode(EnhancedStatsExtractor.RangeExtractionMode.DIRECT_SQL_QUERY);
                        logger.info(LM.formatBilingual("MainCli.RangeModeDirectSql"));
                    } else {
                        enhancedExtractor.setRangeExtractionMode(EnhancedStatsExtractor.RangeExtractionMode.HISTOGRAM_BOUNDS);
                        logger.info(LM.formatBilingual("MainCli.RangeModeHistogram"));
                    }

                    if (sqlDir != null && !sqlDir.isBlank()) {
                        enhancedExtractor.setSqlWorkloadDirectory(sqlDir.trim(), defaultSchema);
                        logger.info(LM.formatBilingual("MainCli.SqlWorkloadDirAndSchema", sqlDir.trim(), defaultSchema));
                    }
                    if ("off".equalsIgnoreCase(partitionMode)) {
                        enhancedExtractor.setPartitionStatsMode(EnhancedStatsExtractor.PartitionStatsMode.OFF);
                    } else {
                        enhancedExtractor.setPartitionStatsMode(EnhancedStatsExtractor.PartitionStatsMode.FULL);
                    }
                    
                    enhancedExtractor.extractEnhancedStatistics(dbConnector, outputDir, workers);
                    long extractTime = System.currentTimeMillis() - extractStart;

                    timing.recordStatisticsExtraction(extractTime);
                    timing.recordExtractionEnd();

                    logger.info(LM.formatBilingual("MainCli.ExtractDoneWithWorkers", outputDir, workers));

                    // 打印计时报告
                    timing.printFinalReport();

                    return 0;

                } finally {
                    // 关闭数据库连接
                    try {
                        if (dbConnector.getConnection() != null) {
                            dbConnector.getConnection().close();
                        }
                    } catch (SQLException e) {
                        logger.warn(LM.formatBilingual("MainCli.CloseDbWarn", e.getMessage()));
                    }
                }

            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.ExtractError", e.getMessage()), e);
                return 1;
            }
        }
    }

    /**
     * 生成数据命令
     */
    @Command(name = "generate", description = "基于统计信息生成测试数据")
    static class GenerateCommand implements Callable<Integer> {
        @Option(names = { "-i", "--input" }, description = "统计信息输入目录", required = true)
        String inputDir;

        @Option(names = { "-o", "--output" }, description = "数据输出目录", required = true)
        String outputDir;

        @Option(names = { "-s", "--scale" }, description = "缩放因子（影响生成的数据量）", defaultValue = "1.0")
        double scaleFactor;

        @Option(names = { "-w", "--workers" }, description = "并行生成的工作线程数", defaultValue = "1")
        int numWorkers;

        @Option(names = { "-t", "--tables" }, description = "要生成的表列表（逗号分隔），不指定则生成所有表")
        String tables;

        @Option(names = { "--phase" }, description = "指定生成阶段: 1=仅非键列, 2=+主键列, 3=+外键列, 4=完整生成", defaultValue = "4")
        int phase;

        @Override
        public Integer call() {
            TimingInfo timing = new TimingInfo();

            try {
                logger.info(LM.formatBilingual("MainCli.StartGenerateData"));
                logger.info(LM.formatBilingual("MainCli.GenerateInputOutputScale", inputDir, outputDir, scaleFactor));

                // 确保输出目录存在
                File outputDirectory = new File(outputDir);
                if (!outputDirectory.exists()) {
                    outputDirectory.mkdirs();
                }

                // 加载增强的统计信息
                long loadStart = System.currentTimeMillis();
                ObjectMapper objectMapper = new ObjectMapper();
                File statsFile = new File(inputDir, "enhanced_column_statistics.json");

                if (!statsFile.exists()) {
                    logger.error(LM.formatBilingual("MainCli.EnhancedStatsNotFound", statsFile.getAbsolutePath()));
                    return 1;
                }

                Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats = objectMapper.readValue(statsFile,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
                                EnhancedStatsExtractor.EnhancedTableStatistics.class));

                // 加载cdfMapping并合并关键值到统计信息中
                enhancedStats = mergeCdfMappingToStats(inputDir, enhancedStats);

                // 从增强统计信息重建TableManager中的表信息
                TableManager tableManager = TableManager.getInstance();

                // 同时加载schema.json以获取外键信息
                File schemaFile = new File(inputDir, "schema.json");
                Map<String, Object> schemaData = null;
                if (schemaFile.exists()) {
                    schemaData = objectMapper.readValue(schemaFile,
                            objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                }

                // 首先加载有统计信息的表
                for (String tableName : enhancedStats.keySet()) {
                    EnhancedStatsExtractor.EnhancedTableStatistics tableStats = enhancedStats.get(tableName);

                    // 检查表是否已存在，如果不存在则创建一个基本的表结构
                    if (!tableManager.getSchemas().containsKey(tableName)) {
                        // 优先从schema.json获取正确的列顺序
                        List<String> columnNames;
                        if (schemaData != null && schemaData.containsKey(tableName)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> tableInfo = (Map<String, Object>) schemaData.get(tableName);
                            Object canonicalColumnsObj = tableInfo.get("canonicalColumnNames");
                            if (canonicalColumnsObj instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<String> canonicalColumns = (List<String>) canonicalColumnsObj;
                                columnNames = new ArrayList<>(canonicalColumns);
                                logger.debug(LM.formatBilingual("MainCli.SchemaJsonColumnOrder", columnNames));
                            } else {
                                // Fallback：使用统计信息中的列名（但顺序可能不对）
                                columnNames = new ArrayList<>(tableStats.getColumns().keySet());
                                logger.warn(LM.formatBilingual("MainCli.NoCanonicalColumnNames"));
                            }
                        } else {
                            // Fallback：使用统计信息中的列名
                            columnNames = new ArrayList<>(tableStats.getColumns().keySet());
                            logger.warn(LM.formatBilingual("MainCli.NoSchemaJson"));
                        }

                        Table table = new Table(columnNames, tableStats.getTableSize());

                        // 只为普通表和根分区表设置外键关系（用于拓扑排序）
                        boolean shouldSetForeignKeys = false;
                        String tableType = "NORMAL"; // 默认为普通表
                        
                        // 从schema.json中获取表类型
                        if (schemaData != null && schemaData.containsKey(tableName)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> tableInfo = (Map<String, Object>) schemaData.get(tableName);
                            Object typeObj = tableInfo.get("type");
                            if (typeObj instanceof String) {
                                tableType = (String) typeObj;
                            }
                        }
                        
                        logger.debug(LM.formatBilingual("MainCli.TableTypeDebug", tableName, tableType));
                        
                        if ("NORMAL".equals(tableType) || "ROOT".equals(tableType)) {
                            shouldSetForeignKeys = true;
                            if ("ROOT".equals(tableType)) {
                                logger.debug(LM.formatBilingual("MainCli.RootPartTableTopo", tableName));
                            } else {
                                logger.debug(LM.formatBilingual("MainCli.NormalTableTopo", tableName));
                            }
                        } else {
                            logger.debug(LM.formatBilingual("MainCli.LeafOrIntermediateNoTopo", tableName, tableType));
                        }
                        
                        if (shouldSetForeignKeys && schemaData != null && schemaData.containsKey(tableName)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> tableInfo = (Map<String, Object>) schemaData.get(tableName);
                            Object foreignKeysObj = tableInfo.get("foreignKeys");
                            if (foreignKeysObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, String> foreignKeys = (Map<String, String>) foreignKeysObj;
                                table.setForeignKeys(foreignKeys);
                                logger.debug(LM.formatBilingual("MainCli.FkRelationsSet", tableName, foreignKeys.size()));
                            }
                        }

                        tableManager.addSchema(tableName, table);
                        logger.debug(LM.formatBilingual("MainCli.RebuildTableFromStats", tableName));
                    }
                }
                
                // 然后加载schema.json中的根分区表（即使没有统计信息）
                if (schemaData != null) {
                    for (String tableName : schemaData.keySet()) {
                        if (!tableManager.getSchemas().containsKey(tableName)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> tableInfo = (Map<String, Object>) schemaData.get(tableName);
                            Object typeObj = tableInfo.get("type");
                            if (typeObj instanceof String && "ROOT".equals(typeObj)) {
                                // 这是一个根分区表，需要加载到TableManager中
                                Object canonicalColumnsObj = tableInfo.get("canonicalColumnNames");
                                List<String> columnNames = new ArrayList<>();
                                if (canonicalColumnsObj instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<String> canonicalColumns = (List<String>) canonicalColumnsObj;
                                    columnNames = new ArrayList<>(canonicalColumns);
                                }
                                
                                // 获取表大小（根分区表通常为0）
                                long tableSize = 0;
                                Object tableSizeObj = tableInfo.get("tableSize");
                                if (tableSizeObj instanceof Number) {
                                    tableSize = ((Number) tableSizeObj).longValue();
                                }
                                
                                Table table = new Table(columnNames, tableSize);
                                
                                // 设置外键关系
                                Object foreignKeysObj = tableInfo.get("foreignKeys");
                                if (foreignKeysObj instanceof Map) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, String> foreignKeys = (Map<String, String>) foreignKeysObj;
                                    table.setForeignKeys(foreignKeys);
                                    logger.debug(LM.formatBilingual("MainCli.RootPartitionFkSet", tableName, foreignKeys.size()));
                                }
                                
                                tableManager.addSchema(tableName, table);
                                logger.debug(LM.formatBilingual("MainCli.LoadRootPartitionFromSchema", tableName));
                            }
                        }
                    }
                }

                // 预加载分区关系信息（在ForeignKeyHandler初始化之前）
                try {
                    PartitionTableManager.getInstance().loadFromFile(inputDir);
                    logger.info(LM.formatBilingual("MainCli.PartitionRelationPreloaded"));
                } catch (IOException e) {
                    logger.info(LM.formatBilingual("MainCli.NoPartitionFile", e.getMessage()));
                }

                // 初始化外键处理器
                ForeignKeyHandler foreignKeyHandler = new ForeignKeyHandler();

                // 获取生成顺序
                List<String> generationOrder;
                if (tables != null && !tables.trim().isEmpty()) {
                    generationOrder = Arrays.asList(tables.split(","));
                } else {
                    generationOrder = foreignKeyHandler.getGenerationOrder();
                    // 如果拓扑排序为空，则使用所有表名
                    if (generationOrder.isEmpty()) {
                        generationOrder = new ArrayList<>(enhancedStats.keySet());
                        logger.info(LM.formatBilingual("MainCli.AllTablesAsOrder", generationOrder));
                    }
                }

                long loadTime = System.currentTimeMillis() - loadStart;
                logger.info(LM.formatBilingual("MainCli.DataLoadInitDone", loadTime / 1000.0));

                // 创建数据生成器
                // RSGenDataGeneratorRefactored dataGenerator = new RSGenDataGeneratorRefactored(foreignKeyHandler, numWorkers);
                RSGenDataGeneratorRefactored dataGenerator = new RSGenDataGeneratorRefactored(foreignKeyHandler);

                // 生成所有表的数据（使用新的5步流程）
                long totalGenerationStart = System.currentTimeMillis();
                dataGenerator.generateAllTablesData(inputDir, outputDir, scaleFactor);
                long totalGenerationTime = System.currentTimeMillis() - totalGenerationStart;
                timing.setTotalGenerationTime(totalGenerationTime);

                logger.info(LM.formatBilingual("MainCli.GenerateDone", outputDir));

                // 打印计时报告
                timing.printFinalReport();

                return 0;

            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.GenerateError", e.getMessage()), e);
                return 1;
            }
        }

        /**
         * 将cdfMapping中的关键参数值合并到统计信息的MCV中
         * 确保RSGen生成的数据包含Mirage计算出的参数值
         */
        private Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> mergeCdfMappingToStats(String inputDir, Map<String, EnhancedStatsExtractor.EnhancedTableStatistics> enhancedStats) {
            File cdfMappingFile = new File(inputDir, "cdfMapping.json");
            if (!cdfMappingFile.exists()) {
                logger.info(LM.formatBilingual("MainCli.CdfMappingMissing"));
                return enhancedStats;
            }

            try {
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Map<String, String>> cdfMapping = objectMapper.readValue(cdfMappingFile,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {});

                int totalMerged = 0;

                // 解释cdfMapping.json的含义
                logger.info(LM.formatBilingual("MainCli.CdfHeader"));
                logger.info(LM.formatBilingual("MainCli.CdfDistJsonExplain"));
                logger.info(LM.formatBilingual("MainCli.CdfMappingExplain"));
                logger.info(LM.formatBilingual("MainCli.CdfMergeLogic"));
                logger.info(LM.formatBilingual("MainCli.CdfPurpose"));
                logger.info(LM.formatBilingual("MainCli.CdfSeparator"));

                for (Map.Entry<String, Map<String, String>> tableEntry : cdfMapping.entrySet()) {
                    String tableColumnName = tableEntry.getKey(); // e.g., "public.customers.c_acctbal"
                    Map<String, String> columnMapping = tableEntry.getValue();

                    // 解析表名和列名
                    String[] parts = tableColumnName.split("\\.");
                    if (parts.length < 3) continue;

                    String tableName = parts[0] + "." + parts[1];
                    String columnName = tableColumnName;

                    // 查找对应的统计信息
                    if (!enhancedStats.containsKey(tableName)) continue;

                    EnhancedStatsExtractor.EnhancedTableStatistics tableStats = enhancedStats.get(tableName);
                    if (!tableStats.getColumns().containsKey(columnName)) continue;

                    EnhancedStatsExtractor.EnhancedColumnStatistics colStats = tableStats.getColumns().get(columnName);

                    // 获取现有的MCV
                    List<String> existingMcvs = colStats.getMostCommonValues();
                    List<Double> existingMcfs = colStats.getMostCommonFrequencies();

                    if (existingMcvs == null) existingMcvs = new ArrayList<>();
                    if (existingMcfs == null) existingMcfs = new ArrayList<>();

                    // 合并cdfMapping中的值
                    for (Map.Entry<String, String> mappingEntry : columnMapping.entrySet()) {
                        String key = mappingEntry.getKey(); // 编码值，如"411712"
                        String value = mappingEntry.getValue(); // 实际值，如"417.06"

                        // 检查是否已经在MCV中
                        if (!existingMcvs.contains(value)) {
                            existingMcvs.add(0, value); // 添加到开头，确保高优先级
                            existingMcfs.add(0, 0.01); // 给一个合理的频率，避免频率过高导致重复生成
                            totalMerged++;
                            logger.info(LM.formatBilingual("MainCli.MergeKeyParam",
                                       key, value, tableColumnName));
                            logger.info(LM.formatBilingual("MainCli.MergeKeyUsage"));
                        } else {
                            logger.debug(LM.formatBilingual("MainCli.McvSkipMerge", value));
                        }
                    }

                    // 更新统计信息
                    colStats.setMostCommonValues(existingMcvs);
                    colStats.setMostCommonFrequencies(existingMcfs);
                    colStats.setMcvCount(existingMcvs.size());
                }

                logger.info(LM.formatBilingual("MainCli.MergeKeySuccessCount", totalMerged));

            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.CdfLoadError", e.getMessage()));
            }

            return enhancedStats;
        }
    }

    /**
     * 完整流程命令
     */
    @Command(name = "all", description = "执行完整的RSGen流程：提取统计信息 -> 生成数据")
    static class AllCommand implements Callable<Integer> {
        @Option(names = { "-h", "--host" }, description = "数据库主机", defaultValue = "localhost")
        String host;

        @Option(names = { "-p", "--port" }, description = "数据库端口", defaultValue = "5432")
        String port;

        @Option(names = { "-d", "--database" }, description = "数据库名称", required = true)
        String database;

        @Option(names = { "-u", "--username" }, description = "用户名", required = true)
        String username;

        @Option(names = { "-w", "--password" }, description = "密码", required = true)
        String password;

        @Option(names = { "-o", "--output" }, description = "输出目录", required = true)
        String outputDir;

        @Option(names = { "-s", "--scale" }, description = "缩放因子（影响生成的数据量）", defaultValue = "1.0")
        double scaleFactor;

        @Option(names = { "-t", "--tables" }, description = "要生成的表列表（逗号分隔），不指定则生成所有表")
        String tables;

        @Option(names = { "-r", "--range-mode" }, description = "范围获取模式: histogram (默认) 或 direct-query", defaultValue = "histogram")
        String rangeMode;

        @Option(names = { "--sql-dir" }, description = "SQL 工作集目录：仅提取其中 .sql 涉及的表")
        String sqlDir;

        @Option(names = { "--default-schema" }, description = "SQL 中未写 schema 的表默认补此 schema", defaultValue = "public")
        String defaultSchema;

        @Option(names = { "--partition-mode" }, description = "分区统计: full 或 off", defaultValue = "full")
        String partitionMode;

        @Option(names = { "-j", "--extract-workers" }, description = "统计提取阶段并行线程数", defaultValue = "4")
        int extractWorkers;

        @Override
        public Integer call() {
            TimingInfo timing = new TimingInfo();

            try {
                // 第一阶段：提取统计信息
                logger.info(LM.formatBilingual("MainCli.Phase1Header"));
                long extractStart = System.currentTimeMillis();

                ExtractCommand extractCommand = new ExtractCommand();
                extractCommand.host = this.host;
                extractCommand.port = this.port;
                extractCommand.database = this.database;
                extractCommand.username = this.username;
                extractCommand.password = this.password;
                extractCommand.outputDir = this.outputDir;
                extractCommand.rangeMode = this.rangeMode;
                extractCommand.sqlDir = this.sqlDir;
                extractCommand.defaultSchema = this.defaultSchema;
                extractCommand.partitionMode = this.partitionMode;
                extractCommand.extractWorkers = this.extractWorkers;

                int extractResult = extractCommand.call();
                if (extractResult != 0) {
                    logger.error(LM.formatBilingual("MainCli.ExtractFailed"));
                    return extractResult;
                }

                long extractTime = System.currentTimeMillis() - extractStart;
                timing.recordStatisticsExtraction(extractTime);
                timing.recordExtractionEnd();

                // 第二阶段：生成数据
                logger.info(LM.formatBilingual("MainCli.Phase2Header"));
                long generateStart = System.currentTimeMillis();

                GenerateCommand generateCommand = new GenerateCommand();
                generateCommand.inputDir = this.outputDir;
                generateCommand.outputDir = this.outputDir + "/data";
                generateCommand.scaleFactor = this.scaleFactor;
                generateCommand.tables = this.tables;

                int generateResult = generateCommand.call();
                if (generateResult != 0) {
                    logger.error(LM.formatBilingual("MainCli.GenerateFailed"));
                    return generateResult;
                }

                long generateTime = System.currentTimeMillis() - generateStart;
                timing.setTotalGenerationTime(generateTime);

                logger.info(LM.formatBilingual("MainCli.AllDoneHeader"));
                logger.info(LM.formatBilingual("MainCli.StatsLocation", this.outputDir));
                logger.info(LM.formatBilingual("MainCli.DataLocation", this.outputDir));

                // 打印最终计时报告
                timing.printFinalReport();

                return 0;

            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.AllPipelineError", e.getMessage()), e);
                return 1;
            }
        }
    }

    /**
     * 多列共现关系挖掘命令
     */
    @Command(name = "mine-multicol", description = "挖掘SQL查询中单表多列共现关系")
    static class MineMultiColCommand implements Callable<Integer> {
        @Option(names = {"-q", "--query-dir"}, description = "SQL查询文件目录", required = true)
        String queryDir;

        @Option(names = {"-o", "--output"}, description = "输出JSON文件路径", required = true)
        String outputPath;

        @Option(names = {"-s", "--schema"}, description = "schema.json文件路径（可选，用于获取表结构信息）")
        String schemaPath;

        @Override
        public Integer call() {
            try {
                MultiColumnRelationshipMiner miner = new MultiColumnRelationshipMiner();
                
                // 如果提供了schema文件，先加载表结构信息
                if (schemaPath != null && !schemaPath.trim().isEmpty()) {
                    logger.info(LM.formatBilingual("MainCli.LoadSchemaPath", schemaPath));
                    miner.loadSchemaInfo(schemaPath);
                }
                
                logger.info(LM.formatBilingual("MainCli.StartMineQueryDir", queryDir));
                miner.analyzeQueryDirectory(queryDir);
                
                logger.info(LM.formatBilingual("MainCli.ExportTo", outputPath));
                miner.exportResultToJson(outputPath);
                
                System.out.println("多列共现关系挖掘完成，结果已保存到: " + outputPath);
                return 0;
            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.MineFailed", e.getMessage()), e);
                System.err.println("多列共现关系挖掘失败: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * 配置文件命令
     */
    @Command(name = "config", description = "使用配置文件执行RSGen操作")
    static class ConfigCommand implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, description = "配置文件名（不包含.json扩展名）", required = true)
        String configName;

        @Option(names = {"-a", "--action"}, description = "执行操作: extract, generate, all", defaultValue = "all")
        String action;

        @Override
        public Integer call() {
            try {
                logger.info(LM.formatBilingual("MainCli.LoadConfigJson", configName));
                ConfigManager.RSGenConfig config = ConfigManager.loadRSGenConfig(configName);
                
                ConfigManager.DatabaseConfig dbConfig = config.getDatabase();
                if (dbConfig == null) {
                    logger.error(LM.formatBilingual("MainCli.ConfigMissingDb"));
                    return 1;
                }

                logger.info(LM.formatBilingual("MainCli.DbType", dbConfig.getType()));
                logger.info(LM.formatBilingual("MainCli.ConnInfo", dbConfig.getHost(), dbConfig.getPort(), dbConfig.getDatabase()));

                switch (action.toLowerCase()) {
                    case "extract":
                        return executeExtract(config);
                    case "generate":
                        return executeGenerate(config);
                    case "all":
                        return executeAll(config);
                    default:
                        logger.error(LM.formatBilingual("MainCli.UnsupportedAction", action));
                        return 1;
                }

            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.ConfigExecError", e.getMessage()), e);
                return 1;
            }
        }

        private int executeExtract(ConfigManager.RSGenConfig config) throws Exception {
            TimingInfo timing = new TimingInfo();
            ConfigManager.DatabaseConfig dbConfig = config.getDatabase();
            
            logger.info(LM.formatBilingual("MainCli.StartExtractStats"));
            logger.info(LM.formatBilingual("MainCli.ConnectionParamsNoWorkers", dbConfig.getHost(), dbConfig.getPort(), dbConfig.getDatabase(), dbConfig.getUsername()));
            
            // 创建数据库连接
            DatabaseConnectorConfig connectorConfig = ConfigManager.createDatabaseConnectorConfig(dbConfig);
            DbConnector dbConnector = createDbConnector(dbConfig, connectorConfig);

            try {
                // 确保输出目录存在
                File outputDirectory = new File(config.getOutputDirectory());
                if (!outputDirectory.exists()) {
                    outputDirectory.mkdirs();
                }

                // 提取统计信息
                long extractStart = System.currentTimeMillis();
                EnhancedStatsExtractor enhancedExtractor = new EnhancedStatsExtractor();
                
                // 设置范围获取模式
                if ("direct-query".equals(dbConfig.getRangeMode())) {
                    enhancedExtractor.setRangeExtractionMode(EnhancedStatsExtractor.RangeExtractionMode.DIRECT_SQL_QUERY);
                    logger.info(LM.formatBilingual("MainCli.RangeModeDirectSql"));
                } else {
                    enhancedExtractor.setRangeExtractionMode(EnhancedStatsExtractor.RangeExtractionMode.HISTOGRAM_BOUNDS);
                    logger.info(LM.formatBilingual("MainCli.RangeModeHistogram"));
                }

                if (config.getSqlWorkloadDirectory() != null && !config.getSqlWorkloadDirectory().isBlank()) {
                    enhancedExtractor.setSqlWorkloadDirectory(
                            config.getSqlWorkloadDirectory().trim(),
                            config.getSqlWorkloadDefaultSchema());
                    logger.info(LM.formatBilingual("MainCli.SqlWorkloadDirOnly", config.getSqlWorkloadDirectory()));
                }
                if ("off".equalsIgnoreCase(config.getPartitionMode())) {
                    enhancedExtractor.setPartitionStatsMode(EnhancedStatsExtractor.PartitionStatsMode.OFF);
                } else {
                    enhancedExtractor.setPartitionStatsMode(EnhancedStatsExtractor.PartitionStatsMode.FULL);
                }
                
                enhancedExtractor.extractEnhancedStatistics(dbConnector, config.getOutputDirectory(), config.getNumWorkers());
                long extractTime = System.currentTimeMillis() - extractStart;

                timing.recordStatisticsExtraction(extractTime);
                timing.recordExtractionEnd();
                
                logger.info(LM.formatBilingual("MainCli.ExtractDoneSimple", config.getOutputDirectory()));

                // 打印计时报告
                timing.printFinalReport();
                return 0;

            } finally {
                if (dbConnector.getConnection() != null) {
                    dbConnector.getConnection().close();
                }
            }
        }

        private int executeGenerate(ConfigManager.RSGenConfig config) throws Exception {
            TimingInfo timing = new TimingInfo();
            
            logger.info(LM.formatBilingual("MainCli.StartGenerateData"));
            logger.info(LM.formatBilingual("MainCli.GenerateInputOutputScale", 
                config.getOutputDirectory(), 
                config.getOutputDirectory() + "/data", 
                config.getScaleFactor()));

            GenerateCommand generateCommand = new GenerateCommand();
            generateCommand.inputDir = config.getOutputDirectory();
            generateCommand.outputDir = config.getOutputDirectory() + "/data";
            generateCommand.scaleFactor = config.getScaleFactor();
            generateCommand.numWorkers = config.getNumWorkers();
            generateCommand.tables = config.getTables();
            generateCommand.phase = config.getPhase();

            int result = generateCommand.call();
            
            // 注意：GenerateCommand内部已经有自己的TimingInfo，这里主要是为了日志输出
            logger.info(LM.formatBilingual("MainCli.GenerateDone", config.getOutputDirectory() + "/data"));
            return result;
        }

        private int executeAll(ConfigManager.RSGenConfig config) throws Exception {
            TimingInfo timing = new TimingInfo();
            
            try {
                // 第一阶段：提取统计信息
                logger.info(LM.formatBilingual("MainCli.Phase1Header"));
                long extractStart = System.currentTimeMillis();

                int extractResult = executeExtract(config);
                if (extractResult != 0) {
                    logger.error(LM.formatBilingual("MainCli.ExtractFailed"));
                    return extractResult;
                }

                long extractTime = System.currentTimeMillis() - extractStart;
                timing.recordStatisticsExtraction(extractTime);
                timing.recordExtractionEnd();

                // 第二阶段：生成数据
                logger.info(LM.formatBilingual("MainCli.Phase2Header"));
                long generateStart = System.currentTimeMillis();

                int generateResult = executeGenerate(config);
                if (generateResult != 0) {
                    logger.error(LM.formatBilingual("MainCli.GenerateFailed"));
                    return generateResult;
                }

                long generateTime = System.currentTimeMillis() - generateStart;
                timing.setTotalGenerationTime(generateTime);

                logger.info(LM.formatBilingual("MainCli.AllDoneHeader"));
                logger.info(LM.formatBilingual("MainCli.StatsLocation", config.getOutputDirectory()));
                logger.info(LM.formatBilingual("MainCli.DataLocation", config.getOutputDirectory()));

                // 打印最终计时报告
                timing.printFinalReport();

                return 0;

            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.AllPipelineError", e.getMessage()), e);
                return 1;
            }
        }

        private DbConnector createDbConnector(ConfigManager.DatabaseConfig dbConfig, DatabaseConnectorConfig connectorConfig) throws Exception {
            switch (dbConfig.getType()) {
                case POSTGRESQL:
                    return new PgConnector(connectorConfig);
                case KINGBASE:
                    return new KingBaseConnector(connectorConfig);
                default:
                    throw new IllegalArgumentException("不支持的数据库类型: " + dbConfig.getType());
            }
        }
    }

    @Command(name = "ddl", description = "生成DDL文件")
    public static class DDLCommand implements Callable<Integer> {
        
        @Option(names = {"-i", "--input"}, description = "输入目录路径", required = true)
        private String inputDir;
        
        @Option(names = {"-o", "--output"}, description = "输出目录路径", required = true)
        private String outputDir;
        
        @Option(names = {"-c", "--config"}, description = "配置文件名称", required = true)
        private String configName;
        
        @Override
        public Integer call() throws Exception {
            logger.info(LM.formatBilingual("MainCli.DdlStart"));
            logger.info(LM.formatBilingual("MainCli.DdlInputDir", inputDir));
            logger.info(LM.formatBilingual("MainCli.DdlOutputDir", outputDir));
            logger.info(LM.formatBilingual("MainCli.DdlConfigName", configName));
            
            try {
                // 检查输入目录是否存在
                File inputDirectory = new File(inputDir);
                if (!inputDirectory.exists()) {
                    logger.error(LM.formatBilingual("MainCli.InputDirNotExist", inputDir));
                    return 1;
                }
                
                // 检查必要的文件是否存在
                File schemaFile = new File(inputDir, "schema.json");
                
                if (!schemaFile.exists()) {
                    logger.error(LM.formatBilingual("MainCli.SchemaJsonMissing", schemaFile.getAbsolutePath()));
                    return 1;
                }
                
                // 创建输出目录
                File outputDirectory = new File(outputDir);
                if (!outputDirectory.exists()) {
                    outputDirectory.mkdirs();
                }
                
                // 加载配置文件
                ConfigManager.RSGenConfig rsGenConfig = ConfigManager.loadRSGenConfig(configName);
                ConfigManager.DatabaseConfig dbConfig = rsGenConfig.getDatabase();
                
                // 转换为Map格式
                Map<String, Object> config = new HashMap<>();
                config.put("dbType", dbConfig.getType().getTypeName().toUpperCase());
                config.put("host", dbConfig.getHost());
                config.put("port", dbConfig.getPort());
                config.put("database", dbConfig.getDatabase());
                config.put("username", dbConfig.getUsername());
                config.put("password", dbConfig.getPassword());
                
                // 创建数据库连接
                Connection connection = createDbConnector(config);
                
                // 生成DDL文件
                DDLSQLGenerator ddlGenerator = new DDLSQLGenerator();
                ddlGenerator.generateAllDDLFiles(inputDir, outputDir, connection);
                
                // 关闭连接
                if (connection != null) {
                    connection.close();
                }
                
                logger.info(LM.formatBilingual("MainCli.DdlDone"));
                logger.info(LM.formatBilingual("MainCli.DdlOutputDirFinal", outputDir));
                
                return 0;
                
            } catch (Exception e) {
                logger.error(LM.formatBilingual("MainCli.DdlError", e.getMessage()), e);
                return 1;
            }
        }
        
        /**
         * 创建数据库连接器
         */
        private Connection createDbConnector(Map<String, Object> config) throws Exception {
            String dbType = (String) config.get("dbType");
            if (dbType == null) {
                throw new IllegalArgumentException("dbType 为空，请检查配置中的 database.type 或 databaseConnectorConfig");
            }
            if ("KINGBASE".equalsIgnoreCase(dbType)) {
                return createKingBaseConnection(config);
            }
            if ("POSTGRESQL".equalsIgnoreCase(dbType)) {
                return createPostgreSQLConnection(config);
            }
            throw new UnsupportedOperationException("ddl 子命令当前仅支持 JDBC 连接 Kingbase / PostgreSQL，不支持: " + dbType);
        }

        private Connection createPostgreSQLConnection(Map<String, Object> config) throws Exception {
            String host = (String) config.get("host");
            String port = (String) config.get("port");
            String database = (String) config.get("database");
            String username = (String) config.get("username");
            String password = (String) config.get("password");
            String url = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
            logger.info("连接 PostgreSQL 以拉取表定义: {}:{}/{}", host, port, database);
            return DriverManager.getConnection(url, username, password);
        }

        /**
         * 创建KingBase连接
         */
        private Connection createKingBaseConnection(Map<String, Object> config) throws Exception {
            String host = (String) config.get("host");
            String port = (String) config.get("port");
            String database = (String) config.get("database");
            String username = (String) config.get("username");
            String password = (String) config.get("password");
            
            String url = String.format("jdbc:kingbase8://%s:%s/%s", host, port, database);
            
            logger.info(LM.formatBilingual("MainCli.KingBaseConnect", host, port));
            
            return DriverManager.getConnection(url, username, password);
        }
    }
}
