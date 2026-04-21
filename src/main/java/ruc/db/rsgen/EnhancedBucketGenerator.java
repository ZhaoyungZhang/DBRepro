package ruc.db.rsgen;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import ruc.db.schema.ColumnType;
import ruc.db.utils.CommonUtils;

/**
 * 增强的Bucket生成器
 * 专门处理增强的统计信息格式，支持主外键列的bucket生成
 * 
 * 主要特性：
 * 1. 处理完整的统计信息（包括主外键）
 * 2. 智能处理缺失的MCV数据
 * 3. 优化的数据类型推断
 * 4. 特殊处理外键列的bucket生成
 * 
 * @author RSGen Implementation
 */
public class EnhancedBucketGenerator {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedBucketGenerator.class);
    
    // 移除MCV数量限制，处理所有MCV值
    // private static final int MAX_MCV_COUNT = 25;  // 最多处理25个最常见值
    private static final int MIN_HISTOGRAM_BUCKETS = 5;  // 最少直方图bucket数

    /** cdfConstraints.json 解析结果缓存，避免每个 batch/每列重复读盘与全量 JSON 解析 */
    private static final Object CDF_CONSTRAINTS_JSON_LOCK = new Object();
    private static String cdfConstraintsCachedAbsPath;
    private static long cdfConstraintsCachedMtime = -1L;
    private static Map<String, Object> cdfConstraintsCachedRoot;
    
    private String projectDir; // 项目目录路径（用于找到cdfConstraints.json文件）
    
    /**
     * 无参构造方法（兼容旧代码）
     */
    public EnhancedBucketGenerator() {
        this.projectDir = null;
    }
    
    /**
     * 带项目目录的构造方法（推荐使用）
     * 
     * @param projectDir 项目目录路径，用于定位cdfConstraints.json
     */
    public EnhancedBucketGenerator(String projectDir) {
        this.projectDir = projectDir;
    }
    
    /**
     * 从增强的统计信息生成Bucket列表
     * 
     * @param stats 列统计信息
     * @param tableSize 表大小（剩余大小，排除bound位置后的大小）
     * @param boundValueCounts bound阶段已生成的值及其count（值 -> count），如果为null表示没有bound值
     * @param totalTableSize 总表大小（用于计算MCV的原始count）
     */
    public List<Bucket> generateBuckets(EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        return generateBuckets(stats, tableSize, null, tableSize);
    }
    
    /**
     * 从增强的统计信息生成Bucket列表（带bound值信息）
     * 
     * @param stats 列统计信息
     * @param tableSize 表大小（剩余大小，排除bound位置后的大小）
     * @param boundValueCounts bound阶段已生成的值及其count（值 -> count），如果为null表示没有bound值
     * @param totalTableSize 总表大小（用于计算MCV的原始count）
     */
    public List<Bucket> generateBuckets(EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize, 
                                       Map<String, Integer> boundValueCounts, long totalTableSize) {
        List<Bucket> buckets = new ArrayList<>();
        
        logger.info("调用generateBuckets方法，开始为列 {} 生成buckets，表大小: {}, 总表大小: {}, bound值数量: {}", 
                   stats.getColumnName(), tableSize, totalTableSize, 
                   boundValueCounts != null ? boundValueCounts.size() : 0);
        
        try {
            // 推断列类型
            ColumnType columnType = inferColumnType(stats);
            
            // 步骤1: 处理NULL值
            addNullBucket(buckets, stats, tableSize);
            
            // 步骤2: 处理MCVs（最常见值），排除bound阶段已生成的值
            addMcvBuckets(buckets, stats, tableSize, boundValueCounts, totalTableSize);
            
            // 步骤3: 处理直方图（基于已有的MCV buckets，将MCV值作为分割点统一组织）
            addHistogramBucketsWithMcvSplits(buckets, stats, columnType, tableSize);
            
            // 步骤4: 特殊处理（主外键优化）
            // optimizeBucketsForKeys(buckets, stats, tableSize);
            
            // 验证bucket总数
            validateBuckets(buckets, tableSize, stats.getColumnName());
            
        } catch (Exception e) {
            logger.error("生成buckets时出错: {}", e.getMessage(), e);
            // 返回一个默认的bucket以避免程序崩溃
            buckets.clear();
            buckets.add(createDefaultBucket(stats, tableSize));
        }
        
        logger.info("列 {} 生成了 {} 个buckets", stats.getColumnName(), buckets.size());
        
        // 输出bucket信息到单独的日志文件
        // writeBucketsToLogFile(stats.getColumnName(), buckets);
        
        return buckets;
    }
    
    /**
     * 专门用于CDF构建的bucket生成方法（参数估计阶段）
     * 
     * 与generateBuckets()的区别：
     * - 不需要主外键优化（optimizeBucketsForKeys）
     * - 不需要bucket验证（validateBuckets）
     * - 不需要写日志文件（writeBucketsToLogFile）
     * - 不需要bound值处理（boundValueCounts参数）
     * 
     * @param stats 列统计信息
     * @param tableSize 表大小
     * @return 生成的bucket列表（已按值排序，包含NULL + MCV + Histogram）
     */
    public List<Bucket> generateBucketsForCDF(EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        List<Bucket> buckets = new ArrayList<>();
        logger.info("调用generateBucketsForCDF方法，列名: {}", stats.getColumnName());
        try {
            // 推断列类型
            ColumnType columnType = inferColumnType(stats);
            
            // 步骤1: 处理NULL值
            addNullBucket(buckets, stats, tableSize);
            
            // 步骤2: 处理MCVs（最常见值），不需要bound值处理
            addMcvBuckets(buckets, stats, tableSize, null, tableSize);
            
            // 步骤3: 处理直方图（基于已有的MCV buckets，将MCV值作为分割点统一组织）
            addHistogramBucketsWithMcvSplits(buckets, stats, columnType, tableSize);
            
            // 注意：跳过步骤4（optimizeBucketsForKeys）- 参数估计不需要主外键优化
            // 注意：跳过validateBuckets - 参数估计不需要严格验证
            // 注意：跳过writeBucketsToLogFile - 参数估计不需要写日志文件
            
        } catch (Exception e) {
            logger.warn("为CDF生成buckets时出错: {}", e.getMessage(), e);
            // 返回一个默认的bucket以避免程序崩溃
            buckets.clear();
            buckets.add(createDefaultBucket(stats, tableSize));
        }
        
        return buckets;
    }
    
    /**
     * 添加NULL值bucket
     */
    private void addNullBucket(List<Bucket> buckets, EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        if (stats.getNullFraction() > 0) {
            long nullCount = Math.round(tableSize * stats.getNullFraction());
            if (nullCount > 0) {
                buckets.add(Bucket.createNullBucket(nullCount));
                logger.debug("添加NULL bucket，count: {}", nullCount);
            }
        }
    }
    
    /**
     * 添加MCV（最常见值）buckets
     * 
     * @param buckets bucket列表
     * @param stats 列统计信息
     * @param tableSize 剩余表大小（排除bound位置后）
     * @param boundValueCounts bound阶段已生成的值及其count（值 -> count），如果为null表示没有bound值
     * @param totalTableSize 总表大小（用于计算MCV的原始count）
     */
    private void addMcvBuckets(List<Bucket> buckets, EnhancedStatsExtractor.EnhancedColumnStatistics stats, 
                               long tableSize, Map<String, Integer> boundValueCounts, long totalTableSize) {
        logger.info("调用addMcvBuckets方法，列名: {}", stats.getColumnName());
        
        // 加载并应用CDF约束（ADD_MCV 和 UPDATE_MCV）
        Map<String, Double> adjustedFrequencies = loadAndAdjustFrequencies(stats);
        
        // 获取MCV数据（可能已被ADD_MCV修改）
        List<String> mcvs = stats.getMostCommonValues();
        List<Double> mcfs = stats.getMostCommonFrequencies();
        
        // 如果有调整后的频率（UPDATE_MCV），使用调整后的频率；否则使用原始频率
        if (!adjustedFrequencies.isEmpty()) {
            mcvs = new ArrayList<>(adjustedFrequencies.keySet());
            mcfs = new ArrayList<>();
            for (String mcv : mcvs) {
                mcfs.add(adjustedFrequencies.get(mcv));
            }
            logger.debug("列 {} 使用调整后的MCV数据（包含 {} 个值）", stats.getColumnName(), mcvs.size());
        } else if (mcvs == null || mcfs == null || mcvs.isEmpty() || mcfs.isEmpty()) {
            logger.debug("没有MCV数据可用于列 {}", stats.getColumnName());
            return;
        }
        
        logFrequencyAdjustments(stats.getColumnName(), mcvs, mcfs, adjustedFrequencies);
        
        int mcvCount = Math.min(mcvs.size(), mcfs.size());
        logger.debug("列 {} 处理 {} 个MCV值{}", stats.getColumnName(), mcvCount,
                    adjustedFrequencies.isEmpty() ? "" : " (已调整频率分布)");

        // Identify MCV values that were already generated in bound phase (should be excluded)
        Set<String> boundValues = identifyBoundValues(mcvs, boundValueCounts, stats.getColumnName());
        
        // Calculate remaining frequency sum for non-bound MCV values
        double remainingFreqSum = calculateRemainingFrequencySum(mcvs, mcfs, boundValues, adjustedFrequencies, mcvCount);
        
        // Create buckets for remaining MCV values
        List<Bucket> mcvBuckets = new ArrayList<>();
        long totalMcvCount = createMcvBuckets(mcvBuckets, mcvs, mcfs, adjustedFrequencies, boundValues, 
                                               remainingFreqSum, totalTableSize, mcvCount, stats);
        
        // Only scale down if MCV count exceeds table size (should not happen, but handle for safety)
        if (totalMcvCount > tableSize) {
            scaleDownMcvBuckets(mcvBuckets, totalMcvCount, tableSize, stats.getColumnName());
            totalMcvCount = tableSize;
        }
        // If totalMcvCount < tableSize, this is normal - remaining rows will be handled by histogram
        
        buckets.addAll(mcvBuckets);
        logger.debug("列 {} 添加了 {} 个MCV buckets，总count: {}, 剩余行数: {}", 
                   stats.getColumnName(), mcvBuckets.size(), totalMcvCount, tableSize - totalMcvCount);
        }
        
    /**
     * Log frequency adjustment information
     */
    private void logFrequencyAdjustments(String columnName, List<String> mcvs, List<Double> mcfs, 
                                        Map<String, Double> adjustedFrequencies) {
            if (!adjustedFrequencies.isEmpty()) {
            logger.info("列 {} 已加载调整后的MCV频率，将用于计算bucket count", columnName);
                double adjustedFreqSum = adjustedFrequencies.values().stream().mapToDouble(Double::doubleValue).sum();
            logger.info("调整后MCV频率总和: {}", adjustedFreqSum);
            // Log first 5 MCV values with adjustments to avoid log bloat
            for (int i = 0; i < Math.min(mcvs.size(), 5); i++) {
                    String mcv = mcvs.get(i);
                    double originalFreq = mcfs.get(i);
                    double adjustedFreq = adjustedFrequencies.getOrDefault(mcv, originalFreq);
                    if (Math.abs(originalFreq - adjustedFreq) > 0.0001) {
                    logger.info("  MCV[{}] '{}': {} -> {} (变化: {})", 
                               i, mcv, originalFreq, adjustedFreq, adjustedFreq - originalFreq);
                    }
                }
            } else {
            logger.debug("列 {} 未找到CDF约束，使用原始MCV频率", columnName);
            }
        }

    /**
     * Identify MCV values that were already generated in bound phase
     */
    private Set<String> identifyBoundValues(List<String> mcvs, Map<String, Integer> boundValueCounts, 
                                            String columnName) {
        Set<String> boundValues = new HashSet<>();
        if (boundValueCounts == null || boundValueCounts.isEmpty()) {
            return boundValues;
        }
        
        for (String mcvValue : mcvs) {
            int boundCount = findBoundCount(mcvValue, boundValueCounts);
            if (boundCount > 0) {
                boundValues.add(mcvValue);
                logger.debug("MCV值 '{}' 已在bound阶段生成 {} 行，将完全排除", mcvValue, boundCount);
            }
        }
        return boundValues;
    }
    
    /**
     * Find bound count for a given MCV value using flexible matching
     */
    private int findBoundCount(String mcvValue, Map<String, Integer> boundValueCounts) {
        // Direct match
        if (boundValueCounts.containsKey(mcvValue)) {
            return boundValueCounts.get(mcvValue);
        }
        
        // Try trimmed match
        String trimmedValue = mcvValue.trim();
        if (boundValueCounts.containsKey(trimmedValue)) {
            return boundValueCounts.get(trimmedValue);
        }
        
        // Try normalized match
        for (Map.Entry<String, Integer> entry : boundValueCounts.entrySet()) {
            String boundKey = entry.getKey();
            if (boundKey != null && boundKey.trim().equals(trimmedValue)) {
                return entry.getValue();
            }
        }
        
        return 0;
    }
    
    /**
     * Calculate remaining frequency sum for non-bound MCV values
     */
    private double calculateRemainingFrequencySum(List<String> mcvs, List<Double> mcfs, Set<String> boundValues,
                                                  Map<String, Double> adjustedFrequencies, int mcvCount) {
        double remainingFreqSum = 0.0;
        for (int i = 0; i < mcvCount; i++) {
            String value = mcvs.get(i);
            if (!boundValues.contains(value)) {
                double originalFreq = mcfs.get(i);
                double adjustedFreq = adjustedFrequencies.getOrDefault(value, originalFreq);
                remainingFreqSum += adjustedFreq;
            }
        }
        return remainingFreqSum;
                }
                
    /**
     * Create MCV buckets for remaining values
     * 使用精确计算避免Math.round()的累积误差
     */
    private long createMcvBuckets(List<Bucket> mcvBuckets, List<String> mcvs, List<Double> mcfs,
                                  Map<String, Double> adjustedFrequencies, Set<String> boundValues,
                                  double remainingFreqSum, long totalTableSize, int mcvCount,
                                  EnhancedStatsExtractor.EnhancedColumnStatistics stats) {
        // 先计算所有MCV值的精确count（使用double避免精度损失）
        List<Double> exactCounts = new ArrayList<>();
        double totalExactCount = 0.0;
        
        for (int i = 0; i < mcvCount; i++) {
            String value = mcvs.get(i);
            if (boundValues.contains(value)) {
                exactCounts.add(0.0);
                    continue;
                }
                
            double originalFreq = mcfs.get(i);
            double frequency = adjustedFrequencies.getOrDefault(value, originalFreq);
            
            if (frequency > 0 && remainingFreqSum > 0) {
                double exactCount = totalTableSize * frequency;
                exactCounts.add(exactCount);
                totalExactCount += exactCount;
            } else {
                exactCounts.add(0.0);
            }
        }
        
        // 使用累积舍入误差修正算法（类似 Bresenham 算法）
        // 确保所有count的总和等于 Math.round(totalExactCount)
        long totalMcvCount = 0;
        double accumulatedError = 0.0;
        long targetTotal = Math.round(totalExactCount);
        
        for (int i = 0; i < mcvCount; i++) {
            String value = mcvs.get(i);
            if (boundValues.contains(value)) {
                continue;
            }
            
            double exactCount = exactCounts.get(i);
            if (exactCount <= 0) {
                continue;
            }
            
            // 累积误差修正
            double adjustedCount = exactCount + accumulatedError;
            long roundedCount = Math.round(adjustedCount);
            accumulatedError = adjustedCount - roundedCount;
            
                    Datum mcvDatum = createDatum(value, stats.getDataType());
                    if (mcvDatum != null) {
                Bucket mcvBucket = new Bucket(mcvDatum, mcvDatum, roundedCount, 1, Bucket.BucketType.MCV);
                    mcvBuckets.add(mcvBucket);
                totalMcvCount += roundedCount;
            }
        }
        
        // 处理舍入误差：如果总和与目标不一致，调整最后一个bucket
        long difference = targetTotal - totalMcvCount;
        if (difference != 0 && !mcvBuckets.isEmpty()) {
            Bucket lastBucket = mcvBuckets.get(mcvBuckets.size() - 1);
            long newCount = Math.max(0, lastBucket.getCount() + difference);
            totalMcvCount = totalMcvCount - lastBucket.getCount() + newCount;
            lastBucket.setCount(newCount);
        }
        
        return totalMcvCount;
    }
    
    /**
     * Scale down MCV buckets if total count exceeds table size
     */
    private void scaleDownMcvBuckets(List<Bucket> mcvBuckets, long totalMcvCount, long tableSize, String columnName) {
        double scaleFactor = (double) tableSize / totalMcvCount;
        logger.warn("列 {} MCV buckets总count {} 超过表大小 {}，按比例 {} 缩小", 
                   columnName, totalMcvCount, tableSize, scaleFactor);
        
        long adjustedTotal = 0;
        for (Bucket bucket : mcvBuckets) {
            long newCount = Math.round(bucket.getCount() * scaleFactor);
            bucket.setCount(newCount);
            adjustedTotal += newCount;
        }
        
        // Handle rounding errors: add difference to last bucket
        long difference = tableSize - adjustedTotal;
        if (difference != 0 && !mcvBuckets.isEmpty()) {
            Bucket lastBucket = mcvBuckets.get(mcvBuckets.size() - 1);
            lastBucket.setCount(lastBucket.getCount() + difference);
        }
    }

    /**
     * 加载CDF约束信息并调整MCV频率分布
     * 
     * Load CDF constraint information and adjust MCV frequency distribution.
     * 
     * Note: This method only processes constraints that truly need adjustment.
     * The instantiate phase has already filtered out:
     * 1. Constraints with selectivity=1 (already decoupled)
     * 2. Columns with Histogram (can theoretically always find suitable values)
     * 
     * So we can directly load and adjust here without re-checking.
     * 
     * @param stats 列统计信息
     * @return 调整后的频率映射，如果没有约束则返回空Map
     */
    private Map<String, Double> loadAndAdjustFrequencies(EnhancedStatsExtractor.EnhancedColumnStatistics stats) {
        Map<String, Double> adjustedFrequencies = new HashMap<>();

        try {
            // 尝试加载CDF约束文件，从多个可能的位置查找
            File constraintsFile = findConstraintsFile();
            if (constraintsFile == null || !constraintsFile.exists()) {
                logger.debug("CDF约束文件不存在，跳过频率调整");
                return adjustedFrequencies;
            }

            // 尝试加载 varcharpatterns.json（只加载一次；供 Stage3 的 matcher 做 pattern-aware 比较）
            try {
                File patternsFile = findVarcharPatternsFile();
                if (patternsFile != null && patternsFile.exists()) {
                    logger.debug("尝试加载 varcharpatterns.json: {}", patternsFile.getAbsolutePath());
                    ruc.db.utils.VarcharPatternManager.tryLoadFromDistributionDir(patternsFile.getParentFile().getAbsolutePath());
                    logger.debug("varcharpatterns.json 加载完成");
                } else {
                    logger.debug("varcharpatterns.json 文件不存在，跳过加载");
                }
            } catch (Exception e) {
                logger.warn("加载 varcharpatterns.json 失败: {}", e.getMessage());
                // 不影响主流程
            }

            String absPath = constraintsFile.getAbsolutePath();
            long mtime = constraintsFile.lastModified();
            Map<String, Object> constraintsRoot;
            synchronized (CDF_CONSTRAINTS_JSON_LOCK) {
                if (cdfConstraintsCachedRoot != null
                        && absPath.equals(cdfConstraintsCachedAbsPath)
                        && mtime == cdfConstraintsCachedMtime) {
                    constraintsRoot = cdfConstraintsCachedRoot;
                } else {
                    logger.debug("加载并缓存 CDF 约束文件: {} (mtime={})", absPath, mtime);
                    String content = CommonUtils.readFile(constraintsFile.getPath());
                    cdfConstraintsCachedRoot = CommonUtils.MAPPER.readValue(content, new TypeReference<Map<String, Object>>() {});
                    cdfConstraintsCachedAbsPath = absPath;
                    cdfConstraintsCachedMtime = mtime;
                    constraintsRoot = cdfConstraintsCachedRoot;
                }
            }

            String columnName = stats.getColumnName();
            if (!constraintsRoot.containsKey(columnName)) {
                return adjustedFrequencies; // 没有该列的约束
            }

            Object columnConstraintsObj = constraintsRoot.get(columnName);
            if (!(columnConstraintsObj instanceof Map)) {
                logger.warn("列 {} 的约束对象类型不正确，期望 Map，实际: {}，跳过解析",
                           columnName, columnConstraintsObj != null ? columnConstraintsObj.getClass().getSimpleName() : "null");
                return adjustedFrequencies;
            }

            // 深拷贝该列子树：CdfConstraintsApplier 会向子 Map 写入 _pattern，不能污染缓存根对象
            String columnJson = CommonUtils.MAPPER.writeValueAsString(columnConstraintsObj);
            @SuppressWarnings("unchecked")
            Map<String, Object> valuesMap = CommonUtils.MAPPER.readValue(columnJson, new TypeReference<Map<String, Object>>() {});

            // ★★★ 使用 CdfConstraintsApplier 统一处理所有约束（ADD_MCV 和 UPDATE_MCV）★★★
            adjustedFrequencies = ruc.db.utils.CdfConstraintsApplier.applyConstraintsAndGetAdjustedFrequencies(stats, valuesMap);

        } catch (Exception e) {
            logger.warn("加载CDF约束信息失败: {}", e.getMessage());
        }

        return adjustedFrequencies;
    }
    
    /**
     * 查找CDF约束文件
     */
    private File findConstraintsFile() {
        List<String> candidatePaths = new ArrayList<>();
        
        if (projectDir != null && !projectDir.isEmpty()) {
            candidatePaths.add(projectDir + "/distribution/cdfConstraints.json");
        }
        
        String cwd = System.getProperty("user.dir");
        candidatePaths.add(cwd + "/distribution/cdfConstraints.json");
        candidatePaths.add("distribution/cdfConstraints.json");

        for (String path : candidatePaths) {
            File file = new File(path);
            if (file.exists()) {
                // logger.debug("✓ 找到CDF约束文件: {}", file.getAbsolutePath());
                return file;
            }
        }
        // logger.debug("✗ 未找到CDF约束文件，已尝试 {} 个路径", candidatePaths.size());
        // if (logger.isDebugEnabled()) {
        //     logger.debug("尝试过的路径:");
        //     for (String path : candidatePaths) {
        //         logger.debug("  - {}", path);
        //     }
        // }
        return null;
    }

    /**
     * 查找 varcharpatterns.json（与 cdfConstraints.json 同目录）
     */
    private File findVarcharPatternsFile() {
        List<String> candidatePaths = new ArrayList<>();
        if (projectDir != null && !projectDir.isEmpty()) {
            candidatePaths.add(projectDir + "/distribution/varcharpatterns.json");
        }
        String cwd = System.getProperty("user.dir");
        candidatePaths.add(cwd + "/distribution/varcharpatterns.json");
        candidatePaths.add("distribution/varcharpatterns.json");
        for (String path : candidatePaths) {
            File f = new File(path);
            if (f.exists()) return f;
        }
        return null;
    }
    
    // ========== 以下方法已删除，统一由 CdfConstraintsApplier 处理 ==========
    
    
    /**
     * 添加直方图buckets（基于已有的MCV buckets，将MCV值作为分割点统一组织）
     */
    private void addHistogramBucketsWithMcvSplits(List<Bucket> buckets, EnhancedStatsExtractor.EnhancedColumnStatistics stats, 
                                                  ColumnType columnType, long tableSize) {
        List<String> histogramBounds = stats.getHistogramBounds();
        
        if (histogramBounds == null || histogramBounds.size() < 2) {
            logger.debug("没有足够的直方图数据用于列 {}", stats.getColumnName());
            return;
        }
        
        // 计算已用的行数（NULL + MCV）
        long usedRows = buckets.stream().mapToLong(Bucket::getCount).sum();
        long remainingRows = tableSize - usedRows;
        
        if (remainingRows <= 0) {
            logger.debug("所有行已被NULL和MCV buckets覆盖");
            return;
        }
        
        // 计算剩余的distinct值
        long totalDistinct = calculateTotalDistinct(stats, tableSize);
        long usedDistinct = buckets.stream()
            .filter(bucket -> bucket.getType() != Bucket.BucketType.NULL)
            .mapToLong(Bucket::getDistinct)
            .sum();
        long remainingDistinct = Math.max(1, totalDistinct - usedDistinct);
        
        logger.debug("列 {} 统一bucket生成：剩余行数={}, 剩余distinct={}, 直方图边界数={}", 
                   stats.getColumnName(), remainingRows, remainingDistinct, histogramBounds.size());
        
        // 提取已有的MCV buckets（不包含NULL）
        List<Bucket> mcvBuckets = new ArrayList<>();
        List<Bucket> otherBuckets = new ArrayList<>(); // NULL buckets
        for (Bucket bucket : buckets) {
            if (bucket.getType() == Bucket.BucketType.MCV) {
                mcvBuckets.add(bucket);
            } else if (bucket.getType() == Bucket.BucketType.NULL) {
                otherBuckets.add(bucket);
            }
        }
        
        // 如果没有MCV buckets，使用原来的方法
        if (mcvBuckets.isEmpty()) {
            createHistogramBuckets(buckets, histogramBounds, remainingRows, remainingDistinct, stats.getDataType(), stats);
            return;
        }
        
        // 提取MCV值的Datum对象（作为分割点）
        List<Datum> mcvDatums = new ArrayList<>();
        for (Bucket mcvBucket : mcvBuckets) {
            if (mcvBucket.getLow() != null) {
                mcvDatums.add(mcvBucket.getLow()); // MCV bucket的low和high是相同的
            }
        }
        
        // 收集所有分割点（histogram边界 + MCV值）
        List<Datum> allSplitPoints = new ArrayList<>();
        String dataType = stats.getDataType();
        
        // 添加histogram边界
        for (String bound : histogramBounds) {
            Datum boundDatum = createDatum(bound, dataType);
            if (boundDatum != null) {
                allSplitPoints.add(boundDatum);
            }
        }
        
        // 添加MCV值
        allSplitPoints.addAll(mcvDatums);
        
        // 去重并排序
        allSplitPoints = allSplitPoints.stream()
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.toList());
        
        if (allSplitPoints.size() < 2) {
            logger.warn("列 {} 分割点不足，使用原有方法", stats.getColumnName());
            createHistogramBuckets(buckets, histogramBounds, remainingRows, remainingDistinct, dataType, stats);
            return;
        }
        
        logger.info("列 {} 统一bucket生成：{} 个分割点（{} 个histogram边界 + {} 个MCV值）",
                   stats.getColumnName(), allSplitPoints.size(),
                   histogramBounds.size(), mcvDatums.size());
        
        // 清空buckets列表（保留NULL），重新组织
        buckets.clear();
        buckets.addAll(otherBuckets); // 先添加NULL buckets
        
        // 创建MCV buckets的映射（value -> bucket），用于后续查找
        Map<Datum, Bucket> mcvBucketMap = new HashMap<>();
        for (Bucket mcvBucket : mcvBuckets) {
            if (mcvBucket.getLow() != null) {
                mcvBucketMap.put(mcvBucket.getLow(), mcvBucket);
            }
        }
        
        // 创建histogram buckets的映射（区间 -> 原始count和distinct）
        // 这里需要先计算每个原始histogram区间的count和distinct
        int numOriginalHistogramBuckets = histogramBounds.size() - 1;
        long countPerOriginalBucket = remainingRows / numOriginalHistogramBuckets;
        long distinctPerOriginalBucket = Math.max(1, remainingDistinct / numOriginalHistogramBuckets);
        
        // 遍历所有分割点区间，创建bucket
        long usedHistogramCount = 0;
        long usedHistogramDistinct = 0;
        int histogramBucketIndex = 0;
        
        for (int i = 0; i < allSplitPoints.size() - 1; i++) {
            Datum low = allSplitPoints.get(i);
            Datum high = allSplitPoints.get(i + 1);
            
            // 检查low是否是MCV值
            if (mcvBucketMap.containsKey(low)) {
                // 创建MCV bucket（保持原有属性）
                Bucket mcvBucket = mcvBucketMap.get(low);
                buckets.add(mcvBucket);
                //logger.debug("添加MCV bucket: {} (count={})", low.getValue(), mcvBucket.getCount());
            }
            
            // 检查区间内是否有其他MCV值（在low和high之间）
            List<Datum> mcvsInRange = new ArrayList<>();
            for (int j = i + 1; j < allSplitPoints.size(); j++) {
                Datum sp = allSplitPoints.get(j);
                if (mcvBucketMap.containsKey(sp) && 
                    sp.compareTo(low) > 0 && sp.compareTo(high) < 0) {
                    mcvsInRange.add(sp);
                }
            }
            
            // 为区间内的MCV值创建bucket（按值顺序）
            mcvsInRange.sort(Datum::compareTo);
            for (Datum mcvDatum : mcvsInRange) {
                Bucket mcvBucket = mcvBucketMap.get(mcvDatum);
                buckets.add(mcvBucket);
                //logger.debug("添加MCV bucket (区间内): {} (count={})", mcvDatum.getValue(), mcvBucket.getCount());
            }
            
            // 创建histogram bucket（区间bucket）
            // 只对在histogram范围内的区间创建histogram bucket
            Datum histogramMin = createDatum(histogramBounds.get(0), dataType);
            Datum histogramMax = createDatum(histogramBounds.get(histogramBounds.size() - 1), dataType);
            
            // 检查区间是否与histogram范围有重叠
            boolean overlapsWithHistogram = false;
            if (histogramMin != null && histogramMax != null) {
                // 有重叠：low <= histogramMax && high >= histogramMin
                overlapsWithHistogram = low.compareTo(histogramMax) <= 0 && high.compareTo(histogramMin) >= 0;
            }
            
            if (overlapsWithHistogram) {
                // 调整边界值，使其不包含MCV值，避免重复生成
                Datum adjustedLow = adjustBucketBoundaryForMcv(low, mcvBucketMap, dataType, true);  // 下界：如果等于MCV，增加
                Datum adjustedHigh = adjustBucketBoundaryForMcv(high, mcvBucketMap, dataType, false); // 上界：如果等于MCV，减少
                
                // 确保调整后的边界在histogram范围内（只有当边界不在histogram范围内时才调整）
                if (histogramMin != null && adjustedLow.compareTo(histogramMin) < 0) {
                    adjustedLow = histogramMin;
                }
                if (histogramMax != null && adjustedHigh.compareTo(histogramMax) > 0) {
                    adjustedHigh = histogramMax;
                }
                
                // 只有当调整后的区间有效且与histogram范围有重叠时才创建histogram bucket
                if (adjustedLow.compareTo(adjustedHigh) < 0 && 
                    adjustedLow.compareTo(histogramMax) <= 0 && adjustedHigh.compareTo(histogramMin) >= 0) {
                    // 计算该区间应该占用的count和distinct（基于原始histogram分布）
                    long intervalCount = calculateHistogramIntervalCount(adjustedLow, adjustedHigh, histogramBounds, 
                                                                       remainingRows, numOriginalHistogramBuckets,
                                                                       usedHistogramCount, dataType);
                    long intervalDistinct = calculateHistogramIntervalDistinct(adjustedLow, adjustedHigh, histogramBounds,
                                                                              remainingDistinct, numOriginalHistogramBuckets,
                                                                              distinctPerOriginalBucket, mcvsInRange.size(), dataType);
                    
                    if (intervalCount > 0) {
                        buckets.add(Bucket.createHistogramBucket(adjustedLow, adjustedHigh, intervalCount, intervalDistinct));
                        usedHistogramCount += intervalCount;
                        histogramBucketIndex++;
                        // logger.debug("添加Histogram bucket: [{} - {}] (count={}, distinct={}, 已调整边界避免MCV重复)",
                        //            adjustedLow.getValue(), adjustedHigh.getValue(), intervalCount, intervalDistinct);
                    }
                }
            } else {
                // 区间完全在histogram范围外，不创建histogram bucket
                // logger.debug("跳过histogram bucket创建，区间 [{}, {}] 完全在histogram范围外", low.getValue(), high.getValue());
            }
        }
        
        // 处理剩余count（累积误差修正）
        long remainingHistogramCount = remainingRows - usedHistogramCount;
        if (remainingHistogramCount != 0 && !buckets.isEmpty()) {
            // 调整最后一个histogram bucket
            Bucket lastHistBucket = null;
            for (int i = buckets.size() - 1; i >= 0; i--) {
                if (buckets.get(i).getType() == Bucket.BucketType.HISTOGRAM) {
                    lastHistBucket = buckets.get(i);
                    break;
                }
            }
            if (lastHistBucket != null) {
                lastHistBucket.setCount(lastHistBucket.getCount() + remainingHistogramCount);
                logger.debug("调整最后一个histogram bucket的count: {} -> {}",
                           lastHistBucket.getCount() - remainingHistogramCount, lastHistBucket.getCount());
            }
        }
        
        logger.info("列 {} 统一bucket生成完成：共 {} 个buckets（NULL: {}, MCV: {}, Histogram: {}）",
                   stats.getColumnName(), buckets.size(),
                   otherBuckets.size(), mcvBuckets.size(), histogramBucketIndex);
    }
    
    /**
     * 计算histogram区间的count（基于原始histogram分布）
     */
    private long calculateHistogramIntervalCount(Datum low, Datum high, List<String> histogramBounds,
                                                long totalCount, int numOriginalBuckets,
                                                long usedCount, String dataType) {
        Datum histogramMin = createDatum(histogramBounds.get(0), dataType);
        Datum histogramMax = createDatum(histogramBounds.get(histogramBounds.size() - 1), dataType);
        
        // 如果区间完全在histogram范围外，返回0
        if (histogramMin != null && histogramMax != null) {
            if (high.compareTo(histogramMin) < 0 || low.compareTo(histogramMax) > 0) {
                return 0;
            }
        }
        
        // 计算该区间覆盖了多少原始histogram buckets
        int coveredOriginalBuckets = 0;
        for (int i = 0; i < histogramBounds.size() - 1; i++) {
            Datum origLow = createDatum(histogramBounds.get(i), dataType);
            Datum origHigh = createDatum(histogramBounds.get(i + 1), dataType);
            
            // 检查原始bucket是否与当前区间有重叠
            // 注意：使用 < 和 > 来检查重叠（因为我们已经调整了边界，避免MCV值重复）
            if (origLow != null && origHigh != null) {
                // 有重叠：origLow < high && origHigh > low（使用严格不等式避免边界问题）
                if (origLow.compareTo(high) < 0 && origHigh.compareTo(low) > 0) {
                    coveredOriginalBuckets++;
                }
            }
        }
        
        // 如果没有覆盖任何原始bucket，但区间在histogram范围内，按区间长度比例分配
        if (coveredOriginalBuckets == 0 && histogramMin != null && histogramMax != null) {
            // 计算区间在histogram范围内的长度比例
            try {
                double intervalLength = high.getNumericValue() - low.getNumericValue();
                double histogramLength = histogramMax.getNumericValue() - histogramMin.getNumericValue();
                if (histogramLength > 0 && intervalLength > 0) {
                    // 按长度比例分配count
                    double ratio = intervalLength / histogramLength;
                    long intervalCount = (long) (ratio * totalCount);
                    long remainingCount = totalCount - usedCount;
                    return Math.max(1, Math.min(intervalCount, remainingCount)); // 至少返回1，避免返回0
                }
            } catch (Exception e) {
                // 如果无法计算数值，使用默认方法：平均分配剩余的count
                long remainingCount = totalCount - usedCount;
                if (remainingCount > 0) {
                    // 粗略估计：假设还有一部分区间需要分配，返回一个小值
                    return Math.max(1, remainingCount / numOriginalBuckets);
                }
            }
        }
        
        // 基于覆盖的原始bucket数量计算count
        long intervalCount = (long) ((double) coveredOriginalBuckets / numOriginalBuckets * totalCount);
        
        // 确保不超过剩余count，并且至少为1（除非剩余count为0）
        long remainingCount = totalCount - usedCount;
        if (remainingCount <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(intervalCount, remainingCount));
    }
    
    /**
     * 计算histogram区间的distinct（基于原始histogram分布）
     */
    private long calculateHistogramIntervalDistinct(Datum low, Datum high, List<String> histogramBounds,
                                                   long totalDistinct, int numOriginalBuckets,
                                                   long distinctPerOriginalBucket, int mcvCountInRange, String dataType) {
        // 计算该区间覆盖了多少原始histogram buckets
        int coveredOriginalBuckets = 0;
        for (int i = 0; i < histogramBounds.size() - 1; i++) {
            Datum origLow = createDatum(histogramBounds.get(i), dataType);
            Datum origHigh = createDatum(histogramBounds.get(i + 1), dataType);
            
            if (origLow != null && origHigh != null) {
                // 注意：使用 <= 和 >= 来包含边界（因为我们已经调整了边界，避免MCV值重复）
                if (origLow.compareTo(high) <= 0 && origHigh.compareTo(low) >= 0) {
                    coveredOriginalBuckets++;
                }
            }
        }
        
        if (coveredOriginalBuckets == 0) {
            return Math.max(1, distinctPerOriginalBucket);
        }
        
        // 基于覆盖的原始bucket数量计算distinct
        long intervalDistinct = (long) ((double) coveredOriginalBuckets / numOriginalBuckets * totalDistinct);
        
        // 如果区间内有MCV值，从distinct中扣除（每个MCV值占用1个distinct）
        intervalDistinct = Math.max(1, intervalDistinct - mcvCountInRange);
        
        return intervalDistinct;
    }
    
    /**
     * 调整bucket边界值，使其不包含MCV值（避免重复生成）
     * 
     * @param boundary 边界值
     * @param mcvBucketMap MCV bucket映射（value -> bucket）
     * @param dataType 数据类型
     * @param isLowerBound 是否为下界（true=下界，false=上界）
     * @return 调整后的边界值
     */
    private Datum adjustBucketBoundaryForMcv(Datum boundary, Map<Datum, Bucket> mcvBucketMap, 
                                             String dataType, boolean isLowerBound) {
        // 如果边界值不在MCV值集合中，不需要调整
        if (!mcvBucketMap.containsKey(boundary)) {
            return boundary;
        }
        
        // 根据数据类型调整边界值
        Datum.DatumType datumType = convertColumnTypeToDatumType(dataType);
        
        try {
            switch (datumType) {
                case INTEGER:
                    // INTEGER类型：±1
                    long intValue = (Long) boundary.getValue();
                    long adjustedInt = isLowerBound ? intValue + 1 : intValue - 1;
                    return Datum.createInteger(adjustedInt);
                    
                case DECIMAL:
                    // DECIMAL类型：±1e-9
                    java.math.BigDecimal decValue = (java.math.BigDecimal) boundary.getValue();
                    java.math.BigDecimal epsilon = new java.math.BigDecimal("0.0000001");
                    java.math.BigDecimal adjustedDec = isLowerBound ? 
                        decValue.add(epsilon) : decValue.subtract(epsilon);
                    return Datum.createDecimal(adjustedDec);
                    
                case DATE:
                    // DATE类型：±1天
                    java.time.LocalDate dateValue = (java.time.LocalDate) boundary.getValue();
                    java.time.LocalDate adjustedDate = isLowerBound ? 
                        dateValue.plusDays(1) : dateValue.minusDays(1);
                    return Datum.createDate(adjustedDate);
                    
                case VARCHAR:
                    // VARCHAR类型：暂时不处理，直接返回原值
                    //logger.debug("VARCHAR类型的bucket边界值等于MCV值，暂不调整（边界值: {}）", boundary.getValue());
                    return boundary;
                    
                default:
                    // 其他类型：不调整
                    return boundary;
            }
        } catch (Exception e) {
            logger.warn("调整bucket边界值失败，使用原值: {}", e.getMessage());
            return boundary;
        }
    }
    
    /**
     * 添加直方图buckets（原有方法，保留作为fallback）
     */
    private void addHistogramBuckets(List<Bucket> buckets, EnhancedStatsExtractor.EnhancedColumnStatistics stats, 
                                    ColumnType columnType, long tableSize) {
        List<String> histogramBounds = stats.getHistogramBounds();
        
        if (histogramBounds == null || histogramBounds.size() < 2) {
            logger.debug("没有足够的直方图数据用于列 {}", stats.getColumnName());
            return;
        }
        
        // 计算已用的行数（NULL + MCV）
        long usedRows = buckets.stream().mapToLong(Bucket::getCount).sum();
        long remainingRows = tableSize - usedRows;
        
        if (remainingRows <= 0) {
            logger.debug("所有行已被NULL和MCV buckets覆盖");
            return;
        }
        
        // 计算剩余的distinct值
        long totalDistinct = calculateTotalDistinct(stats, tableSize);
        long usedDistinct = buckets.stream()
            .filter(bucket -> bucket.getType() != Bucket.BucketType.NULL)
            .mapToLong(Bucket::getDistinct)
            .sum();
        long remainingDistinct = Math.max(1, totalDistinct - usedDistinct);
        
        logger.debug("列 {} 直方图建模：剩余行数={}, 剩余distinct={}, 直方图边界数={}", 
                   stats.getColumnName(), remainingRows, remainingDistinct, histogramBounds.size());
        
        // 创建直方图buckets，避免与MCV重复
        createHistogramBuckets(buckets, histogramBounds, remainingRows, remainingDistinct, stats.getDataType(), stats);
    }
    
    /**
     * 创建直方图buckets，避免与MCV重复
     */
    private void createHistogramBuckets(List<Bucket> buckets, List<String> bounds,
                                       long totalCount, long totalDistinct, String dataType,
                                       EnhancedStatsExtractor.EnhancedColumnStatistics stats) {
        if (bounds.size() < 2) return;
        
        int numBuckets = bounds.size() - 1; // 使用实际的bucket数量，不强制最小数量
        long countPerBucket = totalCount / numBuckets;
        long distinctPerBucket = Math.max(1, totalDistinct / numBuckets);
        
        logger.debug("创建 {} 个直方图bucket，每个bucket基础count={}, 基础distinct={}",
                   numBuckets, countPerBucket, distinctPerBucket);

        // 获取MCV值集合，用于过滤重复值
        Set<String> mcvValues = new HashSet<>();
        if (stats.getMostCommonValues() != null) {
            mcvValues.addAll(stats.getMostCommonValues());
        }

        int actualBucketIndex = 0;
        for (int i = 0; i < bounds.size() - 1; i++) {
            String lowStr = bounds.get(i);
            String highStr = bounds.get(i + 1);
            
            // 检查边界值是否已经在MCV中，如果是则跳过这个bucket（避免重复生成）
            if (mcvValues.contains(lowStr) || mcvValues.contains(highStr)) {
                logger.info("跳过直方图bucket [{} - {}]，因为边界值已在MCV中", lowStr, highStr);
                continue;
            }

            Datum low = createDatum(lowStr, dataType);
            Datum high = createDatum(highStr, dataType);

            // 最后一个bucket包含剩余的所有数据
            long bucketCount = (i == bounds.size() - 2) ?
                (totalCount - countPerBucket * actualBucketIndex) : countPerBucket;
            long bucketDistinct = Math.min(distinctPerBucket, bucketCount);

            if (bucketCount > 0) {
                buckets.add(Bucket.createHistogramBucket(low, high, bucketCount, bucketDistinct));
            }
            if (bucketCount == 0) {
                logger.warn("直方图bucket {} 的count为0，跳过", i);
            }
            actualBucketIndex++;
        }
        logger.info("列 {} 共创建 {} 个直方图buckets", stats.getColumnName(), buckets.size());
    }
    
    /**
     * 为主外键优化buckets
     */
    private void optimizeBucketsForKeys(List<Bucket> buckets, EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        if (stats.isPrimaryKey()) {
            optimizeForPrimaryKey(buckets, stats, tableSize);
        } else if (stats.isForeignKey()) {
            optimizeForForeignKey(buckets, stats, tableSize);
        }
    }
    
    /**
     * 主键优化：确保每个值都是唯一的
     */
    private void optimizeForPrimaryKey(List<Bucket> buckets, EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        // logger.debug("对主键列 {} 进行优化", stats.getColumnName());
        
        // // 主键列不应该有NULL值
        // buckets.removeIf(bucket -> bucket.getType() == Bucket.BucketType.NULL);
        
        // // 确保每个bucket的distinct值不超过count值
        // for (Bucket bucket : buckets) {
        //     if (bucket.getDistinct() > bucket.getCount()) {
        //         bucket.setDistinct(bucket.getCount());
        //     }
        // }
        
        // // 如果需要，添加更多distinct值以达到表大小
        // long totalDistinct = buckets.stream().mapToLong(Bucket::getDistinct).sum();
        // if (totalDistinct < tableSize) {
        //     addPrimaryKeyBuckets(buckets, tableSize - totalDistinct, stats);
        // }
    }
    
    /**
     * 外键优化：为后续对齐做准备
     */
    private void optimizeForForeignKey(List<Bucket> buckets, EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        logger.debug("对外键列 {} 进行优化", stats.getColumnName());
        
        // 外键列需要引用已存在的值，这里为后续的bucket对齐做准备
        // 实际的对齐将在ForeignKeyHandler中进行
        
        // 确保bucket的结构适合对齐
        // consolidateBuckets(buckets);
    }
    
    /**
     * 为主键添加额外的buckets
     */
    private void addPrimaryKeyBuckets(List<Bucket> buckets, long neededDistinct, EnhancedStatsExtractor.EnhancedColumnStatistics stats) {
        if (neededDistinct <= 0) return;
        
        // 创建一个大的范围bucket来包含剩余的主键值
        Datum low = createDatum("1", stats.getDataType());
        Datum high = createDatum(String.valueOf(Long.MAX_VALUE), stats.getDataType());
        
        buckets.add(Bucket.createHistogramBucket(low, high, neededDistinct, neededDistinct));
        logger.debug("为主键添加额外bucket，distinct: {}", neededDistinct);
    }
    
    /**
     * 合并小的buckets
     */
    private void consolidateBuckets(List<Bucket> buckets) {
        // 简单的合并策略：合并count很小的相邻buckets
        // 这里可以实现更复杂的合并逻辑
        buckets.removeIf(bucket -> bucket.getCount() == 0);
    }
    
    /**
     * 推断列类型
     */
    private ColumnType inferColumnType(EnhancedStatsExtractor.EnhancedColumnStatistics stats) {
        String dataType = stats.getDataType();
        if (dataType == null) {
            return ColumnType.VARCHAR; // 默认类型
        }
        
        dataType = dataType.toUpperCase();
        
        if (dataType.contains("INT") || dataType.contains("SERIAL") || dataType.contains("BIGINT")) {
            return ColumnType.INTEGER;
        } else if (dataType.contains("DECIMAL") || dataType.contains("NUMERIC") || dataType.contains("REAL") || dataType.contains("DOUBLE")) {
            return ColumnType.DECIMAL;
        } else if (dataType.contains("DATE") || dataType.contains("TIME")) {
            return ColumnType.DATE;
        } else if (dataType.contains("BOOL")) {
            return ColumnType.BOOL;
        } else {
            return ColumnType.VARCHAR;
        }
    }
    
    /**
     * 创建Datum对象
     */
    private Datum createDatum(String value, String dataType) {
        try {
            Datum.DatumType datumType = convertColumnTypeToDatumType(dataType);
            return Datum.parseFromString(value, datumType);
        } catch (Exception e) {
            logger.debug("创建Datum失败，使用字符串类型: {}", e.getMessage());
            return Datum.parseFromString(value, Datum.DatumType.VARCHAR);
        }
    }
    
    /**
     * 从字符串推断列类型并转换为DatumType
     */
    private Datum.DatumType convertColumnTypeToDatumType(String dataType) {
        ColumnType columnType = inferColumnTypeFromString(dataType);
        return convertColumnTypeToDatumType(columnType);
    }
    
    /**
     * 将ColumnType转换为Datum.DatumType
     */
    private Datum.DatumType convertColumnTypeToDatumType(ColumnType columnType) {
        switch (columnType) {
            case INTEGER:
                return Datum.DatumType.INTEGER;
            case DECIMAL:
                return Datum.DatumType.DECIMAL;
            case VARCHAR:
                return Datum.DatumType.VARCHAR;
            case DATE:
                return Datum.DatumType.DATE;
            case DATETIME:
                return Datum.DatumType.DATETIME;
            case BOOL:
                return Datum.DatumType.BOOLEAN;
            default:
                return Datum.DatumType.VARCHAR;
        }
    }
    
    /**
     * 从字符串推断列类型
     */
    private ColumnType inferColumnTypeFromString(String dataType) {
        if (dataType == null) return ColumnType.VARCHAR;
        
        String type = dataType.toUpperCase();
        if (type.contains("INT") || type.contains("SERIAL")) {
            return ColumnType.INTEGER;
        } else if (type.contains("DECIMAL") || type.contains("NUMERIC") || type.contains("REAL")) {
            return ColumnType.DECIMAL;
        } else if (type.contains("DATE") || type.contains("TIME")) {
            return ColumnType.DATE;
        } else if (type.contains("BOOL")) {
            return ColumnType.BOOL;
        } else {
            return ColumnType.VARCHAR;
        }
    }
    
    /**
     * 计算总的distinct值数量
     */
    private long calculateTotalDistinct(EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        double nDistinct = stats.getNDistinct();
        
        if (nDistinct > 0 && nDistinct <= 1) {
            // 这是一个比例
            return Math.round(tableSize * nDistinct);
        } else if (nDistinct > 1) {
            // 这是绝对数量
            return Math.round(nDistinct);
        } else if (nDistinct < 0) {
            // 负值表示比例的负数
            return Math.round(tableSize * Math.abs(nDistinct));
        } else {
            // 默认假设所有值都不同
            return tableSize;
        }
    }
    
    /**
     * 创建默认bucket（当统计信息不足时）
     */
    private Bucket createDefaultBucket(EnhancedStatsExtractor.EnhancedColumnStatistics stats, long tableSize) {
        logger.warn("为列 {} 创建默认bucket", stats.getColumnName());
        
        Datum low = createDatum("1", stats.getDataType());
        Datum high = createDatum("1000000", stats.getDataType());
        
        long distinctCount = stats.isPrimaryKey() ? tableSize : Math.min(1000, tableSize);
        
        return Bucket.createHistogramBucket(low, high, tableSize, distinctCount);
    }
    
    /**
     * 验证buckets的一致性
     */
    private void validateBuckets(List<Bucket> buckets, long tableSize, String columnName) {
        long totalCount = buckets.stream().mapToLong(Bucket::getCount).sum();
        
        // 任何不匹配都需要调整，不允许误差
        if (totalCount != tableSize) {
            logger.warn("列 {} 的bucket总数 ({}) 与表大小 ({}) 不匹配，进行调整", columnName, totalCount, tableSize);
            
            // 调整最后一个非NULL bucket的count
            adjustBucketCounts(buckets, tableSize);
            
            // 重新验证
            long newTotalCount = buckets.stream().mapToLong(Bucket::getCount).sum();
            logger.info("列 {} 调整后的bucket总数: {}", columnName, newTotalCount);
        }
        
        logger.info("列 {} 验证通过，bucket数量: {}, bucket总行数: {}, 表大小: {}", 
                   columnName, buckets.size(), totalCount, tableSize);
        
        // 添加详细的 bucket 分布信息
        if (logger.isDebugEnabled()) {
            long nullCount = buckets.stream()
                .filter(b -> b.getType() == Bucket.BucketType.NULL)
                .mapToLong(Bucket::getCount).sum();
            long mcvCount = buckets.stream()
                .filter(b -> b.getType() == Bucket.BucketType.MCV)
                .mapToLong(Bucket::getCount).sum();
            long histogramCount = buckets.stream()
                .filter(b -> b.getType() == Bucket.BucketType.HISTOGRAM)
                .mapToLong(Bucket::getCount).sum();
            
            logger.debug("  └─ NULL buckets: {} 行, MCV buckets: {} 行, HISTOGRAM buckets: {} 行", 
                       nullCount, mcvCount, histogramCount);
        }
    }
    
    /**
     * 调整bucket的count以匹配表大小
     */
    private void adjustBucketCounts(List<Bucket> buckets, long tableSize) {
        if (buckets.isEmpty()) return;
        
        long currentTotal = buckets.stream().mapToLong(Bucket::getCount).sum();
        long difference = tableSize - currentTotal;
        
        // 找到最大的非NULL bucket进行调整
        Bucket largestBucket = buckets.stream()
            .filter(bucket -> bucket.getType() != Bucket.BucketType.NULL)
            .max(Comparator.comparingLong(Bucket::getCount))
            .orElse(buckets.get(buckets.size() - 1));
        
        long newCount = Math.max(0, largestBucket.getCount() + difference);
        largestBucket.setCount(newCount);
        
        logger.debug("调整bucket count，差异: {}, 新count: {}", difference, newCount);
    }
    
    /**
     * 将bucket信息写入单独的日志文件
     */
    private void writeBucketsToLogFile(String columnName, List<Bucket> buckets) {
        try {
            // 创建日志文件目录
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            // 生成文件名（使用列名，处理特殊字符）
            String safeColumnName = columnName.replaceAll("[^a-zA-Z0-9._-]", "_");
            File logFile = new File(logDir, "buckets_" + safeColumnName + ".log");
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, false))) {
                writer.write("=== Buckets for column: " + columnName + " ===\n");
                writer.write("Total buckets: " + buckets.size() + "\n\n");
                
                long totalCount = 0;
                long totalDistinct = 0;
                int nullCount = 0;
                int mcvCount = 0;
                int histogramCount = 0;
                
                for (int i = 0; i < buckets.size(); i++) {
                    Bucket bucket = buckets.get(i);
                    totalCount += bucket.getCount();
                    totalDistinct += bucket.getDistinct();
                    
                    writer.write(String.format("Bucket[%d]: type=%s, count=%d, nDistinct=%d",
                                              i, bucket.getType(), bucket.getCount(), bucket.getDistinct()));
                    
                    if (bucket.getLow() != null) {
                        writer.write(", low=" + bucket.getLow().getValue());
                    } else {
                        writer.write(", low=null");
                    }
                    
                    if (bucket.getHigh() != null) {
                        writer.write(", high=" + bucket.getHigh().getValue());
                    } else {
                        writer.write(", high=null");
                    }
                    
                    writer.write("\n");
                    
                    // 统计各类型数量
                    switch (bucket.getType()) {
                        case NULL:
                            nullCount++;
                            break;
                        case MCV:
                            mcvCount++;
                            break;
                        case HISTOGRAM:
                            histogramCount++;
                            break;
                    }
                }
                
                writer.write("\n=== Summary ===\n");
                writer.write("Total count: " + totalCount + "\n");
                writer.write("Total distinct: " + totalDistinct + "\n");
                writer.write("NULL buckets: " + nullCount + "\n");
                writer.write("MCV buckets: " + mcvCount + "\n");
                writer.write("Histogram buckets: " + histogramCount + "\n");
            }
            
            logger.debug("列 {} 的bucket信息已写入日志文件: {}", columnName, logFile.getAbsolutePath());
            
        } catch (IOException e) {
            logger.warn("写入bucket日志文件失败: {}", e.getMessage());
        }
    }
}

