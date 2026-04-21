package ruc.db.schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.filter.arithmetic.ArithmeticNode;
import ruc.db.generator.constraintchain.filter.operation.CompareOperator;
import ruc.db.rsgen.Bucket;
import ruc.db.rsgen.Datum;
import ruc.db.rsgen.EnhancedBucketGenerator;
import ruc.db.rsgen.EnhancedStatsExtractor;
import ruc.db.rsgen.RSGenDataGeneratorRefactored;
import ruc.db.utils.CommonUtils;
import static ruc.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;
import ruc.db.utils.DataExportConstants;

/**
 * @author wangqingshuai
 */
@JsonPropertyOrder({"columnType", "nullPercentage", "specialValue", "min", "range", "minLength", "rangeLength", "originalType"})
public class Column {
    private static final Logger logger = LoggerFactory.getLogger(Column.class);
    /** 无统计信息时打印 paraData2Probability 的最大条数，避免万级桶卡死与日志爆内存 */
    private static final int MAX_DISTRIBUTION_LOG_ENTRIES = 64;
    /** offset2Pv 在分布日志中的最大条数 */
    private static final int MAX_OFFSET2PV_LOG_ENTRIES = 32;

    private ColumnType columnType;
    private long min;
    private String originalType;
    private long range = 1;
    private long specialValue;
    private BigDecimal nullPercentage = BigDecimal.ZERO;
    private int avgLength;
    private int maxLength;
    @JsonIgnore
    private BigDecimal decimalPre;
    @JsonIgnore
    private StringTemplate stringTemplate;
    @JsonIgnore
    private long[] columnData;  // 非统计信息列使用（dataIndex）
    @JsonIgnore
    private Object[] columnActualData;  // 统计信息列使用（实际值）
    @JsonIgnore
    private double[] columnActualNumericValues;  // 用于 CDF 列的实际数值（算术计算）
    @JsonIgnore
    private Distribution distribution;
    @JsonIgnore
    private ColumnCDF columnCDF;
    @JsonIgnore
    private EnhancedColumnStatistics statistics;
    @JsonIgnore
    private Map<Long, String> dataIndex2ActualValue = new TreeMap<>();  // CDF: 虚拟dataIndex → 实际值的映射
    @JsonIgnore
    private double[] accSampleDataCache = null;  // ★★★ ACC采样数据缓存（避免重复生成）
    @JsonIgnore
    private int accSampleSizeCache = -1;  // ★★★ 缓存的采样大小

    public Distribution getDistribution() {
        return distribution;
    }

    public Column() {
    }

    public Column(ColumnType columnType) {
        this.columnType = columnType;
    }

    public String getOriginalType() {
        return originalType;
    }

    public void setOriginalType(String originalType) {
        this.originalType = originalType;
    }


    public void init() {
        distribution = new Distribution(nullPercentage, range);
        if (columnType == ColumnType.VARCHAR) {
            stringTemplate = new StringTemplate(avgLength, maxLength, specialValue, range + 20);
        }
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    public long getRange() {
        return range;
    }

    public void setRange(long range) {
        this.range = range;
    }

    public BigDecimal getNullPercentage() {
        return nullPercentage;
    }

    public void setNullPercentage(BigDecimal nullPercentage) {
        this.nullPercentage = nullPercentage;
    }

    /**
     * 生成列数据
     * 
     * 如果该列有统计信息（EnhancedColumnStatistics），使用 RSGen 生成实际值
     * 否则回退到原始的 bin-packing 方法生成 dataIndex
     * 
     * @param size 需要生成的行数
     */
    public void prepareTupleData(int size) {
        // printDistributionInfoBeforeGeneration(size);
        
        if (statistics != null) {
            // 有统计信息对象：使用统计信息方法（包括没有MCV/Histogram的情况）
            if (hasStatistics()) {
                // 有MCV或Histogram：使用RSGen生成
            prepareTupleDataWithStatistics(size);
        } else {
                // 没有MCV和Histogram：使用随机生成（仍归类为统计信息方法）
                prepareTupleDataWithoutMCVOrHistogram(size);
            }
        } else {
            // 没有统计信息对象：使用原有的 bin-packing 方法生成 dataIndex
            columnData = distribution.prepareTupleData(size);
        }
    }
    
    /**
     * 在生成数据之前打印列的分布信息
     * ★★★ 使用synchronized确保日志完整输出，避免并行生成时日志交错 ★★★
     */
    private void printDistributionInfoBeforeGeneration(int size) {
        // ★★★ 获取列名：优先从统计信息，否则从ColumnManager反向查找 ★★★
        String columnName = statistics != null ? statistics.getColumnName() : findColumnNameFromManager();
        if (columnName == null || columnName.equals("unknown")) {
            columnName = "unknown";
        }
        
        // ★★★ 使用StringBuilder构建完整消息，然后一次性打印，避免并行生成时日志交错 ★★★
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("═══════════════════════════════════════════════════════════════\n");
        logMessage.append("📊 列数据生成前 - 分布信息: ").append(columnName).append("\n");
        logMessage.append("  └─ 列类型: ").append(columnType).append("\n");
        logMessage.append("  └─ 生成行数: ").append(size).append("\n");
        
        // 判断生成方式
        String generationMethod;
        if (statistics != null) {
            if (hasStatistics()) {
                generationMethod = "统计信息方法 (RSGen) - 有MCV或Histogram";
            } else {
                generationMethod = "统计信息方法 (随机生成) - 无MCV和Histogram";
            }
        } else {
            generationMethod = "原始方法 (bin-packing) - 生成dataIndex";
        }
        logMessage.append("  └─ 生成方式: ").append(generationMethod).append("\n");
        
        // ★★★ 对于原始方法，打印完整的dataIndex到实际值的映射和概率分布 ★★★
        if (statistics == null && distribution != null) {
            SortedMap<Long, BigDecimal> paraData2Prob = distribution.getParaData2Probability();
            if (paraData2Prob != null && !paraData2Prob.isEmpty()) {
                logMessage.append("  └─ 完整概率分布 (dataIndex区间 -> 实际值范围 -> probability):\n");
                logMessage.append("      ★★★ 注意：dataIndex表示区间右边界，实际生成时在区间内随机生成 ★★★\n");
                
                BigDecimal totalProbability = BigDecimal.ZERO;
                for (BigDecimal p : paraData2Prob.values()) {
                    totalProbability = totalProbability.add(p);
                }
                int entryCount = 0;
                long lastParaData = 1;  // 初始左边界
                for (Map.Entry<Long, BigDecimal> entry : paraData2Prob.entrySet()) {
                    if (entryCount >= MAX_DISTRIBUTION_LOG_ENTRIES) {
                        int omitted = paraData2Prob.size() - entryCount;
                        logMessage.append(String.format(
                                "      ... 省略 %d 个概率区间（日志仅展示前 %d 条，不影响数据生成）\n",
                                omitted, MAX_DISTRIBUTION_LOG_ENTRIES));
                        break;
                    }
                    long currentParaData = entry.getKey();  // 当前区间的右边界
                    BigDecimal probability = entry.getValue();
                    
                    // 计算区间范围
                    long rangeStart = lastParaData;
                    long rangeEnd = currentParaData;
                    
                    // 将区间边界转换为实际值
                    String startValue = transferDataToValue(rangeStart);
                    String endValue = transferDataToValue(rangeEnd);
                    String startDisplay = startValue != null && startValue.length() > 50 
                        ? startValue.substring(0, 50) + "..." 
                        : startValue;
                    String endDisplay = endValue != null && endValue.length() > 50 
                        ? endValue.substring(0, 50) + "..." 
                        : endValue;
                    
                    // 显示区间信息
                    if (rangeStart == rangeEnd) {
                        // 单个值
                        logMessage.append(String.format("      [%d] dataIndex=%d (固定值) -> 实际值='%s' -> probability=%.6f\n",
                            entryCount,
                            currentParaData,
                            startDisplay,
                            probability.doubleValue()));
                    } else {
                        // 区间范围
                        logMessage.append(String.format("      [%d] dataIndex区间=[%d, %d] -> 实际值范围=['%s' ~ '%s'] -> probability=%.6f\n",
                            entryCount,
                            rangeStart,
                            rangeEnd,
                            startDisplay,
                            endDisplay,
                            probability.doubleValue()));
                    }
                    entryCount++;
                    
                    // 更新左边界为当前右边界+1（因为区间是左闭右开）
                    lastParaData = currentParaData + 1;
                }
                
                // 验证概率总和
                logMessage.append(String.format("  └─ 概率总和: %.6f (期望: 1.0)\n", totalProbability.doubleValue()));
                if (totalProbability.compareTo(BigDecimal.ONE) != 0) {
                    BigDecimal diff = totalProbability.subtract(BigDecimal.ONE).abs();
                    if (diff.compareTo(BigDecimal.valueOf(0.0001)) > 0) {
                        logMessage.append(String.format("  ⚠️  概率总和与1.0差异较大: %.6f\n", diff.doubleValue()));
                    }
                }
                
                // offset2Pv信息 (bound约束)
                SortedMap<BigDecimal, Long> offset2Pv = distribution.getOffset2Pv();
                if (offset2Pv != null && !offset2Pv.isEmpty()) {
                    logMessage.append(String.format("  └─ offset2Pv (bound约束): %d 个bound映射\n", offset2Pv.size()));
                    logMessage.append("      ★★★ 注意：bound约束使用固定的dataIndex值（不是区间）★★★\n");
                    int offIdx = 0;
                    for (Map.Entry<BigDecimal, Long> entry : offset2Pv.entrySet()) {
                        if (offIdx >= MAX_OFFSET2PV_LOG_ENTRIES) {
                            logMessage.append(String.format("      ... 省略 %d 个 bound 映射（日志上限 %d）\n",
                                    offset2Pv.size() - offIdx, MAX_OFFSET2PV_LOG_ENTRIES));
                            break;
                        }
                        long boundDataIndex = entry.getValue();
                        String boundActualValue = transferDataToValue(boundDataIndex);
                        String boundValueDisplay = boundActualValue != null && boundActualValue.length() > 60 
                            ? boundActualValue.substring(0, 60) + "..." 
                            : boundActualValue;
                        logMessage.append(String.format("      offset=%.6f -> dataIndex=%d (固定值) -> 实际值='%s'\n",
                            entry.getKey().doubleValue(),
                            boundDataIndex,
                            boundValueDisplay));
                        offIdx++;
                    }
                } else {
                    logMessage.append("  └─ offset2Pv (bound约束): 无\n");
                }
            } else {
                logMessage.append("  └─ paraData2Probability: 无（列可能未初始化）\n");
            }
        }
        
        // 打印统计信息（如果有）
        if (statistics != null) {
            logMessage.append("  └─ 统计信息对象: 存在\n");
            
            // MCV信息
            List<String> mcvValues = statistics.getMostCommonValues();
            List<Double> mcvFreqs = statistics.getMostCommonFrequencies();
            if (mcvValues != null && !mcvValues.isEmpty()) {
                logMessage.append(String.format("  └─ MCV数量: %d\n", mcvValues.size()));
                int showCount = Math.min(15, mcvValues.size());
                for (int i = 0; i < showCount; i++) {
                    String value = mcvValues.get(i);
                    double freq = (mcvFreqs != null && i < mcvFreqs.size()) ? mcvFreqs.get(i) : 0.0;
                    String valueDisplay = value != null && value.length() > 50 ? value.substring(0, 50) + "..." : value;
                    logMessage.append(String.format("      MCV[%d]: '%s' (freq=%.6f)\n", i, valueDisplay, freq));
                }
                if (mcvValues.size() > showCount) {
                    logMessage.append(String.format("      ... 还有 %d 个MCV值\n", mcvValues.size() - showCount));
                }
            } else {
                logMessage.append("  └─ MCV: 无\n");
            }
            
            // Histogram信息
            List<String> histBounds = statistics.getHistogramBounds();
            if (histBounds != null && !histBounds.isEmpty()) {
                logMessage.append(String.format("  └─ Histogram buckets数量: %d\n", histBounds.size() - 1));
                if (histBounds.size() > 1) {
                    try {
                        double minBound = Double.parseDouble(histBounds.get(0));
                        double maxBound = Double.parseDouble(histBounds.get(histBounds.size() - 1));
                        logMessage.append(String.format("      Histogram范围: [%.2f ~ %.2f]\n", minBound, maxBound));
                    } catch (NumberFormatException e) {
                        logMessage.append(String.format("      Histogram范围: [%s ~ %s]\n",
                            histBounds.get(0), histBounds.get(histBounds.size() - 1)));
                    }
                }
            } else {
                logMessage.append("  └─ Histogram: 无\n");
            }
        } else {
            logMessage.append("  └─ 统计信息对象: 不存在（使用原始方法）\n");
        }
        
        // 打印CDF约束信息（仅在有统计信息时）
        if (statistics != null && columnCDF != null) {
            ColumnCDF.ParameterConstraint constraint = columnCDF.getParameterConstraint();
            if (constraint != null && constraint.selectedValues != null && !constraint.selectedValues.isEmpty()) {
                logMessage.append(String.format("  └─ CDF约束值数量: %d\n", constraint.selectedValues.size()));
                int showCount = Math.min(5, constraint.selectedValues.size());
                for (int i = 0; i < showCount; i++) {
                    String value = constraint.selectedValues.get(i);
                    BigDecimal sel = constraint.valueToSelectivity.get(value);
                    CompareOperator op = constraint.getOperatorForValue(value);
                    String valueDisplay = value != null && value.length() > 50 ? value.substring(0, 50) + "..." : value;
                    logMessage.append(String.format("      约束值[%d]: '%s' (selectivity=%s, operator=%s)\n",
                        i,
                        valueDisplay,
                        sel != null ? String.format("%.6f", sel) : "null",
                        op != null ? op.toString() : "null"));
                }
                if (constraint.selectedValues.size() > showCount) {
                    logMessage.append(String.format("      ... 还有 %d 个约束值\n", constraint.selectedValues.size() - showCount));
                }
            } else {
                logMessage.append("  └─ CDF约束: 无\n");
            }
        }
        
        // 对于VARCHAR列，打印特殊值信息
        if (columnType == ColumnType.VARCHAR) {
            if (specialValue != 0) {
                logMessage.append(String.format("  └─ VARCHAR特殊值: %d\n", specialValue));
            }
            if (stringTemplate != null) {
                logMessage.append(String.format("  └─ VARCHAR长度范围: avg=%d, max=%d\n", avgLength, maxLength));
            }
        }
        
        logMessage.append("═══════════════════════════════════════════════════════════════");
        // 不在全局锁内打日志：慢磁盘/同步 appender 会阻塞所有并行 prepareTupleData 的线程，表现为整表“卡住”
        // logger.info("\n{}", logMessage.toString());
    }
    
    /**
     * 从ColumnManager反向查找当前Column对应的列名
     */
    private String findColumnNameFromManager() {
        try {
            ruc.db.schema.ColumnManager cm = ruc.db.schema.ColumnManager.getInstance();
            // 通过反射访问columns字段
            java.lang.reflect.Field field = ruc.db.schema.ColumnManager.class.getDeclaredField("columns");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ruc.db.schema.Column> columns = (Map<String, ruc.db.schema.Column>) field.get(cm);
            if (columns != null) {
                for (Map.Entry<String, ruc.db.schema.Column> entry : columns.entrySet()) {
                    if (entry.getValue() == this) {
                        return entry.getKey();
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常，返回unknown
            logger.debug("无法从ColumnManager查找列名: {}", e.getMessage());
        }
        return "unknown";
    }
    
    /**
     * 检查是否有统计信息
     */
    private boolean hasStatistics() {
        return statistics != null && 
               (statistics.getHistogramBounds() != null && !statistics.getHistogramBounds().isEmpty() ||
                statistics.getMostCommonValues() != null && !statistics.getMostCommonValues().isEmpty());
    }
    
    /**
     * 使用统计信息生成实际值（完全不用 dataIndex）
     * 
     * 步骤：
     * 1. 先处理 Bound 值位置（确定哪些位置需要 bound 值）
     * 2. 使用 RSGen 生成剩余位置的数据
     * 3. 填充数值数组（用于 ACCs 计算）
     * 
     * @param size 需要生成的行数
     */
    private void prepareTupleDataWithStatistics(int size) {
        logger.info("prepareTupleDataWithStatistics: size={}", size);
        // 初始化实际值数组
        columnActualData = new Object[size];
        
        // ★★★ 关键修复：重置 columnActualNumericValues 为 null，确保下次 calculate() 时重新转换 ★★★
        // 因为 columnActualData 被重新生成了，旧的 columnActualNumericValues 已经过时
        columnActualNumericValues = null;
        
        // ★★★ 延迟转换：不再在这里初始化 columnActualNumericValues ★★★
        // 只有在 Column.calculate() 被调用时（即列参与 ACCs 计算时）才初始化并转换
        // 这样可以避免对不需要参与 ACCs 计算的列（如 VARCHAR）进行不必要的内存分配
        
        // 第一步：处理 Bound 值位置
        // Collect bound values and their counts
        Map<String, Integer> boundValueCounts = new HashMap<>();
        Set<Integer> boundPositions = processBoundValuePositions(size, boundValueCounts);
        
        // 第二步：使用 RSGen 生成剩余位置的数据
        generateRemainingDataWithRSGen(size, boundPositions, boundValueCounts);
        
        // ★★★ 延迟转换：不再在这里转换，而是在 Column.calculate() 被调用时按需转换 ★★★
        // 这样可以避免对不需要参与 ACCs 计算的列（如 VARCHAR）进行不必要的转换
    }
    
    /**
     * 对于有统计信息对象但没有MCV和Histogram的列，使用随机生成
     * 仍归类为"使用统计信息"的方法，不使用原始的dataIndex逻辑
     * 
     * @param size 需要生成的行数
     */
    private void prepareTupleDataWithoutMCVOrHistogram(int size) {
        String columnName = statistics != null ? statistics.getColumnName() : "unknown";
        logger.info("列 {} 有统计信息对象但没有MCV和Histogram，使用随机生成方法", columnName);
        
        // ★★★ 关键修复：重置 columnActualNumericValues 为 null，确保下次 calculate() 时重新转换 ★★★
        columnActualNumericValues = null;
        
        // 初始化实际值数组
        columnActualData = new Object[size];
        
        // ★★★ 延迟转换：不再在这里初始化 columnActualNumericValues ★★★
        // 只有在 Column.calculate() 被调用时（即列参与 ACCs 计算时）才初始化并转换
        // 这样可以避免对不需要参与 ACCs 计算的列（如 VARCHAR）进行不必要的内存分配
        
        // 第一步：处理 Bound 值位置
        Map<String, Integer> boundValueCounts = new HashMap<>();
        Set<Integer> boundPositions = processBoundValuePositions(size, boundValueCounts);
        
        // 第二步：为剩余位置生成随机数据
        int remainingSize = size - boundPositions.size();
        if (remainingSize > 0) {
            Object[] generatedData = generateRandomDataWithoutStatistics(remainingSize);
            if (generatedData == null) {
                // INTEGER/DATE 等在无 MCV/Histogram 时原实现返回 null，曾导致 NPE；回退到分布层生成 dataIndex 再转为实际值
                logger.warn("列 {} 无 MCV/Histogram 且类型 {} 无专用随机数组，使用 distribution 回退生成 {} 行",
                        columnName, columnType, remainingSize);
                generatedData = fallbackActualObjectsFromDistribution(remainingSize);
            }
            // 填充到 columnActualData（跳过 bound 位置）
            int dataIndex = 0;
            for (int i = 0; i < size && dataIndex < generatedData.length; i++) {
                if (boundPositions.contains(i)) {
                    continue; // 跳过 bound 位置
                }
                columnActualData[i] = generatedData[dataIndex++];
            }
        }
        
        // ★★★ 延迟转换：不再在这里转换，而是在 Column.calculate() 被调用时按需转换 ★★★
        // 这样可以避免对不需要参与 ACCs 计算的列（如 VARCHAR）进行不必要的转换
    }
    
    /**
     * 用 {@link Distribution#prepareTupleData} 生成 dataIndex，再转为 {@link #columnActualData} 可用的对象（与 bin-packing 路径一致）。
     */
    private Object[] fallbackActualObjectsFromDistribution(int size) {
        long[] idx = distribution.prepareTupleData(size);
        Object[] out = new Object[size];
        for (int i = 0; i < size; i++) {
            String s = transferDataToValue(idx[i]);
            out[i] = parseActualValue(s);
        }
        return out;
    }

    /**
     * 为没有MCV和Histogram的列生成随机数据
     * 
     * @param size 需要生成的行数
     * @return 生成的数据数组
     */
    private Object[] generateRandomDataWithoutStatistics(int size) {
        String columnName = statistics != null ? statistics.getColumnName() : "unknown";
        
        switch (columnType) {
            case VARCHAR:
                return generateRandomVarcharWithoutStatistics(size);
            case INTEGER:
            case DECIMAL:
            case DATE:
            case DATETIME:
                // 对于数值型和日期型，如果没有统计信息，回退到原始方法
                logger.warn("列 {} 是数值/日期类型但没有统计信息，回退到原始方法", columnName);
                return null; // 返回null表示回退
            default:
                logger.warn("列 {} 类型 {} 不支持随机生成，回退到原始方法", columnName, columnType);
                return null;
        }
    }
    
    /**
     * 为VARCHAR列生成随机字符串（没有MCV和Histogram的情况）
     * 
     * @param size 需要生成的行数
     * @return 生成的字符串数组
     */
    private Object[] generateRandomVarcharWithoutStatistics(int size) {
        String columnName = statistics != null ? statistics.getColumnName() : "unknown";
        
        // 解析长度信息
        int minLength = 1;
        int maxLength = this.maxLength > 0 ? this.maxLength : 50;
        int avgWidth = this.avgLength > 0 ? this.avgLength : 10;
        
        // 尝试从dataPattern中解析
        if (statistics != null && statistics.getDataPattern() != null) {
            String dataPattern = statistics.getDataPattern();
            if (dataPattern.contains("max_length=")) {
                try {
                    String maxLenStr = dataPattern.substring(dataPattern.indexOf("max_length=") + 11);
                    maxLenStr = maxLenStr.split(",")[0].trim();
                    maxLength = Integer.parseInt(maxLenStr);
                } catch (Exception e) {
                    logger.debug("解析max_length失败: {}", e.getMessage());
                }
            }
            if (dataPattern.contains("avg_width=")) {
                try {
                    String avgWidthStr = dataPattern.substring(dataPattern.indexOf("avg_width=") + 10);
                    avgWidthStr = avgWidthStr.split(",")[0].trim();
                    avgWidth = Integer.parseInt(avgWidthStr);
                } catch (Exception e) {
                    logger.debug("解析avg_width失败: {}", e.getMessage());
                }
            }
        }
        
        // 如果统计信息中有avgWidth，优先使用
        if (statistics != null && statistics.getAvgWidth() > 0) {
            avgWidth = statistics.getAvgWidth();
        }
        
        // 确保长度范围合理
        maxLength = Math.max(minLength, Math.min(maxLength, 1000)); // 限制最大长度为1000
        avgWidth = Math.max(minLength, Math.min(avgWidth, maxLength));
        
        logger.info("列 {} 随机生成VARCHAR: size={}, minLength={}, maxLength={}, avgWidth={}", 
                   columnName, size, minLength, maxLength, avgWidth);
        
        // 生成随机字符串
        Object[] data = new Object[size];
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random random = new java.util.Random();
        
        for (int i = 0; i < size; i++) {
            // 方案1：使用固定长度（avgWidth）
            // int targetLength = avgWidth;
            
            // 方案2：在minLength和maxLength之间随机，但倾向于avgWidth
            int targetLength;
            if (random.nextDouble() < 0.7) {
                // 70%的概率在avgWidth附近生成
                int variation = Math.max(1, avgWidth / 4);
                targetLength = Math.max(minLength, Math.min(maxLength, 
                    avgWidth + random.nextInt(-variation, variation + 1)));
            } else {
                // 30%的概率在整个范围内随机生成
                targetLength = random.nextInt(minLength, maxLength + 1);
            }
            
            StringBuilder sb = new StringBuilder(targetLength);
            for (int j = 0; j < targetLength; j++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            data[i] = sb.toString();
        }
        
        return data;
    }
    
    /**
     * 处理 Bound 值位置
     * 返回需要填充 bound 值的位置集合，同时收集bound值及其count
     * 
     * @param size 表大小
     * @param boundValueCounts 输出参数：bound值及其count（值 -> count）
     * @return bound位置集合
     */
    private Set<Integer> processBoundValuePositions(int size, Map<String, Integer> boundValueCounts) {
        Set<Integer> boundPositions = new HashSet<>();
        SortedMap<BigDecimal, Long> offset2Pv = distribution.getOffset2Pv();
        String columnName = statistics != null ? statistics.getColumnName() : "unknown";
        
        logger.info("BOUND: 开始处理列 {} 的bound值位置，表大小: {}", columnName, size);
        logger.info("BOUND: 列 {} 的offset2Pv: {}", columnName, offset2Pv);
        
        if (offset2Pv != null && !offset2Pv.isEmpty()) {
            logger.info("BOUND: 列 {} 有 {} 个bound映射", columnName, offset2Pv.size());
            
            TableBoundInfo tableBoundInfo = distribution.getTableBoundInfo();
            if (tableBoundInfo == null) {
                // 如果没有共享的TableBoundInfo，创建新的（后向兼容）
                tableBoundInfo = new TableBoundInfo();
                distribution.setTableBoundInfo(tableBoundInfo);
                logger.info("BOUND: 为Distribution创建新的TableBoundInfo（后向兼容）");
            } else {
                logger.info("BOUND: 列 {} 使用共享的 TableBoundInfo（Bound Group: {}）", 
                    columnName, distribution.getBoundGroupId());
            }
            
            // 从 dataIndex2ActualValue 获取 bound 值对应的实际值
            for (Map.Entry<BigDecimal, Long> entry : offset2Pv.entrySet()) {
                BigDecimal offset = entry.getKey();
                long boundDataIndex = entry.getValue();
                
                // 计算 bound 值应该出现的起始位置
                int startPos = offset.multiply(BigDecimal.valueOf(size))
                                    .setScale(0, RoundingMode.HALF_UP)
                                    .intValue();
                
                // 根据该 dataIndex 的概率计算需要填充的行数
                Map<Long, BigDecimal> paraData2Probability = distribution.getParaData2Probability();
                if (paraData2Probability != null && paraData2Probability.containsKey(boundDataIndex)) {
                    BigDecimal probability = paraData2Probability.get(boundDataIndex);
                    int count = probability.multiply(BigDecimal.valueOf(size))
                                          .setScale(0, RoundingMode.HALF_UP)
                                          .intValue();
                    
                    // 获取 bound 值对应的实际值
                    String boundActualValue = dataIndex2ActualValue.get(boundDataIndex);
                    if (boundActualValue == null) {
                        logger.warn("BOUND: 列 {} Bound dataIndex {} 没有对应的实际值映射，跳过。当前映射大小: {}", 
                                   columnName, boundDataIndex, dataIndex2ActualValue.size());
                        if (!dataIndex2ActualValue.isEmpty()) {
                            logger.warn("BOUND: 列 {} 当前映射内容: {}", columnName, dataIndex2ActualValue);
                        }
                        continue;
                    }
                    
                    // Collect bound value and its count (accumulate, as same value may appear in multiple positions)
                    logger.info("BOUND: 列 {} 填充bound值: actualValue='{}', 行范围: [{}, {})", 
                        columnName, boundActualValue, startPos, startPos + count - 1);
                    
                    int oldCount = boundValueCounts.getOrDefault(boundActualValue, 0);
                    int newCount = oldCount + count;
                    boundValueCounts.put(boundActualValue, newCount);
                    logger.info("BOUND: 列 {} bound值 '{}' count更新: {} -> {}", columnName, boundActualValue, oldCount, newCount);
                    
                    // 填充 bound 值到指定位置
                    // 注意：boundValueCounts 使用原始字符串（用于后续与统计信息 MCV 匹配/排除）。
                    // 输出阶段的 COPY 分隔符问题应在具体字符串生成器（如 RSGen 的 varchar/comment 生成）中控制。
                    Object boundValue = parseActualValue(boundActualValue);
                    for (int i = 0; i < count && (startPos + i) < size; i++) {
                        int pos = startPos + i;
                        columnActualData[pos] = boundValue;
                        boundPositions.add(pos);
                    }
                }
            }
        } else {
            logger.info("BOUND: 列 {} 没有offset2Pv映射（无bound约束）", columnName);
        }
        
        logger.info("BOUND: 列 {} 的bound处理完成，共处理 {} 个bound行", columnName, boundPositions.size());
        return boundPositions;
    }
    
    /**
     * 使用 RSGen 生成剩余位置的数据
     * 
     * @param size 总表大小
     * @param boundPositions bound位置集合
     * @param boundValueCounts bound值及其count（值 -> count）
     */
    private void generateRemainingDataWithRSGen(int size, Set<Integer> boundPositions, Map<String, Integer> boundValueCounts) {
        // 计算需要生成的行数（排除 bound 位置）
        int remainingSize = size - boundPositions.size();
        String columnName = statistics != null ? statistics.getColumnName() : "unknown";
        
        if (remainingSize <= 0) {
            logger.info("RSGen: 列 {} 无需生成数据（bound位置已覆盖全部）", columnName);
            return;
        }
        
        logger.info("generateRemainingDataWithRSGen: 开始为列 {} 生成数据，总大小={}, bound位置={}, 剩余大小={}, bound值数量={}",
        columnName, size, boundPositions.size(), remainingSize, boundValueCounts.size());
        // 检查是否有MCV或Histogram
        if (!hasStatistics()) {
            logger.warn("RSGen: 列 {} 调用generateRemainingDataWithRSGen但没有MCV和Histogram，这不应该发生", columnName);
            // 这种情况不应该发生，因为prepareTupleDataWithoutMCVOrHistogram应该被调用
            // 但为了安全起见，生成随机数据
            Object[] randomData = generateRandomDataWithoutStatistics(remainingSize);
            if (randomData == null) {
                logger.warn("RSGen: 列 {} 无 MCV/Histogram，随机路径返回 null，使用 distribution 回退", columnName);
                randomData = fallbackActualObjectsFromDistribution(remainingSize);
            }
            int dataIndex = 0;
            for (int i = 0; i < size && dataIndex < randomData.length; i++) {
                if (boundPositions.contains(i)) {
                    continue;
                }
                columnActualData[i] = randomData[dataIndex++];
            }
            return;
        }
        
        try {
            logger.info("RSGen: 开始为列 {} 生成数据，总大小={}, bound位置={}, 剩余大小={}, bound值数量={}", 
                       columnName, size, boundPositions.size(), remainingSize, boundValueCounts.size());
            
            if (!boundValueCounts.isEmpty()) {
                logger.info("RSGen: 列 {} bound值详情:", columnName);
                for (Map.Entry<String, Integer> entry : boundValueCounts.entrySet()) {
                    String key = entry.getKey();
                    logger.info("RSGen:   '{}' (length={}, hashCode={}): {}", 
                               key, key != null ? key.length() : 0, 
                               key != null ? key.hashCode() : 0, entry.getValue());
                }
            } else {
                logger.info("RSGen: 列 {} bound值详情为空", columnName);
            }
            
            EnhancedBucketGenerator bucketGenerator = new EnhancedBucketGenerator(ColumnManager.getInstance().getResultDirPath());
            EnhancedStatsExtractor.EnhancedColumnStatistics rsgenStats = convertToRSGenStatistics(statistics);
            
            logger.info("RSGen: 列 {} 转换统计信息完成，开始生成buckets", columnName);
            // Pass bound value information and total table size
            List<Bucket> buckets = bucketGenerator.generateBuckets(rsgenStats, (long) remainingSize, 
                                                                  boundValueCounts.isEmpty() ? null : boundValueCounts, 
                                                                  (long) size);
            logger.info("RSGen: 列 {} 生成了 {} 个buckets", columnName, buckets.size());
            
            // 打印bucket详情（前5个）
            // for (int i = 0; i < Math.min(5, buckets.size()); i++) {
            //     Bucket b = buckets.get(i);
            //     logger.info("RSGen: 列 {} Bucket[{}]: type={}, count={}, nDistinct={}, low={}, high={}", 
            //                columnName, i, b.getType(), b.getCount(), b.getDistinct(),
            //                b.getLow() != null ? b.getLow().getValue() : "null",
            //                b.getHigh() != null ? b.getHigh().getValue() : "null");
            // }
            
            // 使用 RSGenDataGeneratorRefactored 生成数据
            // 注意：generateColumnData 是 private，我们需要创建一个包装方法
            // logger.info("RSGen: 列 {} 开始调用 generateColumnDataPublic 生成数据", columnName);

            // // 打印11个MCV的数据
            // for (int i = 0; i < Math.min(11, buckets.size()); i++) {
            //     Bucket b = buckets.get(i);
            //     logger.info("RSGen: 列 {} Bucket[{}]: type={}, count={}, nDistinct={}, low={}, high={}", 
            //                columnName, i, b.getType(), b.getCount(), b.getDistinct(), 
            //                b.getLow() != null ? b.getLow().getValue() : "null",
            //                b.getHigh() != null ? b.getHigh().getValue() : "null");
            // }

            Object[] generatedData = generateColumnDataWithRSGen(rsgenStats, (long) remainingSize, buckets);
            logger.info("RSGen: 列 {} 成功生成 {} 行数据", columnName, generatedData != null ? generatedData.length : 0);
            
            // TODO: 这个地方似乎有问题啊，生成了generatedData了，又把他填充到columnActualData了，后面有需要转换
            // 这个columnActualData为 DOUBLE，好费劲啊

            // 填充到 columnActualData（跳过 bound 位置）
            int dataIndex = 0;
            int remainingCount = 0;
            int defaultedDueToShortage = 0;
            for (int i = 0; i < size; i++) {
                if (boundPositions.contains(i)) {
                    continue;  // 跳过 bound 位置
                }
                remainingCount++;
                if (dataIndex < generatedData.length) {
                columnActualData[i] = generatedData[dataIndex++];
                } else {
                    // ★★★ 修复：如果生成的数据不足，填充默认值以避免 NaN ★★★
                    defaultedDueToShortage++;
                    columnActualData[i] = getDefaultValueForType();
                }
            }
            if (defaultedDueToShortage > 0) {
                logger.debug("RSGen: 列 {} 生成行数不足：需 {} 非 bound 行，实际 {} 行，{} 个位置已填默认值",
                        columnName, remainingSize, generatedData.length, defaultedDueToShortage);
            }
            
            // ★★★ 验证：确保所有非 bound 位置都已填充 ★★★
            if (dataIndex < generatedData.length) {
                logger.warn("RSGen: 列 {} 生成的数据过多：需要 {} 行，实际生成 {} 行，多余 {} 行被忽略", 
                           columnName, remainingSize, generatedData.length, generatedData.length - dataIndex);
            }
            
            // 确保所有非 bound 位置都已填充（防止 NaN）；不在此处按行打日志（大表会刷屏）
            int defaultedDueToNull = 0;
            for (int i = 0; i < size; i++) {
                if (!boundPositions.contains(i) && columnActualData[i] == null) {
                    defaultedDueToNull++;
                    columnActualData[i] = getDefaultValueForType();
                }
            }
            if (defaultedDueToNull > 0) {
                logger.debug("RSGen: 列 {} 有 {} 个非 bound 位置原为 null，已填默认值", columnName, defaultedDueToNull);
            }
        } catch (Exception e) {
            logger.error("使用 RSGen 生成数据失败，回退到默认值: {}", e.getMessage(), e);
            // 回退：填充默认值
            for (int i = 0; i < size; i++) {
                if (!boundPositions.contains(i) && columnActualData[i] == null) {
                    columnActualData[i] = getDefaultValueForType();
                }
            }
        }
    }
    
    /**
     * 将 schema.EnhancedColumnStatistics 转换为 rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics
     */
    private EnhancedStatsExtractor.EnhancedColumnStatistics convertToRSGenStatistics(EnhancedColumnStatistics stats) {
        if (stats == null) {
            return null;
        }
        
        EnhancedStatsExtractor.EnhancedColumnStatistics rsgenStats = new EnhancedStatsExtractor.EnhancedColumnStatistics();
        rsgenStats.setColumnName(stats.getColumnName());
        rsgenStats.setTableName(stats.getTableName());
        rsgenStats.setShortColumnName(stats.getShortColumnName());
        rsgenStats.setDataType(stats.getDataType());
        rsgenStats.setNullFraction(stats.getNullFraction());
        rsgenStats.setAvgWidth(stats.getAvgWidth());
        rsgenStats.setMostCommonValues(stats.getMostCommonValues());
        rsgenStats.setMostCommonFrequencies(stats.getMostCommonFrequencies());
        rsgenStats.setHistogramBounds(stats.getHistogramBounds());
        rsgenStats.setMinValue(stats.getMinValue());
        rsgenStats.setMaxValue(stats.getMaxValue());
        // 关键：把 dataPattern 传过去（包含 max_length/avg_width），否则 VARCHAR 长度约束会退化为默认值 50
        rsgenStats.setDataPattern(stats.getDataPattern());
        rsgenStats.setMcvCount(stats.getMcvCount());
        rsgenStats.setHistogramBoundsCount(stats.getHistogramBoundsCount());
        rsgenStats.setNDistinct(stats.getNdistinct());
        rsgenStats.setPrimaryKey(stats.isPrimaryKey());
        rsgenStats.setForeignKey(stats.isForeignKey());
        
        return rsgenStats;
    }
    
    /**
     * 使用 RSGen 生成列数据（调用已有接口）
     */
    private Object[] generateColumnDataWithRSGen(EnhancedStatsExtractor.EnhancedColumnStatistics colStats, 
                                                 long tableSize, List<Bucket> buckets) {
        // 使用 RSGenDataGeneratorRefactored 的公共方法
        // 创建一个临时的实例（只需要用于生成数据，不需要完整初始化）
        RSGenDataGeneratorRefactored rsGenGenerator = new RSGenDataGeneratorRefactored(null);
        // 设置分布模型（从全局配置获取）
        String distributionModel = System.getProperty("mirage.distribution.model", "GOLDEN_RATIO");
        rsGenGenerator.setDistributionModel(distributionModel);
        return rsGenGenerator.generateColumnDataPublic(colStats, tableSize, buckets);
    }
    
    
    /**
     * 解析实际值字符串为对应的对象类型
     */
    private Object parseActualValue(String valueStr) {
        if (valueStr == null || "\\N".equals(valueStr)) {
            return null;
        }
        
        try {
            switch (columnType) {
                case INTEGER:
                    return Long.parseLong(valueStr);
                case DECIMAL:
                    return Double.parseDouble(valueStr);
                case DATE:
                case DATETIME:
                    return valueStr;  // 日期类型保持字符串
                default:
                    return valueStr;
            }
        } catch (Exception e) {
            logger.warn("解析实际值失败: {}, 使用原始字符串", valueStr);
            return valueStr;
        }
    }
    
    /**
     * 获取类型的默认值
     */
    private Object getDefaultValueForType() {
        switch (columnType) {
            case INTEGER:
                return 0L;
            case DECIMAL:
                return 0.0;
            case DATE:
            case DATETIME:
                return "1970-01-01";
            default:
                return "";
        }
    }
    
    /**
     * 填充数值数组（用于 ACCs 计算）
     * ★★★ 延迟转换：只在 Column.calculate() 被调用时才会调用此方法 ★★★
     */
    private void fillNumericValuesForStatistics(int size) {
        String columnName = statistics != null ? statistics.getColumnName() : "unknown";
        logger.info("fillNumericValuesForStatistics: 列 {} 开始填充数值数组，大小={}", columnName, size);   
        
        if (columnActualNumericValues == null) {
            logger.warn("fillNumericValuesForStatistics: 列 {} 的 columnActualNumericValues 为 null，无法转换", columnName);
            return;
        }
        
        // ★★★ 类型检查：只对数值型和日期型列进行转换 ★★★
        if (columnType != ColumnType.INTEGER && 
            columnType != ColumnType.DECIMAL && 
            columnType != ColumnType.DATE && 
            columnType != ColumnType.DATETIME) {
            logger.warn("fillNumericValuesForStatistics: 列 {} 类型为 {}，不应该进行数值转换，跳过", 
                       columnName, columnType);
            return;
        }
        
        for (int i = 0; i < size; i++) {
            Object value = columnActualData[i];
            if (value == null) {
                columnActualNumericValues[i] = Double.NaN;
            } else if (value instanceof Number) {
                columnActualNumericValues[i] = ((Number) value).doubleValue();
            } else if (value instanceof String) {
                // 日期类型字符串转换为数值
                try {
                    if (columnType == ColumnType.DATE || columnType == ColumnType.DATETIME) {
                        java.sql.Date date = java.sql.Date.valueOf((String) value);
                        columnActualNumericValues[i] = date.toLocalDate().toEpochDay();
                    } else {
                        columnActualNumericValues[i] = Double.parseDouble((String) value);
                    }
                } catch (Exception e) {
                    columnActualNumericValues[i] = Double.NaN;
                }
            } else if (value instanceof java.time.LocalDate) {
                // ★★★ 新增：处理LocalDate对象（MCV bucket生成的）★★★
                columnActualNumericValues[i] = ((java.time.LocalDate) value).toEpochDay();
            } else if (value instanceof java.time.LocalDateTime) {
                // ★★★ 新增：处理LocalDateTime对象 ★★★
                columnActualNumericValues[i] = ((java.time.LocalDateTime) value).toLocalDate().toEpochDay();
            } else {
                columnActualNumericValues[i] = Double.NaN;
            }
        }

        // ★★★ 诊断：统计columnActualNumericValues中null值数量 ★★★
        int nullCount = 0;
        for (double v : columnActualNumericValues) {
            if (Double.isNaN(v)) {
                nullCount++;
            }
        }
        if (nullCount > 0) {
            logger.warn("fillNumericValuesForStatistics: 列 {} 发现 {} 个null值（总数={}，占比={}%），这些位置将被设置为NaN", 
                       statistics.getColumnName(), nullCount, size, (double) nullCount / size * 100);
        }

    }
    
    /**
     * 基于 CDF 和统计信息生成列数据
     * 
     * 步骤：
     * 1. 处理 bound 值（来自 boundParas 的绑定关系，确保多列约束的值在同一行）
     * 2. 填充剩余位置（使用 CDF 生成符合分布的值）
     * 3. 处理 NULL 值
     * 
     * @param size 需要生成的行数
     * @return dataIndex 数组（虚拟索引，后续通过 transferDataToValue 转换）
     */
    private long[] prepareTupleDataFromCDF(int size) {
        long[] columnData = new long[size];
        // 为算术计算准备实际数值数组（只对数值型和日期型列）
        if (columnType == ColumnType.INTEGER || columnType == ColumnType.DECIMAL || 
            columnType == ColumnType.DATE || columnType == ColumnType.DATETIME) {
            columnActualNumericValues = new double[size];
        }
        
        Random random = new Random();
        
        // 第一步：处理 bound 值（来自 boundParas 的绑定关系）
        SortedMap<BigDecimal, Long> offset2Pv = distribution.getOffset2Pv();
        Set<Integer> boundPositions = new HashSet<>();
        
        if (offset2Pv != null && !offset2Pv.isEmpty()) {
            for (Map.Entry<BigDecimal, Long> entry : offset2Pv.entrySet()) {
                BigDecimal offset = entry.getKey();
                long virtualDataIndex = entry.getValue();
                
                // 计算这个 bound 值应该出现的起始位置
                int startPos = offset.multiply(BigDecimal.valueOf(size))
                                    .setScale(0, RoundingMode.HALF_UP)
                                    .intValue();
                
                // 根据该 dataIndex 的概率计算需要填充的行数
                Map<Long, BigDecimal> paraData2Probability = distribution.getParaData2Probability();
                if (paraData2Probability != null && paraData2Probability.containsKey(virtualDataIndex)) {
                    BigDecimal probability = paraData2Probability.get(virtualDataIndex);
                    int count = probability.multiply(BigDecimal.valueOf(size))
                                          .setScale(0, RoundingMode.HALF_UP)
                                          .intValue();
                    
                    // 填充 bound 值到指定位置
                    for (int i = 0; i < count && (startPos + i) < size; i++) {
                        columnData[startPos + i] = virtualDataIndex;
                        
                        // ★★★ 使用存储的实际值映射填充数值 ★★★
                        if (columnActualNumericValues != null && dataIndex2ActualValue.containsKey(virtualDataIndex)) {
                            String actualValue = dataIndex2ActualValue.get(virtualDataIndex);
                            // 对于日期类型，需要特殊处理
                            if (columnType == ColumnType.DATE || columnType == ColumnType.DATETIME) {
                                try {
                                    // 日期字符串 → java.sql.Date → epoch days
                                    java.sql.Date date = java.sql.Date.valueOf(actualValue);
                                    columnActualNumericValues[startPos + i] = date.toLocalDate().toEpochDay();
                                } catch (Exception e) {
                                    // 解析失败，尝试从 CDF 查找
                                    if (columnCDF != null) {
                                        ColumnCDF.ComparableValue boundValue = columnCDF.findValueBySelectivity(
                                            probability, CompareOperator.LE, false);
                                        if (boundValue != null) {
                                            columnActualNumericValues[startPos + i] = convertToNumericValue(boundValue);
                                        } else {
                                            columnActualNumericValues[startPos + i] = 0.0;
                                        }
                                    } else {
                                        columnActualNumericValues[startPos + i] = 0.0;
                                    }
                                }
                            } else {
                                // 数值类型，直接解析
                                try {
                                    columnActualNumericValues[startPos + i] = Double.parseDouble(actualValue);
                                } catch (NumberFormatException e) {
                                    columnActualNumericValues[startPos + i] = 0.0;
                                }
                            }
                        }
                        
                        boundPositions.add(startPos + i);
                    }
                }
            }
        }
        
        // 第二步：填充剩余位置
        // ★★★ 关键修复：严格按 paraData2Probability 生成数据，确保使用真实统计值 ★★★
        Map<Long, BigDecimal> paraData2Probability = distribution.getParaData2Probability();
        
        if (paraData2Probability == null || paraData2Probability.isEmpty()) {
            // 没有概率分布，填充默认值
            for (int i = 0; i < size; i++) {
                if (!boundPositions.contains(i)) {
                    columnData[i] = 0;
                    if (columnActualNumericValues != null) columnActualNumericValues[i] = 0.0;
                }
            }
        } else {
            // 严格按照 paraData2Probability 生成数据
            List<Long> dataIndexList = new ArrayList<>();
            int totalBoundCount = 0;  // 统计 bound 值的总数
            
            for (Map.Entry<Long, BigDecimal> entry : paraData2Probability.entrySet()) {
                long dataIndex = entry.getKey();
                BigDecimal probability = entry.getValue();
                
                // 计算该 dataIndex 应该出现的次数
                int count = probability.multiply(BigDecimal.valueOf(size))
                                      .setScale(0, RoundingMode.HALF_UP)
                                      .intValue();
                
                // 检查是否是 bound 值
                boolean isBound = offset2Pv != null && offset2Pv.containsValue(dataIndex);
                
                if (isBound) {
                    // bound 值已经在第一步处理了，统计数量
                    totalBoundCount += count;
                } else {
                    // 非 bound 值添加到列表
                    for (int i = 0; i < count; i++) {
                        dataIndexList.add(dataIndex);
                    }
                }
            }
            
            // 验证总数是否匹配
            int expectedNonBoundCount = size - boundPositions.size();
            if (dataIndexList.size() != expectedNonBoundCount && dataIndexList.size() > 0) {
                logger.warn("数据生成数量不匹配：预期 {} 个非 bound 值，实际生成 {} 个，总行数 {}, bound 行数 {}",
                           expectedNonBoundCount, dataIndexList.size(), size, boundPositions.size());
            }
            
            // 打乱顺序（保持随机性，但总数精确）
            Collections.shuffle(dataIndexList, random);
            
            // 填充到 columnData（跳过 bound 位置）
            int dataListIndex = 0;
            for (int i = 0; i < size && dataListIndex < dataIndexList.size(); i++) {
                if (boundPositions.contains(i)) {
                    continue;  // 跳过 bound 位置
                }
                
                long dataIndex = dataIndexList.get(dataListIndex++);
                columnData[i] = dataIndex;
                
                // ★★★ 使用存储的实际值映射填充数值 ★★★
                if (columnActualNumericValues != null && dataIndex2ActualValue.containsKey(dataIndex)) {
                    String actualValue = dataIndex2ActualValue.get(dataIndex);
                    // 对于日期类型，需要特殊处理
                    if (columnType == ColumnType.DATE || columnType == ColumnType.DATETIME) {
                        try {
                            // 日期字符串 → java.sql.Date → epoch days
                            java.sql.Date date = java.sql.Date.valueOf(actualValue);
                            columnActualNumericValues[i] = date.toLocalDate().toEpochDay();
                        } catch (Exception e) {
                            // 解析失败，尝试从 CDF 查找
                            if (columnCDF != null) {
                                BigDecimal prob = paraData2Probability.get(dataIndex);
                                if (prob != null) {
                                    ColumnCDF.ComparableValue value = columnCDF.findValueBySelectivity(
                                        prob, CompareOperator.LE, false);
                                    if (value != null) {
                                        columnActualNumericValues[i] = convertToNumericValue(value);
                                    } else {
                                        columnActualNumericValues[i] = 0.0;
                                    }
                                } else {
                                    columnActualNumericValues[i] = 0.0;
                                }
                            } else {
                                columnActualNumericValues[i] = 0.0;
                            }
                        }
                    } else {
                        // 数值类型，直接解析
                        try {
                            columnActualNumericValues[i] = Double.parseDouble(actualValue);
                        } catch (NumberFormatException e) {
                            columnActualNumericValues[i] = 0.0;
                        }
                    }
                }
            }
            
            // 填充剩余位置（如果 dataIndexList 不够）
            for (int i = 0; i < size; i++) {
                if (!boundPositions.contains(i) && columnData[i] == 0) {
                    // 使用默认值（通常是最大的 range 值）
                    columnData[i] = range;
                    if (columnActualNumericValues != null) {
                        columnActualNumericValues[i] = 0.0;
                    }
                }
            }
        }
        
        // 第三步：处理 NULL 值
        if (nullPercentage != null && nullPercentage.compareTo(BigDecimal.ZERO) > 0) {
            int nullCount = nullPercentage.multiply(BigDecimal.valueOf(size))
                                         .setScale(0, RoundingMode.HALF_UP)
                                         .intValue();
            
            // 从末尾开始填充 NULL（Mirage 用 Long.MIN_VALUE 表示 NULL）
            int nullStart = size - nullCount;
            for (int i = nullStart; i < size; i++) {
                if (!boundPositions.contains(i)) {
                    columnData[i] = Long.MIN_VALUE;
                    
                    // NULL 值的数值表示为 0.0（算术计算时可能需要特殊处理）
                    if (columnActualNumericValues != null) {
                        columnActualNumericValues[i] = 0.0;
                    }
                }
            }
        }
        
        return columnData;
    }
    
    /**
     * 将实际值转换为虚拟 dataIndex（与 ColumnManager 中的方法保持一致）
     */
    private long convertValueToVirtualIndex(ColumnCDF.ComparableValue value) {
        if (value == null || value.getValue() == null) {
            return 0;
        }
        
        long hash = Math.abs(value.getValue().toString().hashCode());
        long range = this.range;
        
        if (range <= 0) {
            return hash % 1000000;
        }
        
        return hash % range;
    }
    
    /**
     * 将 CDF 的值转换为数值（用于算术计算）
     * 
     * @param value CDF 中的实际值
     * @return 对应的数值表示
     */
    private double convertToNumericValue(ColumnCDF.ComparableValue value) {
        if (value == null || value.getValue() == null) {
            return 0.0;
        }
        
        Object rawValue = value.getValue();
        
        try {
            switch (columnType) {
                case INTEGER:
                    if (rawValue instanceof Number) {
                        return ((Number) rawValue).doubleValue();
                    }
                    break;
                    
                case DECIMAL:
                    if (rawValue instanceof BigDecimal) {
                        return ((BigDecimal) rawValue).doubleValue();
                    } else if (rawValue instanceof Number) {
                        return ((Number) rawValue).doubleValue();
                    }
                    break;
                    
                case DATE:
                    // 日期转换为从 epoch 起的天数
                    if (rawValue instanceof java.sql.Date) {
                        return ((java.sql.Date) rawValue).toLocalDate()
                                .toEpochDay();
                    }
                    break;
                    
                case DATETIME:
                    // 时间戳转换为从 epoch 起的秒数
                    if (rawValue instanceof java.sql.Timestamp) {
                        return ((java.sql.Timestamp) rawValue).getTime() / 1000.0;
                    }
                    break;
                    
                default:
                    // VARCHAR 等非数值类型不支持算术计算
                    return 0.0;
            }
        } catch (Exception e) {
            // 转换失败，返回默认值
            return 0.0;
        }
        
        // 如果无法转换，尝试解析字符串
        try {
            return Double.parseDouble(rawValue.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 无运算比较，针对传入的参数，对于单操作符进行比较
     *
     * @param operator   运算操作符
     * @param parameters 待比较的参数
     * @return 运算结果
     */
    public boolean[] evaluate(CompareOperator operator, List<Parameter> parameters) {
        // 根据是否有统计信息选择比较方式
        String colName = statistics != null ? statistics.getColumnName() : "unknown";
        if (statistics != null && columnActualData != null) {
            // 有统计信息对象且已生成实际值：使用实际值比较（完全不用 dataIndex）
            logger.debug("列 {} 使用 evaluateWithActualValues: statistics != null, columnActualData != null", colName);
            return evaluateWithActualValues(operator, parameters);
        } else {
            logger.debug("列 {} 使用 evaluateWithDataIndex: statistics={}", colName, statistics != null ? "present" : "null");
            return evaluateWithDataIndex(operator, parameters);
        }
    }
    
    /**
     * 使用实际值进行比较（统计信息列）
     */
    private boolean[] evaluateWithActualValues(CompareOperator operator, List<Parameter> parameters) {
        // 获取参数的实际值
        Object paramValue = null;
        if (operator != CompareOperator.ISNULL && operator != CompareOperator.IS_NOT_NULL) {
            Parameter param = parameters.get(0);

            // ★★★ 修复：确保参数有正确的 dataValue ★★★
            // 如果参数的 dataValue 为 null，尝试从映射中设置
            if (param.getDataValue() == null) {
                long dataIndex = param.getData();
                String actualValueStr = dataIndex2ActualValue.get(dataIndex);
                // ★★★ Bug修复：对于dataIndex=-1的情况，尝试使用虚拟dataIndex（-parameterId）查找 ★★★
                if (actualValueStr == null && dataIndex == -1) {
                    long virtualDataIndex = -param.getId();
                    actualValueStr = dataIndex2ActualValue.get(virtualDataIndex);
                }
                if (actualValueStr != null) {
                    param.setDataValue(actualValueStr);  // 设置参数的实际值
                    logger.debug("参数 ID {}: 从映射中设置 dataValue={} (dataIndex={})", 
                               param.getId(), actualValueStr, dataIndex);
                }
            }

            // 优先使用 dataValue（实际值），如果没有则从 dataIndex2ActualValue 查找
            if (param.getDataValue() != null) {
                paramValue = parseActualValue(param.getDataValue());
            } else {
                long dataIndex = param.getData();
                // ★★★ Bug修复：对于dataIndex=-1的情况（解耦约束），尝试使用虚拟dataIndex（-parameterId）查找 ★★★
                String actualValueStr = dataIndex2ActualValue.get(dataIndex);
                if (actualValueStr == null && dataIndex == -1) {
                    // 尝试使用虚拟dataIndex查找
                    long virtualDataIndex = -param.getId();
                    actualValueStr = dataIndex2ActualValue.get(virtualDataIndex);
                    if (actualValueStr != null) {
                        logger.debug("参数 ID {} 使用虚拟dataIndex {} 找到映射值: {}", 
                                   param.getId(), virtualDataIndex, actualValueStr);
                    }
                }
                if (actualValueStr != null) {
                    paramValue = parseActualValue(actualValueStr);
                }
            }

            // ★★★ 调试：记录参数值的解析过程 ★★★
            if (logger.isDebugEnabled()) {
                String colName = statistics != null ? statistics.getColumnName() : "unknown";
                logger.debug("参数值解析调试 - 列: {}, 参数ID: {}, 操作符: {}",
                    colName, param.getId(), operator);
                logger.debug("  dataValue: {}, dataIndex: {}", param.getDataValue(), param.getData());
                logger.debug("  dataIndex2ActualValue映射: {}",
                    dataIndex2ActualValue.get(param.getData()));
                logger.debug("  解析后的参数值: {}", paramValue);
            }
        }
        
        // ★★★ 调试：分析参数值在数据中的分布 ★★★
        if (logger.isDebugEnabled() && paramValue != null && columnActualData != null) {
            int totalRows = columnActualData.length;
            int matchCount = 0;
            int nullCount = 0;

            for (int i = 0; i < totalRows; i++) {
                if (columnActualData[i] == null) {
                    nullCount++;
                } else if (compareValues(columnActualData[i], paramValue) == 0) {
                    matchCount++;
                }
            }

            double matchRate = (double) matchCount / (totalRows - nullCount);
            String colName = statistics != null ? statistics.getColumnName() : "unknown";
            logger.debug("参数值分布分析 - 列: {}, 参数值: {}", colName, paramValue);
            logger.debug("  数据总行数: {}, NULL值: {}, 非NULL值: {}",
                totalRows, nullCount, totalRows - nullCount);
            logger.debug("  匹配行数: {}, 匹配率: {}", matchCount, String.format("%.3f", matchRate));
        }

        boolean[] ret = new boolean[columnActualData.length];
        switch (operator) {
            case ISNULL -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] == null;
                }
            }
            case IS_NOT_NULL -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null;
                }
            }
            case EQ -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && compareValues(columnActualData[i], paramValue) == 0;
                }
            }
            case LIKE -> {
                if (paramValue == null) {
                    java.util.Arrays.fill(ret, false);
                } else {
                    Pattern likeCompiled = Pattern.compile(convertLikePatternToRegex(paramValue.toString()), Pattern.DOTALL);
                    for (int i = 0; i < columnActualData.length; i++) {
                        Object cell = columnActualData[i];
                        ret[i] = cell != null && likeCompiled.matcher(cell.toString()).matches();
                    }
                }
            }
            case NE -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && compareValues(columnActualData[i], paramValue) != 0;
                }
            }
            case NOT_LIKE -> {
                if (paramValue == null) {
                    for (int i = 0; i < columnActualData.length; i++) {
                        ret[i] = columnActualData[i] != null;
                    }
                } else {
                    Pattern likeCompiled = Pattern.compile(convertLikePatternToRegex(paramValue.toString()), Pattern.DOTALL);
                    for (int i = 0; i < columnActualData.length; i++) {
                        Object cell = columnActualData[i];
                        ret[i] = cell != null && !likeCompiled.matcher(cell.toString()).matches();
                    }
                }
            }
            case LT -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && compareValues(columnActualData[i], paramValue) < 0;
                }
            }
            case LE -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && compareValues(columnActualData[i], paramValue) <= 0;
                }
            }
            case GT -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && compareValues(columnActualData[i], paramValue) > 0;
                }
            }
            case GE -> {
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && compareValues(columnActualData[i], paramValue) >= 0;
                }
            }
            case IN -> {
                Set<Object> paramValues = new HashSet<>();
                for (Parameter parameter : parameters) {
                    if (parameter.getDataValue() != null) {
                        paramValues.add(parseActualValue(parameter.getDataValue()));
                    } else {
                        long dataIndex = parameter.getData();
                        String actualValueStr = dataIndex2ActualValue.get(dataIndex);
                        if (actualValueStr != null) {
                            paramValues.add(parseActualValue(actualValueStr));
                        }
                    }
                }
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && paramValues.contains(columnActualData[i]);
                }
            }
            case NOT_IN -> {
                Set<Object> paramValues = new HashSet<>();
                for (Parameter parameter : parameters) {
                    if (parameter.getDataValue() != null) {
                        paramValues.add(parseActualValue(parameter.getDataValue()));
                    } else {
                        long dataIndex = parameter.getData();
                        String actualValueStr = dataIndex2ActualValue.get(dataIndex);
                        if (actualValueStr != null) {
                            paramValues.add(parseActualValue(actualValueStr));
                        }
                    }
                }
                for (int i = 0; i < columnActualData.length; i++) {
                    ret[i] = columnActualData[i] != null && !paramValues.contains(columnActualData[i]);
                }
            }
            default -> throw new UnsupportedOperationException();
        }
        return ret;
    }
    
    private String convertLikePatternToRegex(String likePattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                case '\\' -> {
                    // treat backslash as literal escape
                    regex.append("\\\\");
                }
                default -> {
                    if ("[](){}.*+?$^|#\\".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        regex.append("$");
        return regex.toString();
    }
    
    /**
     * 使用 dataIndex 进行比较（非统计信息列）
     */
    private boolean[] evaluateWithDataIndex(CompareOperator operator, List<Parameter> parameters) {
        long value;
        if (operator == CompareOperator.ISNULL) {
            value = Long.MIN_VALUE;
        } else {
            value = parameters.get(0).getData();
        }
        String colName = statistics != null ? statistics.getColumnName() : "unknown";
        Parameter param = parameters.get(0);
        logger.info("列 {} evaluateWithDataIndex: 操作符={}, 参数dataIndex={}, 参数dataValue={}, columnData长度={}", 
            colName, operator, value, param.getDataValue(), columnData != null ? columnData.length : 0);
        boolean[] ret = new boolean[columnData.length];
        switch (operator) {
            case ISNULL -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] == Long.MIN_VALUE;
                }
            }
            case IS_NOT_NULL -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE;
                }
            }
            case EQ, LIKE -> {
                int matchCount = 0;
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] == value;
                    if (ret[i]) matchCount++;
                }
                logger.info("列 {} evaluateWithDataIndex EQ: dataIndex={}, 匹配行数={}/{}", 
                    colName, value, matchCount, columnData.length);
            }
            case NE, NOT_LIKE -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] != value;
                }
            }
            case LT -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] < value;
                }
            }
            case LE -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] <= value;
                }
            }
            case GT -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] > value;
                }
            }
            case GE -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && columnData[i] >= value;
                }
            }
            case IN -> {
                HashSet<Long> parameterData = new HashSet<>();
                for (Parameter parameter : parameters) {
                    parameterData.add(parameter.getData());
                }
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && parameterData.contains(columnData[i]);
                }
            }
            case NOT_IN -> {
                HashSet<Long> parameterData = new HashSet<>();
                for (Parameter parameter : parameters) {
                    parameterData.add(parameter.getData());
                }
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = columnData[i] != Long.MIN_VALUE && !parameterData.contains(columnData[i]);
                }
            }
            default -> throw new UnsupportedOperationException();
        }
        return ret;
    }

    /**
     * @return 返回用于multi-var计算的一个double数组
     * 
     * ★★★ 注意：这个方法现在主要用于全表数据生成后的计算 ★★★
     * 对于 ACCs 参数估计，应该使用 generateSampleDataForACC() 方法
     * 
     * ★★★ 修复：如果 ArithmeticNode.getSize() > 0，说明是 ACCs 参数估计，应该使用采样数据 ★★★
     */
    public double[] calculate() {
        // ★★★ 关键修复：如果是 ACCs 参数估计（ArithmeticNode.getSize() > 0），使用采样数据 ★★★
        int sampleSize = ArithmeticNode.getSize() > 0 ? ArithmeticNode.getSize() : -1;
        if (sampleSize > 0 && statistics != null && hasStatistics()) {
            logger.debug("Column.calculate() 检测到 ACCs 参数估计，使用采样数据: 列={}, sampleSize={}", 
                        statistics.getColumnName(), sampleSize);
            return generateSampleDataForACC(sampleSize);
        }
        
        // 如果该列使用 CDF 并且已经生成了实际数值数组，直接返回（全表数据）
        if (hasCDF() && columnActualNumericValues != null) {
            return columnActualNumericValues;
        }
        
        // ★★★ 延迟转换：如果 columnActualNumericValues 为 null，但现在需要它，则进行转换 ★★★
        if (hasCDF() && columnActualData != null && columnActualNumericValues == null) {
            String columnName = statistics != null ? statistics.getColumnName() : "unknown";
            
            // 只对数值型和日期型列进行转换
            if (columnType == ColumnType.INTEGER || columnType == ColumnType.DECIMAL || 
                columnType == ColumnType.DATE || columnType == ColumnType.DATETIME) {
                int size = columnActualData.length;
                columnActualNumericValues = new double[size];
                logger.debug("Column.calculate() 延迟转换: 列 {} 开始转换 columnActualData 到 columnActualNumericValues，大小={}", 
                           columnName, size);
                fillNumericValuesForStatistics(size);  // 延迟转换
                return columnActualNumericValues;
            } else {
                // VARCHAR 等类型不应该进行转换
                logger.warn("Column.calculate() 延迟转换: 列 {} 类型为 {}，无法转换为数值数组，返回空数组", 
                           columnName, columnType);
                return new double[0];
            }
        }
        
        // 否则使用原始方法（基于 dataIndex 计算）
        if (columnData == null) {
            logger.warn("列 {} 的 columnData 为 null，返回空数组", statistics != null ? statistics.getColumnName() : "unknown");
            return new double[0];
        }
        
        double[] ret = new double[columnData.length];
        switch (columnType) {
            case DATE, DATETIME -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = (columnData[i] + min);
                }
            }
            case DECIMAL -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = ((double) (columnData[i] + min)) / specialValue;
                }
            }
            case INTEGER -> {
                for (int i = 0; i < columnData.length; i++) {
                    ret[i] = (double) (specialValue * columnData[i]) + min;
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + columnType);
        }
        return ret;
    }
    
    /**
     * ★★★ 新增：专门用于 ACCs 参数估计的采样数据生成 ★★★
     * 
     * 注意：此方法只在有 --statistics 参数时才会被调用
     * 如果没有统计信息参数，应该使用原始的 mirage 逻辑（calculate() 方法）
     * 
     * 与 prepareTupleData() 的区别：
     * 1. 只生成采样大小的数据，不生成全表数据
     * 2. 直接返回 double[]，用于算术计算
     * 3. 确保没有 null 或 NaN
     * 4. 根据 MCV 和直方图统计信息生成符合分布的数据
     * 
     * @param sampleSize 采样大小
     * @return 采样数据的数值数组（用于算术计算）
     */
    public synchronized double[] generateSampleDataForACC(int sampleSize) {
        logger.info("列 {} 生成 ACC 采样数据（使用统计信息），采样大小: {}", 
                   statistics != null ? statistics.getColumnName() : "unknown", sampleSize);
        if (sampleSize <= 0) {
            logger.warn("采样大小 {} <= 0，返回空数组", sampleSize);
            return new double[0];
        }
        
        // ★★★ 新增：检查缓存是否有效（避免重复生成同一列的ACC采样数据，并发安全）★★★
        if (accSampleDataCache != null && accSampleSizeCache == sampleSize) {
            logger.info("列 {} 使用缓存的 ACC 采样数据（大小: {}），避免重复生成", 
                       statistics != null ? statistics.getColumnName() : "unknown", sampleSize);
            return accSampleDataCache;
        }
        
        // ★★★ 安全检查：此方法应该只在有统计信息时被调用 ★★★
        if (statistics == null) {
            logger.error("generateSampleDataForACC() 被调用但 statistics 为 null，这不应该发生！回退到原始方法");
            return generateSampleDataWithoutStatistics(sampleSize);
        }
        
        if (!hasStatistics()) {
            logger.debug("列 {} 有统计信息对象但没有 MCV/直方图，使用原始方法生成采样数据", statistics.getColumnName());
            return generateSampleDataWithoutStatistics(sampleSize);
        }
        
        // 使用统计信息生成采样数据
        logger.info("列 {} 使用统计信息（MCV/直方图）生成 ACC 采样数据，采样大小: {}", statistics.getColumnName(), sampleSize);
        double[] sampleData = generateSampleDataWithStatistics(sampleSize);
        
        // ★★★ 缓存采样数据（synchronized保证线程安全）★★★
        accSampleDataCache = sampleData;
        accSampleSizeCache = sampleSize;
        
        return sampleData;
    }
    
    /**
     * 使用统计信息生成采样数据（MCV + 直方图）
     */
    private double[] generateSampleDataWithStatistics(int sampleSize) {
        String columnName = statistics.getColumnName();
        logger.debug("为列 {} 生成 ACC 采样数据，采样大小: {}", columnName, sampleSize);
        
        // 转换统计信息格式
        EnhancedStatsExtractor.EnhancedColumnStatistics rsgenStats = convertToRSGenStatistics(statistics);
        
        // 生成 buckets（用于采样，不需要 bound 值信息）
        EnhancedBucketGenerator bucketGenerator = new EnhancedBucketGenerator(ColumnManager.getInstance().getResultDirPath());
        List<Bucket> buckets = bucketGenerator.generateBuckets(rsgenStats, (long) sampleSize, null, (long) sampleSize);
        
        // 从 buckets 中采样生成数据
        double[] sampleData = new double[sampleSize];
        Random random = new Random();
        int index = 0;
        int nanCount = 0;
        int nullBucketCount = 0;
        
        logger.info("列 {} 开始从 {} 个 buckets 中采样，目标大小: {}", columnName, buckets.size(), sampleSize);
        
        for (int bucketIdx = 0; bucketIdx < buckets.size(); bucketIdx++) {
            Bucket bucket = buckets.get(bucketIdx);
            int count = (int) bucket.getCount();
            if (count <= 0) continue;
            
            // ★★★ 诊断：记录每个 bucket 的信息 ★★★
            if (bucket.getType() == Bucket.BucketType.NULL) {
                nullBucketCount += count;
                logger.debug("列 {} bucket[{}] 是 NULL bucket，count={}", columnName, bucketIdx, count);
            }
            
            // 从 bucket 中生成 count 个值
            for (int i = 0; i < count && index < sampleSize; i++) {
                double value = sampleValueFromBucket(bucket, random);
                if (Double.isNaN(value)) {
                    nanCount++;
                    logger.error("⚠️ 列 {} bucket[{}] 采样产生 NaN: bucketType={}, low={}, high={}, index={}", 
                               columnName, bucketIdx, bucket.getType(), 
                               bucket.getLow() != null ? bucket.getLow().getValue() : "null",
                               bucket.getHigh() != null ? bucket.getHigh().getValue() : "null",
                               index);
                }
                sampleData[index++] = value;
            }
        }
        
        logger.info("列 {} 采样完成: 已填充={}, NaN数量={}, NULL bucket数量={}, buckets总数={}", 
                   columnName, index, nanCount, nullBucketCount, buckets.size());
        
        // 确保所有位置都已填充（防止 NaN）
        while (index < sampleSize) {
            logger.warn("列 {} 采样数据不足，位置 {} 使用默认值", columnName, index);
            sampleData[index++] = getDefaultNumericValue();
        }
        
        // ★★★ 诊断：统计最终的 NaN 分布 ★★★
        int finalNaNCount = 0;
        for (int i = 0; i < sampleSize; i++) {
            if (Double.isNaN(sampleData[i])) {
                finalNaNCount++;
                if (finalNaNCount <= 10) {  // 只记录前10个 NaN 的位置
                    logger.error("⚠️ 列 {} 采样数据位置 {} 为 NaN", columnName, i);
                }
            }
        }
        if (finalNaNCount > 0) {
            logger.error("⚠️ 列 {} 最终采样数据包含 {} 个 NaN (总数: {})", columnName, finalNaNCount, sampleSize);
        }
        
        logger.debug("列 {} ACC 采样数据生成完成，大小: {}", columnName, sampleSize);
        return sampleData;
    }
    
    /**
     * 从 bucket 中采样一个值
     */
    private double sampleValueFromBucket(Bucket bucket, Random random) {
        Datum low = bucket.getLow();
        Datum high = bucket.getHigh();
        
        if (low == null || high == null) {
            logger.warn("⚠️ sampleValueFromBucket - bucket 的 low 或 high 为 null: low={}, high={}, bucketType={}", 
                       low, high, bucket != null ? bucket.getType() : "null");
            return getDefaultNumericValue();
        }
        
        Object lowValue = low.getValue();
        Object highValue = high.getValue();
        
        // ★★★ 诊断：记录 bucket 的详细信息 ★★★
        if (lowValue == null || highValue == null) {
            logger.error("⚠️ sampleValueFromBucket - bucket 的值对象为 null: lowValue={}, highValue={}, bucketType={}, columnType={}", 
                        lowValue, highValue, bucket.getType(), columnType);
            return getDefaultNumericValue();
        }
        
        try {
            switch (columnType) {
                case INTEGER:
                    if (lowValue instanceof Number && highValue instanceof Number) {
                        long lowLong = ((Number) lowValue).longValue();
                        long highLong = ((Number) highValue).longValue();
                        if (lowLong == highLong) {
                            return lowLong;
                        }
                        // 在区间内均匀采样
                        double result = lowLong + random.nextDouble() * (highLong - lowLong);
                        if (Double.isNaN(result)) {
                            logger.error("⚠️ sampleValueFromBucket - INTEGER 采样产生 NaN: low={}, high={}, result={}", 
                                        lowLong, highLong, result);
                        }
                        return result;
                    } else {
                        logger.warn("⚠️ sampleValueFromBucket - INTEGER 类型但值不是 Number: lowValue={} ({}), highValue={} ({})", 
                                   lowValue, lowValue.getClass(), highValue, highValue.getClass());
                    }
                    break;
                    
                case DECIMAL:
                    if (lowValue instanceof Number && highValue instanceof Number) {
                        double lowDouble = ((Number) lowValue).doubleValue();
                        double highDouble = ((Number) highValue).doubleValue();
                        if (lowDouble == highDouble) {
                            return lowDouble;
                        }
                        // 在区间内均匀采样
                        double result = lowDouble + random.nextDouble() * (highDouble - lowDouble);
                        if (Double.isNaN(result)) {
                            logger.error("⚠️ sampleValueFromBucket - DECIMAL 采样产生 NaN: low={}, high={}, result={}", 
                                        lowDouble, highDouble, result);
                        }
                        return result;
                    } else {
                        logger.warn("⚠️ sampleValueFromBucket - DECIMAL 类型但值不是 Number: lowValue={} ({}), highValue={} ({})", 
                                   lowValue, lowValue.getClass(), highValue, highValue.getClass());
                    }
                    break;
                    
                case DATE:
                case DATETIME:
                    // ★★★ 修复：支持 LocalDate 类型（bucket 中存储的是 LocalDate，不是 String）★★★
                    long lowDays = -1;
                    long highDays = -1;
                    
                    // 处理 lowValue
                    if (lowValue instanceof java.time.LocalDate) {
                        lowDays = ((java.time.LocalDate) lowValue).toEpochDay();
                    } else if (lowValue instanceof String) {
                        try {
                            java.sql.Date lowDate = java.sql.Date.valueOf((String) lowValue);
                            lowDays = lowDate.toLocalDate().toEpochDay();
                        } catch (Exception e) {
                            logger.error("⚠️ sampleValueFromBucket - 日期字符串解析失败: lowValue={}, error={}", 
                                        lowValue, e.getMessage());
                        }
                    } else {
                        logger.warn("⚠️ sampleValueFromBucket - DATE 类型但值类型不支持: lowValue={} ({})", 
                                   lowValue, lowValue != null ? lowValue.getClass() : "null");
                    }
                    
                    // 处理 highValue
                    if (highValue instanceof java.time.LocalDate) {
                        highDays = ((java.time.LocalDate) highValue).toEpochDay();
                    } else if (highValue instanceof String) {
                        try {
                            java.sql.Date highDate = java.sql.Date.valueOf((String) highValue);
                            highDays = highDate.toLocalDate().toEpochDay();
                        } catch (Exception e) {
                            logger.error("⚠️ sampleValueFromBucket - 日期字符串解析失败: highValue={}, error={}", 
                                        highValue, e.getMessage());
                        }
                    } else {
                        logger.warn("⚠️ sampleValueFromBucket - DATE 类型但值类型不支持: highValue={} ({})", 
                                   highValue, highValue != null ? highValue.getClass() : "null");
                    }
                    
                    // 如果解析成功，进行采样
                    if (lowDays >= 0 && highDays >= 0) {
                        if (lowDays == highDays) {
                            return (double) lowDays;
                        }
                        // 在区间内均匀采样
                        long sampledDays = lowDays + (long)(random.nextDouble() * (highDays - lowDays));
                        double result = (double) sampledDays;
                        if (Double.isNaN(result)) {
                            logger.error("⚠️ sampleValueFromBucket - DATE 采样产生 NaN: low={} ({}), high={} ({}), result={}", 
                                        lowValue, lowDays, highValue, highDays, result);
                        }
                        return result;
                    }
                    break;
                    
                default:
                    logger.warn("⚠️ sampleValueFromBucket - 不支持的列类型: {}", columnType);
                    return getDefaultNumericValue();
            }
        } catch (Exception e) {
            logger.error("⚠️ sampleValueFromBucket - 从 bucket 采样值失败: columnType={}, lowValue={}, highValue={}, error={}", 
                        columnType, lowValue, highValue, e.getMessage(), e);
        }
        
        double defaultValue = getDefaultNumericValue();
        logger.warn("⚠️ sampleValueFromBucket - 返回默认值: {}", defaultValue);
        return defaultValue;
    }
    
    /**
     * 没有统计信息时，使用原始方法生成采样数据
     */
    private double[] generateSampleDataWithoutStatistics(int sampleSize) {
        double[] ret = new double[sampleSize];
        // 使用原始的 dataIndex 方法生成采样数据
        long[] sampleDataIndex = distribution.prepareTupleData(sampleSize);
        
        switch (columnType) {
            case DATE, DATETIME -> {
                for (int i = 0; i < sampleSize; i++) {
                    ret[i] = (sampleDataIndex[i] + min);
                }
            }
            case DECIMAL -> {
                for (int i = 0; i < sampleSize; i++) {
                    ret[i] = ((double) (sampleDataIndex[i] + min)) / specialValue;
                }
            }
            case INTEGER -> {
                for (int i = 0; i < sampleSize; i++) {
                    ret[i] = (double) (specialValue * sampleDataIndex[i]) + min;
                }
            }
            default -> {
                // 非数值类型，返回默认值
                for (int i = 0; i < sampleSize; i++) {
                    ret[i] = getDefaultNumericValue();
                }
            }
        }
        return ret;
    }
    
    /**
     * 获取默认数值（用于填充缺失值）
     */
    private double getDefaultNumericValue() {
        switch (columnType) {
            case INTEGER:
                return 0.0;
            case DECIMAL:
                return 0.0;
            case DATE:
            case DATETIME:
                // 返回 epoch 的日期（1970-01-01）
                return 0.0;
            default:
                return 0.0;
        }
    }

    public int getAvgLength() {
        return avgLength;
    }

    public void setAvgLength(int avgLength) {
        this.avgLength = avgLength;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public String transferDataToValue(long data) {
        if (data == Long.MIN_VALUE) {
            return "\\N";
        }
        
        // ★★★ 优先使用 CDF 的实际值映射 ★★★
        if (hasCDF() && dataIndex2ActualValue.containsKey(data)) {
            return dataIndex2ActualValue.get(data);
        }
        
        // ★★★ 对于 RSGen 生成的列，如果 dataIndex2ActualValue 为空，尝试从 CDF 中查找 ★★★
        if (hasCDF() && columnCDF != null && dataIndex2ActualValue.isEmpty()) {
            try {
                // 将 dataIndex 转换为选择率（这是一个启发式方法）
                // 假设 dataIndex 代表一个排序位置，转换为选择率
                BigDecimal selectivity;
                if (data == -1) {
                    // 特殊值 -1 通常表示最大值
                    selectivity = BigDecimal.ONE;
                } else if (data == 1) {
                    // 特殊值 1 通常表示最小值
                    selectivity = BigDecimal.ZERO;
                } else {
                    // 将 dataIndex 映射到 [0, 1] 范围（这是一个粗略的映射）
                    // 这里假设 dataIndex 的范围大致对应选择率
                    selectivity = BigDecimal.valueOf(Math.abs(data) % 10000).divide(BigDecimal.valueOf(10000), 4, java.math.RoundingMode.HALF_UP);
                }
                
                // 尝试从 CDF 中查找值（使用 EQ 操作符作为默认）
                ColumnCDF.ComparableValue cv = columnCDF.findValueBySelectivity(selectivity, CompareOperator.EQ, false);
                if (cv != null && cv.getValue() != null) {
                    return cv.getValue().toString();
                }
            } catch (Exception e) {
                // 如果从 CDF 查找失败，继续使用原始方法
                logger.debug("从 CDF 查找参数值失败，使用原始方法: {}", e.getMessage());
            }
        }
        
        // 否则使用原始方法
        return switch (columnType) {
            case INTEGER -> Long.toString((specialValue * data) + min);
            case DECIMAL -> BigDecimal.valueOf(data + min).multiply(decimalPre).toString();
            case VARCHAR -> stringTemplate.getParameterValue(data);
            case DATE -> CommonUtils.dateFormatter.format(Instant.ofEpochSecond((data + min) * 24 * 60 * 60));
            case DATETIME -> CommonUtils.dateTimeFormatter.format(Instant.ofEpochSecond(data + min));
            default -> throw new UnsupportedOperationException();
        };
    }

    public void addSubStringIndex(long dataId) {
        stringTemplate.addSubStringIndex(dataId);
    }

    public void setColumnData(long[] columnData) {
        this.columnData = columnData;
    }
    
    public Object[] getColumnActualData() {
        return columnActualData;
    }

    public boolean hasDataForEvaluation() {
        return columnActualData != null || columnData != null;
    }

    public String output(int index) {
        // 根据是否有统计信息选择输出方式
        final String raw;
        if (statistics != null && columnActualData != null) {
            raw = outputActualValue(index);
        } else {
            raw = transferDataToValue(columnData[index]);
        }
        // 文本导出使用 '|' 为字段分隔符；varchar 中不得含 '|'（MCV 等可能来自源库）
        if (columnType == ColumnType.VARCHAR && raw != null && !raw.equals("\\N")) {
            String s = DataExportConstants.stripFieldDelimiterFromText(raw);
            int cap = effectiveVarcharExportMaxLength();
            if (cap > 0) {
                s = DataExportConstants.truncateToMaxChars(s, cap);
            }
            return s;
        }
        return raw;
    }

    /**
     * 导出截断用长度：优先 {@link #maxLength}（分布/统计写入），否则从 {@link #originalType} 解析。
     */
    private int effectiveVarcharExportMaxLength() {
        if (columnType != ColumnType.VARCHAR) {
            return 0;
        }
        if (maxLength > 0) {
            return maxLength;
        }
        Integer parsed = DataExportConstants.parseCharFamilyMaxLength(originalType);
        return parsed != null && parsed > 0 ? parsed : 0;
    }
    
    /**
     * 输出实际值（统计信息列）
     */
    private String outputActualValue(int index) {
        Object value = columnActualData[index];
        if (value == null) {
            return "\\N";
        }
        
        // 根据类型格式化输出
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Number) {
            if (columnType == ColumnType.DECIMAL) {
                return BigDecimal.valueOf(((Number) value).doubleValue()).toString();
            } else {
                return value.toString();
            }
        } else {
            return value.toString();
        }
    }
    
    /**
     * 比较两个实际值
     * 返回：负数表示 value1 < value2，0 表示相等，正数表示 value1 > value2
     */
    @SuppressWarnings("unchecked")
    private int compareValues(Object value1, Object value2) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return -1;
        if (value2 == null) return 1;
        
        // 如果都是数字，进行数值比较
        if (value1 instanceof Number && value2 instanceof Number) {
            double d1 = ((Number) value1).doubleValue();
            double d2 = ((Number) value2).doubleValue();
            return Double.compare(d1, d2);
        }
        
        // 如果都是字符串，进行字符串比较
        if (value1 instanceof String && value2 instanceof String) {
            // 尝试解析为日期
            try {
                java.sql.Date date1 = java.sql.Date.valueOf((String) value1);
                java.sql.Date date2 = java.sql.Date.valueOf((String) value2);
                return date1.compareTo(date2);
            } catch (Exception e) {
                // 不是日期格式，使用字符串比较
                return ((String) value1).compareTo((String) value2);
            }
        }
        
        // 其他情况，转换为字符串比较
        return value1.toString().compareTo(value2.toString());
    }

    public long getMin() {
        return min;
    }

    public void setMin(long min) {
        this.min = min;
    }

    public long getSpecialValue() {
        return specialValue;
    }

    public void setSpecialValue(long specialValue) {
        if (columnType == ColumnType.DECIMAL) {
            decimalPre = BigDecimal.ONE.divide(BigDecimal.valueOf(specialValue), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
        }
        this.specialValue = specialValue;
    }


    public StringTemplate getStringTemplate() {
        return stringTemplate;
    }

    /**
     * 从统计信息构建 CDF
     * @param stats 增强的列统计信息
     */
    public void buildCDFFromStatistics(EnhancedColumnStatistics stats) {
        this.statistics = stats;
        // 保留实例化阶段写入的 ParameterConstraint；否则 loadStatistics 会新建 CDF 导致 amendParameters 无法从 CDF 恢复 LIKE 字面量
        ColumnCDF.ParameterConstraint preservedPc = this.columnCDF != null
                ? this.columnCDF.getParameterConstraint() : null;
        this.columnCDF = new ColumnCDF();
        this.columnCDF.buildFromStatistics(stats);
        if (preservedPc != null) {
            this.columnCDF.setParameterConstraint(preservedPc);
        }
    }

    /**
     * 根据选择率和操作符从 CDF 中查找参数值
     * @param selectivity 选择率 (0.0-1.0)
     * @param operator 比较操作符
     * @return 对应的值（包装在 ComparableValue 中）
     */
    public ColumnCDF.ComparableValue findParameterValue(BigDecimal selectivity, CompareOperator operator) {
        if (columnCDF == null) {
            throw new IllegalStateException("CDF not initialized for column");
        }
        return columnCDF.findValueBySelectivity(selectivity, operator);
    }

    /**
     * 检查是否已构建 CDF 且 CDF 不为空
     * @return 如果 CDF 已构建且有数据返回 true
     */
    public boolean hasCDF() {
        return columnCDF != null && !columnCDF.isEmpty();
    }

    public ColumnCDF getColumnCDF() {
        return columnCDF;
    }
    
    public void setColumnCDF(ColumnCDF cdf) {
        this.columnCDF = cdf;
    }

    public EnhancedColumnStatistics getStatistics() {
        return statistics;
    }
    
    public Map<Long, String> getDataIndex2ActualValue() {
        return dataIndex2ActualValue;
    }
    
    public void setDataIndex2ActualValue(Map<Long, String> dataIndex2ActualValue) {
        this.dataIndex2ActualValue = dataIndex2ActualValue;
    }
    
    public BigDecimal getDecimalPre() {
        return decimalPre;
    }
    
    /**
     * ★★★ 新增：清理 ACC 采样数据缓存
     */
    public void clearAccSampleDataCache() {
        accSampleDataCache = null;
        accSampleSizeCache = -1;
    }

}
