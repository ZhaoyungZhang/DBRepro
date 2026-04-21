package ruc.db.rsgen;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import ruc.db.LanguageManager;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedTableStatistics;
import ruc.db.schema.Column;
import ruc.db.schema.TableManager;

/**
 * 重构的RSGen数据生成器 - 简洁优美的5步流程
 *
 * 数据生成流程：
 * 1. 拓扑排序后，为所有列独立初始化bucket（NULL/MCV/Histogram），不做对齐
 * 2. 对所有主外键列做bucket alignment，统一边界并对齐nDistinct
 * 3. 按拓扑顺序依次生成数据：主/外键列用对齐后bucket，非主外键列用自身bucket
 * 4. 写入数据文件，保证列顺序与schema一致
 * 5. 生成DDL SQL
 *
 * @author RSGen Team
 */
public class RSGenDataGeneratorRefactored {

    /**
     * 数据分布模型枚举
     */
    public enum DistributionModel {
        UNIFORM,        // 均匀分布
        NORMAL,         // 正态分布
        EXPONENTIAL,    // 指数分布
        GOLDEN_RATIO    // 黄金分割比（默认，用于避免过于均匀）
    }
    private static final Logger logger = LoggerFactory.getLogger(RSGenDataGeneratorRefactored.class);
    private static final LanguageManager LM = LanguageManager.getInstance();

    private final ForeignKeyHandler foreignKeyHandler;
    private EnhancedBucketGenerator bucketGenerator; // 改成非final，可以在运行时更新
    private final BucketAlignment bucketAlignment;
    private final DataFileWriter dataFileWriter;
    private final VarcharDataGenerator varcharGenerator;
    // ★★★ 延迟初始化：只在需要时创建（根据用户设计理念，PK-FK应该用mirage生成器） ★★★
    private PrimaryKeyDataGenerator primaryKeyDataGenerator;
    private final ObjectMapper objectMapper;
    private final Random random;
    private final int numWorkers; // 工作线程数
    private DistributionModel distributionModel = DistributionModel.GOLDEN_RATIO; // 默认分布模型
    
    // 全局状态
    private Map<String, EnhancedTableStatistics> globalTableStats;
    private Map<String, List<Bucket>> globalBuckets; // 所有列的buckets（对齐前）
    private Map<String, List<Bucket>> alignedBuckets; // 对齐后的buckets
    private List<String> topologicalOrder; // 表的拓扑排序
    private List<String> optimizedTopologicalOrder; // 优化的拓扑排序（用于DDL生成）
    private String inputDir; // 输入目录路径
    private Map<String, Map<String, Object[]>> globalTableData; // 当前表的数据（用于流式处理）

    public RSGenDataGeneratorRefactored(ForeignKeyHandler foreignKeyHandler) {
        this.foreignKeyHandler = foreignKeyHandler;
        this.bucketGenerator = new EnhancedBucketGenerator(null); // 稍后会在generateAllTablesData时更新
        this.bucketAlignment = new BucketAlignment();
        this.dataFileWriter = new DataFileWriter();
        this.varcharGenerator = new VarcharDataGenerator();
        this.objectMapper = new ObjectMapper();
        // 注册JSR310模块以支持Java 8日期时间类型
        this.objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        this.random = new Random(System.currentTimeMillis());
        
        this.globalTableStats = new HashMap<>();
        this.globalBuckets = new ConcurrentHashMap<>();
        this.alignedBuckets = new ConcurrentHashMap<>();
        this.topologicalOrder = new ArrayList<>();
        this.optimizedTopologicalOrder = new ArrayList<>();
        this.globalTableData = new ConcurrentHashMap<>();
        
        // ★★★ 修复：延迟初始化 PrimaryKeyDataGenerator，只在需要时创建（避免非主键列也初始化） ★★★
        // 注意：根据用户设计理念，PK-FK 应该用 mirage 的生成器来生成，所以这里延迟初始化
        this.primaryKeyDataGenerator = null;
        
        this.numWorkers = 4; // 默认工作线程数
        
        // logger.info(LM.formatBilingual("RsgenGenEntry.InitDone"));
    }

    /**
     * 设置分布模型
     * @param model 分布模型
     */
    public void setDistributionModel(DistributionModel model) {
        this.distributionModel = model;
        // logger.info(LM.formatBilingual("RsgenGenEntry.DistributionModelSet", model));
    }

    /**
     * 设置分布模型（字符串版本）
     * @param modelName 分布模型名称
     */
    public void setDistributionModel(String modelName) {
        try {
            DistributionModel model = DistributionModel.valueOf(modelName.toUpperCase());
            setDistributionModel(model);
        } catch (IllegalArgumentException e) {
            logger.warn(LM.formatBilingual("RsgenGenEntry.UnknownDistributionModel", modelName));
            setDistributionModel(DistributionModel.GOLDEN_RATIO);
        }
    }

    /**
     * 生成所有表的数据（5步流程）
     */
    public void generateAllTablesData(String inputDir, String outputDir, double scaleFactor) throws Exception {
        this.inputDir = inputDir; // 保存输入目录路径
        this.bucketGenerator = new EnhancedBucketGenerator(inputDir); // ★ 用正确的项目目录初始化BucketGenerator ★
        logger.info(LM.formatBilingual("RsgenGenEntry.PipelineStart", inputDir, outputDir, scaleFactor));
        logger.info(LM.formatBilingual("RsgenGenEntry.BoundDebugStart"));
        
        try {
        // 加载统计信息
        globalTableStats = loadEnhancedStatistics(inputDir);
        logger.info(LM.formatBilingual("RsgenGenEntry.LoadedNTables", globalTableStats.size()));
        
        // 应用缩放因子
            if(scaleFactor != 1.0) {
                applyScaleFactor(scaleFactor);
            }
            
            logger.info(LM.formatBilingual("RsgenGenEntry.BoundDebugBeforeStep1"));
            // 步骤1：初始化所有bucket
            step1_InitializeAllBuckets();

            // 步骤1.5：进行拓扑排序（在初始化完成后）
            topologicalOrder = foreignKeyHandler.getGenerationOrder();
            if (topologicalOrder.isEmpty()) {
                topologicalOrder = new ArrayList<>(globalTableStats.keySet());
                logger.warn(LM.formatBilingual("RsgenGenEntry.TopoEmptyWarn"));
            }
            logger.info(LM.formatBilingual("RsgenGenEntry.TopoOrder", topologicalOrder));
                
            // 步骤2：对齐主外键bucket
            step2_AlignPrimaryForeignKeyBuckets();

            // 步骤2.5：输出所有schema 下的所有表的所有列的 buckets 到文件
            outputAllBucketsToFile(outputDir);
                
            // 步骤3：按拓扑顺序生成数据并立即写入磁盘
            step3_GenerateDataInTopologicalOrder(outputDir);
                
            // 步骤4：生成DDL SQL
            step4_GenerateDDL(outputDir);
            
            // 步骤5：生成详细统计报告
            generateDetailedReport(outputDir);
        
            logger.info(LM.formatBilingual("RsgenGenEntry.PipelineDone"));
            
        } catch (Exception e) {
            logger.error(LM.formatBilingual("RsgenGenEntry.PipelineError", e.getMessage()), e);
            
            // 即使前面的步骤失败，也要尝试生成DDL
            try {
                logger.info(LM.formatBilingual("RsgenGenEntry.TryDdlAfterError"));
                step4_GenerateDDL(outputDir);
                logger.info(LM.formatBilingual("RsgenGenEntry.DdlOk"));
            } catch (Exception ddlError) {
                logger.error(LM.formatBilingual("RsgenGenEntry.DdlAlsoFailed", ddlError.getMessage()), ddlError);
            }
            
            throw e; // 重新抛出原始异常
        }
    }

    /**
     * 步骤1：直接遍历所有表，为普通表和叶子表初始化bucket
     * 修改点：不依赖拓扑排序，直接遍历所有表，只初始化普通表和叶子表，跳过tableSize为0的表
     */
    private void step1_InitializeAllBuckets() throws Exception {
        logger.info("=== 步骤1：为所有列独立初始化bucket ===");
        
        // 加载分区关系信息（如果已存在会自动跳过）
        try {
            PartitionTableManager.getInstance().loadFromFile(inputDir);
            logger.info("分区表关系信息加载完成");
        } catch (IOException e) {
            logger.info("未找到分区关系文件，将按普通表处理: {}", e.getMessage());
        }
        
        // 统计信息
        int normalTableCount = 0;
        int leafTableCount = 0;
        int skippedRootTableCount = 0;
        int skippedIntermediateTableCount = 0;
        int skippedZeroSizeTableCount = 0;
        int totalInitializedColumns = 0;
        
        // 直接遍历所有表
        for (String tableName : globalTableStats.keySet()) {
            EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
            if (tableStats == null) {
                logger.warn("表{}的统计信息不存在，跳过", tableName);
                continue;
            }
            
            long tableSize = tableStats.getTableSize();
            
            // 检查表大小，如果为0则跳过
            if (tableSize == 0) {
                logger.info("表{}大小为0，跳过初始化", tableName);
                skippedZeroSizeTableCount++;
                continue;
            }
            
            // 检查表类型
            boolean isRootPartition = PartitionTableManager.getInstance().isRootPartitionTable(tableName);
            boolean isIntermediatePartition = PartitionTableManager.getInstance().isIntermediatePartition(tableName);
            boolean isLeafPartition = PartitionTableManager.getInstance().isLeafTable(tableName);
            boolean isNormalTable = !PartitionTableManager.getInstance().isPartitionTable(tableName);
            
            if (isNormalTable) {
                // 普通表：正常初始化
                logger.info("为普通表{}初始化buckets", tableName);
                normalTableCount++;
            
            for (Map.Entry<String, EnhancedColumnStatistics> entry : tableStats.getColumns().entrySet()) {
                String columnName = entry.getKey();
                EnhancedColumnStatistics colStats = entry.getValue();
                
                // 独立初始化每个列的bucket（不考虑对齐）
                List<Bucket> buckets = bucketGenerator.generateBuckets(colStats, tableSize);
                String bucketKey = generateBucketKey(tableName, columnName);
                globalBuckets.put(bucketKey, buckets);
                    totalInitializedColumns++;
                }
            } else if (isLeafPartition) {
                // 叶子表：正常初始化
                logger.info("为叶子表{}初始化buckets", tableName);
                leafTableCount++;
                
                for (Map.Entry<String, EnhancedColumnStatistics> entry : tableStats.getColumns().entrySet()) {
                    String columnName = entry.getKey();
                    EnhancedColumnStatistics colStats = entry.getValue();
                    
                    // 独立初始化每个列的bucket（不考虑对齐）
                    List<Bucket> buckets = bucketGenerator.generateBuckets(colStats, tableSize);
                    String bucketKey = generateBucketKey(tableName, columnName);
                    globalBuckets.put(bucketKey, buckets);
                    totalInitializedColumns++;
                }
            } else if (isRootPartition) {
                // 根分区表：跳过初始化
                logger.info("表{}是根分区表，跳过初始化", tableName);
                skippedRootTableCount++;
                continue;
            } else if (isIntermediatePartition) {
                // 中间分区表：跳过初始化
                logger.info("表{}是中间分区表，跳过初始化", tableName);
                skippedIntermediateTableCount++;
                continue;
            }
        }
        
        logger.info("步骤1完成报告：");
        logger.info("  普通表: {}个已初始化", normalTableCount);
        logger.info("  叶子表: {}个已初始化", leafTableCount);
        logger.info("  跳过根分区表: {}个", skippedRootTableCount);
        logger.info("  跳过中间分区表: {}个", skippedIntermediateTableCount);
        logger.info("  跳过零大小表: {}个", skippedZeroSizeTableCount);
        logger.info("  总计初始化列数: {}", totalInitializedColumns);
    }

    /**
     * 步骤2：对所有主外键列做bucket alignment
     * 修改点：跳过涉及分区表的对齐，后续需要优化
     */
    private void step2_AlignPrimaryForeignKeyBuckets() throws Exception {
        logger.info("=== 步骤2：对主外键列进行bucket alignment ===");
        
        // 收集所有需要对齐的主外键关系对
        List<ForeignKeyRelation> alignmentPairs = collectAlignmentPairs();
        logger.info("发现{}对需要对齐的主外键关系", alignmentPairs.size());
        
        int alignedCount = 0;
        int skippedPartitionCount = 0;
        
        // 执行bucket对齐
        for (ForeignKeyRelation relation : alignmentPairs) {
            // 检查是否涉及分区表
            String primaryTable = relation.primaryKeyTable;
            String foreignTable = relation.foreignKeyTable;
            
            boolean primaryIsPartition = PartitionTableManager.getInstance().isRootPartitionTable(primaryTable) ||
                                       (PartitionTableManager.getInstance().isPartitionTable(primaryTable) && 
                                        PartitionTableManager.getInstance().isChildTable(primaryTable));
            boolean foreignIsPartition = PartitionTableManager.getInstance().isRootPartitionTable(foreignTable) ||
                                       (PartitionTableManager.getInstance().isPartitionTable(foreignTable) && 
                                        PartitionTableManager.getInstance().isChildTable(foreignTable));
            
            if (primaryIsPartition || foreignIsPartition) {
                logger.info("跳过涉及分区表的对齐关系: {} -> {}", foreignTable, primaryTable);
                skippedPartitionCount++;
                continue;
            }
            
            // 执行对齐
            alignBucketPair(relation);
            alignedCount++;
        }
        
        logger.info("步骤2完成报告：");
        logger.info("  成功对齐: {}对主外键关系", alignedCount);
        logger.info("  跳过分区表相关: {}对主外键关系", skippedPartitionCount);
    }

    /**
     * 步骤3：按拓扑排序顺序生成数据并立即写入磁盘
     * 修改点：直接使用拓扑排序，正确处理分区表逻辑
     */
    private void step3_GenerateDataInTopologicalOrder(String outputDir) throws Exception {
        logger.info("=== 步骤3：按拓扑排序顺序生成数据并立即写入磁盘 ===");
        
        // 使用拓扑排序
        if (topologicalOrder == null || topologicalOrder.isEmpty()) {
            logger.warn("拓扑排序为空，使用所有表名作为顺序");
            topologicalOrder = new ArrayList<>(globalTableStats.keySet());
        }
        
        logger.info("使用拓扑排序，包含{}个表", topologicalOrder.size());
        
        int normalTableCount = 0;
        int rootPartitionTableCount = 0;
        int leafTableGeneratedCount = 0;
        int skippedZeroSizeCount = 0;
        int successCount = 0;
        int totalCount = topologicalOrder.size();
        
        for (String tableName : topologicalOrder) {
            try {
                logger.info("开始处理表 {} ({}/{})", tableName, successCount + 1, totalCount);
                
                // 检查表大小
                EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
                if (tableStats != null && tableStats.getTableSize() == 0) {
                    logger.info("表{}大小为0，跳过数据生成", tableName);
                    skippedZeroSizeCount++;
                    successCount++;
                    continue;
                }
                
                if (PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                    // 根分区表：递归生成其叶子表的数据
                    logger.info("处理根分区表{}，生成其叶子表数据", tableName);
                    int leafCount = processPartitionTableRecursively(tableName, outputDir);
                    leafTableGeneratedCount += leafCount;
                    rootPartitionTableCount++;
                } else {
                    // 普通表：直接生成数据
                    logger.info("处理普通表{}", tableName);
                    processNormalTable(tableName, outputDir);
                    normalTableCount++;
                }
                
                successCount++;
                logger.info("表{}处理完成 ({}/{})", tableName, successCount, totalCount);
                
            } catch (Exception e) {
                logger.error("表{}处理失败：{}", tableName, e.getMessage(), e);
                // 清空内存中的表数据（即使失败也要清理）
                clearTableData(tableName);
                // 继续处理其他表，不中断整个流程
            }
        }
        
        logger.info("步骤3完成报告：");
        logger.info("  成功处理: {}/{}个表", successCount, totalCount);
        logger.info("  普通表: {}个", normalTableCount);
        logger.info("  根分区表: {}个", rootPartitionTableCount);
        logger.info("  生成的叶子表: {}个", leafTableGeneratedCount);
        logger.info("  跳过零大小表: {}个", skippedZeroSizeCount);
    }

    /**
     * 获取优化的拓扑排序：只包含根表（普通表和根分区表），不包含子表和中间分区表
     */
    private List<String> getOptimizedTopologicalOrder() {
        // 获取所有根分区表
        List<String> rootPartitionTables = PartitionTableManager.getInstance().getAllRootPartitionTables();
        
        // 从原始拓扑排序中过滤出普通表（不是任何类型的分区表，也不是子表）
        List<String> normalTables = new ArrayList<>();
        for (String tableName : topologicalOrder) {
            if (!PartitionTableManager.getInstance().isChildTable(tableName) && 
                !PartitionTableManager.getInstance().isAnyPartitionTable(tableName)) {
                normalTables.add(tableName);
            }
        }
        
        // 合并根分区表和普通表，并保持拓扑顺序
        List<String> optimizedOrder = new ArrayList<>();
        
        // 首先添加普通表（保持原有拓扑顺序）
        optimizedOrder.addAll(normalTables);
        
        // 然后添加根分区表（这些表通常没有外键依赖）
        optimizedOrder.addAll(rootPartitionTables);
        
        // 记录详细的表类型分布
        logger.info("=== 优化拓扑排序详情 ===");
        logger.info("普通表 ({} 个): {}", normalTables.size(), normalTables);
        logger.info("根分区表 ({} 个): {}", rootPartitionTables.size(), rootPartitionTables);
        
        // 记录被排除的表
        List<String> excludedTables = new ArrayList<>();
        for (String tableName : topologicalOrder) {
            if (PartitionTableManager.getInstance().isChildTable(tableName)) {
                excludedTables.add(tableName + " (子表)");
            } else if (PartitionTableManager.getInstance().isPartitionTable(tableName) && 
                      !PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                excludedTables.add(tableName + " (中间分区表)");
            }
        }
        
        if (!excludedTables.isEmpty()) {
            logger.info("被排除的表 ({} 个): {}", excludedTables.size(), excludedTables);
        }
        
        logger.info("最终优化拓扑排序：{} 个根表，总计 {} 个表", 
                   optimizedOrder.size(), optimizedOrder.size());
        
        return optimizedOrder;
    }

    /**
     * 清空指定表的内存数据
     */
    private void clearTableData(String tableName) {
        Map<String, Object[]> tableData = globalTableData.remove(tableName);
        if (tableData != null) {
            tableData.clear();
            logger.debug("已清空表{}的内存数据", tableName);
        }
    }

    /**
     * 递归并行处理分区表及其所有子表
     * @return 成功生成的叶子表数量
     */
    private int processPartitionTableRecursively(String partitionTableName, String outputDir) throws Exception {
        logger.info("递归处理分区表: {}", partitionTableName);
        
        // 收集所有需要生成数据的子表（只有叶子表才生成数据）
        List<String> leafTables = collectLeafTables(partitionTableName);
        
        if (leafTables.isEmpty()) {
            logger.warn("分区表 {} 没有叶子表，跳过数据生成", partitionTableName);
            return 0;
        }
        
        logger.info("分区表 {} 包含 {} 个叶子表，开始并行生成", partitionTableName, leafTables.size());
        
        // 统计成功生成的叶子表数量
        AtomicInteger successCount = new AtomicInteger(0);
        
        // 并行生成所有叶子表的数据
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(numWorkers, leafTables.size()));
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (String leafTable : leafTables) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // 检查叶子表大小
                    EnhancedTableStatistics tableStats = globalTableStats.get(leafTable);
                    if (tableStats != null && tableStats.getTableSize() == 0) {
                        logger.info("叶子表{}大小为0，跳过数据生成", leafTable);
                        return;
                    }
                    
                    logger.info("并行生成叶子表: {}", leafTable);
                    processNormalTable(leafTable, outputDir);
                    successCount.incrementAndGet();
                    logger.info("叶子表 {} 生成完成", leafTable);
                } catch (Exception e) {
                    logger.warn("叶子表 {} 生成失败: {}", leafTable, e.getMessage());
                    // 不抛出异常，继续处理其他叶子表
                }
            }, executor);
            
            futures.add(future);
        }
        
        // 等待所有叶子表生成完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            logger.info("分区表 {} 的叶子表生成完成: {}/{}个成功", partitionTableName, successCount.get(), leafTables.size());
        } catch (Exception e) {
            logger.warn("分区表 {} 的叶子表并行生成失败: {}", partitionTableName, e.getMessage());
            // 不抛出异常，继续处理其他表
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
        
        return successCount.get();
    }

    /**
     * 递归收集分区表的所有叶子表
     */
    private List<String> collectLeafTables(String partitionTableName) {
        List<String> leafTables = new ArrayList<>();
        collectLeafTablesRecursively(partitionTableName, leafTables);
        return leafTables;
    }

    /**
     * 递归收集叶子表的辅助方法
     */
    private void collectLeafTablesRecursively(String tableName, List<String> leafTables) {
        if (PartitionTableManager.getInstance().isPartitionTable(tableName)) {
            // 如果是分区表，继续递归其子表
            Set<String> childTables = PartitionTableManager.getInstance().getChildTables(tableName);
            if (childTables != null) {
                for (String childTable : childTables) {
                    collectLeafTablesRecursively(childTable, leafTables);
                }
            }
        } else {
            // 如果不是分区表，说明是叶子表
            leafTables.add(tableName);
            // logger.debug("发现叶子表: {}", tableName);
        }
    }

    /**
     * 处理普通表（包括叶子表）
     */
    private void processNormalTable(String tableName, String outputDir) throws Exception {
        // 生成表数据
        generateTableData(tableName);
        
        // 立即写入磁盘
        writeTableDataToFile(tableName, outputDir);
        
        // 清空内存中的表数据
        clearTableData(tableName);
    }

    /**
     * 步骤4：生成DDL SQL
     * 修改点：只生成importData.sql，支持分区表的导入逻辑
     */
    private void step4_GenerateDDL(String outputDir) throws Exception {
        logger.info("=== 步骤4：生成DDL SQL ===");
        
        // 使用新的方法只生成importData.sql
        generateImportDataSQLForPartitions(outputDir);
        
        logger.info("步骤4完成：importData.sql生成完毕");
    }
    
    /**
     * 生成支持分区表的importData.sql文件
     */
    private void generateImportDataSQLForPartitions(String outputDir) throws Exception {
        logger.info("开始生成支持分区表的importData.sql");
        
        // 创建create_sql目录
        File createSqlDir = new File(outputDir, "create_sql");
        if (!createSqlDir.exists()) {
            createSqlDir.mkdirs();
        }
        
        File importDataFile = new File(createSqlDir, "importData.sql");
        
        int normalTableCount = 0;
        int rootPartitionTableCount = 0;
        int leafTableCount = 0;
        int skippedZeroSizeCount = 0;
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(importDataFile))) {
            writer.write("-- RSGen生成的数据导入SQL脚本\n");
            writer.write("-- 支持分区表的导入逻辑\n");
            writer.write("-- 生成时间: " + java.time.LocalDateTime.now() + "\n\n");
            
            // 使用拓扑排序顺序
            if (topologicalOrder == null || topologicalOrder.isEmpty()) {
                logger.warn("拓扑排序为空，使用所有表名作为顺序");
                topologicalOrder = new ArrayList<>(globalTableStats.keySet());
            }
            
            for (String tableName : topologicalOrder) {
                EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
                
                if (PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                    // 根分区表：即使没有统计信息或表大小为0，也要生成叶子表到根表的导入命令
                    logger.info("为根分区表{}生成导入SQL", tableName);
                    generatePartitionImportSQL(writer, tableName, outputDir);
                    rootPartitionTableCount++;
                } else if (tableStats == null) {
                    logger.debug("表{}没有统计信息，跳过", tableName);
                    continue;
                } else if (tableStats.getTableSize() == 0) {
                    // 对于非根分区表，跳过表大小为0的表
                    logger.info("表{}大小为0，跳过生成导入SQL", tableName);
                    skippedZeroSizeCount++;
                    continue;
                } else if (!PartitionTableManager.getInstance().isChildTable(tableName) && 
                          !PartitionTableManager.getInstance().isAnyPartitionTable(tableName)) {
                    // 普通表：直接生成导入命令
                    logger.info("为普通表{}生成导入SQL", tableName);
                    generateNormalTableImportSQL(writer, tableName, outputDir);
                    normalTableCount++;
                }
                // 中间分区表和叶子表不需要单独处理，因为叶子表的数据会通过根分区表导入
            }
            
            writer.write("\n-- 导入完成\n");
        }
        
        logger.info("importData.sql生成完成报告：");
        logger.info("  普通表: {}个", normalTableCount);
        logger.info("  根分区表: {}个", rootPartitionTableCount);
        logger.info("  跳过零大小表: {}个", skippedZeroSizeCount);
        logger.info("  文件位置: {}", importDataFile.getAbsolutePath());
    }
    
    /**
     * 为根分区表生成导入SQL（叶子表数据导入到根表）
     */
    private void generatePartitionImportSQL(BufferedWriter writer, String rootTableName, String outputDir) throws Exception {
        writer.write("-- 根分区表: " + rootTableName + "\n");
        
        // 收集所有叶子表
        List<String> leafTables = collectLeafTables(rootTableName);
        
        for (String leafTable : leafTables) {
            EnhancedTableStatistics tableStats = globalTableStats.get(leafTable);
            if (tableStats != null && tableStats.getTableSize() > 0) {
                // 叶子表数据导入到根表，利用数据库的自动路由机制
                String standardFileName = getStandardFileName(leafTable) + ".tbl";
                String fullDataFilePath = new File(outputDir, "data/" + standardFileName).getAbsolutePath();
                String importSQL = String.format("COPY %s FROM '%s' WITH (FORMAT csv, DELIMITER '|', NULL '\\\\N');",
                        rootTableName, fullDataFilePath);
                writer.write(importSQL + "\n");
                logger.debug("生成叶子表{}到根表{}的导入SQL", leafTable, rootTableName);
            }
        }
        
        writer.write("\n");
    }
    
    /**
     * 获取标准化的文件名（去掉schema前缀）
     */
    private String getStandardFileName(String tableName) {
        // 如果表名包含schema前缀，去掉前缀
        if (tableName.contains(".")) {
            return tableName.substring(tableName.lastIndexOf(".") + 1);
        }
        return tableName;
    }
    
    /**
     * 为普通表生成导入SQL
     */
    private void generateNormalTableImportSQL(BufferedWriter writer, String tableName, String outputDir) throws Exception {
        writer.write("-- 普通表: " + tableName + "\n");
        
        // 生成标准化的文件名（去掉schema前缀）
        String standardFileName = getStandardFileName(tableName) + ".tbl";
        // 生成完整的文件路径
        String fullDataFilePath = new File(outputDir, "data/" + standardFileName).getAbsolutePath();
        String importSQL = String.format("COPY %s FROM '%s' WITH (FORMAT csv, DELIMITER '|', NULL '\\\\N');",
                tableName, fullDataFilePath);
        writer.write(importSQL + "\n\n");
    }

    /**
     * 步骤2.5：输出所有schema下的所有表的所有列的buckets到文件
     * 修改点：跳过分区根表、中间表和tableSize为0的表
     */
    private void outputAllBucketsToFile(String outputDir) throws Exception {
        logger.info("=== 步骤2.5：输出所有buckets到文件 ===");
        
        // 创建输出目录
        String bucketOutputDir = outputDir + "/data_distributions/";
        File outputDirectory = new File(bucketOutputDir);
        if (!outputDirectory.exists()) {
            if (!outputDirectory.mkdirs()) {
                throw new IOException("无法创建输出目录: " + bucketOutputDir);
            }
        }
        
        int successCount = 0;
        int skippedRootTableCount = 0;
        int skippedIntermediateTableCount = 0;
        int skippedZeroSizeTableCount = 0;
        int totalCount = globalTableStats.size();
        
        for (String tableName : globalTableStats.keySet()) {
            try {
                // 获取表的统计信息
                EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
                if (tableStats == null) {
                    logger.warn("表{}的统计信息不存在，跳过", tableName);
                    continue;
                }
                
                long tableSize = tableStats.getTableSize();
                
                // 检查表大小，如果为0则跳过
                if (tableSize == 0) {
                    logger.info("表{}大小为0，跳过输出buckets", tableName);
                    skippedZeroSizeTableCount++;
                    continue;
                }
                
                // 检查表类型
                boolean isRootPartition = PartitionTableManager.getInstance().isRootPartitionTable(tableName);
                boolean isIntermediatePartition = PartitionTableManager.getInstance().isPartitionTable(tableName) && 
                                                 PartitionTableManager.getInstance().isChildTable(tableName);
                
                if (isRootPartition) {
                    logger.info("表{}是根分区表，跳过输出buckets", tableName);
                    skippedRootTableCount++;
                    continue;
                }
                
                if (isIntermediatePartition) {
                    logger.info("表{}是中间分区表，跳过输出buckets", tableName);
                    skippedIntermediateTableCount++;
                    continue;
                }
                
                logger.info("输出表 {} 的buckets ({}/{})", tableName, successCount + 1, totalCount);
                
                // 收集该表所有列的buckets信息
                Map<String, Object> tableBucketsInfo = new HashMap<>();
                tableBucketsInfo.put("table_name", tableName);
                tableBucketsInfo.put("table_size", tableStats.getTableSize());
                tableBucketsInfo.put("columns", new HashMap<String, Object>());
                
                Map<String, Object> columnsInfo = (Map<String, Object>) tableBucketsInfo.get("columns");
                
                // 遍历表的每一列
                for (Map.Entry<String, EnhancedColumnStatistics> entry : tableStats.getColumns().entrySet()) {
                    String columnName = entry.getKey();
                    EnhancedColumnStatistics colStats = entry.getValue();
                    
                    // 获取该列的buckets（优先使用对齐后的buckets）
                    String bucketKey = generateBucketKey(tableName, columnName);
                    List<Bucket> buckets = alignedBuckets.get(bucketKey);
                    String bucketSource = "aligned";
                    
                    // 如果没有对齐后的buckets，使用原始buckets
                    if (buckets == null) {
                        buckets = globalBuckets.get(bucketKey);
                        bucketSource = "original";
                    }
                    
                    // 构建列信息
                    Map<String, Object> columnInfo = new HashMap<>();
                    columnInfo.put("column_name", columnName);
                    columnInfo.put("data_type", colStats.getDataType());
                    columnInfo.put("is_primary_key", colStats.isPrimaryKey());
                    columnInfo.put("is_foreign_key", colStats.isForeignKey());
                    columnInfo.put("n_distinct", colStats.getNDistinct());
                    columnInfo.put("null_fraction", colStats.getNullFraction());
                    columnInfo.put("bucket_source", bucketSource);
                    
                    // 构建buckets信息
                    List<Map<String, Object>> bucketsList = new ArrayList<>();
                    
                    if (buckets != null) {
                        for (int i = 0; i < buckets.size(); i++) {
                            Bucket bucket = buckets.get(i);
                            Map<String, Object> bucketInfo = new HashMap<>();
                            
                            bucketInfo.put("bucket_index", i);
                            bucketInfo.put("bucket_type", bucket.getType().toString());
                            bucketInfo.put("count", bucket.getCount());
                            bucketInfo.put("n_distinct", bucket.getDistinct());
                            
                            // 处理边界值
                            if (bucket.getLow() != null) {
                                bucketInfo.put("low_value", bucket.getLow().getValue());
                            } else {
                                bucketInfo.put("low_value", null);
                            }
                            
                            if (bucket.getHigh() != null) {
                                bucketInfo.put("high_value", bucket.getHigh().getValue());
                            } else {
                                bucketInfo.put("high_value", null);
                            }
                            
                            bucketsList.add(bucketInfo);
                        }
                    }
                    
                    columnInfo.put("buckets", bucketsList);
                    columnInfo.put("bucket_count", bucketsList.size());
                    
                    columnsInfo.put(columnName, columnInfo);
                }
                
                // 写入文件
                String fileName = tableName + ".json";
                File outputFile = new File(bucketOutputDir, fileName);
                
                try {
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, tableBucketsInfo);
                    logger.info("表 {} 的buckets已输出到: {}", tableName, outputFile.getAbsolutePath());
                    successCount++;
                } catch (IOException e) {
                    logger.error("写入表 {} 的buckets文件失败: {}", tableName, e.getMessage(), e);
                }
                
            } catch (Exception e) {
                logger.error("处理表 {} 的buckets输出时出错: {}", tableName, e.getMessage(), e);
            }
        }
        
        logger.info("步骤2.5完成报告：");
        logger.info("  成功输出buckets: {}个表", successCount);
        logger.info("  跳过根分区表: {}个", skippedRootTableCount);
        logger.info("  跳过中间分区表: {}个", skippedIntermediateTableCount);
        logger.info("  跳过零大小表: {}个", skippedZeroSizeTableCount);
        logger.info("  输出目录: {}", bucketOutputDir);
    }

    // ==================== 辅助方法 ====================

    /**
     * 收集需要对齐的主外键关系对
     */
    private List<ForeignKeyRelation> collectAlignmentPairs() {
        List<ForeignKeyRelation> pairs = new ArrayList<>();
        
        for (String tableName : topologicalOrder) {
            // 使用ForeignKeyHandler获取外键关系
            List<ForeignKeyHandler.ForeignKeyRelation> fkRelations = foreignKeyHandler.getForeignKeyRelations(tableName);
            
            for (ForeignKeyHandler.ForeignKeyRelation fkRelation : fkRelations) {
                ForeignKeyRelation relation = new ForeignKeyRelation(
                    tableName, fkRelation.getForeignKeyColumn(), 
                    fkRelation.getReferencedTable(), fkRelation.getReferencedColumn()
                );
                pairs.add(relation);
                logger.debug("发现外键关系：{}.{} -> {}.{}", 
                    tableName, fkRelation.getForeignKeyColumn(), 
                    fkRelation.getReferencedTable(), fkRelation.getReferencedColumn());
            }
        }
        
        return pairs;
    }

    /**
     * 对齐一对主外键bucket
     */
    private void alignBucketPair(ForeignKeyRelation relation) {
        String fkBucketKey = generateBucketKey(relation.foreignKeyTable, relation.foreignKeyColumn);
        String pkBucketKey = generateBucketKey(relation.primaryKeyTable, relation.primaryKeyColumn);
        
        List<Bucket> fkBuckets = globalBuckets.get(fkBucketKey);
        List<Bucket> pkBuckets = globalBuckets.get(pkBucketKey);
        
        if (fkBuckets == null || pkBuckets == null) {
            logger.warn(
                "[Bucket对齐失败] 外键表: {}, 外键列: {}, 主键表: {}, 主键列: {}, 外键key: {}, 主键key: {}, 当前全局bucket keys: {}", 
                relation.foreignKeyTable, relation.foreignKeyColumn, relation.primaryKeyTable, relation.primaryKeyColumn, fkBucketKey, pkBucketKey, globalBuckets.keySet()
            );

            // 检查统计信息
            if (!globalTableStats.containsKey(relation.foreignKeyTable)) {
                logger.warn("[Bucket对齐失败] enhanced_column_statistics.json中缺少外键表: {}", relation.foreignKeyTable);
            }
            if (!globalTableStats.containsKey(relation.primaryKeyTable)) {
                logger.warn("[Bucket对齐失败] enhanced_column_statistics.json中缺少主键表: {}", relation.primaryKeyTable);
            }
            if (fkBuckets == null) {
                logger.warn("[Bucket对齐失败] 外键bucket为null, key: {}", fkBucketKey);
            }
            if (pkBuckets == null) {
                logger.warn("[Bucket对齐失败] 主键bucket为null, key: {}", pkBucketKey);
            }
            return;
        }
        
        logger.info("对齐bucket：{}.{} -> {}.{}", 
            relation.foreignKeyTable, relation.foreignKeyColumn,
            relation.primaryKeyTable, relation.primaryKeyColumn);
        
        // 执行bucket对齐
        BucketAlignment.AlignedBuckets alignedResult = bucketAlignment.alignBuckets(
            fkBuckets, pkBuckets, 
            relation.foreignKeyTable + "." + relation.foreignKeyColumn,
            relation.primaryKeyTable + "." + relation.primaryKeyColumn
        );
        
        // 保存对齐后的buckets
        alignedBuckets.put(fkBucketKey, alignedResult.getForeignKeyBuckets());
        alignedBuckets.put(pkBucketKey, alignedResult.getReferencedBuckets());
        
        // logger.debug("bucket对齐完成：外键{}个buckets，主键{}个buckets", 
        //        alignedResult.getForeignKeyBuckets().size(), 
        //        alignedResult.getReferencedBuckets().size());
    }

    /**
     * 生成单表数据
     */
    private void generateTableData(String tableName) throws Exception {
        logger.info("生成表{}的数据", tableName);
        
        EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
        
        // 检查tableStats是否为null
        if (tableStats == null) {
            logger.warn("表 {} 的统计信息为null，跳过数据生成", tableName);
            return; // 跳过该表，不抛出异常
        }
        
        long tableSize = tableStats.getTableSize();
        logger.debug("表 {} 统计信息: 大小={}, 列数={}", tableName, tableSize, tableStats.getColumns().size());
        Map<String, Object[]> tableData = new HashMap<>();
        
        // 按列顺序生成数据
        List<String> columnOrder = getColumnOrder(tableStats);
        
        // 列级别并行生成
        if (numWorkers > 1) {
            logger.info("使用列级别并行生成，工作线程数: {}", numWorkers);
            generateColumnsInParallel(tableName, columnOrder, tableStats, tableSize, tableData);
        } else {
            logger.info("使用串行模式生成列数据");
            generateColumnsSequentially(tableName, columnOrder, tableStats, tableSize, tableData);
        }
        
        // 保存表数据到全局状态
        globalTableData.put(tableName, tableData);
        
        logger.info("表{}数据生成完成", tableName);
    }

    /**
     * 串行生成列数据
     */
    private void generateColumnsSequentially(String tableName, List<String> columnOrder,
                                           EnhancedTableStatistics tableStats,
                                           long tableSize, Map<String, Object[]> tableData) throws Exception {
        logger.debug("开始串行生成表 {} 的列数据，列顺序: {}", tableName, columnOrder);
        
        // ★★★ 新增：获取bound信息（如果存在）★★★
        Column columnObj = getColumnObjectFromManager(tableName, columnOrder.get(0));
        ruc.db.schema.TableBoundInfo tableBoundInfo = null;
        if (columnObj != null && columnObj.getDistribution() != null) {
            tableBoundInfo = columnObj.getDistribution().getTableBoundInfo();
            if (tableBoundInfo != null && tableBoundInfo.hasBoundConstraints()) {
                logger.info("🔗 BOUND DEBUG: 表 {} 检测到bound约束，将保留Stage 2的bound行", tableName);
            }
        }
        
        for (String columnName : columnOrder) {
            EnhancedColumnStatistics colStats = tableStats.getColumns().get(columnName);
            if (colStats == null) {
                logger.warn("表 {} 的列 {} 统计信息为null，跳过", tableName, columnName);
                continue;
            }
            
            String bucketKey = generateBucketKey(tableName, columnName);
            logger.debug("生成列 {} 数据，bucketKey: {}", columnName, bucketKey);
            
            List<Bucket> buckets = getBucketsForColumn(bucketKey, colStats);
            if (buckets == null || buckets.isEmpty()) {
                logger.warn("表 {} 列 {} 没有buckets数据，生成空值", tableName, columnName);
            } else {
                logger.debug("表 {} 列 {} 有 {} 个buckets", tableName, columnName, buckets.size());
            }
            
            Object[] columnData = generateColumnDataWithBoundHandling(colStats, tableSize, buckets, 
                                                                     tableBoundInfo, columnName);
            if (columnData != null) {
                logger.debug("表 {} 列 {} 生成了 {} 行数据", tableName, columnName, columnData.length);
                // 检查前几个数据值
                if (columnData.length > 0) {
                    Object firstValue = columnData[0];
                    Object secondValue = columnData.length > 1 ? columnData[1] : null;
                    logger.debug("表 {} 列 {} 前两个值: [0]={}, [1]={}", tableName, columnName, firstValue, secondValue);
                }
            } else {
                logger.warn("表 {} 列 {} 生成数据为null", tableName, columnName);
            }
            tableData.put(columnName, columnData);
            
            logger.debug("生成列{}.{}的数据：{}行", tableName, columnName, columnData.length);
        }
    }
    
    /**
     * 列级别并行生成
     */
    private void generateColumnsInParallel(String tableName, List<String> columnOrder, 
                                         EnhancedTableStatistics tableStats, 
                                         long tableSize, Map<String, Object[]> tableData) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 使用ConcurrentHashMap来确保线程安全
        Map<String, Object[]> threadSafeTableData = new ConcurrentHashMap<>();
        
        for (String columnName : columnOrder) {
            EnhancedColumnStatistics colStats = tableStats.getColumns().get(columnName);
            if (colStats == null) continue;
            
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    logger.debug("并行生成列: {}", columnName);
                    String bucketKey = generateBucketKey(tableName, columnName);
                    List<Bucket> buckets = getBucketsForColumn(bucketKey, colStats);
                    Object[] columnData = generateColumnData(colStats, tableSize, buckets);
                    threadSafeTableData.put(columnName, columnData);
                    logger.debug("列 {} 生成完成: {} 行", columnName, columnData.length);
                } catch (Exception e) {
                    logger.error("生成列 {} 时出错: {}", columnName, e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }, executor);
            
            futures.add(future);
        }
        
        // 等待所有列生成完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            logger.error("列并行生成失败: {}", e.getMessage(), e);
            throw e;
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // 将线程安全的数据复制到原始tableData中
        tableData.putAll(threadSafeTableData);
    }

    /**
     * 获取列的buckets（优先使用对齐后的）
     */
    private List<Bucket> getBucketsForColumn(String bucketKey, EnhancedColumnStatistics colStats) {
        // 优先使用对齐后的buckets
        List<Bucket> buckets = alignedBuckets.get(bucketKey);
        if (buckets != null) {
            // logger.debug("使用对齐后的buckets：{}", bucketKey);
            return buckets;
        }
        
        // 否则使用原始buckets
        buckets = globalBuckets.get(bucketKey);
        if (buckets != null) {
            // logger.debug("使用原始buckets：{}", bucketKey);
            return buckets;
        }
        
        logger.warn("未找到列{}的buckets", bucketKey);
        return new ArrayList<>();
    }

    /**
     * 生成列数据（公共方法，供外部调用）
     * 
     * @param colStats 列统计信息
     * @param tableSize 表大小
     * @param buckets bucket列表
     * @return 生成的数据数组
     */
    public Object[] generateColumnDataPublic(EnhancedColumnStatistics colStats, long tableSize, List<Bucket> buckets) {
        return generateColumnData(colStats, tableSize, buckets);
    }
    
    /**
     * 生成列数据
     */
    private Object[] generateColumnData(EnhancedColumnStatistics colStats, long tableSize, List<Bucket> buckets) {
        // 检查是否为varchar类型的非主外键列
        String dataType = colStats.getDataType().toLowerCase();
        boolean isVarcharType = dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text");
        boolean isNonKeyColumn = !colStats.isPrimaryKey() && !colStats.isForeignKey();
        
        // 如果是varchar/bpchar类型的非主外键列，优先使用buckets（当存在MCV注入等显式计数时），否则使用智能生成器
        if (isVarcharType && isNonKeyColumn) {
            // if (hasMcvOrExplicitBuckets(buckets)) {
            //     logger.info("检测到{} 存在MCV/显式桶计数，使用bucket驱动生成", colStats.getColumnName());
            //     // 走下方统一bucket路径
            // } else {
            logger.info("检测到varchar/bpchar类型非主外键列 {}，使用智能生成策略", colStats.getColumnName());
            return varcharGenerator.generateSmartVarcharColumnData(colStats, tableSize);
            
        }
        
        // 如果是主键列，使用智能主键生成策略
        // ★★★ 注意：根据用户设计理念，PK-FK 应该用 mirage 的生成器来生成，这里不应该被调用 ★★★
        if (colStats.isPrimaryKey()) {
            logger.warn("⚠️ 检测到主键列 {}，但根据设计理念，PK应该用mirage生成器生成，这里使用bucket生成策略", colStats.getColumnName());
            // 不再使用 PrimaryKeyDataGenerator，而是使用标准的 bucket 生成策略
            // return primaryKeyDataGenerator.generatePrimaryKeyColumnData(colStats, tableSize, buckets);
        }
        
        // 验证buckets总数
        long totalBucketCount = buckets.stream().mapToLong(Bucket::getCount).sum();
        if (totalBucketCount != tableSize) {
            logger.info("列 {} 的bucket总数 {} 不等于表大小 {}，进行调整", 
                       colStats.getColumnName(), totalBucketCount, tableSize);
            
            // 调整最后一个bucket的count
            if (!buckets.isEmpty()) {
                Bucket lastBucket = buckets.get(buckets.size() - 1);
                long adjustment = tableSize - totalBucketCount;
                long newCount = Math.max(0, lastBucket.getCount() + adjustment);
                lastBucket.setCount(newCount);
                logger.info("调整最后一个bucket的count：{} -> {}", lastBucket.getCount(), newCount);
            }
        }

        // 使用改进的bucket数据生成逻辑
        Object[] data = new Object[(int) tableSize];
        int index = 0;
        
        for (int bucketIdx = 0; bucketIdx < buckets.size(); bucketIdx++) {
            Bucket bucket = buckets.get(bucketIdx);
            int count = (int) bucket.getCount();
            
            Object[] bucketData = generateBucketData(bucket, count, colStats);
            
            // ★★★ 防御性检查：确保bucketData大小与count一致 ★★★
            if (bucketData.length != count) {
                logger.error("bucket数据大小不匹配！期望: {}, 实际: {}", count, bucketData.length);
                // 使用实际的bucketData大小，避免数组越界
                count = Math.min(count, bucketData.length);
            }
            
            // 复制bucket数据到结果数组（添加越界检查）
            if (index + count <= data.length) {
            System.arraycopy(bucketData, 0, data, index, count);
            index += count;
            } else {
                logger.error("数组复制会越界！index={}, count={}, data.length={}", index, count, data.length);
                // 只复制能放下的部分
                int copyCount = data.length - index;
                if (copyCount > 0) {
                    System.arraycopy(bucketData, 0, data, index, copyCount);
                    index += copyCount;
                }
            }
        }
        
        return data;
    }

    /** 直方图桶内按 RSGen 用 DateDataGenerator 的类型（含 timestamp/datetime；date 子串会匹配 datetime） */
    private static boolean isDateLikeHistogramDataType(String dataType) {
        if (dataType == null) {
            return false;
        }
        String dt = dataType.toLowerCase();
        return dt.contains("timestamp") || dt.contains("datetime")
                || (dt.contains("date") && !dt.contains("json"));
    }

    private boolean hasMcvOrExplicitBuckets(List<Bucket> buckets) {
        if (buckets == null || buckets.isEmpty()) return false;
        long total = 0;
        boolean hasMcv = false;
        for (Bucket b : buckets) {
            total += b.getCount();
            if (b.getType() == Bucket.BucketType.MCV) hasMcv = true;
        }
        return hasMcv && total > 0;
    }
    
    /**
     * 打印详细的bucket信息
     */
    private void logBucketDetails(String columnName, List<Bucket> buckets, EnhancedColumnStatistics colStats) {
        logger.info("=== 列 {} 的Bucket详细信息 ===", columnName);
        
        // 从全局统计信息中获取表大小
        long tableSize = 0;
        String tableName = colStats.getTableName();
        if (globalTableStats.containsKey(tableName)) {
            tableSize = globalTableStats.get(tableName).getTableSize();
        }
        
        logger.info("数据类型: {}, 表大小: {}, 统计信息nDistinct: {}", 
                   colStats.getDataType(), tableSize, colStats.getNDistinct());
        
        int nullBucketCount = 0;
        int mcvBucketCount = 0;
        int histogramBucketCount = 0;
        
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            String bucketType = bucket.getType().toString();
            
            switch (bucket.getType()) {
                case NULL:
                    nullBucketCount++;
                    break;
                case MCV:
                    mcvBucketCount++;
                    break;
                case HISTOGRAM:
                    histogramBucketCount++;
                    break;
            }
            
            // 打印每个bucket的详细信息
            String lowStr = bucket.getLow() != null ? bucket.getLow().getValue().toString() : "null";
            String highStr = bucket.getHigh() != null ? bucket.getHigh().getValue().toString() : "null";
            
            logger.info("Bucket[{}]: 类型={}, 区间=[{}, {}], count={}, nDistinct={}", 
                       i, bucketType, lowStr, highStr, bucket.getCount(), bucket.getNDistinct());
        }
        
        logger.info("Bucket统计: NULL={}, MCV={}, HISTOGRAM={}, 总计={}", 
                   nullBucketCount, mcvBucketCount, histogramBucketCount, buckets.size());
        logger.info("=== 列 {} Bucket信息结束 ===", columnName);
    }

    /**
     * 生成单个bucket的数据
     * 根据RSGen论文的算法：将bucket区间划分为nDistinct个子区间，每个子区间取中点作为候选值
     */
    private Object[] generateBucketData(Bucket bucket, int count, EnhancedColumnStatistics colStats) {
        Object[] data = new Object[count];
        String dataType = colStats.getDataType().toLowerCase();
        
        if (bucket.getType() == Bucket.BucketType.NULL) {
            for (int i = 0; i < count; i++) {
                data[i] = null;
            }
        } else if (bucket.getType() == Bucket.BucketType.MCV) {
            // MCV bucket
            Object value = bucket.getLow().getValue();
            for (int i = 0; i < count; i++) {
                data[i] = value;
            }
        } else {
            // Histogram bucket - 使用RSGen核心算法
            if (bucket.getLow() != null && bucket.getHigh() != null) {
                long nDistinct = bucket.getDistinct();
                if (nDistinct <= 1) {
                    // 只有一个唯一值，直接使用low值
                    Object value = bucket.getLow().getValue();
                    for (int i = 0; i < count; i++) {
                        data[i] = value;
                    }
                } else {
                    // 日期/时间直方图：generateValueFromSubInterval 内曾为每行 new DateDataGenerator 并生成 nDistinct 个候选，
                    // 对 count 达百万级时复杂度爆炸（单列可卡数十分钟）。此处按桶只生成一次候选列表再循环取值。
                    if (isDateLikeHistogramDataType(dataType)) {
                        DateDataGenerator dateGenerator = new DateDataGenerator();
                        Bucket dateBucket = new Bucket(bucket.getLow(), bucket.getHigh(), 0, nDistinct, Bucket.BucketType.HISTOGRAM);
                        List<Object> distinctValues = dateGenerator.generateDateDistinctValues(dateBucket, (int) nDistinct);
                        int dvSize = distinctValues.isEmpty() ? 1 : distinctValues.size();
                        for (int i = 0; i < count; i++) {
                            long idx = i % nDistinct;
                            data[i] = distinctValues.get((int) (idx % dvSize));
                        }
                    } else {
                        // 核心算法：将区间划分为nDistinct个子区间，每个子区间取中点
                        for (int i = 0; i < count; i++) {
                            long index = i % nDistinct; // 确定性索引，保证主外键一致性
                            data[i] = generateValueFromSubInterval(bucket.getLow(), bucket.getHigh(), index, nDistinct, dataType);
                        }
                    }
                }
            } else {
                // 边界为null，检查是否有统计信息，如果有则使用智能默认生成
                if (colStats.getMinValue() != null && colStats.getMaxValue() != null) {
                    logger.info("列 {} 的histogram bucket边界为null，但有min/max值，使用智能默认生成", colStats.getColumnName());
                for (int i = 0; i < count; i++) {
                        data[i] = generateSmartDefaultValue(colStats, i);
                    }
                } else {
                    // 没有统计信息，使用简单默认生成
                    logger.info("列 {} 的histogram bucket边界为null且无统计信息，使用简单默认生成", colStats.getColumnName());
                    for (int i = 0; i < count; i++) {
                        data[i] = generateSimpleDefaultValue(colStats, i);
                    }
                }
            }
        }
        
        return data;
    }

    /**
     * 根据分布模型计算位置比例
     * @param index 子区间索引
     * @param nDistinct 总子区间数
     * @param model 分布模型
     * @return 位置比例 (0.0 ~ 1.0)
     */
    private double getPositionRatio(long index, long nDistinct, DistributionModel model) {
        double baseRatio = (double) index / Math.max(1, nDistinct - 1); // 避免除零

        switch (model) {
            case UNIFORM:
                // 均匀分布：简单取中点
                return 0.5;

            case GOLDEN_RATIO:
                // 黄金分割比：避免过于均匀，使用黄金角分割
                return (index * 0.618033988749895) % 1.0;

            case NORMAL:
                // 正态分布：中心密集，边缘稀疏
                // 使用正态分布的累积分布函数近似
                double z = (baseRatio - 0.5) * 3.0; // 3σ范围
                return 0.5 + 0.5 * Math.tanh(z); // sigmoid近似

            case EXPONENTIAL:
                // 指数分布：左侧密集，右侧稀疏
                if (index == 0) return 0.1; // 避免0值
                return 1.0 - Math.exp(-baseRatio * 2.0);

            default:
                return 0.5; // 默认中点
        }
    }

    /**
     * 根据子区间索引生成值
     * 将[low, high]区间划分为nDistinct个子区间，取第index个子区间的中点
     */
    private Object generateValueFromSubInterval(Datum low, Datum high, long index, long nDistinct, String dataType) {
        try {
            if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
                long lowVal = (Long) low.getValue();
                long highVal = (Long) high.getValue();
                
                // 特殊处理：当nDistinct等于区间范围时，直接生成区间内的所有整数值
                long range = highVal - lowVal + 1;
                if (nDistinct == range && range > 0) {
                    // 这种情况下，直接返回区间内的第index个整数值
                    long value = lowVal + index;
                    if (value <= highVal) {
                        return value;
                    } else {
                        // 如果超出范围，循环使用
                        return lowVal + (index % range);
                    }
                }
                
                // 计算子区间边界
                double rangeDouble = (double) (highVal - lowVal);
                double intervalSize = rangeDouble / nDistinct;
                double subIntervalStart = lowVal + (index * intervalSize);
                double subIntervalEnd = lowVal + ((index + 1) * intervalSize);

                // 根据分布模型选择位置（默认为黄金分割比）
                double positionRatio = getPositionRatio(index, nDistinct, distributionModel);
                double value = subIntervalStart + (subIntervalEnd - subIntervalStart) * positionRatio;

                // 确保值在有效范围内
                value = Math.max(subIntervalStart, Math.min(subIntervalEnd - 0.01, value));
                long roundedValue = Math.round(value);

                // 边界检查和调整
                if (roundedValue < lowVal) roundedValue = lowVal;
                if (roundedValue > highVal) roundedValue = highVal;

                return roundedValue;
            } else if (dataType.contains("decimal") || dataType.contains("numeric") || dataType.contains("real") || dataType.contains("float") || dataType.contains("double")) {
                double lowVal = convertToDouble(low.getValue());
                double highVal = convertToDouble(high.getValue());
                double intervalSize = (highVal - lowVal) / nDistinct;
                double subIntervalStart = lowVal + (index * intervalSize);
                double subIntervalEnd = lowVal + ((index + 1) * intervalSize);

                // 根据分布模型选择位置
                double positionRatio = getPositionRatio(index, nDistinct, distributionModel);
                double value = subIntervalStart + (subIntervalEnd - subIntervalStart) * positionRatio;

                // 边界检查
                value = Math.max(lowVal, Math.min(highVal, value));
                return value;
            } else if (dataType.contains("date")) {
                // 日期类型：使用DateDataGenerator生成标准日期
                DateDataGenerator dateGenerator = new DateDataGenerator();
                Bucket dateBucket = new Bucket(low, high, 0, nDistinct, Bucket.BucketType.HISTOGRAM);
                List<Object> distinctValues = dateGenerator.generateDateDistinctValues(dateBucket, (int) nDistinct);
                
                if (!distinctValues.isEmpty()) {
                    int valueIndex = (int) (index % distinctValues.size());
                    return distinctValues.get(valueIndex);
                } else {
                    // 如果生成失败，返回标准日期格式
                    return "1992-01-01";
                }
            } else {
                // 非数值/日期类型：直接返回基础值，不追加索引后缀，避免导入数值型列失败
                String baseValue = low.getValue().toString();
                return baseValue;
            }
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("子区间值生成出错：数据类型={}, low={}, high={}, index={}, nDistinct={}, 错误={}", 
                    dataType, low != null ? low.getValue() : "null", 
                    high != null ? high.getValue() : "null", index, nDistinct, e.getMessage());
            }
            return low != null ? low.getValue() : getDefaultValueForType(dataType);
        }
    }

    /**
     * 安全地将Object转换为double
     */
    private double convertToDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        } else {
            return 0.0;
        }
    }

    /**
     * 根据数据类型获取默认值
     */
    private Object getDefaultValueForType(String dataType) {
        if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
            return 0L;
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            return 0.0;
        } else if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            return "default";
        } else {
            return null;
        }
    }

    /**
     * 获取默认值
     */
    private Object getDefaultValue(EnhancedColumnStatistics colStats) {
        String dataType = colStats.getDataType().toLowerCase();
        
        if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
            return 0L;
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            return 0.0;
        } else if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            return "default";
        } else {
            return null;
        }
    }
    
    /**
     * 生成合理的值（当bucket边界为null时使用）
     */
    /**
     * 智能默认值生成（基于统计信息）
     */
    private Object generateSmartDefaultValue(EnhancedColumnStatistics colStats, int index) {
        String dataType = colStats.getDataType().toLowerCase();
        
        // 专门处理布尔列：按频率生成 true/false
        if (dataType.contains("bool")) {
            return generateBooleanValue(colStats, index);
        }

        // 获取min/max值和nDistinct
        Object minValue = colStats.getMinValue();
        Object maxValue = colStats.getMaxValue();
        long nDistinct = (long) colStats.getNDistinct();
        
        if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
            if (minValue != null && maxValue != null) {
                long min = (Long) minValue;
                long max = (Long) maxValue;
                if (nDistinct > 0 && nDistinct <= (max - min + 1)) {
                    // 在范围内均匀生成
                    long range = max - min + 1;
                    long valueIndex = (long) index % nDistinct;
                    long value = min + (valueIndex * range / nDistinct);
                    return Math.min(value, max);
                } else {
                    // 简单递增
                    return min + (long) index;
                }
            } else {
                return (long) index;
            }
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            if (minValue != null && maxValue != null) {
                double min = convertToDouble(minValue);
                double max = convertToDouble(maxValue);
                if (nDistinct > 0) {
                    // 在范围内均匀生成
                    double range = max - min;
                    long valueIndex = (long) index % nDistinct;
                    double value = min + ((double) valueIndex * range / nDistinct);
                    return Math.min(value, max);
                } else {
                    return min + (double) index;
                }
            } else {
                return (double) index;
            }
        } else if (dataType.contains("date")) {
            if (minValue != null && maxValue != null) {
                // 日期类型处理（兼容 String 与 LocalDate）
                java.time.LocalDate minDate = parseToLocalDate(minValue);
                java.time.LocalDate maxDate = parseToLocalDate(maxValue);
                long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(minDate, maxDate);
                if (nDistinct > 0 && nDistinct <= daysBetween + 1) {
                    long valueIndex = (long) index % nDistinct;
                    long daysToAdd = (valueIndex * daysBetween / nDistinct);
                    return minDate.plusDays(daysToAdd);
                } else {
                    return minDate.plusDays(index);
                }
            } else {
                return java.time.LocalDate.now().plusDays(index);
            }
        } else if (dataType.contains("timestamp")) {
            if (minValue != null && maxValue != null) {
                // 时间戳类型处理（兼容 String 与 LocalDateTime）
                java.time.LocalDateTime minTime = parseToLocalDateTime(minValue);
                java.time.LocalDateTime maxTime = parseToLocalDateTime(maxValue);
                long secondsBetween = java.time.temporal.ChronoUnit.SECONDS.between(minTime, maxTime);
                if (nDistinct > 0 && nDistinct <= secondsBetween + 1) {
                    long valueIndex = (long) index % nDistinct;
                    long secondsToAdd = (valueIndex * secondsBetween / nDistinct);
                    return minTime.plusSeconds(secondsToAdd);
                } else {
                    return minTime.plusSeconds(index);
                }
            } else {
                return java.time.LocalDateTime.now().plusSeconds(index);
            }
        } else if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            // 字符串类型使用简单稳定值，不添加索引后缀（避免导入失败）
            return "value";
        } else {
            // 默认返回索引值
            return index;
        }
    }

    // ===== 布尔列生成：期望累计法，确定性且分布均匀 =====
    private Object generateBooleanValue(EnhancedColumnStatistics colStats, int index) {
        List<String> mcvValues = colStats.getMostCommonValues();
        List<Double> mcvFreqs = colStats.getMostCommonFrequencies();
        double pTrue = 0.5;
        if (mcvValues != null && mcvFreqs != null && !mcvValues.isEmpty() && mcvValues.size() == mcvFreqs.size()) {
            for (int i = 0; i < mcvValues.size(); i++) {
                String v = mcvValues.get(i);
                if (v != null && v.equalsIgnoreCase("true")) {
                    pTrue = Math.max(0.0, Math.min(1.0, mcvFreqs.get(i)));
                    break;
                }
            }
        }
        // 期望累计：第 index 行应当有 expectedTrue 个 true
        // 当 floor(expected) 比已写入 true 多时，此行输出 true
        double expected = (index + 1) * pTrue;
        double prevExpected = index * pTrue;
        boolean out = Math.floor(expected) > Math.floor(prevExpected);
        return out ? Boolean.TRUE : Boolean.FALSE;
    }

    // 兼容 String/LocalDate 的解析
    private java.time.LocalDate parseToLocalDate(Object value) {
        if (value instanceof java.time.LocalDate d) return d;
        String s = String.valueOf(value).trim();
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception ignore) {
        }
        try {
            return java.time.LocalDateTime.parse(s, ruc.db.utils.CommonUtils.INPUT_FMT).toLocalDate();
        } catch (Exception e) {
            // 兜底：当前日期
            return java.time.LocalDate.now();
        }
    }

    // 兼容 String/LocalDateTime 的解析
    private java.time.LocalDateTime parseToLocalDateTime(Object value) {
        if (value instanceof java.time.LocalDateTime dt) return dt;
        String s = String.valueOf(value).trim();
        try {
            return java.time.LocalDateTime.parse(s);
        } catch (Exception ignore) {
        }
        try {
            return java.time.LocalDateTime.parse(s, ruc.db.utils.CommonUtils.INPUT_FMT);
        } catch (Exception e) {
            // 兜底：当前时间
            return java.time.LocalDateTime.now();
        }
    }

    /**
     * 简单默认值生成（无统计信息时）
     */
    private Object generateSimpleDefaultValue(EnhancedColumnStatistics colStats, int index) {
        String dataType = colStats.getDataType().toLowerCase();
        
        if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
            // 生成递增的整数值
            return (long) index;
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            // 生成递增的小数值
            return (double) index;
        } else if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            // 生成有意义的字符串
            return "value_" + index;
        } else if (dataType.contains("date")) {
            // 生成递增的日期
            return java.time.LocalDate.now().plusDays(index);
        } else if (dataType.contains("timestamp")) {
            // 生成递增的时间戳
            return java.time.LocalDateTime.now().plusSeconds(index);
        } else {
            // 默认返回索引值
            return index;
        }
    }

    /**
     * 写入表数据到文件
     */
    private void writeTableDataToFile(String tableName, String outputDir) throws Exception {
        EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
        Map<String, Object[]> tableData = globalTableData.get(tableName);
        
        if (tableStats == null) {
            logger.error("表 {} 的统计信息为null，无法写入数据", tableName);
            throw new IllegalStateException("表 " + tableName + " 的统计信息为null，请检查统计信息提取是否成功");
        }
        
        if (tableData == null) {
            logger.warn("表{}的数据未找到，跳过写入", tableName);
            return;
        }
        
        dataFileWriter.writeDataToFiles(tableName, tableStats, tableData, tableStats.getTableSize(), outputDir);
        logger.info("表{}数据写入完成", tableName);
    }

    /**
     * 获取列顺序
     */
    private List<String> getColumnOrder(EnhancedTableStatistics tableStats) {
        if (tableStats == null) {
            logger.error("tableStats为null，无法获取列顺序");
            throw new IllegalArgumentException("tableStats不能为null");
        }
        
        try {
            String tableName = tableStats.getTableName();
            ruc.db.schema.Table table = ruc.db.schema.TableManager.getInstance().getSchemas().get(tableName);
            if (table != null && table.getCanonicalColumnNames() != null) {
                return new ArrayList<>(table.getCanonicalColumnNames());
            }
        } catch (Exception e) {
            logger.warn("无法从TableManager获取列顺序：{}", e.getMessage());
        }
        
        // Fallback：使用统计信息中的列名
        if (tableStats.getColumns() == null) {
            logger.error("tableStats.getColumns()为null，无法获取列顺序");
            throw new IllegalStateException("tableStats.getColumns()不能为null");
        }
        
        return new ArrayList<>(tableStats.getColumns().keySet());
    }

    /**
     * 生成bucket key
     */
    private String generateBucketKey(String tableName, String columnName) {
        // 如果columnName已经包含了tableName前缀，直接返回columnName
        if (columnName.startsWith(tableName + ".")) {
            return columnName;
        }
        // 如果columnName是完整的规范列名（包含schema.table.column），直接返回
        if (columnName.contains(".") && columnName.split("\\.").length >= 3) {
            return columnName;
        }
        // 否则拼接tableName和columnName
        return tableName + "." + columnName;
    }

    /**
     * 加载增强统计信息
     */
    private Map<String, EnhancedTableStatistics> loadEnhancedStatistics(String inputDir) throws IOException {
        File statsFile = new File(inputDir, "enhanced_column_statistics.json");
        if (!statsFile.exists()) {
            throw new IOException("增强统计信息文件不存在: " + statsFile.getAbsolutePath());
        }
        
        Map<String, EnhancedTableStatistics> stats = objectMapper.readValue(statsFile, 
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, EnhancedTableStatistics.class));
        
        // 验证加载的统计信息
        if (stats == null || stats.isEmpty()) {
            throw new IOException("增强统计信息文件为空或格式错误");
        }
        
        // 检查是否有null值
        for (Map.Entry<String, EnhancedTableStatistics> entry : stats.entrySet()) {
            if (entry.getValue() == null) {
                logger.warn("表 {} 的统计信息为null，将从统计信息中移除", entry.getKey());
                stats.remove(entry.getKey());
            }
        }
        
        logger.info("成功加载 {} 个表的统计信息", stats.size());
        return stats;
    }
    
    /**
     * 应用缩放因子
     */
    private void applyScaleFactor(double scaleFactor) {
        if (scaleFactor != 1.0) {
            logger.info("应用缩放因子: {}", scaleFactor);
            for (EnhancedTableStatistics tableStats : globalTableStats.values()) {
                long originalSize = tableStats.getTableSize();
                long newSize = Math.round(originalSize * scaleFactor);
                tableStats.setTableSize(newSize);
                logger.debug("表{}大小调整: {} -> {}", tableStats.getTableName(), originalSize, newSize);
            }
        }
    }

    // ==================== 内部类 ====================
    
    /**
     * 外键关系
     */
    private static class ForeignKeyRelation {
        final String foreignKeyTable;
        final String foreignKeyColumn;
        final String primaryKeyTable;
        final String primaryKeyColumn;
        
        ForeignKeyRelation(String fkTable, String fkCol, String pkTable, String pkCol) {
            this.foreignKeyTable = fkTable;
            this.foreignKeyColumn = fkCol;
            this.primaryKeyTable = pkTable;
            this.primaryKeyColumn = pkCol;
        }
    }
    
    /**
     * 生成详细的统计报告
     */
    public void generateDetailedReport(String outputDir) throws IOException {
        File reportFile = new File(outputDir, "generation_report.txt");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile, StandardCharsets.UTF_8))) {
            writer.write("RSGen 数据生成统计报告\n");
            writer.write("=".repeat(50) + "\n");
            writer.write("生成时间: " + java.time.LocalDateTime.now() + "\n\n");
            
            // 获取schema中的表（实际参与生成的表）
            TableManager tableManager = TableManager.getInstance();
            Set<String> schemaTables = tableManager.getSchemas().keySet();
            
            // 1. 表类型统计（基于schema中的表）
            writer.write("1. 表类型统计 (基于schema.json中的表):\n");
            int normalTableCount = 0;
            int rootPartitionCount = 0;
            int intermediatePartitionCount = 0;
            int leafTableCount = 0;
            int zeroSizeTableCount = 0;
            
            for (String tableName : schemaTables) {
                EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
                
                if (tableStats == null) {
                    continue;
                }
                
                if (tableStats.getTableSize() == 0) {
                    zeroSizeTableCount++;
                }
                
                if (PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                    rootPartitionCount++;
                } else if (PartitionTableManager.getInstance().isChildTable(tableName) && 
                          !PartitionTableManager.getInstance().isLeafTable(tableName)) {
                    intermediatePartitionCount++;
                } else if (PartitionTableManager.getInstance().isLeafTable(tableName)) {
                    leafTableCount++;
                } else {
                    normalTableCount++;
                }
            }
            
            writer.write("  - 普通表: " + normalTableCount + " 个\n");
            writer.write("  - 根分区表: " + rootPartitionCount + " 个\n");
            writer.write("  - 中间分区表: " + intermediatePartitionCount + " 个\n");
            writer.write("  - 叶子表: " + leafTableCount + " 个\n");
            writer.write("  - 零大小表: " + zeroSizeTableCount + " 个\n");
            writer.write("  - Schema中总表数: " + schemaTables.size() + " 个\n");
            writer.write("  - 提取的总表数: " + globalTableStats.size() + " 个\n\n");
            
            // 2. 拓扑排序统计
            writer.write("2. 拓扑排序统计:\n");
            writer.write("  - 参与拓扑排序的表数: " + (topologicalOrder != null ? topologicalOrder.size() : 0) + " 个\n");
            if (topologicalOrder != null) {
                writer.write("  - 拓扑排序结果: " + topologicalOrder.toString() + "\n");
            }
            writer.write("\n");
            
            // 3. 数据生成统计（基于schema中的表）
            writer.write("3. 数据生成统计:\n");
            int successfullyGenerated = 0;
            int skippedZeroSize = 0;
            int skippedRootOrIntermediate = 0;
            int failed = 0;
            
            for (String tableName : schemaTables) {
                EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
                boolean isRoot = PartitionTableManager.getInstance().isRootPartitionTable(tableName);
                boolean isIntermediate = PartitionTableManager.getInstance().isChildTable(tableName) &&
                        !PartitionTableManager.getInstance().isLeafTable(tableName);
                if (isRoot || isIntermediate) {
                    skippedRootOrIntermediate++;
                } else if (tableStats == null) {
                    failed++;
                } else if (tableStats.getTableSize() == 0) {
                    skippedZeroSize++;
                } else {
                    successfullyGenerated++;
                }
            }
            
            writer.write("  - 成功生成数据的表: " + successfullyGenerated + " 个\n");
            writer.write("  - 因表大小为0跳过的表: " + skippedZeroSize + " 个\n");
            writer.write("  - 因为分区根/中间表跳过: " + skippedRootOrIntermediate + " 个\n");
            writer.write("  - 失败的表(无统计信息或其他错误): " + failed + " 个\n\n");
            
            // 4. 详细的表信息（基于schema中的表）
            writer.write("4. 详细表信息:\n");
            writer.write("-".repeat(50) + "\n");
            
            for (String tableName : schemaTables) {
                EnhancedTableStatistics tableStats = globalTableStats.get(tableName);
                writer.write("表名: " + tableName + "\n");
                
                boolean isRoot = PartitionTableManager.getInstance().isRootPartitionTable(tableName);
                boolean isIntermediate = PartitionTableManager.getInstance().isChildTable(tableName) &&
                        !PartitionTableManager.getInstance().isLeafTable(tableName);
                if (isRoot) {
                    writer.write("  状态: 跳过（根分区表，不参与数据生成）\n");
                } else if (isIntermediate) {
                    writer.write("  状态: 跳过（中间分区表，不参与数据生成）\n");
                } else if (tableStats == null) {
                    writer.write("  状态: 无统计信息\n");
                } else {
                    writer.write("  表大小: " + tableStats.getTableSize() + " 行\n");
                    writer.write("  列数: " + (tableStats.getColumns() != null ? tableStats.getColumns().size() : 0) + " 个\n");
                    
                    String tableType = "普通表";
                    if (PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                        tableType = "根分区表";
                    } else if (PartitionTableManager.getInstance().isChildTable(tableName) && 
                              !PartitionTableManager.getInstance().isLeafTable(tableName)) {
                        tableType = "中间分区表";
                    } else if (PartitionTableManager.getInstance().isLeafTable(tableName)) {
                        tableType = "叶子表";
                    }
                    writer.write("  表类型: " + tableType + "\n");
                    
                    if (tableStats.getTableSize() == 0) {
                        writer.write("  状态: 跳过（表大小为0）\n");
                    } else {
                        writer.write("  状态: 已生成数据\n");
                    }
                }
                writer.write("\n");
            }
            
            logger.info("详细统计报告已生成: {}", reportFile.getAbsolutePath());
        }
    }
    
    /**
     * ★★★ 新增：从ColumnManager获取Column对象 ★★★
     */
    private ruc.db.schema.Column getColumnObjectFromManager(String tableName, String columnName) {
        try {
            String canonicalColumnName = tableName + "." + columnName;
            return ruc.db.schema.ColumnManager.getInstance().getColumn(canonicalColumnName);
        } catch (Exception e) {
            logger.debug("无法从ColumnManager获取列 {}.{}：{}", tableName, columnName, e.getMessage());
            return null;
        }
    }
    
    /**
     * ★★★ 新增：生成列数据，同时处理bound约束 ★★★
     * 如果有bound信息，先从Stage 2的columnActualData中复制bound行，然后生成非bound行
     */
    private Object[] generateColumnDataWithBoundHandling(EnhancedColumnStatistics colStats, 
                                                        long tableSize, 
                                                        List<Bucket> buckets,
                                                        ruc.db.schema.TableBoundInfo tableBoundInfo,
                                                        String columnName) {
        Object[] data = new Object[(int) tableSize];
        
        // 如果没有bound约束，直接使用原始逻辑
        if (tableBoundInfo == null || !tableBoundInfo.hasBoundConstraints()) {
            return generateColumnData(colStats, tableSize, buckets);
        }
        
        // ★★★ 新增逻辑：处理bound约束 ★★★
        logger.info("🔗 BOUND DEBUG: 列 {} 检测到bound约束，使用分离生成策略", columnName);
        
        // 获取bound行的集合
        java.util.Set<Integer> boundRows = tableBoundInfo.getAllBoundRows();
        java.util.Set<Integer> nonBoundRows = tableBoundInfo.getNonBoundRows((int) tableSize);
        
        logger.info("🔗 BOUND DEBUG: 列 {} bound行数: {}, 非bound行数: {}", 
            columnName, boundRows.size(), nonBoundRows.size());
        
        // 尝试从Stage 2中获取已有的bound值
        ruc.db.schema.Column columnObj = getColumnObjectFromManager(colStats.getTableName(), columnName);
        if (columnObj != null && columnObj.getColumnActualData() != null) {
            Object[] columnActualData = columnObj.getColumnActualData();
            logger.info("🔗 BOUND DEBUG: 列 {} 从Stage 2复制bound行数据", columnName);
            
            // 复制bound行
            for (int boundRow : boundRows) {
                if (boundRow < columnActualData.length && columnActualData[boundRow] != null) {
                    data[boundRow] = columnActualData[boundRow];
                    logger.debug("🔗 BOUND DEBUG: 列 {} 行 {} 从Stage 2复制值: {}", 
                        columnName, boundRow, columnActualData[boundRow]);
                }
            }
        } else {
            logger.warn("🔗 BOUND DEBUG: 列 {} 无法从Stage 2获取bound值", columnName);
        }
        
        // 对非bound行，用RSGen生成数据
        if (!nonBoundRows.isEmpty()) {
            logger.info("🔗 BOUND DEBUG: 列 {} 为非bound行({})生成RSGen数据", 
                columnName, nonBoundRows.size());
            
            // 生成足够的非bound行数据
            Object[] rsgGenData = generateColumnData(colStats, nonBoundRows.size(), buckets);
            
            if (rsgGenData != null) {
                int rsgIdx = 0;
                for (int nonBoundRow : nonBoundRows) {
                    if (rsgIdx < rsgGenData.length) {
                        data[nonBoundRow] = rsgGenData[rsgIdx++];
                    }
                }
                logger.info("🔗 BOUND DEBUG: 列 {} 完成非bound行数据生成", columnName);
            }
        }
        
        return data;
    }
}
