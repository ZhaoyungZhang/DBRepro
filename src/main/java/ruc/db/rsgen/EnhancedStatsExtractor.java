package ruc.db.rsgen;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.DbType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ruc.db.analyzer.statical.QueryReader;
import ruc.db.dbconnector.DbConnector;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.utils.exception.analyze.IllegalQueryTableNameException;

/**
 * 增强的统计信息提取器
 * 专门为RSGen优化，提取完整的统计信息包括主外键列
 * 
 * 主要改进：
 * 1. 提取所有列的统计信息（包括主外键）
 * 2. 获取准确的数据类型信息
 * 3. 处理缺失的MCV数据
 * 4. 优化统计信息存储格式
 * 
 * @author RSGen Implementation
 */
public class EnhancedStatsExtractor {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedStatsExtractor.class);

    // PostgreSQL数组解析正则表达式
    private static final Pattern PG_ARRAY_PATTERN = Pattern.compile("\\{([^}]*)\\}");

    // 在类的开头添加新的成员变量
    private NumericStringDetector numericStringDetector = new NumericStringDetector();
    private NumericStringDataCleaner numericStringCleaner = new NumericStringDataCleaner();
    private DataQualityAnalyzer dataQualityAnalyzer = new DataQualityAnalyzer();

    /**
     * 范围获取模式
     */
    public enum RangeExtractionMode {
        /** 从直方图边界推断（默认模式） */
        HISTOGRAM_BOUNDS,
        /** 直接执行SQL查询MIN/MAX */
        DIRECT_SQL_QUERY
    }

    /**
     * 分区表统计粒度：FULL 为原逻辑（根/中间表跳过，叶子与普通表抽列统计）；OFF 为不建分区树、不按子分区抽数（适合只要父表/整表一层统计）。
     */
    public enum PartitionStatsMode {
        FULL,
        OFF
    }

    private final ObjectMapper objectMapper;
    private RangeExtractionMode rangeExtractionMode = RangeExtractionMode.HISTOGRAM_BOUNDS;
    private PartitionStatsMode partitionStatsMode = PartitionStatsMode.FULL;
    /** 非空时仅从该目录下 .sql 解析涉及的表并提取统计 */
    private String sqlWorkloadDirectory;
    private String sqlWorkloadDefaultSchema = "public";
    /** 与库中规范名对齐后的表集合；null 表示未启用工作集过滤 */
    private Set<String> workloadResolvedAllowlist;

    public EnhancedStatsExtractor() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 设置范围获取模式
     */
    public void setRangeExtractionMode(RangeExtractionMode mode) {
        this.rangeExtractionMode = mode;
        logger.info("范围获取模式设置为: {}", mode);
    }

    /**
     * 获取当前范围获取模式
     */
    public RangeExtractionMode getRangeExtractionMode() {
        return this.rangeExtractionMode;
    }

    public void setPartitionStatsMode(PartitionStatsMode mode) {
        this.partitionStatsMode = mode != null ? mode : PartitionStatsMode.FULL;
        logger.info("分区统计模式: {}", this.partitionStatsMode);
    }

    public PartitionStatsMode getPartitionStatsMode() {
        return partitionStatsMode;
    }

    /**
     * 指定 SQL 工作集目录：提取前解析其中 .sql 出现的表，仅对这些表拉 schema 与统计（需配合库内实际存在的 schema.table）。
     *
     * @param directory       目录路径；传 null 或空则恢复为全库表
     * @param defaultSchema   未写 schema 的表名默认补此前缀（如 public）
     */
    public void setSqlWorkloadDirectory(String directory, String defaultSchema) {
        this.sqlWorkloadDirectory = directory != null && !directory.isBlank() ? directory : null;
        if (defaultSchema != null && !defaultSchema.isBlank()) {
            this.sqlWorkloadDefaultSchema = defaultSchema;
        }
    }

    /**
     * 提取增强的统计信息
     */
    public void extractEnhancedStatistics(DbConnector dbConnector, String outputDir) throws SQLException, IOException {
        extractEnhancedStatistics(dbConnector, outputDir, 4); // 默认使用4个worker
    }

    /**
     * 提取增强的统计信息（带worker数量参数）
     */
    public void extractEnhancedStatistics(DbConnector dbConnector, String outputDir, int numWorkers) throws SQLException, IOException {
        logger.info("开始提取增强的统计信息");

        // 确保输出目录存在
        File outputDirectory = new File(outputDir);
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        // 设置TableManager的输出目录
        TableManager.getInstance().setResultDir(outputDir);

        try {
            resolveWorkloadAllowlistIfNeeded(dbConnector);
        } catch (IllegalQueryTableNameException e) {
            throw new IOException("解析 SQL 工作集表名失败", e);
        }

        // 首先直接从数据库提取并构建schema信息（并行处理）
        extractAndBuildSchemaInformation(dbConnector, numWorkers);

        Map<String, EnhancedTableStatistics> enhancedStats = new HashMap<>();

        // 获取所有表
       //  List<String> tableNames = dbConnector.getAllTableNames();

        // 使用新的三阶段并行统计信息提取
        enhancedStats = extractStatisticsInPhases(dbConnector, numWorkers);

        // 数据质量分析和清洗处理
        // logger.info("🔍 开始数据质量分析和清洗...");
        // enhancedStats = processDataQualityAndCleaning(enhancedStats);
        // logger.info("✅ 数据质量分析和清洗完成");

        // 保存增强的统计信息
        File enhancedStatsFile = new File(outputDir, "enhanced_column_statistics.json");
        objectMapper.writeValue(enhancedStatsFile, enhancedStats);

        logger.info("增强统计信息已保存到: {}", enhancedStatsFile.getAbsolutePath());
        
        // 保存分区表关系信息
        try {
            PartitionTableManager.getInstance().saveToFile(outputDir);
        } catch (IOException e) {
            logger.error("保存分区表关系信息失败: {}", e.getMessage(), e);
        }
        
        // 生成表类型摘要文件
        try {
            generateTableTypeSummary(outputDir, enhancedStats);
        } catch (IOException e) {
            logger.error("生成表类型摘要文件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 直接从数据库提取并构建schema信息
     */
    private void extractAndBuildSchemaInformation(DbConnector dbConnector) throws SQLException, IOException {
        extractAndBuildSchemaInformation(dbConnector, 4); // 默认使用4个worker
    }

    /**
     * 直接从数据库提取并构建schema信息（带worker数量参数）
     */
    private void extractAndBuildSchemaInformation(DbConnector dbConnector, int numWorkers) throws SQLException, IOException {
        logger.info("开始从数据库提取Schema信息");

        if (partitionStatsMode == PartitionStatsMode.FULL) {
            detectPartitionTables(dbConnector);
        } else {
            PartitionTableManager.getInstance().clear();
            PartitionTreeManager.getInstance().clear();
            logger.info("分区统计模式 OFF：跳过分区检测，按整体表处理");
        }

        List<String> allTableNames;
        if (workloadResolvedAllowlist != null) {
            allTableNames = new ArrayList<>(workloadResolvedAllowlist);
            allTableNames.sort(String::compareTo);
            logger.info("仅处理 SQL 工作集中的 {} 张表", allTableNames.size());
        } else {
            allTableNames = dbConnector.getAllTableNames();
        }

        Map<String, List<String>> tableGroups = groupTablesByType(allTableNames);

        // debug: 这里输出一下全部的 tablename，看看表的格式是什么样子的
        // logger.info("DEBUG: 所有表名: {}", allTableNames);

        // 普通表/根分区表
        List<String> priorityTables = tableGroups.get("priority");
        // 中间分区表
        List<String> intermediatePartitionTables = tableGroups.get("intermediate");
        // 叶子分区表
        List<String> leafTables = tableGroups.get("leaf");
        
        int totalTables = allTableNames.size();
        logger.info("发现 {} 个表: {} 个优先表, {} 个中间分区表, {} 个叶子表", 
                   totalTables, priorityTables.size(), 
                   intermediatePartitionTables.size(), leafTables.size());

        // 第一阶段：并行处理所有根分区表和普通表（优先处理）
        logger.info("=== 提取schema第一阶段：并行处理 {} 个根分区表和普通表 ===", priorityTables.size());
        processTablesInParallel(dbConnector, priorityTables, "第一阶段", numWorkers);
        logger.info("提取schema第一阶段完成，开始第二阶段");

        // 第二阶段：并行处理中间分区表（继承schema）
        if (!intermediatePartitionTables.isEmpty()) {
            logger.info("=== 提取schema第二阶段：并行处理 {} 个中间分区表 ===", intermediatePartitionTables.size());
            processTablesInParallel(dbConnector, intermediatePartitionTables, "第二阶段", numWorkers);
            logger.info("提取schema第二阶段完成，开始第三阶段");
        } else {
            logger.info("提取schema第二阶段跳过（无中间分区表），开始第三阶段");
        }

        // 第三阶段：并行处理叶子表
        if (!leafTables.isEmpty()) {
            logger.info("=== 提取schema第三阶段：并行处理 {} 个叶子表 ===", leafTables.size());
            processTablesInParallel(dbConnector, leafTables, "第三阶段", numWorkers);
            logger.info("提取schema第三阶段完成");
        } else {
            logger.info("提取schema第三阶段跳过（无叶子表）");
        }
        
        logger.info("所有表schema处理任务已完成");

        // 生成统计报告
        generateExtractionReport();

        // 持久化Schema信息
        try {
            TableManager.getInstance().storeSchemaInfo();
            logger.info("Schema信息已保存");
        } catch (IOException e) {
            logger.error("保存Schema信息失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 将表名规范化为 schema.table 的无引号形式（不更改大小写）。
     * 仅去除外围的双引号与多余空白，避免因不同来源的格式差异导致 TableManager 查找失败。
     */
    private String canonicalizeTableName(String tableName) {
        if (tableName == null) {
            return null;
        }
        String t = tableName.trim();
        // 简单去除 schema 或 table 两侧可能存在的双引号
        int dot = t.indexOf('.');
        if (dot > 0 && dot < t.length() - 1) {
            String schema = t.substring(0, dot).trim();
            String table = t.substring(dot + 1).trim();
            if (schema.startsWith("\"") && schema.endsWith("\"")) {
                schema = schema.substring(1, schema.length() - 1);
            }
            if (table.startsWith("\"") && table.endsWith("\"")) {
                table = table.substring(1, table.length() - 1);
            }
            return schema + "." + table;
        }
        return t;
    }

    private void resolveWorkloadAllowlistIfNeeded(DbConnector dbConnector)
            throws IOException, IllegalQueryTableNameException, SQLException {
        if (sqlWorkloadDirectory == null || sqlWorkloadDirectory.isBlank()) {
            workloadResolvedAllowlist = null;
            return;
        }
        File dir = new File(sqlWorkloadDirectory);
        if (!dir.isDirectory()) {
            throw new IOException("SQL 工作集目录不存在或不是目录: " + dir.getAbsolutePath());
        }
        QueryReader reader = new QueryReader(sqlWorkloadDefaultSchema, sqlWorkloadDirectory);
        reader.setDbType(DbType.postgresql);
        List<File> files = reader.loadQueryFiles();
        if (files.isEmpty()) {
            throw new IOException("目录中未找到 .sql 文件: " + dir.getAbsolutePath());
        }
        Set<String> raw = new LinkedHashSet<>();
        for (File f : files) {
            for (String q : reader.getQueriesFromFileOrThrow(f.getAbsolutePath())) {
                raw.addAll(reader.collectTableNamesFromAllStatements(q));
            }
        }
        if (raw.isEmpty()) {
            throw new IOException("未能从 SQL 中解析出任何表名");
        }
        List<String> dbTables = dbConnector.getAllTableNames();
        Map<String, String> keyToCanon = new HashMap<>();
        for (String t : dbTables) {
            String c = canonicalizeTableName(t);
            keyToCanon.put(c.toLowerCase(Locale.ROOT), c);
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (String r : raw) {
            String key = canonicalizeTableName(r).toLowerCase(Locale.ROOT);
            String canon = keyToCanon.get(key);
            if (canon != null) {
                resolved.add(canon);
            } else {
                logger.warn("SQL 中出现的表在数据库中未找到（忽略）: {}", r);
            }
        }
        if (resolved.isEmpty()) {
            throw new IOException("SQL 中的表名均无法在数据库中匹配，请检查 schema/大小写");
        }
        workloadResolvedAllowlist = resolved;
        logger.info("SQL 工作集解析到 {} 张表: {}", resolved.size(), resolved);
    }

    private List<String> filterByWorkloadAllowlist(List<String> names) {
        if (workloadResolvedAllowlist == null || names == null || names.isEmpty()) {
            return names;
        }
        return names.stream()
                .filter(t -> workloadResolvedAllowlist.contains(canonicalizeTableName(t)))
                .collect(Collectors.toList());
    }

    /**
     * 当发现比 TableManager/PartitionTreeManager 已知更可靠的 tableSize (>0) 时，尝试写回。
     */
    private void persistTableSizeIfBetter(String rawTableName, long discoveredSize) {
        if (discoveredSize <= 0) {
            return;
        }
        String tableName = canonicalizeTableName(rawTableName);
        try {
            // 更新 PartitionTreeManager
            try {
                PartitionTreeManager.getInstance().setTableSize(tableName, (int) discoveredSize);
            } catch (Exception ignored) {
            }
            // 更新 TableManager 中的 Table 对象
            try {
                Table table = TableManager.getInstance().getSchema(tableName);
                if (table != null) {
                    table.setTableSize(discoveredSize);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignoredOuter) {
            // 保守处理，绝不影响主流程
        }
    }
    
    /**
     * 三阶段并行提取统计信息
     */
    private Map<String, EnhancedTableStatistics> extractStatisticsInPhases(DbConnector dbConnector, int numWorkers) throws SQLException {
        logger.info("开始三阶段并行统计信息提取");
        
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        Map<String, EnhancedTableStatistics> enhancedStats = new HashMap<>();
        
        // 统计报告数据结构
        StatisticsExtractionReport report = new StatisticsExtractionReport();
        
        // 第一阶段：处理普通表和根分区表（工作集模式下按白名单过滤）
        List<String> normalTables = filterByWorkloadAllowlist(treeManager.getAllNormalTables());
        List<String> rootTables = filterByWorkloadAllowlist(treeManager.getAllRootPartitionTables());
        
        logger.info("=== 提取统计信息第一阶段：处理普通表和根分区表 (普通表: {}, 根表: {}) ===", normalTables.size(), rootTables.size());
        
        // 分别处理普通表和根分区表
        Map<String, EnhancedTableStatistics> normalResults = processStatisticsPhase(
            dbConnector, normalTables, "提取统计信息第一阶段-普通表", numWorkers, report.normalTables);
        enhancedStats.putAll(normalResults);
        
        Map<String, EnhancedTableStatistics> rootResults = processStatisticsPhase(
            dbConnector, rootTables, "提取统计信息第一阶段-根表", numWorkers, report.rootTables);
        enhancedStats.putAll(rootResults);
        
        logger.info("提取统计信息第一阶段完成");
        
        // 第二阶段：处理中间分区表
        List<String> intermediateTables = filterByWorkloadAllowlist(treeManager.getAllIntermediatePartitionTables());
        if (!intermediateTables.isEmpty()) {
            logger.info("=== 提取统计信息第二阶段：处理中间分区表 ({}) ===", intermediateTables.size());
            Map<String, EnhancedTableStatistics> phase2Results = processStatisticsPhase(
                dbConnector, intermediateTables, "第二阶段", numWorkers, report.intermediateTables);
            enhancedStats.putAll(phase2Results);
            logger.info("提取统计信息第二阶段完成");
        } else {
            logger.info("提取统计信息第二阶段跳过（无中间分区表）");
        }
        
        // 第三阶段：处理叶子分区表
        List<String> leafTables = filterByWorkloadAllowlist(treeManager.getAllLeafTables());
        if (!leafTables.isEmpty()) {
            logger.info("=== 提取统计信息第三阶段：处理叶子分区表 ({}) ===", leafTables.size());
            Map<String, EnhancedTableStatistics> phase3Results = processStatisticsPhase(
                dbConnector, leafTables, "第三阶段", numWorkers, report.leafTables);
            enhancedStats.putAll(phase3Results);
            logger.info("提取统计信息第三阶段完成");
        } else {
            logger.info("提取统计信息第三阶段跳过（无叶子表）");
        }
        
        // 输出统计报告
        generateStatisticsExtractionReport(report);
        
        return enhancedStats;
    }
    
    /**
     * 处理单个阶段的统计信息提取
     */
    private Map<String, EnhancedTableStatistics> processStatisticsPhase(
            DbConnector dbConnector, List<String> tableNames, String phaseName, 
            int numWorkers, PhaseStatistics... phaseStats) throws SQLException {
        
        if (tableNames.isEmpty()) {
            return new HashMap<>();
        }
        
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        List<Future<StatisticsResult>> futures = new ArrayList<>();
        
        // 用于跟踪进度的原子计数器
        AtomicInteger processedCount = new AtomicInteger(0);
        
        // 为每个表提交处理任务
        for (String tableName : tableNames) {
            Future<StatisticsResult> future = executor.submit(() -> {
                try {
                    // 统一表名格式，避免大小写/引号差异
                    String canonical = canonicalizeTableName(tableName);
                    StatisticsResult result = processTableStatistics(dbConnector, canonical);
                    int current = processedCount.incrementAndGet();
                    logger.info("提取统计信息{}进度: {}/{} - 表 {} 处理完成", phaseName, current, tableNames.size(), canonical);
                    return result;
                } catch (Exception e) {
                    logger.error("提取统计信息{} - 处理表 {} 时出错: {}", phaseName, tableName, e.getMessage(), e);
                    int current = processedCount.incrementAndGet();
                    logger.info("提取统计信息{}进度: {}/{} - 表 {} 处理失败", phaseName, current, tableNames.size(), tableName);
                    return new StatisticsResult(tableName, null, StatisticsResult.Status.ERROR);
                }
            });
            futures.add(future);
        }
        
        // 等待所有任务完成并收集结果
        Map<String, EnhancedTableStatistics> results = new HashMap<>();
        try {
            for (Future<StatisticsResult> future : futures) {
                StatisticsResult result = future.get();
                if (result.status == StatisticsResult.Status.EXTRACTED) {
                    results.put(result.tableName, result.stats);
                }
                
                // 只更新第一个传入的统计对象（对应当前阶段的统计）
                if (phaseStats.length > 0) {
                    phaseStats[0].updateStatistics(result);
                }
            }
            logger.info("提取统计信息{}并行处理完成，所有任务已等待完成", phaseName);
        } catch (Exception e) {
            logger.error("等待提取统计信息{}并行处理任务完成时出错: {}", phaseName, e.getMessage(), e);
            throw new RuntimeException("并行处理统计信息时出错", e);
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        return results;
    }
    
    /**
     * 处理单个表的统计信息提取
     */
    private StatisticsResult processTableStatistics(DbConnector dbConnector, String tableName) throws SQLException {
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        PartitionTreeNode.NodeType nodeType = treeManager.getTableType(tableName);
        if (nodeType == null) {
            logger.warn("表 {} 不在分区树中，按普通表处理", tableName);
            nodeType = PartitionTreeNode.NodeType.NORMAL;
        }
        
        // 检查表大小
        int tableSize = 0;
        try {
            tableSize = dbConnector.getTableSize(tableName);
            persistTableSizeIfBetter(tableName, tableSize);
        } catch (Exception e) {
            logger.warn("获取表 {} 大小时出错: {}", tableName, e.getMessage());
            return new StatisticsResult(tableName, null, StatisticsResult.Status.ERROR);
        }
        
        // 根据表类型和大小决定是否提取统计信息
        if (tableSize == 0) {
            logger.info("表 {} 大小为0，跳过统计信息提取", tableName);
            return new StatisticsResult(tableName, null, StatisticsResult.Status.SKIPPED_SIZE_ZERO);
        }
        
        // OFF 模式：对根/中间分区表也按整张表拉列统计（父表级）；FULL 模式仅普通表与叶子表
        boolean extractColumns = partitionStatsMode == PartitionStatsMode.OFF
                || nodeType == PartitionTreeNode.NodeType.NORMAL
                || nodeType == PartitionTreeNode.NodeType.LEAF;

        if (extractColumns) {
            try {
                EnhancedTableStatistics stats = extractTableStatistics(dbConnector, tableName);
                if (stats != null && !stats.getColumns().isEmpty()) {
                    return new StatisticsResult(tableName, stats, StatisticsResult.Status.EXTRACTED);
                } else {
                    return new StatisticsResult(tableName, stats, StatisticsResult.Status.EXTRACTED_EMPTY);
                }
            } catch (Exception e) {
                logger.error("提取表 {} 统计信息时出错: {}", tableName, e.getMessage(), e);
                return new StatisticsResult(tableName, null, StatisticsResult.Status.ERROR);
            }
        } else {
            // 根表和中间表跳过统计信息提取
            logger.info("表 {} 是分区表 ({}), 跳过统计信息提取", tableName, nodeType);
            return new StatisticsResult(tableName, null, StatisticsResult.Status.SKIPPED_PARTITION);
        }
    }
    
    /**
     * 生成统计信息提取报告
     */
    private void generateStatisticsExtractionReport(StatisticsExtractionReport report) {
        logger.info("=== 统计信息提取报告 ===");
        
        // 普通表统计 - 根据实际提取过程情况报告
        logger.info("普通表: 跳过{}个, 提取{}个, 空结果{}个, 错误{}个", 
                   report.normalTables.skippedSizeZero, report.normalTables.extracted, 
                   report.normalTables.extractedEmpty, report.normalTables.errors);
        
        // 根分区表统计 - 根据实际提取过程情况报告
        logger.info("根分区表: 跳过{}个, 提取{}个, 空结果{}个, 错误{}个", 
                   report.rootTables.skippedSizeZero + report.rootTables.skippedPartition, 
                   report.rootTables.extracted, 
                   report.rootTables.extractedEmpty, report.rootTables.errors);
        
        // 中间分区表统计 - 根据实际提取过程情况报告
        logger.info("中间分区表: 跳过{}个, 提取{}个, 空结果{}个, 错误{}个", 
                   report.intermediateTables.skippedSizeZero + report.intermediateTables.skippedPartition, 
                   report.intermediateTables.extracted, 
                   report.intermediateTables.extractedEmpty, report.intermediateTables.errors);
        
        // 叶子分区表统计 - 根据实际提取过程情况报告
        logger.info("叶子分区表: 跳过{}个, 提取{}个, 空结果{}个, 错误{}个", 
                   report.leafTables.skippedSizeZero + report.leafTables.skippedPartition, 
                   report.leafTables.extracted, 
                   report.leafTables.extractedEmpty, report.leafTables.errors);
        
        // 总计 - 根据实际提取过程情况统计
        int totalSkipped = report.normalTables.skippedSizeZero + 
                          (report.rootTables.skippedSizeZero + report.rootTables.skippedPartition) + 
                          (report.intermediateTables.skippedSizeZero + report.intermediateTables.skippedPartition) + 
                          (report.leafTables.skippedSizeZero + report.leafTables.skippedPartition);
        int totalExtracted = report.normalTables.extracted + report.rootTables.extracted + 
                           report.intermediateTables.extracted + report.leafTables.extracted;
        int totalEmpty = report.normalTables.extractedEmpty + report.rootTables.extractedEmpty + 
                        report.intermediateTables.extractedEmpty + report.leafTables.extractedEmpty;
        int totalErrors = report.normalTables.errors + report.rootTables.errors + 
                         report.intermediateTables.errors + report.leafTables.errors;
        
        logger.info("总计: 跳过{}个, 提取{}个, 空结果{}个, 错误{}个", 
                   totalSkipped, totalExtracted, totalEmpty, totalErrors);
        logger.info("=== 报告结束 ===");
    }
    
    /**
     * 统计信息提取报告数据结构
     */
    private static class StatisticsExtractionReport {
        PhaseStatistics normalTables = new PhaseStatistics();
        PhaseStatistics rootTables = new PhaseStatistics();
        PhaseStatistics intermediateTables = new PhaseStatistics();
        PhaseStatistics leafTables = new PhaseStatistics();
    }
    
    /**
     * 单个阶段的统计信息
     */
    private static class PhaseStatistics {
        int skippedSizeZero = 0;
        int skippedPartition = 0;
        int extracted = 0;
        int extractedEmpty = 0;
        int errors = 0;
        
        void updateStatistics(StatisticsResult result) {
            switch (result.status) {
                case SKIPPED_SIZE_ZERO:
                    skippedSizeZero++;
                    break;
                case SKIPPED_PARTITION:
                    skippedPartition++;
                    break;
                case EXTRACTED:
                    extracted++;
                    break;
                case EXTRACTED_EMPTY:
                    extractedEmpty++;
                    break;
                case ERROR:
                    errors++;
                    break;
            }
        }
    }
    
    /**
     * 统计信息提取结果
     */
    private static class StatisticsResult {
        String tableName;
        EnhancedTableStatistics stats;
        Status status;
        
        StatisticsResult(String tableName, EnhancedTableStatistics stats, Status status) {
            this.tableName = tableName;
            this.stats = stats;
            this.status = status;
        }
        
        enum Status {
            SKIPPED_SIZE_ZERO,    // 因大小为0而跳过
            SKIPPED_PARTITION,    // 因是分区表而跳过
            EXTRACTED,            // 成功提取
            EXTRACTED_EMPTY,      // 提取但结果为空
            ERROR                 // 提取出错
        }
    }
    
    /**
     * 生成提取统计报告
     */
    private void generateExtractionReport() {
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        
        // 获取各种类型的表列表
        List<String> rootTables = treeManager.getAllRootPartitionTables();
        List<String> intermediateTables = treeManager.getAllIntermediatePartitionTables();
        List<String> leafTables = treeManager.getAllLeafTables();
        List<String> normalTables = treeManager.getAllNormalTables();
        
        // 统计数量
        int rootCount = rootTables.size();
        int intermediateCount = intermediateTables.size();
        int leafCount = leafTables.size();
        int normalCount = normalTables.size();
        int totalPartitionTables = rootCount + intermediateCount + leafCount;
        int totalTables = totalPartitionTables + normalCount;
        
        // 输出统计报告
        logger.info("=== 分区表统计信息提取报告 ===");
        logger.info("总表数: {}", totalTables);
        logger.info("分区表总数: {} (根表: {}, 中间表: {}, 叶子表: {})", 
                   totalPartitionTables, rootCount, intermediateCount, leafCount);
        logger.info("普通表数: {}", normalCount);
        
        // 计算分区表占比
        if (totalTables > 0) {
            double partitionRatio = (double) totalPartitionTables / totalTables * 100;
            logger.info("分区表占比: {}%", String.format("%.1f", partitionRatio));
        }
        
        // 如果有分区表，显示层级信息
        if (totalPartitionTables > 0) {
            if (intermediateCount > 0) {
                logger.info("分区层级: 多级分区 (根表 → 中间表 → 叶子表)");
            } else {
                logger.info("分区层级: 单级分区 (根表 → 叶子表)");
            }
        }
        
        logger.info("=== 报告结束 ===");
    }
    
    /**
     * 并行处理表列表
     * 
     * @param dbConnector 数据库连接器
     * @param tableNames 表名列表
     * @param phaseName 阶段名称
     * @param numWorkers 工作线程数
     */
    private void processTablesInParallel(DbConnector dbConnector, List<String> tableNames, String phaseName, int numWorkers) {
        if (tableNames.isEmpty()) {
            return;
        }
        
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        List<Future<Void>> futures = new ArrayList<>();
        
        // 用于跟踪进度的原子计数器
        java.util.concurrent.atomic.AtomicInteger parallelProcessedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // 为每个表提交处理任务
        for (String tableName : tableNames) {
            Future<Void> future = executor.submit(() -> {
                try {
                    processTable(dbConnector, tableName);
                    // 更新进度
                    int current = parallelProcessedCount.incrementAndGet();
                    logger.info("提取schema信息{}进度: {}/{} - 表 {} 处理完成", phaseName, current, tableNames.size(), tableName);
                } catch (Exception e) {
                    logger.error("{} - 处理表 {} 时出错: {}", phaseName, tableName, e.getMessage(), e);
                    // 即使出错也要更新进度
                    int current = parallelProcessedCount.incrementAndGet();
                    logger.info("提取schema信息{}进度: {}/{} - 表 {} 处理失败", phaseName, current, tableNames.size(), tableName);
                }
                return null;
            });
            futures.add(future);
        }

        // 等待所有任务完成
        try {
            for (Future<Void> future : futures) {
                future.get();
            }
            logger.info("{}并行处理完成，所有任务已等待完成", phaseName);
        } catch (Exception e) {
            logger.error("等待{}并行处理任务完成时出错: {}", phaseName, e.getMessage(), e);
            throw new RuntimeException("并行处理表时出错", e);
        } finally {
            // 关闭线程池
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 处理单个表的方法（用于并行处理）
     */
    private void processTable(DbConnector dbConnector, String tableName) {
        try {
            PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
            PartitionTreeNode.NodeType nodeType = treeManager.getTableType(tableName);

            // 输出表名以及类型
            logger.info("DEBUG:处理表: {} 类型: {}", tableName, nodeType);
            
            if (nodeType == null) {
                logger.warn("表 {} 的类型未知，将作为普通表处理", tableName);
                nodeType = PartitionTreeNode.NodeType.NORMAL;
            }
            
            logger.info("表 {} 的类型: {}", tableName, nodeType);

            // 根据表类型采用不同的处理策略
            switch (nodeType) {
                case ROOT:
                    processRootPartitionTable(dbConnector, tableName);
                    break;
                case INTERMEDIATE:
                    processIntermediatePartitionTable(dbConnector, tableName);
                    break;
                case LEAF:
                    processLeafTable(dbConnector, tableName);
                    break;
                case NORMAL:
                    processNormalTable(dbConnector, tableName);
                    break;
                default:
                    logger.warn("未知的表类型: {}, 表: {}", nodeType, tableName);
                    processNormalTable(dbConnector, tableName);
                    break;
            }

        } catch (Exception e) {
            logger.error("处理表 {} 时出错: {}", tableName, e.getMessage(), e);
        }
    }
    
    /**
     * 处理根分区表
     * 
     * @param dbConnector 数据库连接器
     * @param tableName 表名
     */
    private void processRootPartitionTable(DbConnector dbConnector, String tableName) throws SQLException {
        logger.info("处理根分区表: {}", tableName);
        
        // 根分区表需要提取schema信息，但tableSize为0
        int tableSize = 0;
        
        // 获取 schema 信息
        List<String> columnNames;
        try {
            columnNames = dbConnector.getColumnMetadata(tableName);
        } catch (Exception e) {
            logger.error("获取根分区表 {} 的 schema 信息时出错: {}", tableName, e.getMessage());
            throw new SQLException("获取 schema 信息失败", e);
        }
        
        // 创建表对象
        Table table = new Table(columnNames, tableSize);
        table.setType("ROOT"); // 设置表类型为根分区表
        
        // 获取主键
        List<String> primaryKeys = dbConnector.getPrimaryKey(tableName);
        // 判断是否主键为空，不为空加入到 table 中，为空输出 log
        if (primaryKeys.isEmpty()) {
            logger.warn("根分区表 {} 没有主键", tableName);
        } else {
            table.setPrimaryKeys(primaryKeys);
        }
        
        // 获取外键
        Map<String, String> foreignKeys = dbConnector.getForeignKeys(tableName);
        // 判断是否外键为空，不为空加入到 table 中，为空输出 log
        if (foreignKeys.isEmpty()) {
            logger.warn("根分区表 {} 没有外键", tableName);
        } else {
            table.setForeignKeys(foreignKeys);
        }
        
        // 添加到管理器（需要线程安全）
        synchronized (TableManager.getInstance()) {
            TableManager.getInstance().addSchema(tableName, table);
        }
        
        // 更新树管理器中的状态
        PartitionTreeManager.getInstance().setHasSchema(tableName, true);
        PartitionTreeManager.getInstance().setTableSize(tableName, tableSize);
        
        logger.info("根分区表 {} 处理完成: {} 列, {} 行, {} 主键, {} 外键",
                tableName, columnNames.size(), tableSize, primaryKeys.size(), foreignKeys.size());
    }
    
    /**
     * 处理中间分区表
     * 
     * @param dbConnector 数据库连接器
     * @param tableName 表名
     */
    private void processIntermediatePartitionTable(DbConnector dbConnector, String tableName) throws SQLException {
        logger.info("处理中间分区表: {}", tableName);
        
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        String parentTableName = treeManager.getParentTable(tableName);
        
        if (parentTableName != null && treeManager.hasSchema(parentTableName)) {
            // 继承父表的schema
            logger.info("中间分区表 {} 继承父表 {} 的schema", tableName, parentTableName);
            
            if (inheritParentSchema(tableName, parentTableName, "INTERMEDIATE")) {
                // 中间分区表的tableSize为0
                treeManager.setTableSize(tableName, 0);
                logger.info("中间分区表 {} 成功继承父表schema", tableName);
                return;
            } else {
                logger.warn("中间分区表 {} 继承父表schema失败，将正常处理", tableName);
            }
        } else {
            logger.warn("中间分区表 {} 的父表 {} 尚未处理或schema不存在", tableName, parentTableName);
        }
        
        // 如果继承失败，正常处理
        processRootPartitionTable(dbConnector, tableName); // 中间分区表的处理逻辑与根分区表相同
    }
    
    /**
     * 处理叶子表
     * 
     * @param dbConnector 数据库连接器
     * @param tableName 表名
     */
    private void processLeafTable(DbConnector dbConnector, String tableName) throws SQLException {
        logger.info("处理叶子表: {}", tableName);
        
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        String parentTableName = treeManager.getParentTable(tableName);
        
        if (parentTableName != null && treeManager.hasSchema(parentTableName)) {
            // 继承父表的schema
            logger.info("叶子表 {} 继承父表 {} 的schema", tableName, parentTableName);
            
            if (inheritParentSchema(tableName, parentTableName, "LEAF")) {
                // 叶子表需要获取实际的表大小
                int tableSize = dbConnector.getTableSize(tableName);
                treeManager.setTableSize(tableName, tableSize);
                
                // 更新TableManager中的Table对象的tableSize
                try {
                    Table table = TableManager.getInstance().getSchema(tableName);
                    if (table != null) {
                        table.setTableSize(tableSize);
                        logger.debug("更新叶子表 {} 在TableManager中的表大小: {}", tableName, tableSize);
                    }
                } catch (Exception e) {
                    logger.warn("更新叶子表 {} 在TableManager中的表大小失败: {}", tableName, e.getMessage());
                }
                
                // 如果表大小为0，跳过处理
                if (tableSize == 0) {
                    logger.info("叶子表 {} 大小为0，跳过统计信息提取", tableName);
                    return;
                }
                
                logger.info("叶子表 {} 成功继承父表schema，表大小: {}", tableName, tableSize);
                return;
            } else {
                logger.warn("叶子表 {} 继承父表schema失败，将正常处理", tableName);
            }
        } else {
            logger.warn("叶子表 {} 的父表 {} 尚未处理或schema不存在", tableName, parentTableName);
        }
        
        // 如果继承失败，正常处理
        processNormalTable(dbConnector, tableName);
    }
    
    /**
     * 处理普通表
     * 
     * @param dbConnector 数据库连接器
     * @param tableName 表名
     */
    private void processNormalTable(DbConnector dbConnector, String tableName) throws SQLException {
        logger.info("处理普通表: {}", tableName);
        
        // 获取表大小
        int tableSize = dbConnector.getTableSize(tableName);
        
        // 获取列信息
        List<String> columnNames;
        try {
            columnNames = dbConnector.getColumnMetadata(tableName);
        } catch (Exception e) {
            logger.error("获取普通表 {} 的列信息时出错: {}", tableName, e.getMessage());
            throw new SQLException("获取列信息失败", e);
        }
        
        // 创建表对象
        Table table = new Table(columnNames, tableSize);
        table.setType("NORMAL"); // 设置表类型为普通表
        
        // 获取主键
        List<String> primaryKeys = dbConnector.getPrimaryKey(tableName);
        table.setPrimaryKeys(primaryKeys);
        
        // 获取外键
        Map<String, String> foreignKeys = dbConnector.getForeignKeys(tableName);
        table.setForeignKeys(foreignKeys);
        
        // 添加到管理器（需要线程安全）
        synchronized (TableManager.getInstance()) {
            TableManager.getInstance().addSchema(tableName, table);
        }
        
        // 更新树管理器中的状态
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        treeManager.setHasSchema(tableName, true);
        treeManager.setTableSize(tableName, tableSize);
        
        if (tableSize == 0) {
            logger.info("普通表 {} 处理完成: {} 列, {} 行, {} 主键, {} 外键 (表大小为0，已添加到schema但跳过统计信息提取)",
                    tableName, columnNames.size(), tableSize, primaryKeys.size(), foreignKeys.size());
        } else {
            logger.info("普通表 {} 处理完成: {} 列, {} 行, {} 主键, {} 外键",
                    tableName, columnNames.size(), tableSize, primaryKeys.size(), foreignKeys.size());
        }
    }
    
    /**
     * 继承父表的schema
     * 
     * @param childTableName 子表名
     * @param parentTableName 父表名
     * @param childTableType 子表类型
     * @return 是否成功继承
     */
    private boolean inheritParentSchema(String childTableName, String parentTableName, String childTableType) {
        // 最多重试3次，每次等待100ms
        for (int retry = 0; retry < 3; retry++) {
            try {
                // 获取父表的schema信息
                Table parentTable = TableManager.getInstance().getSchema(parentTableName);
                if (parentTable == null) {
                    if (retry < 2) {
                        logger.warn("无法获取父表 {} 的schema信息，等待100ms后重试 ({}/{})", parentTableName, retry + 1, 3);
                        Thread.sleep(100);
                        continue;
                    } else {
                        logger.warn("无法获取父表 {} 的schema信息，已重试3次", parentTableName);
                        return false;
                    }
                }
                
                // 创建子表的Table对象，复制父表的schema信息，但替换列名中的表名部分
                List<String> childCanonicalColumnNames = new ArrayList<>();
                for (String parentColumnName : parentTable.getCanonicalColumnNames()) {
                    // 替换列名中的表名部分：schema.parent_table.column -> schema.child_table.column
                    // 使用简单的字符串替换，避免正则表达式问题
                    String childColumnName = parentColumnName.replace(parentTableName + ".", childTableName + ".");
                    childCanonicalColumnNames.add(childColumnName);
                }
                
                // ==== 这个地方可能会有问题 ====
                Table childTable = new Table(childCanonicalColumnNames, 0); // 初始tableSize为0，后续会更新
                childTable.setType(childTableType); // 设置子表类型
                
                // 复制并替换主键列名
                List<String> childPrimaryKeys = new ArrayList<>();
                for (String parentPrimaryKey : parentTable.getPrimaryKeys()) {
                    String childPrimaryKey = parentPrimaryKey.replace(parentTableName + ".", childTableName + ".");
                    childPrimaryKeys.add(childPrimaryKey);
                }
                childTable.setPrimaryKeys(childPrimaryKeys);
                
                // 复制并替换外键列名
                Map<String, String> childForeignKeys = new HashMap<>();
                for (Map.Entry<String, String> entry : parentTable.getForeignKeys().entrySet()) {
                    String childKeyColumn = entry.getKey().replace(parentTableName + ".", childTableName + ".");
                    String childValueColumn = entry.getValue(); // 外键引用的表名不需要替换
                    childForeignKeys.put(childKeyColumn, childValueColumn);
                }
                childTable.setForeignKeys(childForeignKeys);
                
                // 添加到管理器（需要线程安全）
                synchronized (TableManager.getInstance()) {
                    TableManager.getInstance().addSchema(childTableName, childTable);
                }
                
                // 更新树管理器中的状态
                PartitionTreeManager.getInstance().setHasSchema(childTableName, true);
                
                logger.info("子表 {} 成功继承父表 {} 的schema: {} 列, {} 主键, {} 外键",
                        childTableName, parentTableName, 
                        childTable.getCanonicalColumnNames().size(),
                        childTable.getPrimaryKeys().size(),
                        childTable.getForeignKeys().size());
                
                return true;
                
            } catch (Exception e) {
                if (retry < 2) {
                    logger.warn("子表 {} 继承父表 {} 的schema时出错，等待100ms后重试 ({}/{}): {}", 
                            childTableName, parentTableName, retry + 1, 3, e.getMessage());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    logger.error("子表 {} 继承父表 {} 的schema时出错，已重试3次: {}", childTableName, parentTableName, e.getMessage(), e);
                    return false;
                }
            }
        }
        return false;
    }
    
    /**
     * 复用父表的schema定义
     * 
     * @param dbConnector 数据库连接器
     * @param childTableName 子表名
     * @param parentTableName 父表名
     * @return 是否成功复用
     */
    private boolean reuseParentTableSchema(DbConnector dbConnector, String childTableName, String parentTableName) {
        try {
            // 检查父表是否已经处理过
            if (!TableManager.getInstance().containSchema(parentTableName)) {
                logger.info("父表 {} 尚未处理，触发递归处理", parentTableName);
                
                // 递归处理父表
                processTable(dbConnector, parentTableName);
                
                // 再次检查父表是否处理成功
                if (!TableManager.getInstance().containSchema(parentTableName)) {
                    logger.warn("递归处理父表 {} 失败", parentTableName);
                    return false;
                }
                
                logger.info("递归处理父表 {} 成功", parentTableName);
            }
            
            // 获取父表的schema信息
            Table parentTable = TableManager.getInstance().getSchema(parentTableName);
            if (parentTable == null) {
                logger.warn("无法获取父表 {} 的schema信息", parentTableName);
                return false;
            }
            
            // 获取子表的表大小 - 添加详细的日志和错误处理
            int childTableSize = 0;
            try {
                logger.info("开始获取子表 {} 的大小", childTableName);
                childTableSize = dbConnector.getTableSize(childTableName);
                logger.info("子表 {} 的大小为: {}", childTableName, childTableSize);
            } catch (SQLException e) {
                logger.warn("获取子表 {} 大小失败: {}", childTableName, e.getMessage());
                
                // 尝试使用更详细的查询来获取表大小
                try {
                    childTableSize = getTableSizeWithDetailedQuery(dbConnector, childTableName);
                    logger.info("使用详细查询获取子表 {} 的大小: {}", childTableName, childTableSize);
                } catch (Exception e2) {
                    logger.error("详细查询也失败，子表 {} 大小获取失败: {}", childTableName, e2.getMessage());
                    return false;
                }
            }
            
            if (childTableSize == 0) {
                logger.info("子表 {} 大小为0，跳过处理", childTableName);
                return true; // 表大小为0，无需处理，返回成功
            }
            
            // 创建子表的Table对象，复用父表的列定义
            Table childTable = new Table(parentTable.getCanonicalColumnNames(), childTableSize);
            
            // 复用父表的主键和外键定义
            childTable.setPrimaryKeys(parentTable.getPrimaryKeys());
            childTable.setForeignKeys(parentTable.getForeignKeys());
            
            // 添加到管理器
            synchronized (TableManager.getInstance()) {
                TableManager.getInstance().addSchema(childTableName, childTable);
            }
            
            logger.info("成功复用父表 {} 的schema，子表 {} 处理完成: {} 列, {} 行", 
                       parentTableName, childTableName, childTable.getCanonicalColumnNames().size(), childTableSize);
            
            return true;
            
        } catch (Exception e) {
            logger.error("复用父表 {} 的schema时出错: {}", parentTableName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 使用更详细的查询来获取表大小，处理分区表的特殊情况
     */
    private int getTableSizeWithDetailedQuery(DbConnector dbConnector, String tableName) throws SQLException {
        String[] tableParts = tableName.split("\\.");
        if (tableParts.length != 2) {
            logger.warn("表名格式不正确: {}", tableName);
            return 0;
        }
        
        String schema = tableParts[0];
        String table = tableParts[1];
        
        // 尝试多种查询方式
        String[] queries = {
            // 标准查询
            String.format("SELECT COUNT(*) FROM %s.%s", schema, table),
            // 带引号的查询（处理特殊字符）
            String.format("SELECT COUNT(*) FROM \"%s\".\"%s\"", schema, table),
            // 检查表是否存在
            String.format("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '%s' AND table_name = '%s'", schema, table),
            // 检查分区表信息
            String.format("SELECT COUNT(*) FROM pg_inherits i JOIN pg_class c ON i.inhrelid = c.oid JOIN pg_namespace n ON c.relnamespace = n.oid WHERE n.nspname = '%s' AND c.relname = '%s'", schema, table)
        };
        
        for (int i = 0; i < queries.length; i++) {
            try {
                logger.debug("尝试查询 {}: {}", i + 1, queries[i]);
                try (Statement stmt = dbConnector.getConnection().createStatement()) {
                    ResultSet rs = stmt.executeQuery(queries[i]);
                    if (rs.next()) {
                        int count = rs.getInt(1);
                        logger.debug("查询 {} 成功，结果: {}", i + 1, count);
                        if (i < 2) { // 前两个查询是获取表大小的
                            return count;
                        } else if (count > 0) { // 后两个查询是检查表是否存在
                            logger.info("表 {} 存在，但大小查询失败", tableName);
                            return 0; // 表存在但无法获取大小
                        }
                    }
                }
            } catch (SQLException e) {
                logger.debug("查询 {} 失败: {}", i + 1, e.getMessage());
            }
        }
        
        logger.warn("所有查询方式都失败，无法获取表 {} 的大小", tableName);
        return 0;
    }

    /**
     * 提取单个表的增强统计信息
     */
    private EnhancedTableStatistics extractTableStatistics(DbConnector dbConnector, String tableName)
            throws SQLException {
        EnhancedTableStatistics tableStats = new EnhancedTableStatistics();
        tableStats.setTableName(tableName);

        // 从TableManager获取表大小（已在schema构建阶段获取）
        try {
            long managerSize = TableManager.getInstance().getTableSize(tableName);
            tableStats.setTableSize(managerSize);
            logger.debug("从TableManager获取表 {} 大小: {}", tableName, managerSize);
            if (managerSize <= 0) {
                // 兜底：直接从数据库获取一次并回写
                try {
                    int liveSize = dbConnector.getTableSize(tableName);
                    if (liveSize > 0) {
                        tableStats.setTableSize(liveSize);
                        persistTableSizeIfBetter(tableName, liveSize);
                        logger.info("表 {} 的大小从DB校正为 {} 并写回缓存", tableName, liveSize);
                    }
                } catch (Exception ignore) {
                    // 保守忽略
                }
            }
        } catch (Exception e) {
            logger.warn("从TableManager获取表 {} 大小失败: {}", tableName, e.getMessage());
            // 兜底：直接从数据库获取
            try {
                int liveSize = dbConnector.getTableSize(tableName);
                tableStats.setTableSize(liveSize);
                persistTableSizeIfBetter(tableName, liveSize);
                logger.info("表 {} 的大小直接从DB获取为 {} 并写回缓存", tableName, liveSize);
            } catch (Exception ignore) {
                tableStats.setTableSize(0);
            }
        }

        // 获取所有列的信息
        Map<String, EnhancedColumnStatistics> columnStats = new HashMap<>();

        // 获取列的数据类型信息
        Map<String, String> columnTypes = getColumnTypes(dbConnector, tableName);

        // 获取所有列（包括主外键）
        List<String> allColumns = getAllColumns(dbConnector, tableName);

        for (String columnName : allColumns) {
            String fullColumnName = tableName + "." + columnName;

            EnhancedColumnStatistics colStats = extractColumnStatistics(
                    dbConnector, tableName, columnName, fullColumnName, columnTypes.get(columnName));

            columnStats.put(fullColumnName, colStats);
        }

        tableStats.setColumns(columnStats);

        return tableStats;
    }

    /**
     * 提取单个列的增强统计信息
     */
    private EnhancedColumnStatistics extractColumnStatistics(DbConnector dbConnector,
            String tableName, String columnName,
            String fullColumnName, String dataType) throws SQLException {
        EnhancedColumnStatistics stats = new EnhancedColumnStatistics();
        stats.setColumnName(fullColumnName);
        stats.setDataType(dataType);
        stats.setTableName(tableName);
        stats.setShortColumnName(columnName);


        logger.debug("检查列 {} 是否为主键，tableName={}, columnName={}", fullColumnName, tableName, columnName);
        // 尝试不同的格式
        boolean isPK = isPrimaryKey(fullColumnName);
        if (!isPK) {
            // 如果完整格式不匹配，尝试其他可能的格式
            String alternativeFormat = tableName + "." + columnName;
            isPK = isPrimaryKey(alternativeFormat);
            logger.debug("尝试替代格式 {} 检查主键: {}", alternativeFormat, isPK);
        }

        // 检查是否为主键或外键
        stats.setPrimaryKey(isPK);
        // stats.setPrimaryKey(isPrimaryKey(fullColumnName));
        stats.setForeignKey(isForeignKey(fullColumnName));

        // 从pg_stats获取基础统计信息
        extractBasicStatistics(dbConnector, tableName, columnName, stats);

        // 提取范围信息
        extractRangeInformation(dbConnector, tableName, columnName, stats);

        return stats;
    }

    /**
     * 从pg_stats提取基础统计信息，对于主外键列使用增强提取
     */
    private void extractBasicStatistics(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) throws SQLException {
        
        // 首先尝试从pg_stats获取统计信息
        boolean hasStatsData = extractFromPgStats(dbConnector, tableName, columnName, stats);
        
        // 如果统计信息为空，先针对布尔列做专门提取
        if (!hasStatsData) {
            logger.info("列 {} 没有统计信息，尝试专门针对布尔列做频率提取", stats.getColumnName());
            if ("bool".equalsIgnoreCase(stats.getDataType()) || "boolean".equalsIgnoreCase(stats.getDataType())) {
                extractBooleanStatistics(dbConnector, tableName, columnName, stats);
                return; // 已完成该列提取
            }
        }

        // 如果是主键或外键且没有统计数据，使用增强提取
        if (!hasStatsData && (stats.isPrimaryKey() || stats.isForeignKey())) {
            logger.info("为主外键列 {} 使用增强统计信息提取", stats.getColumnName());
            extractEnhancedKeyStatistics(dbConnector, tableName, columnName, stats);
        }
        
        // 如果仍然没有统计数据，使用基础分析
        if (stats.getNDistinct() == 0.0 && (stats.getMostCommonValues() == null || stats.getMostCommonValues().isEmpty())) {
            logger.debug("为列 {} 使用基础数据分析", stats.getColumnName());
            extractBasicColumnAnalysis(dbConnector, tableName, columnName, stats);
        }
    }
    
    /**
     * 从pg_stats提取统计信息
     */
    private boolean extractFromPgStats(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) throws SQLException {
        String sql = """
                SELECT
                    n_distinct, null_frac, avg_width,
                    most_common_vals, most_common_freqs,
                    histogram_bounds
                FROM pg_stats
                WHERE schemaname = ? AND tablename = ? AND attname = ?
                """;

        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
            String[] tableParts = tableName.split("\\.");
            stmt.setString(1, tableParts[0]); // schema
            stmt.setString(2, tableParts[1]); // table
            stmt.setString(3, columnName);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                logger.info("处理表 {} 的列 {}", tableName, columnName);
                // 安全地获取统计数据，处理NULL值
                double nDistinct = rs.getDouble("n_distinct");
                stats.setNDistinct(rs.wasNull() ? 0.0 : nDistinct);

                double nullFrac = rs.getDouble("null_frac");
                stats.setNullFraction(rs.wasNull() ? 0.0 : nullFrac);

                int avgWidth = rs.getInt("avg_width");
                stats.setAvgWidth(rs.wasNull() ? 0 : avgWidth);

                // 解析MCV数据
                String mcvArray = rs.getString("most_common_vals");
                String mcfArray = rs.getString("most_common_freqs");
                if (mcvArray != null && mcfArray != null && !mcvArray.trim().isEmpty() && !mcfArray.trim().isEmpty()) {
                    try {
                        logger.debug("解析表 {} 列 {} 的MCV数据", tableName, columnName);
                        logger.debug("MCV数组: {}", mcvArray);
                        logger.debug("MCF数组: {}", mcfArray);

                        List<String> mcvValues = parsePostgreSQLArray(mcvArray);
                        List<Double> mcvFrequencies = parseFrequencyArray(mcfArray);
                        
                        // 对于KingBase的date类型，截断时间部分
                        if ("date".equals(stats.getDataType()) && dbConnector instanceof ruc.db.dbconnector.adapter.KingBaseConnector) {
                            mcvValues = mcvValues.stream()
                                .map(value -> value == null ? null : value.substring(0, 10))
                                .collect(java.util.stream.Collectors.toList());
                            logger.debug("对KingBase的date类型列 {} 的MCV值截断时间部分", columnName);
                        }
                        // 对于bpchar类型，去除MCV值的尾部空格
                        if ("bpchar".equals(stats.getDataType())) {
                            mcvValues = mcvValues.stream()
                                .map(value -> value == null ? null : value.replaceAll("\\s+$", ""))
                                .collect(java.util.stream.Collectors.toList());
                            logger.debug("对bpchar类型列 {} 的MCV值去除尾部空格", columnName);
                        }
                        
                        stats.setMostCommonValues(mcvValues);
                        stats.setMostCommonFrequencies(mcvFrequencies);
                        stats.setMcvCount(mcvValues.size()); // 设置MCV数量

                        logger.debug("成功解析了 {} 个MCV值和 {} 个频率",
                                mcvValues.size(), mcvFrequencies.size());
                    } catch (Exception e) {
                        logger.warn("解析表 {} 列 {} 的MCV数据失败: {}", tableName, columnName, e.getMessage());
                        stats.setMostCommonValues(new ArrayList<>());
                        stats.setMostCommonFrequencies(new ArrayList<>());
                        stats.setMcvCount(0); // 设置MCV数量为0
                    }
                } else {
                    logger.debug("表 {} 列 {} 没有MCV数据", tableName, columnName);
                    stats.setMostCommonValues(new ArrayList<>());
                    stats.setMostCommonFrequencies(new ArrayList<>());
                    stats.setMcvCount(0); // 设置MCV数量为0
                }

                // 解析直方图边界
                String histogramArray = rs.getString("histogram_bounds");
                if (histogramArray != null && !histogramArray.trim().isEmpty()) {
                    try {
                        List<String> histogramBounds = parsePostgreSQLArray(histogramArray);
                        
                        // 对于KingBase的date类型，截断时间部分
                        if ("date".equals(stats.getDataType()) && dbConnector instanceof ruc.db.dbconnector.adapter.KingBaseConnector) {
                            histogramBounds = histogramBounds.stream()
                                .map(value -> value == null ? null : value.substring(0, 10))
                                .collect(java.util.stream.Collectors.toList());
                            logger.debug("对KingBase的date类型列 {} 的直方图边界截断时间部分", columnName);
                        }
                        // 对于bpchar类型，去除直方图边界值的尾部空格
                        if ("bpchar".equals(stats.getDataType())) {
                            histogramBounds = histogramBounds.stream()
                                .map(value -> value == null ? null : value.replaceAll("\\s+$", ""))
                                .collect(java.util.stream.Collectors.toList());
                            logger.debug("对bpchar类型列 {} 的直方图边界去除尾部空格", columnName);
                        }
                        
                        stats.setHistogramBounds(histogramBounds);
                        stats.setHistogramBoundsCount(histogramBounds.size()); // 设置直方图边界数量
                    } catch (Exception e) {
                        logger.debug("解析直方图数据失败 {}: {}", stats.getColumnName(), e.getMessage());
                        stats.setHistogramBounds(new ArrayList<>());
                        stats.setHistogramBoundsCount(0);
                    }
                } else {
                    stats.setHistogramBounds(new ArrayList<>());
                    stats.setHistogramBoundsCount(0);
                }
                return true; // 成功获取到统计数据
            } else {
                return false; // 没有找到统计数据
            }
        } catch (SQLException e) {
            logger.warn("获取列 {} 的pg_stats失败: {}", stats.getColumnName(), e.getMessage());
            return false;
        }
    }
    
    /**
     * 为主外键列提取增强统计信息
     */
    private void extractEnhancedKeyStatistics(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) throws SQLException {
        
        // 获取表的大小
        long tableSize = getTableRowCount(dbConnector, tableName);
        
        // 主外键上的布尔列：优先按布尔专用提取（pg_stats常为空）
        if ("bool".equalsIgnoreCase(stats.getDataType()) || "boolean".equalsIgnoreCase(stats.getDataType())) {
            extractBooleanStatistics(dbConnector, tableName, columnName, stats);
            return;
        }

        if (stats.isPrimaryKey()) {
            // 主键统计信息：通常是唯一的
            extractPrimaryKeyStatistics(dbConnector, tableName, columnName, stats, tableSize);
        } else if (stats.isForeignKey()) {
            // 外键统计信息：需要分析引用分布
            extractForeignKeyStatistics(dbConnector, tableName, columnName, stats, tableSize);
        }
    }

    /**
     * 专门提取布尔列统计信息：优先pg_stats的MCV/频率；否则精确统计true/false比例
     */
    private void extractBooleanStatistics(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) {
        List<String> mcvValues = new ArrayList<>();
        List<Double> mcvFreqs = new ArrayList<>();
        boolean filled = false;

        try {
            String[] parts = tableName.split("\\.");
            String pgSql = "SELECT most_common_vals, most_common_freqs FROM pg_stats WHERE schemaname=? AND tablename=? AND attname=?";
            try (PreparedStatement st = dbConnector.getConnection().prepareStatement(pgSql)) {
                st.setString(1, parts[0]);
                st.setString(2, parts[1]);
                st.setString(3, columnName);
                ResultSet prs = st.executeQuery();
                if (prs.next()) {
                    String vals = prs.getString("most_common_vals");
                    String freqs = prs.getString("most_common_freqs");
                    if (vals != null && freqs != null && !vals.isEmpty() && !freqs.isEmpty()) {
                        List<String> vs = parsePostgreSQLArray(vals);
                        List<Double> fs = parseFrequencyArray(freqs);
                        for (int i = 0; i < vs.size() && i < fs.size(); i++) {
                            String v = vs.get(i) == null ? null : vs.get(i).trim().toLowerCase();
                            if (v == null) continue;
                            if ("t".equals(v) || "true".equals(v)) { mcvValues.add("true"); mcvFreqs.add(fs.get(i)); }
                            else if ("f".equals(v) || "false".equals(v)) { mcvValues.add("false"); mcvFreqs.add(fs.get(i)); }
                        }
                        if (!mcvValues.isEmpty() && mcvValues.size() == mcvFreqs.size()) {
                            filled = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("读取pg_stats布尔MCV失败 {}.{}: {}", tableName, columnName, e.getMessage());
        }

        if (!filled) {
            // 兜底：精确统计比例
            try {
                String[] parts = tableName.split("\\.");
                String countSql = String.format(
                    "SELECT COUNT(*) FILTER (WHERE %s IS TRUE) AS t_cnt, COUNT(*) FILTER (WHERE %s IS FALSE) AS f_cnt, COUNT(*) AS total FROM %s.%s",
                    columnName, columnName, parts[0], parts[1]);
                try (PreparedStatement st = dbConnector.getConnection().prepareStatement(countSql)) {
                    ResultSet rs2 = st.executeQuery();
                    if (rs2.next()) {
                        long t = rs2.getLong("t_cnt");
                        long f = rs2.getLong("f_cnt");
                        long total = rs2.getLong("total");
                        if (total > 0) {
                            mcvValues.add("true"); mcvFreqs.add((double) t / total);
                            mcvValues.add("false"); mcvFreqs.add((double) f / total);
                            filled = true;
                        }
                        stats.setNDistinct(2.0 / Math.max(1.0, total) * (-1)); // 负号语义：近似unique比率
                        stats.setNullFraction(0.0);
                    }
                }
            } catch (SQLException ex) {
                logger.warn("统计布尔列 {}.{} 频率失败: {}", tableName, columnName, ex.getMessage());
            }
        }

        if (filled) {
            stats.setMostCommonValues(mcvValues);
            stats.setMostCommonFrequencies(mcvFreqs);
            stats.setMcvCount(mcvValues.size());
        } else {
            stats.setMostCommonValues(new ArrayList<>());
            stats.setMostCommonFrequencies(new ArrayList<>());
            stats.setMcvCount(0);
        }
        stats.setHistogramBounds(new ArrayList<>());
        stats.setHistogramBoundsCount(0);
    }
    
    /**
     * 提取主键列的统计信息
     */
    private void extractPrimaryKeyStatistics(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats, long tableSize) throws SQLException {
        
        String[] tableParts = tableName.split("\\.");
        String sql = String.format("""
                SELECT 
                    COUNT(DISTINCT %s) as distinct_count,
                    COUNT(*) as total_count,
                    MIN(%s) as min_val,
                    MAX(%s) as max_val,
                    COUNT(CASE WHEN %s IS NULL THEN 1 END) as null_count
                FROM %s.%s
                """, columnName, columnName, columnName, columnName, tableParts[0], tableParts[1]);
        
        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long distinctCount = rs.getLong("distinct_count");
                long totalCount = rs.getLong("total_count");
                long nullCount = rs.getLong("null_count");
                
                // 主键的ndistinct通常等于行数（唯一性）
                stats.setNDistinct(totalCount > 0 ? (double) distinctCount / totalCount * (-1) : -1.0);
                stats.setNullFraction(totalCount > 0 ? (double) nullCount / totalCount : 0.0);
                
                // 设置范围信息
                Object minVal = rs.getObject("min_val");
                Object maxVal = rs.getObject("max_val");
                if (minVal != null) {
                    stats.setMinValue(minVal.toString());
                }
                if (maxVal != null) {
                    stats.setMaxValue(maxVal.toString());
                }
                
                logger.info("主键列 {} 统计: distinct={}, total={}, ndistinct={}", 
                        stats.getColumnName(), distinctCount, totalCount, stats.getNDistinct());
            }
        } catch (SQLException e) {
            logger.warn("提取主键列 {} 统计信息失败: {}", stats.getColumnName(), e.getMessage());
            // 设置主键默认值
            stats.setNDistinct(-1.0); // 完全唯一
            stats.setNullFraction(0.0); // 主键不允许NULL
        }
        
        // 主键通常没有MCV数据，因为每个值都是唯一的
        stats.setMostCommonValues(new ArrayList<>());
        stats.setMostCommonFrequencies(new ArrayList<>());
        stats.setMcvCount(0);
        stats.setHistogramBounds(new ArrayList<>());
        stats.setHistogramBoundsCount(0);
    }
    
    /**
     * 提取外键列的统计信息
     */
    private void extractForeignKeyStatistics(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats, long tableSize) throws SQLException {
        
        String[] tableParts = tableName.split("\\.");
        
        // 分析外键值的分布
        String sql = String.format("""
                SELECT 
                    COUNT(DISTINCT %s) as distinct_count,
                    COUNT(*) as total_count,
                    MIN(%s) as min_val,
                    MAX(%s) as max_val,
                    COUNT(CASE WHEN %s IS NULL THEN 1 END) as null_count
                FROM %s.%s
                """, columnName, columnName, columnName, columnName, tableParts[0], tableParts[1]);
        
        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long distinctCount = rs.getLong("distinct_count");
                long totalCount = rs.getLong("total_count");
                long nullCount = rs.getLong("null_count");
                
                // 外键的ndistinct通常小于行数
                stats.setNDistinct(totalCount > 0 ? (double) distinctCount / totalCount * (-1) : 0.0);
                stats.setNullFraction(totalCount > 0 ? (double) nullCount / totalCount : 0.0);
                
                // 设置范围信息
                Object minVal = rs.getObject("min_val");
                Object maxVal = rs.getObject("max_val");
                if (minVal != null) {
                    stats.setMinValue(minVal.toString());
                }
                if (maxVal != null) {
                    stats.setMaxValue(maxVal.toString());
                }
                
                logger.info("外键列 {} 统计: distinct={}, total={}, ndistinct={}", 
                        stats.getColumnName(), distinctCount, totalCount, stats.getNDistinct());
            }
        } catch (SQLException e) {
            logger.warn("提取外键列 {} 统计信息失败: {}", stats.getColumnName(), e.getMessage());
            // 设置外键默认值
            stats.setNDistinct(-0.1); // 假设10%的唯一性
            stats.setNullFraction(0.0);
        }
        
        // 尝试获取外键的前几个最常见值
        extractForeignKeyMCV(dbConnector, tableName, columnName, stats);
    }
    
    /**
     * 提取外键的最常见值
     */
    private void extractForeignKeyMCV(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) throws SQLException {
        
        String[] tableParts = tableName.split("\\.");
        String sql = String.format("""
                SELECT %s as value, COUNT(*) as frequency
                FROM %s.%s
                WHERE %s IS NOT NULL
                GROUP BY %s
                ORDER BY COUNT(*) DESC
                LIMIT 10
                """, columnName, tableParts[0], tableParts[1], columnName, columnName);
        
        List<String> mcvValues = new ArrayList<>();
        List<Double> mcvFrequencies = new ArrayList<>();
        long totalCount = getTableRowCount(dbConnector, tableName);
        
        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String value = rs.getString("value");
                long frequency = rs.getLong("frequency");
                
                if (value != null && totalCount > 0) {
                    mcvValues.add(value);
                    mcvFrequencies.add((double) frequency / totalCount);
                }
            }
        } catch (SQLException e) {
            logger.debug("提取外键 {} MCV失败: {}", stats.getColumnName(), e.getMessage());
        }
        
        stats.setMostCommonValues(mcvValues);
        stats.setMostCommonFrequencies(mcvFrequencies);
        stats.setMcvCount(mcvValues.size());
        stats.setHistogramBounds(new ArrayList<>()); // 外键通常不需要直方图
        stats.setHistogramBoundsCount(0);
    }
    
    /**
     * 基础列数据分析（用于没有pg_stats的列）
     */
    private void extractBasicColumnAnalysis(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) throws SQLException {
        
        String[] tableParts = tableName.split("\\.");
        String sql = String.format("""
                SELECT 
                    COUNT(DISTINCT %s) as distinct_count,
                    COUNT(*) as total_count,
                    COUNT(CASE WHEN %s IS NULL THEN 1 END) as null_count
                FROM %s.%s
                """, columnName, columnName, tableParts[0], tableParts[1]);
        
        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long distinctCount = rs.getLong("distinct_count");
                long totalCount = rs.getLong("total_count");
                long nullCount = rs.getLong("null_count");

                stats.setNDistinct(totalCount > 0 ? (double) distinctCount / totalCount * (-1) : 0.0);
                stats.setNullFraction(totalCount > 0 ? (double) nullCount / totalCount : 0.0);

                logger.debug("基础分析列 {} 统计: distinct={}, total={}, ndistinct={}",
                        stats.getColumnName(), distinctCount, totalCount, stats.getNDistinct());
            }
        } catch (SQLException e) {
            logger.warn("基础分析列 {} 失败: {}", stats.getColumnName(), e.getMessage());
            // 设置默认值
            stats.setNDistinct(-0.5);
            stats.setNullFraction(0.0);
        }
        
        // 针对布尔列：从pg_stats读取或兜底统计 true/false 频率，写入MCV
        if ("bool".equalsIgnoreCase(stats.getDataType()) || "boolean".equalsIgnoreCase(stats.getDataType())) {
            List<String> mcvValues = new ArrayList<>();
            List<Double> mcvFreqs = new ArrayList<>();
            boolean filled = false;

            try {
                String[] parts = tableName.split("\\.");
                String pgSql = "SELECT most_common_vals, most_common_freqs FROM pg_stats WHERE schemaname=? AND tablename=? AND attname=?";
                try (PreparedStatement st = dbConnector.getConnection().prepareStatement(pgSql)) {
                    st.setString(1, parts[0]);
                    st.setString(2, parts[1]);
                    st.setString(3, columnName);
                    ResultSet prs = st.executeQuery();
                    if (prs.next()) {
                        String vals = prs.getString("most_common_vals");
                        String freqs = prs.getString("most_common_freqs");
                        if (vals != null && freqs != null && !vals.isEmpty() && !freqs.isEmpty()) {
                            List<String> vs = parsePostgreSQLArray(vals);
                            List<Double> fs = parseFrequencyArray(freqs);
                            // 只保留 true/false
                            for (int i = 0; i < vs.size() && i < fs.size(); i++) {
                                String v = vs.get(i).trim().toLowerCase();
                                if ("t".equals(v) || "true".equals(v)) { mcvValues.add("true"); mcvFreqs.add(fs.get(i)); }
                                else if ("f".equals(v) || "false".equals(v)) { mcvValues.add("false"); mcvFreqs.add(fs.get(i)); }
                            }
                            if (!mcvValues.isEmpty() && mcvValues.size() == mcvFreqs.size()) {
                                filled = true;
                            }
                        }
                    }
                }
            } catch (Exception ignore) {}

            if (!filled) {
                // 兜底：精确统计比例
                try {
                    String[] parts = tableName.split("\\.");
                    String countSql = String.format(
                        "SELECT COUNT(*) FILTER (WHERE %s IS TRUE) AS t_cnt, COUNT(*) FILTER (WHERE %s IS FALSE) AS f_cnt, COUNT(*) AS total FROM %s.%s",
                        columnName, columnName, parts[0], parts[1]);
                    try (PreparedStatement st = dbConnector.getConnection().prepareStatement(countSql)) {
                        ResultSet rs2 = st.executeQuery();
                        if (rs2.next()) {
                            long t = rs2.getLong("t_cnt");
                            long f = rs2.getLong("f_cnt");
                            long total = rs2.getLong("total");
                            if (total > 0) {
                                mcvValues.add("true"); mcvFreqs.add((double) t / total);
                                mcvValues.add("false"); mcvFreqs.add((double) f / total);
                                filled = true;
                            }
                        }
                    }
                } catch (SQLException ex) {
                    logger.warn("统计布尔列 {} true/false 频率失败: {}", stats.getColumnName(), ex.getMessage());
                }
            }

            if (filled) {
                stats.setMostCommonValues(mcvValues);
                stats.setMostCommonFrequencies(mcvFreqs);
                stats.setMcvCount(mcvValues.size());
            } else {
                stats.setMostCommonValues(new ArrayList<>());
                stats.setMostCommonFrequencies(new ArrayList<>());
                stats.setMcvCount(0);
            }
            stats.setHistogramBounds(new ArrayList<>());
            stats.setHistogramBoundsCount(0);
            return;
        }

        stats.setMostCommonValues(new ArrayList<>());
        stats.setMostCommonFrequencies(new ArrayList<>());
        stats.setMcvCount(0);
        stats.setHistogramBounds(new ArrayList<>());
        stats.setHistogramBoundsCount(0);
    }
    
    /**
     * 获取表的行数
     */
    private long getTableRowCount(DbConnector dbConnector, String tableName) throws SQLException {
        String[] tableParts = tableName.split("\\.");
        String sql = String.format("SELECT COUNT(*) FROM %s.%s", tableParts[0], tableParts[1]);
        
        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.warn("获取表 {} 行数失败: {}", tableName, e.getMessage());
        }
        
        // 从TableManager获取缓存的表大小
        try {
            return TableManager.getInstance().getTableSize(tableName);
        } catch (Exception e) {
            logger.warn("从TableManager获取表 {} 大小失败: {}", tableName, e.getMessage());
            return 1000; // 默认值
        }
    }

    /**
     * 获取表的所有列
     */
    private List<String> getAllColumns(DbConnector dbConnector, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();

        String[] tableParts = tableName.split("\\.");
        DatabaseMetaData metaData = dbConnector.getConnection().getMetaData();

        try (ResultSet rs = metaData.getColumns(null, tableParts[0], tableParts[1], null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }

        return columns;
    }

    /**
     * 获取列的数据类型
     */
    private Map<String, String> getColumnTypes(DbConnector dbConnector, String tableName) throws SQLException {
        Map<String, String> columnTypes = new HashMap<>();

        String[] tableParts = tableName.split("\\.");
        DatabaseMetaData metaData = dbConnector.getConnection().getMetaData();

        try (ResultSet rs = metaData.getColumns(null, tableParts[0], tableParts[1], null)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String dataType = rs.getString("TYPE_NAME");
                columnTypes.put(columnName, dataType);
            }
        }

        return columnTypes;
    }

    /**
     * 从系统元数据提取范围信息（支持两种模式：直方图推断和直接SQL查询）
     */
    private void extractRangeInformation(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) {
        try {
            // 根据设置的模式决定范围获取方式
            if (rangeExtractionMode == RangeExtractionMode.DIRECT_SQL_QUERY) {
                // 直接执行SQL查询MIN/MAX
                extractRangeByDirectQuery(dbConnector, tableName, columnName, stats);
            } else {
                // 默认模式：从直方图边界推断
                extractRangeByHistogramBounds(dbConnector, tableName, columnName, stats);
            }

            // 对于VARCHAR/BPCHAR类型，从元数据分析数据模式
            if ("varchar".equals(stats.getDataType()) || "bpchar".equals(stats.getDataType())) {
                String pattern = analyzeStringPatternFromMetadata(dbConnector, tableName, columnName, stats);
                stats.setDataPattern(pattern);
            }

            logger.debug("列 {} 范围信息: MIN={}, MAX={}, Pattern={}",
                    stats.getColumnName(), stats.getMinValue(), stats.getMaxValue(), stats.getDataPattern());

        } catch (Exception e) {
            logger.debug("提取列 {} 范围信息失败: {}", stats.getColumnName(), e.getMessage());
            stats.setMinValue(null);
            stats.setMaxValue(null);
            stats.setDataPattern(null);
        }
    }

    /**
     * 通过直方图边界推断范围信息（原有逻辑）
     */
    private void extractRangeByHistogramBounds(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) {
        try {
            // 首先尝试从直方图边界获取范围信息
            if (stats.getHistogramBounds() != null && !stats.getHistogramBounds().isEmpty()) {
                List<String> bounds = stats.getHistogramBounds();
                stats.setMinValue(bounds.get(0));
                stats.setMaxValue(bounds.get(bounds.size() - 1));
                logger.debug("从直方图获取列 {} 范围信息: MIN={}, MAX={}",
                        stats.getColumnName(), stats.getMinValue(), stats.getMaxValue());
            }
            // 如果没有直方图但有MCV数据，从MCV中推断范围
            else if (stats.getMostCommonValues() != null && !stats.getMostCommonValues().isEmpty()) {
                List<String> mcvs = stats.getMostCommonValues();
                // 对于数值类型，尝试从MCV中找到最小和最大值
                if (isNumericType(stats.getDataType())) {
                    extractRangeFromMCV(mcvs, stats);
                } else {
                    // 对于字符串类型，使用第一个和最后一个MCV作为参考
                    stats.setMinValue(mcvs.get(0));
                    stats.setMaxValue(mcvs.get(mcvs.size() - 1));
                }
                logger.debug("从MCV推断列 {} 范围信息: MIN={}, MAX={}",
                        stats.getColumnName(), stats.getMinValue(), stats.getMaxValue());
            }
            // 如果都没有，则从列定义信息推断
            else {
                extractRangeFromColumnMetadata(dbConnector, tableName, columnName, stats);
            }
        } catch (Exception e) {
            logger.debug("从直方图推断列 {} 范围信息失败: {}", stats.getColumnName(), e.getMessage());
            stats.setMinValue(null);
            stats.setMaxValue(null);
        }
    }

    /**
     * 通过直接SQL查询获取MIN/MAX范围信息
     */
    private void extractRangeByDirectQuery(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) {
        try {
            logger.debug("通过直接SQL查询获取列 {} 的MIN/MAX值", stats.getColumnName());
            
            // 构建查询SQL - 处理schema.table格式的表名
            String quotedColumnName = "\"" + columnName + "\"";
            String quotedTableName;
            
            if (tableName.contains(".")) {
                // 如果包含schema，分别引用schema和table
                String[] parts = tableName.split("\\.");
                quotedTableName = "\"" + parts[0] + "\".\"" + parts[1] + "\"";
            } else {
                // 如果没有schema，直接引用表名
                quotedTableName = "\"" + tableName + "\"";
            }
            
            String sql = String.format("SELECT MIN(%s) as min_val, MAX(%s) as max_val FROM %s", 
                    quotedColumnName, quotedColumnName, quotedTableName);
            
            logger.debug("执行SQL: {}", sql);
            
            try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    Object minVal = rs.getObject("min_val");
                    Object maxVal = rs.getObject("max_val");
                    
                    if (minVal != null) {
                        String minStr = minVal.toString();
                        // 对于KingBase的date类型，截断时间部分
                        if ("date".equals(stats.getDataType()) && dbConnector instanceof ruc.db.dbconnector.adapter.KingBaseConnector) {
                            minStr = minStr.substring(0, 10);
                            logger.debug("对KingBase的date类型列 {} 的MIN值截断时间部分", columnName);
                        }
                        // 对于bpchar类型，去除尾部空格
                        if ("bpchar".equals(stats.getDataType())) {
                            minStr = minStr.replaceAll("\\s+$", "");
                            logger.debug("对bpchar类型列 {} 的MIN值去除尾部空格", columnName);
                        }
                        stats.setMinValue(minStr);
                    }
                    
                    if (maxVal != null) {
                        String maxStr = maxVal.toString();
                        // 对于KingBase的date类型，截断时间部分
                        if ("date".equals(stats.getDataType()) && dbConnector instanceof ruc.db.dbconnector.adapter.KingBaseConnector) {
                            maxStr = maxStr.substring(0, 10);
                            logger.debug("对KingBase的date类型列 {} 的MAX值截断时间部分", columnName);
                        }
                        // 对于bpchar类型，去除尾部空格
                        if ("bpchar".equals(stats.getDataType())) {
                            maxStr = maxStr.replaceAll("\\s+$", "");
                            logger.debug("对bpchar类型列 {} 的MAX值去除尾部空格", columnName);
                        }
                        stats.setMaxValue(maxStr);
                    }
                    
                    logger.debug("直接查询获取列 {} 范围信息: MIN={}, MAX={}",
                            stats.getColumnName(), stats.getMinValue(), stats.getMaxValue());
                } else {
                    logger.debug("直接查询没有返回结果");
                }
            }

        } catch (Exception e) {
            logger.debug("直接SQL查询列 {} 范围信息失败: {}", stats.getColumnName(), e.getMessage());
            // 如果直接查询失败，回退到直方图模式
            logger.debug("回退到直方图模式获取范围信息");
            extractRangeByHistogramBounds(dbConnector, tableName, columnName, stats);
        }
    }

    /**
     * 通过元数据分析字符串数据的模式（避免扫描大表）
     */
    private String analyzeStringPatternFromMetadata(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) {
        try {
            // 获取列的类型信息和约束
            String[] tableParts = tableName.split("\\.");

            // 查询列的详细类型信息
            String typeInfoSql = """
                    SELECT
                        c.character_maximum_length,
                        c.character_octet_length,
                        c.data_type,
                        c.column_default
                    FROM information_schema.columns c
                    WHERE c.table_schema = ? AND c.table_name = ? AND c.column_name = ?
                    """;

            try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(typeInfoSql)) {
                stmt.setString(1, tableParts[0]);
                stmt.setString(2, tableParts[1]);
                stmt.setString(3, columnName);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    Integer maxLength = (Integer) rs.getObject("character_maximum_length");

                    StringBuilder pattern = new StringBuilder();

                    // 基于数据类型构建模式
                    if (maxLength != null) {
                        pattern.append("max_length=").append(maxLength);
                    }

                    // 使用平均宽度作为典型长度
                    if (stats.getAvgWidth() > 0) {
                        pattern.append(",avg_width=").append(stats.getAvgWidth());
                    }

                    // 分析最常见值的模式
                    if (stats.getMostCommonValues() != null && !stats.getMostCommonValues().isEmpty()) {
                        analyzeCommonValuePatterns(stats.getMostCommonValues(), pattern);
                    }

                    return pattern.length() > 0 ? pattern.toString() : "varchar_pattern";
                }
            }
        } catch (SQLException e) {
            logger.debug("通过元数据分析字符串模式失败: {}", e.getMessage());
        }

        // 回退：基于avgWidth估算模式
        if (stats.getAvgWidth() > 0) {
            return String.format("estimated_length=%d", stats.getAvgWidth());
        }

        return "unknown_pattern";
    }

    /**
     * 分析最常见值的模式
     */
    private void analyzeCommonValuePatterns(List<String> commonValues, StringBuilder pattern) {
        if (commonValues.isEmpty())
            return;

        // 分析长度分布
        int minLen = Integer.MAX_VALUE;
        int maxLen = 0;
        int totalLen = 0;

        for (String value : commonValues) {
            if (value != null) {
                int len = value.length();
                minLen = Math.min(minLen, len);
                maxLen = Math.max(maxLen, len);
                totalLen += len;
            }
        }

        if (minLen != Integer.MAX_VALUE) {
            int avgLen = totalLen / commonValues.size();
            if (pattern.length() > 0)
                pattern.append(",");
            pattern.append(String.format("mcv_length_range[%d-%d],mcv_avg=%d", minLen, maxLen, avgLen));
        }
    }

    /**
     * 检查是否为主键
     */
    private boolean isPrimaryKey(String columnName) {
        try {
            return TableManager.getInstance().isPrimaryKey(columnName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否为外键
     */
    private boolean isForeignKey(String columnName) {
        try {
            return TableManager.getInstance().isForeignKey(columnName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否为数值类型
     */
    private boolean isNumericType(String dataType) {
        if (dataType == null)
            return false;
        String type = dataType.toLowerCase();
        return type.contains("int") || type.contains("numeric") || type.contains("decimal") ||
                type.contains("float") || type.contains("double") || type.contains("real") ||
                type.contains("money") || type.contains("serial");
    }

    /**
     * 从MCV中提取数值范围
     */
    private void extractRangeFromMCV(List<String> mcvs, EnhancedColumnStatistics stats) {
        if (mcvs.isEmpty())
            return;

        try {
            double minVal = Double.MAX_VALUE;
            double maxVal = Double.MIN_VALUE;

            for (String mcv : mcvs) {
                if (mcv != null && !mcv.trim().isEmpty()) {
                    try {
                        double val = Double.parseDouble(mcv.trim());
                        minVal = Math.min(minVal, val);
                        maxVal = Math.max(maxVal, val);
                    } catch (NumberFormatException e) {
                        // 忽略非数值的MCV
                    }
                }
            }

            if (minVal != Double.MAX_VALUE && maxVal != Double.MIN_VALUE) {
                stats.setMinValue(String.valueOf(minVal));
                stats.setMaxValue(String.valueOf(maxVal));
            }
        } catch (Exception e) {
            logger.debug("从MCV提取数值范围失败: {}", e.getMessage());
        }
    }

    /**
     * 从列元数据推断范围信息
     */
    private void extractRangeFromColumnMetadata(DbConnector dbConnector, String tableName,
            String columnName, EnhancedColumnStatistics stats) {
        try {
            String[] tableParts = tableName.split("\\.");

            // 查询列的详细信息和约束
            String sql = """
                    SELECT
                        c.data_type,
                        c.character_maximum_length,
                        c.numeric_precision,
                        c.numeric_scale,
                        c.column_default,
                        cc.check_clause
                    FROM information_schema.columns c
                    LEFT JOIN information_schema.check_constraints cc ON cc.constraint_name IN (
                        SELECT constraint_name FROM information_schema.constraint_column_usage
                        WHERE table_schema = c.table_schema AND table_name = c.table_name AND column_name = c.column_name
                    )
                    WHERE c.table_schema = ? AND c.table_name = ? AND c.column_name = ?
                    """;

            try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(sql)) {
                stmt.setString(1, tableParts[0]);
                stmt.setString(2, tableParts[1]);
                stmt.setString(3, columnName);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String dataType = rs.getString("data_type");
                    Integer maxLength = (Integer) rs.getObject("character_maximum_length");
                    Integer precision = (Integer) rs.getObject("numeric_precision");
                    String checkClause = rs.getString("check_clause");

                    // 根据数据类型设置理论范围
                    if (isNumericType(dataType)) {
                        setNumericTypeRange(dataType, precision, stats);
                    } else if ("varchar".equals(dataType) || "bpchar".equals(dataType)) {
                        if (maxLength != null) {
                            // 对于字符串，设置长度信息而不是值范围
                            stats.setDataPattern("max_length=" + maxLength);
                        }
                    }

                    // 如果有CHECK约束，尝试从中提取范围
                    if (checkClause != null) {
                        extractRangeFromCheckConstraint(checkClause, stats);
                    }

                    logger.debug("从元数据推断列 {} 范围: MIN={}, MAX={}",
                            stats.getColumnName(), stats.getMinValue(), stats.getMaxValue());
                }
            }
        } catch (SQLException e) {
            logger.debug("从列元数据推断范围失败: {}", e.getMessage());
        }
    }

    /**
     * 为数值类型设置理论范围
     */
    private void setNumericTypeRange(String dataType, Integer precision, EnhancedColumnStatistics stats) {
        String type = dataType.toLowerCase();

        if (type.contains("smallint")) {
            stats.setMinValue("-32768");
            stats.setMaxValue("32767");
        } else if (type.contains("integer") || type.equals("int") || type.equals("int4")) {
            stats.setMinValue("-2147483648");
            stats.setMaxValue("2147483647");
        } else if (type.contains("bigint") || type.equals("int8")) {
            stats.setMinValue("-9223372036854775808");
            stats.setMaxValue("9223372036854775807");
        } else if (type.contains("serial")) {
            stats.setMinValue("1");
            if (type.contains("bigserial")) {
                stats.setMaxValue("9223372036854775807");
            } else {
                stats.setMaxValue("2147483647");
            }
        } else if (type.contains("numeric") || type.contains("decimal")) {
            // 对于NUMERIC类型，范围取决于精度
            if (precision != null && precision > 0) {
                StringBuilder maxVal = new StringBuilder();
                for (int i = 0; i < precision; i++) {
                    maxVal.append("9");
                }
                stats.setMaxValue(maxVal.toString());
                stats.setMinValue("-" + maxVal.toString());
            }
        }
        // 对于REAL、DOUBLE等浮点型，范围过大，不设置具体值
    }

    /**
     * 从CHECK约束中提取范围信息
     */
    private void extractRangeFromCheckConstraint(String checkClause, EnhancedColumnStatistics stats) {
        if (checkClause == null)
            return;

        try {
            // 简单的模式匹配来提取范围约束
            // 例如: (value >= 0 AND value <= 100)
            Pattern rangePattern = Pattern
                    .compile(".*>= *([\\d.-]+).*<= *([\\d.-]+).*|.*<= *([\\d.-]+).*>= *([\\d.-]+).*");
            Matcher matcher = rangePattern.matcher(checkClause);

            if (matcher.find()) {
                String min1 = matcher.group(1);
                String max1 = matcher.group(2);
                String max2 = matcher.group(3);
                String min2 = matcher.group(4);

                if (min1 != null && max1 != null) {
                    stats.setMinValue(min1);
                    stats.setMaxValue(max1);
                } else if (min2 != null && max2 != null) {
                    stats.setMinValue(min2);
                    stats.setMaxValue(max2);
                }
            }
        } catch (Exception e) {
            logger.debug("从CHECK约束提取范围失败: {}", e.getMessage());
        }
    }

    /**
     * 解析PostgreSQL数组
     */
    private List<String> parsePostgreSQLArray(String arrayStr) {
        if (arrayStr == null || arrayStr.trim().isEmpty()) {
            return new ArrayList<>();
        }

        logger.debug("解析PostgreSQL数组: {}", arrayStr);

        Matcher matcher = PG_ARRAY_PATTERN.matcher(arrayStr);
        if (matcher.find()) {
            String content = matcher.group(1);
            if (content.trim().isEmpty()) {
                return new ArrayList<>();
            }

            List<String> result = new ArrayList<>();

            // 改进的数组分割，处理引号和转义字符
            boolean inQuotes = false;
            StringBuilder current = new StringBuilder();

            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);

                if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                    inQuotes = !inQuotes;
                    current.append(c);
                } else if (c == ',' && !inQuotes) {
                    result.add(current.toString().trim());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }

            // 添加最后一个元素
            if (current.length() > 0) {
                result.add(current.toString().trim());
            }

            // 清理引号
            for (int i = 0; i < result.size(); i++) {
                String item = result.get(i);
                if (item.startsWith("\"") && item.endsWith("\"") && item.length() >= 2) {
                    result.set(i, item.substring(1, item.length() - 1));
                }
            }

            logger.debug("解析结果: {} 个元素", result.size());
            return result;
        }

        logger.warn("无法匹配PostgreSQL数组格式: {}", arrayStr);
        return new ArrayList<>();
    }

    /**
     * 解析频率数组
     */
    private List<Double> parseFrequencyArray(String arrayStr) {
        List<String> stringFreqs = parsePostgreSQLArray(arrayStr);
        List<Double> frequencies = new ArrayList<>();

        for (String freq : stringFreqs) {
            try {
                String trimmedFreq = freq.trim();
                // logger.debug("尝试解析频率值: '{}'", trimmedFreq);
                frequencies.add(Double.parseDouble(trimmedFreq));
            } catch (NumberFormatException e) {
                logger.warn("解析频率值失败: '{}', 错误: {}", freq.trim(), e.getMessage());
            }
        }

        return frequencies;
    }

    /**
     * 增强的表统计信息
     */
    public static class EnhancedTableStatistics {
        private String tableName;
        private long tableSize;
        private Map<String, EnhancedColumnStatistics> columns;

        // Getters and Setters
        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public long getTableSize() {
            return tableSize;
        }

        public void setTableSize(long tableSize) {
            this.tableSize = tableSize;
        }

        public Map<String, EnhancedColumnStatistics> getColumns() {
            return columns;
        }

        public void setColumns(Map<String, EnhancedColumnStatistics> columns) {
            this.columns = columns;
        }
    }

    /**
     * 增强的列统计信息
     */
    public static class EnhancedColumnStatistics {
        private String columnName;
        private String tableName;
        private String shortColumnName;
        private String dataType;
        private boolean isPrimaryKey;
        private boolean isForeignKey;
        private double nDistinct;
        private double nullFraction;
        private int avgWidth;
        private List<String> mostCommonValues;
        private List<Double> mostCommonFrequencies;
        private List<String> histogramBounds;

        // 新增范围信息
        private String minValue;
        private String maxValue;
        private String dataPattern; // 用于VARCHAR类型的数据模式描述
        
        // 新增统计数量信息
        private int mcvCount; // MCV值的数量
        private int histogramBoundsCount; // 直方图边界的数量

        // 默认构造函数，初始化列表
        public EnhancedColumnStatistics() {
            this.mostCommonValues = new ArrayList<>();
            this.mostCommonFrequencies = new ArrayList<>();
            this.histogramBounds = new ArrayList<>();
        }

        // Getters and Setters
        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getShortColumnName() {
            return shortColumnName;
        }

        public void setShortColumnName(String shortColumnName) {
            this.shortColumnName = shortColumnName;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public boolean isPrimaryKey() {
            return isPrimaryKey;
        }

        public void setPrimaryKey(boolean primaryKey) {
            isPrimaryKey = primaryKey;
        }

        public boolean isForeignKey() {
            return isForeignKey;
        }

        public void setForeignKey(boolean foreignKey) {
            isForeignKey = foreignKey;
        }

        public double getNDistinct() {
            return nDistinct;
        }

        public void setNDistinct(double nDistinct) {
            this.nDistinct = nDistinct;
        }

        public double getNullFraction() {
            return nullFraction;
        }

        public void setNullFraction(double nullFraction) {
            this.nullFraction = nullFraction;
        }

        public int getAvgWidth() {
            return avgWidth;
        }

        public void setAvgWidth(int avgWidth) {
            this.avgWidth = avgWidth;
        }

        public List<String> getMostCommonValues() {
            return mostCommonValues;
        }

        public void setMostCommonValues(List<String> mostCommonValues) {
            this.mostCommonValues = mostCommonValues;
        }

        public List<Double> getMostCommonFrequencies() {
            return mostCommonFrequencies;
        }

        public void setMostCommonFrequencies(List<Double> mostCommonFrequencies) {
            this.mostCommonFrequencies = mostCommonFrequencies;
        }

        public List<String> getHistogramBounds() {
            return histogramBounds;
        }

        public void setHistogramBounds(List<String> histogramBounds) {
            this.histogramBounds = histogramBounds;
        }

        // 新增字段的getter/setter
        public String getMinValue() {
            return minValue;
        }

        public void setMinValue(String minValue) {
            this.minValue = minValue;
        }

        public String getMaxValue() {
            return maxValue;
        }

        public void setMaxValue(String maxValue) {
            this.maxValue = maxValue;
        }

        public String getDataPattern() {
            return dataPattern;
        }

        public void setDataPattern(String dataPattern) {
            this.dataPattern = dataPattern;
        }
        
        // MCV和直方图边界数量的getter/setter
        public int getMcvCount() {
            return mcvCount;
        }

        public void setMcvCount(int mcvCount) {
            this.mcvCount = mcvCount;
        }

        public int getHistogramBoundsCount() {
            return histogramBoundsCount;
        }

        public void setHistogramBoundsCount(int histogramBoundsCount) {
            this.histogramBoundsCount = histogramBoundsCount;
        }
    }

    /**
     * 处理列统计信息（包括数据质量分析和清洗）
     */
    private EnhancedColumnStatistics processColumnStatistics(EnhancedColumnStatistics original) {
        String columnName = original.getColumnName();
        
        // 1. 数据质量分析（记录各种问题）
        dataQualityAnalyzer.analyzeColumnDataQuality(original);
        
        // 2. 检测是否需要数值型字符串清洗
        NumericStringDetector.DetectionResult detection = 
            numericStringDetector.detectNumericStringColumn(original);
        
        if (detection.isNumericString()) {
            logger.info("🧹 开始清洗数值型字符串列: {}", columnName);
            
            try {
                // 3. 执行清洗
                NumericStringDataCleaner.CleanedStatistics cleaned = 
                    numericStringCleaner.cleanNumericStringData(original);
                
                // 4. 评估清洗效果
                NumericStringDataCleaner.CleaningReport report = cleaned.getQualityReport();
                logCleaningResult(columnName, report);
                
                if (report.shouldApplyCleaning()) {
                    logger.info("✅ 列 {} 清洗成功应用", columnName);
                    return cleaned.getCleanedStatistics();
                } else {
                    logger.warn("❌ 列 {} 清洗质量不达标，保持原始数据", columnName);
                    return original;
                }
                
            } catch (Exception e) {
                logger.error("💥 列 {} 清洗过程出错: {}", columnName, e.getMessage(), e);
                return original;
            }
        }
        
        return original;
    }
    
    /**
     * 记录清洗结果日志
     */
    private void logCleaningResult(String columnName, NumericStringDataCleaner.CleaningReport report) {
        logger.info("📋 列 {} 清洗报告:", columnName);
        logger.info("   保留率: {}% ({}/{})", 
                   report.getRetentionRate() * 100,
                   report.getCleanedCount(), 
                   report.getOriginalCount());
        logger.info("   一致性: {}%", report.getConsistencyRate() * 100);
        logger.info("   主导长度: {} 位", report.getDominantLength());
        if (report.getDominantPrefix() != null) {
            logger.info("   主导前缀: {}", report.getDominantPrefix());
        }
        logger.info("   清洗操作: {}", report.getCleaningActions());
    }

    /**
     * 处理数据质量分析和清洗
     */
    private Map<String, EnhancedTableStatistics> processDataQualityAndCleaning(
            Map<String, EnhancedTableStatistics> originalStats) {
        
        Map<String, EnhancedTableStatistics> processedStats = new HashMap<>();
        
        for (Map.Entry<String, EnhancedTableStatistics> tableEntry : originalStats.entrySet()) {
            String tableName = tableEntry.getKey();
            EnhancedTableStatistics tableStats = tableEntry.getValue();
            
            logger.debug("处理表 {} 的数据质量", tableName);
            
            // 处理该表的所有列
            Map<String, EnhancedColumnStatistics> processedColumns = new HashMap<>();
            
            for (Map.Entry<String, EnhancedColumnStatistics> columnEntry : tableStats.getColumns().entrySet()) {
                String columnKey = columnEntry.getKey();
                EnhancedColumnStatistics originalColumnStats = columnEntry.getValue();
                
                // 处理单个列的统计信息（包括质量分析和清洗）
                EnhancedColumnStatistics processedColumnStats = processColumnStatistics(originalColumnStats);
                
                processedColumns.put(columnKey, processedColumnStats);
            }
            
            // 创建新的表统计信息
            EnhancedTableStatistics processedTableStats = new EnhancedTableStatistics();
            processedTableStats.setTableName(tableStats.getTableName());
            processedTableStats.setTableSize(tableStats.getTableSize());
            processedTableStats.setColumns(processedColumns);
            
            processedStats.put(tableName, processedTableStats);
        }
        
        return processedStats;
    }

    /**
     * 检测分区表关系
     * 
     * @param dbConnector 数据库连接器
     * @throws SQLException SQL异常
     */
    private void detectPartitionTables(DbConnector dbConnector) throws SQLException {
        logger.info("开始检测分区表关系");
        
        // 清空现有的分区关系
        PartitionTableManager.getInstance().clear();
        PartitionTreeManager.getInstance().clear();
        
        // 获取配置中的所有schema
        List<String> schemas = getConfiguredSchemas(dbConnector);
        
        for (String schema : schemas) {
            logger.info("检测schema {} 中的分区表", schema);
            detectPartitionTablesInSchema(dbConnector, schema);
        }
        
        // 重新分析节点类型（在所有分区关系添加完毕后）
        PartitionTreeManager.getInstance().finalizeNodeTypes();
        
        // 打印检测结果
        PartitionTableManager.getInstance().printPartitionInfo();
        PartitionTreeManager.getInstance().printPartitionTrees();
        
        logger.info("分区表关系检测完成");
    }
    
    /**
     * 检测指定schema中的分区表
     * 
     * @param dbConnector 数据库连接器
     * @param schema schema名称
     * @throws SQLException SQL异常
     */
    private void detectPartitionTablesInSchema(DbConnector dbConnector, String schema) throws SQLException {
        // 查询分区表的SQL - 使用更通用的方法，兼容KingBase
        String partitionTableQuery = """
            SELECT c.relname AS table_name
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ? 
            AND c.relkind = 'p'
            """;
        
        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(partitionTableQuery)) {
            stmt.setString(1, schema);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String partitionTableName = schema + "." + rs.getString("table_name");
                    logger.info("发现分区表: {}", partitionTableName);
                    
                    // 查询该分区表的所有子表
                    detectChildTablesForPartition(dbConnector, schema, partitionTableName);
                }
            }
        }
    }
    
    /**
     * 检测分区表的所有子表
     * 
     * @param dbConnector 数据库连接器
     * @param schema schema名称
     * @param partitionTableName 分区表名（包含schema）
     * @throws SQLException SQL异常
     */
    private void detectChildTablesForPartition(DbConnector dbConnector, String schema, String partitionTableName) throws SQLException {
        // 获取分区表的简单表名（不含schema）
        String simplePartitionTableName = partitionTableName.substring(partitionTableName.indexOf('.') + 1);
        
        // 查询子表的SQL
        String childTablesQuery = """
            SELECT c.relname AS child_table_name
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE p.relname = ? AND n.nspname = ?
            """;
        
        try (PreparedStatement stmt = dbConnector.getConnection().prepareStatement(childTablesQuery)) {
            stmt.setString(1, simplePartitionTableName);
            stmt.setString(2, schema);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String childTableName = schema + "." + rs.getString("child_table_name");
                    logger.info("发现子表: {} -> 父表: {}", childTableName, partitionTableName);
                    
                    // 添加分区关系（同时维护新旧两个管理器）
                    PartitionTableManager.getInstance().addPartitionRelation(partitionTableName, childTableName);
                    PartitionTreeManager.getInstance().addPartitionRelation(partitionTableName, childTableName);
                }
            }
        }
    }
    
    /**
     * 获取配置中的schema列表
     * 
     * @param dbConnector 数据库连接器
     * @return schema列表
     */
    private List<String> getConfiguredSchemas(DbConnector dbConnector) {
        // 从数据库连接器的配置中获取schema列表
        List<String> schemas = new ArrayList<>();
        
        try {
            // 使用反射获取protected的getConfig方法
            java.lang.reflect.Method getConfigMethod = DbConnector.class.getDeclaredMethod("getConfig");
            getConfigMethod.setAccessible(true);
            Object config = getConfigMethod.invoke(dbConnector);
            
            if (config != null) {
                java.lang.reflect.Method getSchemasMethod = config.getClass().getMethod("getSchemas");
                String[] schemaArray = (String[]) getSchemasMethod.invoke(config);
                if (schemaArray != null) {
                    for (String schema : schemaArray) {
                        schemas.add(schema);
                    }
                }
            }
            
            // 如果没有获取到schema，使用默认的public schema
            if (schemas.isEmpty()) {
                schemas.add("public");
            }
        } catch (Exception e) {
            logger.warn("获取配置的schema列表失败，使用默认schema: {}", e.getMessage());
            schemas.add("public");
        }
        
        logger.info("将检测以下schema中的分区表: {}", schemas);
        return schemas;
    }

    /**
     * 按表类型分组：根分区表、普通表、中间分区表、叶子表
     * 使用新的PartitionTreeManager进行三阶段处理
     */
    private Map<String, List<String>> groupTablesByType(List<String> tableNames) {
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        
        // 为所有表创建节点（如果还不存在的话）
        for (String tableName : tableNames) {
            if (!treeManager.containsTable(tableName)) {
                // 这些表还没有被检测为分区表的一部分，所以它们是普通表
                treeManager.getOrCreateNode(tableName); // 这会创建一个NORMAL类型的节点
            }
        }
        
        // 使用PartitionTreeManager的内置分组方法
        Map<String, List<String>> phaseGroups = treeManager.groupTablesByPhase();
        
        // 将phase分组转换为我们需要的格式
        Map<String, List<String>> groups = new HashMap<>();
        groups.put("priority", phaseGroups.get("phase1"));      // 第一阶段：普通表 + 根分区表
        groups.put("intermediate", phaseGroups.get("phase2"));  // 第二阶段：中间分区表
        groups.put("leaf", phaseGroups.get("phase3"));          // 第三阶段：叶子表
        
        // 按名称排序以保证确定性
        groups.get("priority").sort(String::compareTo);
        groups.get("intermediate").sort(String::compareTo);
        groups.get("leaf").sort(String::compareTo);
        
        logger.info("表分类完成: {} 个优先表(普通表+根分区表), {} 个中间分区表, {} 个叶子表", 
                   groups.get("priority").size(), 
                   groups.get("intermediate").size(), 
                   groups.get("leaf").size());

        return groups;
    }
    
    /**
     * 生成按类型分组的表信息JSON文件
     * 
     * @param outputDir 输出目录
     * @throws IOException IO异常
     */
    private void generateTableTypeSummary(String outputDir, Map<String, EnhancedTableStatistics> enhancedStats) throws IOException {
        logger.info("开始生成按类型分组的表信息");
        
        PartitionTreeManager treeManager = PartitionTreeManager.getInstance();
        TableManager tableManager = TableManager.getInstance();
        
        // 按类型分组的表信息
        Map<String, Object> tableTypeSummary = new HashMap<>();
        
        // 普通表
        List<String> normalTables = treeManager.getAllNormalTables();
        Map<String, Object> normalTableInfo = new HashMap<>();
        for (String tableName : normalTables) {
            try {
                Table table = tableManager.getSchema(tableName);
                if (table != null) {
                    Map<String, Object> tableInfo = new HashMap<>();
                    tableInfo.put("tableSize", table.getTableSize());
                    tableInfo.put("type", table.getType());
                    normalTableInfo.put(tableName, tableInfo);
                }
            } catch (Exception e) {
                logger.warn("获取普通表 {} 的schema信息失败: {}", tableName, e.getMessage());
            }
        }
        tableTypeSummary.put("normalTables", normalTableInfo);
        
        // 根分区表
        List<String> rootTables = treeManager.getAllRootPartitionTables();
        Map<String, Object> rootTableInfo = new HashMap<>();
        for (String tableName : rootTables) {
            try {
                Table table = tableManager.getSchema(tableName);
                if (table != null) {
                    Map<String, Object> tableInfo = new HashMap<>();
                    tableInfo.put("tableSize", table.getTableSize());
                    tableInfo.put("type", table.getType());
                    rootTableInfo.put(tableName, tableInfo);
                }
            } catch (Exception e) {
                logger.warn("获取根分区表 {} 的schema信息失败: {}", tableName, e.getMessage());
            }
        }
        tableTypeSummary.put("rootTables", rootTableInfo);
        
        // 中间分区表
        List<String> intermediateTables = treeManager.getAllIntermediatePartitionTables();
        Map<String, Object> intermediateTableInfo = new HashMap<>();
        for (String tableName : intermediateTables) {
            try {
                Table table = tableManager.getSchema(tableName);
                if (table != null) {
                    Map<String, Object> tableInfo = new HashMap<>();
                    tableInfo.put("tableSize", table.getTableSize());
                    tableInfo.put("type", table.getType());
                    intermediateTableInfo.put(tableName, tableInfo);
                }
            } catch (Exception e) {
                logger.warn("获取中间分区表 {} 的schema信息失败: {}", tableName, e.getMessage());
            }
        }
        tableTypeSummary.put("intermediateTables", intermediateTableInfo);
        
        // 叶子分区表
        List<String> leafTables = treeManager.getAllLeafTables();
        Map<String, Object> leafTableInfo = new HashMap<>();
        for (String tableName : leafTables) {
            try {
                Table table = tableManager.getSchema(tableName);
                if (table != null) {
                    Map<String, Object> tableInfo = new HashMap<>();
                    tableInfo.put("tableSize", table.getTableSize());
                    tableInfo.put("type", table.getType());
                    leafTableInfo.put(tableName, tableInfo);
                }
            } catch (Exception e) {
                logger.warn("获取叶子分区表 {} 的schema信息失败: {}", tableName, e.getMessage());
            }
        }
        tableTypeSummary.put("leafTables", leafTableInfo);
        
        // 统计信息
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalTables", normalTables.size() + rootTables.size() + intermediateTables.size() + leafTables.size());
        summary.put("normalTableCount", normalTables.size());
        summary.put("rootTableCount", rootTables.size());
        summary.put("intermediateTableCount", intermediateTables.size());
        summary.put("leafTableCount", leafTables.size());
        tableTypeSummary.put("summary", summary);
        
        // 保存到JSON文件
        File tableTypeFile = new File(outputDir, "table_type_summary.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tableTypeFile, tableTypeSummary);
        
        logger.info("按类型分组的表信息已保存到: {}", tableTypeFile.getAbsolutePath());
        logger.info("表类型统计: 普通表{}个, 根分区表{}个, 中间分区表{}个, 叶子分区表{}个", 
                   normalTables.size(), rootTables.size(), intermediateTables.size(), leafTables.size());
        
        // 生成提取阶段的详细报告
        generateExtractionReport(outputDir, normalTables, rootTables, intermediateTables, leafTables, enhancedStats);
    }
    
    /**
     * 生成提取阶段的详细报告
     */
    private void generateExtractionReport(String outputDir, 
                                        List<String> normalTables, 
                                        List<String> rootTables, 
                                        List<String> intermediateTables, 
                                        List<String> leafTables,
                                        Map<String, EnhancedTableStatistics> tableStats) throws IOException {
        File reportFile = new File(outputDir, "extraction_report.txt");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile, StandardCharsets.UTF_8))) {
            writer.write("RSGen 统计信息提取报告\n");
            writer.write("=".repeat(50) + "\n");
            writer.write("提取时间: " + java.time.LocalDateTime.now() + "\n\n");
            
            // 1. 总体统计
            writer.write("1. 提取总体统计:\n");
            writer.write("  - 总提取表数: " + (normalTables.size() + rootTables.size() + intermediateTables.size() + leafTables.size()) + " 个\n");
            writer.write("  - 普通表: " + normalTables.size() + " 个\n");
            writer.write("  - 根分区表: " + rootTables.size() + " 个\n");
            writer.write("  - 中间分区表: " + intermediateTables.size() + " 个\n");
            writer.write("  - 叶子分区表: " + leafTables.size() + " 个\n\n");
            
            // 2. 有效表统计（有统计信息的表）
            int validNormalTables = 0;
            int zeroSizeNormalTables = 0;
            int noStatsNormalTables = 0;
            int validRootTables = 0;
            int noStatsRootTables = 0;
            int validIntermediateTables = 0;
            int noStatsIntermediateTables = 0;
            int validLeafTables = 0;
            int zeroSizeLeafTables = 0;
            int noStatsLeafTables = 0;
            
            for (String tableName : normalTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    validNormalTables++;
                    if (stats.getTableSize() == 0) {
                        zeroSizeNormalTables++;
                    }
                } else {
                    noStatsNormalTables++;
                }
            }
            
            for (String tableName : rootTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    validRootTables++;
                } else {
                    noStatsRootTables++;
                }
            }
            
            for (String tableName : intermediateTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    validIntermediateTables++;
                } else {
                    noStatsIntermediateTables++;
                }
            }
            
            for (String tableName : leafTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    validLeafTables++;
                    if (stats.getTableSize() == 0) {
                        zeroSizeLeafTables++;
                    }
                } else {
                    noStatsLeafTables++;
                }
            }
            
            writer.write("2. 有效统计信息表统计:\n");
            writer.write("  - 普通表详细分类:\n");
            writer.write("    - 有统计信息的普通表: " + validNormalTables + " 个\n");
            writer.write("    - 其中零大小的普通表: " + zeroSizeNormalTables + " 个\n");
            writer.write("    - 无统计信息的普通表: " + noStatsNormalTables + " 个\n");
            writer.write("  - 根分区表详细分类:\n");
            writer.write("    - 有统计信息的根分区表: " + validRootTables + " 个\n");
            writer.write("    - 无统计信息的根分区表: " + noStatsRootTables + " 个\n");
            writer.write("  - 中间分区表详细分类:\n");
            writer.write("    - 有统计信息的中间分区表: " + validIntermediateTables + " 个\n");
            writer.write("    - 无统计信息的中间分区表: " + noStatsIntermediateTables + " 个\n");
            writer.write("  - 叶子分区表详细分类:\n");
            writer.write("    - 有统计信息的叶子分区表: " + validLeafTables + " 个\n");
            writer.write("    - 其中零大小的叶子表: " + zeroSizeLeafTables + " 个\n");
            writer.write("    - 无统计信息的叶子表: " + noStatsLeafTables + " 个\n\n");
            
            // 3. Schema生成统计
            writer.write("3. Schema生成统计:\n");
            TableManager tableManager = TableManager.getInstance();
            int schemaTableCount = tableManager.getSchemas().size();
            int totalExtractedTables = tableStats.size();
            int notWrittenToSchema = totalExtractedTables - schemaTableCount;
            writer.write("  - 总提取表数: " + totalExtractedTables + " 个\n");
            writer.write("  - 写入schema.json的表数: " + schemaTableCount + " 个\n");
            if (notWrittenToSchema > 0) {
                writer.write("  - 提取但未写入schema的表数: " + notWrittenToSchema + " 个\n");
                writer.write("    (原因: 可能是系统表、视图或权限不足的表)\n");
            } else if (notWrittenToSchema < 0) {
                writer.write("  - 写入schema但未提取的表数: " + (-notWrittenToSchema) + " 个\n");
                writer.write("    (原因: 可能是重复计算或统计错误)\n");
            } else {
                writer.write("  - 提取和写入schema的表数一致\n");
            }
            writer.write("\n");
            
            // 4. 详细表信息
            writer.write("4. 各类型表详细信息:\n");
            writer.write("-".repeat(50) + "\n");
            
            writer.write("普通表 (" + normalTables.size() + " 个):\n");
            for (String tableName : normalTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    writer.write("  " + tableName + " - " + stats.getTableSize() + " 行\n");
                } else {
                    // 优先根据表大小为0的原因进行说明
                    try {
                        Table table = TableManager.getInstance().getSchema(tableName);
                        if (table != null) {
                            long size = table.getTableSize();
                            if (size == 0) {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小为0）\n");
                            } else {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小不为0但统计信息为空）\n");
                            }
                        } else {
                            writer.write("  " + tableName + " - 无统计信息（原因: schema中无该表记录，可能为系统表/视图/无权限）\n");
                        }
                    } catch (Exception e) {
                        writer.write("  " + tableName + " - 无统计信息（原因: 无法获取schema信息）\n");
                    }
                }
            }
            writer.write("\n");
            
            writer.write("根分区表 (" + rootTables.size() + " 个):\n");
            for (String tableName : rootTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    writer.write("  " + tableName + " - " + stats.getTableSize() + " 行\n");
                } else {
                    try {
                        Table table = TableManager.getInstance().getSchema(tableName);
                        if (table != null) {
                            long size = table.getTableSize();
                            if (size == 0) {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小为0）\n");
                            } else {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小不为0但统计信息为空）\n");
                            }
                        } else {
                            writer.write("  " + tableName + " - 无统计信息（原因: schema中无该表记录，可能为系统表/视图/无权限）\n");
                        }
                    } catch (Exception e) {
                        writer.write("  " + tableName + " - 无统计信息（原因: 无法获取schema信息）\n");
                    }
                }
            }
            writer.write("\n");
            
            writer.write("中间分区表 (" + intermediateTables.size() + " 个):\n");
            for (String tableName : intermediateTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    writer.write("  " + tableName + " - " + stats.getTableSize() + " 行\n");
                } else {
                    try {
                        Table table = TableManager.getInstance().getSchema(tableName);
                        if (table != null) {
                            long size = table.getTableSize();
                            if (size == 0) {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小为0）\n");
                            } else {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小不为0但统计信息为空）\n");
                            }
                        } else {
                            writer.write("  " + tableName + " - 无统计信息（原因: schema中无该表记录，可能为系统表/视图/无权限）\n");
                        }
                    } catch (Exception e) {
                        writer.write("  " + tableName + " - 无统计信息（原因: 无法获取schema信息）\n");
                    }
                }
            }
            writer.write("\n");
            
            writer.write("叶子分区表 (" + leafTables.size() + " 个):\n");
            for (String tableName : leafTables) {
                EnhancedTableStatistics stats = tableStats.get(tableName);
                if (stats != null) {
                    writer.write("  " + tableName + " - " + stats.getTableSize() + " 行\n");
                } else {
                    // 优先根据表大小为0的原因进行说明
                    try {
                        Table table = TableManager.getInstance().getSchema(tableName);
                        if (table != null) {
                            long size = table.getTableSize();
                            if (size == 0) {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小为0）\n");
                            } else {
                                writer.write("  " + tableName + " - 无统计信息（原因: 表大小不为0但统计信息为空）\n");
                            }
                        } else {
                            writer.write("  " + tableName + " - 无统计信息（原因: schema中无该表记录，可能为系统表/视图/无权限）\n");
                        }
                    } catch (Exception e) {
                        writer.write("  " + tableName + " - 无统计信息（原因: 无法获取schema信息）\n");
                    }
                }
            }
            writer.write("\n");
            
            logger.info("提取阶段详细报告已生成: {}", reportFile.getAbsolutePath());
        }
    }
}
