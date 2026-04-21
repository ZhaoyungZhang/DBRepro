package ruc.db.schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.generator.constraintchain.filter.operation.CompareOperator;
import ruc.db.rsgen.Bucket;
import ruc.db.rsgen.Datum;
import ruc.db.rsgen.EnhancedBucketGenerator;
import ruc.db.rsgen.EnhancedStatsExtractor;

/**
 * 基于数据库统计信息构建的列累积分布函数（CDF）
 * 从 MCV（Most Common Values）和 Histogram 构建有序的 CDF
 * 用于根据选择率实例化查询谓词参数
 * 
 * @author wangqingshuai
 */
public class ColumnCDF {
    
    private static final Logger logger = LoggerFactory.getLogger(ColumnCDF.class);
    
    /**
     * 有序的 CDF 点：<值, 累积概率>
     * 从 MCV + Histogram 构建，保证单调递增
     */
    private TreeMap<ComparableValue, BigDecimal> valueToCumulativeProbability;
    
    /**
     * 列的数据类型
     */
    private String dataType;
    
    /**
     * 总行数
     */
    private long tableSize;
    
    /**
     * ★★★ 新增：跟踪本列中已使用的值，用于排除重复 ★★★
     */
    private Set<String> usedValues = new HashSet<>();
    
    /**
     * 列名（用于日志）
     */
    private String columnName;

    /**
     * 统计信息（用于参数选择）
     */
    private EnhancedColumnStatistics statistics;

    /**
     * 参数选择约束信息
     */
    public static class ParameterConstraint {
        public String selectedValue;  // 选择的参数值（单个值，向后兼容）
        public List<String> selectedValues;  // 选择的参数值列表（多个值，用于多个EQ约束）
        public Map<String, BigDecimal> valueToSelectivity;  // 每个值对应的选择率
        public Map<String, CompareOperator> valueToOperator;  // 每个值对应的操作符
        public BigDecimal selectivity;  // 总选择率（向后兼容，不再用于判断）
        public CompareOperator operator;  // 默认操作符（向后兼容）

        public ParameterConstraint(String selectedValue, BigDecimal selectivity, CompareOperator operator) {
            this.selectedValue = selectedValue;
            this.selectedValues = new ArrayList<>();
            this.selectedValues.add(selectedValue);
            this.valueToSelectivity = new HashMap<>();
            this.valueToSelectivity.put(selectedValue, selectivity);
            this.valueToOperator = new HashMap<>();
            this.valueToOperator.put(selectedValue, operator);
            this.selectivity = selectivity;
            this.operator = operator;
        }
        
        public ParameterConstraint(List<String> selectedValues, BigDecimal selectivity, CompareOperator operator) {
            this.selectedValues = selectedValues != null ? new ArrayList<>(selectedValues) : new ArrayList<>();
            this.selectedValue = selectedValues != null && !selectedValues.isEmpty() ? selectedValues.get(0) : null;
            this.valueToSelectivity = new HashMap<>();
            this.valueToOperator = new HashMap<>();
            // 如果没有提供每个值的选择率，假设均匀分布
            if (selectedValues != null && !selectedValues.isEmpty()) {
                BigDecimal avgSelectivity = selectivity.divide(BigDecimal.valueOf(selectedValues.size()), 10, RoundingMode.HALF_UP);
                for (String value : selectedValues) {
                    this.valueToSelectivity.put(value, avgSelectivity);
                    this.valueToOperator.put(value, operator);
                }
            }
            this.selectivity = selectivity;
            this.operator = operator;
        }
        
        /**
         * 添加一个值及其选择率和操作符
         */
        public void addValue(String value, BigDecimal valueSelectivity, CompareOperator valueOperator) {
            if (value == null) return;
            
            if (!selectedValues.contains(value)) {
                selectedValues.add(value);
                if (selectedValue == null) {
                    selectedValue = value;
                }
            }
            
                if (valueSelectivity != null) {
                // ★★★ 修复：对于范围约束的增量选择率，如果值已存在，保留较小的值（增量选择率） ★★★
                // 这样可以避免 updateParameterConstraint 覆盖我们在 findValueBySelectivity 中设置的增量选择率
                if (!valueToSelectivity.containsKey(value)) {
                    // 值不存在，直接添加
                    valueToSelectivity.put(value, valueSelectivity);
                } else {
                    // 值已存在：对范围约束/等值约束都允许更新（后写覆盖前写）
                    // 注意：Stage3 的 UPDATE_MCV/IPF 会按 operator 语义去约束“集合概率”（例如 GE 9 约束的是 P(X>=9)）
                    // 因此这里不再使用“增量选择率”技巧，直接保存累计选择率即可，由IPF自然推出差值。
                    BigDecimal existingSelectivity = valueToSelectivity.get(value);
                    CompareOperator existingOperator = valueToOperator.getOrDefault(value, this.operator);
                    boolean isRangeOperator = (existingOperator == CompareOperator.GE || existingOperator == CompareOperator.GT ||
                                              existingOperator == CompareOperator.LE || existingOperator == CompareOperator.LT);
                    
                    // 统一更新（如果相同则无变化）
                    if (existingSelectivity == null || existingSelectivity.compareTo(valueSelectivity) != 0) {
                        valueToSelectivity.put(value, valueSelectivity);
                    }
                }
                // 重新计算总selectivity
                selectivity = valueToSelectivity.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            
            if (valueOperator != null) {
                valueToOperator.put(value, valueOperator);
            }
        }
        
        /**
         * 添加一个值（使用默认operator）
         */
        public void addValue(String value, BigDecimal valueSelectivity) {
            addValue(value, valueSelectivity, this.operator);
        }
        
        /**
         * 获取指定值的操作符
         */
        public CompareOperator getOperatorForValue(String value) {
            return valueToOperator.getOrDefault(value, operator);
        }
    }

    /**
     * 参数选择约束（用于数据生成阶段调整频率分布）
     */
    private ParameterConstraint parameterConstraint;

    /**
     * 获取参数约束
     */
    public ParameterConstraint getParameterConstraint() {
        return parameterConstraint;
    }
    
    /**
     * 设置参数约束
     */
    public void setParameterConstraint(ParameterConstraint constraint) {
        this.parameterConstraint = constraint;
    }

    public ColumnCDF() {
        this.valueToCumulativeProbability = new TreeMap<>();
    }
    
    /**
     * 从增强的列统计信息构建 CDF
     * @param stats 来自 enhanced_column_statistics.json 的统计信息
     */
    public void buildFromStatistics(EnhancedColumnStatistics stats) {
        this.tableSize = stats.getTableSize();
        this.dataType = stats.getDataType();
        this.columnName = stats.getColumnName();
        this.statistics = stats;  // 保存统计信息引用
        valueToCumulativeProbability.clear();

        // 初始化参数约束为null
        this.parameterConstraint = null;
        
        // 【新增】检查是否需要使用bucket-based CDF（精确估计）
        boolean hasMCV = stats.getMostCommonValues() != null && 
                         !stats.getMostCommonValues().isEmpty();
        boolean hasHistogram = stats.getHistogramBounds() != null && 
                              !stats.getHistogramBounds().isEmpty();
        
        // 如果同时有MCV和Histogram，使用bucket-based CDF（精确估计）
        if (hasMCV && hasHistogram) {
            try {
                // 转换类型
                EnhancedStatsExtractor.EnhancedColumnStatistics rsgenStats = 
                    convertToRSGenStatistics(stats);
                
                // 使用专门的方法生成buckets
                EnhancedBucketGenerator bucketGenerator = new EnhancedBucketGenerator(ColumnManager.getInstance().getResultDirPath());
                List<Bucket> buckets = bucketGenerator.generateBucketsForCDF(rsgenStats, tableSize);
                
                // 基于生成的buckets构建CDF
                buildCDFFromBuckets(buckets, tableSize);
                
                logger.info("✅ Column {}: Built bucket-based CDF ({} buckets) for accurate estimation", 
                           columnName, buckets.size());
                return; // 使用bucket-based CDF，直接返回
            } catch (Exception e) {
                logger.warn("Failed to build bucket-based CDF for column {}, falling back to uniform distribution: {}", 
                           columnName, e.getMessage());
                // 继续执行原有的均匀分布逻辑
            }
        }
        
        // 【原有逻辑】使用均匀分布假设构建CDF
        BigDecimal cumulative = BigDecimal.ZERO;
        
        // 1. 处理 MCV（Most Common Values）
        List<String> mcvs = stats.getMostCommonValues();
        List<Double> freqs = stats.getMostCommonFrequencies();
        
        if (mcvs != null && freqs != null && !mcvs.isEmpty()) {
            logger.debug("Building CDF for column {}: {} MCVs", columnName, mcvs.size());
            for (int i = 0; i < mcvs.size(); i++) {
                ComparableValue value = parseValue(mcvs.get(i), dataType);
                BigDecimal frequency = BigDecimal.valueOf(freqs.get(i));
                cumulative = cumulative.add(frequency);
                valueToCumulativeProbability.put(value, cumulative);
            }
        }
        
        // 2. 处理 Histogram Bounds
        List<String> histogramBounds = stats.getHistogramBounds();
        if (histogramBounds != null && !histogramBounds.isEmpty()) {
            logger.debug("Building CDF for column {}: {} histogram bounds", 
                        columnName, histogramBounds.size());
            
            // histogram 中每个 bucket 的概率（假设均匀分布）
            BigDecimal remainingProb = BigDecimal.ONE.subtract(cumulative);
            int bucketCount = histogramBounds.size();
            
            if (bucketCount > 0) {
                BigDecimal bucketProb = remainingProb.divide(
                    BigDecimal.valueOf(bucketCount), 10, RoundingMode.HALF_UP
                );
                
                for (String boundStr : histogramBounds) {
                    ComparableValue value = parseValue(boundStr, dataType);
                    cumulative = cumulative.add(bucketProb);
                    // 确保不超过 1.0
                    if (cumulative.compareTo(BigDecimal.ONE) > 0) {
                        cumulative = BigDecimal.ONE;
                    }
                    valueToCumulativeProbability.put(value, cumulative);
                }
            }
        }
        
        // 确保最后一个值的累积概率为 1.0
        if (!valueToCumulativeProbability.isEmpty()) {
            ComparableValue lastKey = valueToCumulativeProbability.lastKey();
            valueToCumulativeProbability.put(lastKey, BigDecimal.ONE);
        }
        
        logger.info("Built CDF for column {}: {} unique values", 
                   columnName, valueToCumulativeProbability.size());
    }
    
    /**
     * 检查 CDF 是否为空
     * @return 如果 CDF 没有数据返回 true
     */
    public boolean isEmpty() {
        return valueToCumulativeProbability == null || valueToCumulativeProbability.isEmpty();
    }
    
    /**
     * 根据目标选择率（selectivity）查找对应的值
     * 用于实例化谓词参数
     * 
     * @param targetSelectivity 目标选择率（例如 0.3 表示选择 30% 的数据）
     * @param operator 比较操作符（<, <=, >, >=, =）
     * @return 对应的参数值
     */
    public ComparableValue findValueBySelectivity(BigDecimal targetSelectivity, CompareOperator operator) {
        return findValueBySelectivity(targetSelectivity, operator, true);
    }
    
    /**
     * 根据目标选择率查找值（内部方法，支持控制日志）
     * 
     * @param targetSelectivity 目标选择率
     * @param operator 比较操作符
     * @param enableLogging 是否打印日志（数据生成时关闭以避免日志过大）
     * @return 对应的参数值
     */
    public ComparableValue findValueBySelectivity(BigDecimal targetSelectivity, 
                                                  CompareOperator operator, 
                                                  boolean enableLogging) {
        // 【第0步】去重逻辑：检查是否已有相同操作符且选择率相近的参数
        if (this.parameterConstraint != null && targetSelectivity != null) {
            // 查找是否有相同操作符的参数
            for (Map.Entry<String, BigDecimal> entry : this.parameterConstraint.valueToSelectivity.entrySet()) {
                String existingValue = entry.getKey();
                CompareOperator existingOp = this.parameterConstraint.getOperatorForValue(existingValue);
                if (existingOp == operator) {
                    // 找到相同操作符的参数，检查选择率是否相近
                    BigDecimal existingSelectivity = entry.getValue();
                    BigDecimal diff = targetSelectivity.subtract(existingSelectivity).abs();
                    if (diff.compareTo(new BigDecimal("0.0001")) < 0) {
                        // 选择率很接近，更新选择率为新的选择率（第二个选择率），然后返回已有参数的值
                        this.parameterConstraint.valueToSelectivity.put(existingValue, targetSelectivity);
                        try {
                            ComparableValue result = parseValue(existingValue, dataType);
                            if (enableLogging) {
                                logger.info("🔄 去重逻辑：列 {} 操作符 {} 选择率 {} 与已有参数 {} 的选择率 {} 相近（差值 {}），更新选择率为 {} 并复用已有参数的值 {}",
                                           columnName, operator, targetSelectivity, existingValue, existingSelectivity, diff, targetSelectivity, result);
                            }
                            return result;
                        } catch (Exception e) {
                            logger.warn("无法解析值 {} 为 ComparableValue", existingValue, e);
                        }
                    }
                }
            }
        }
        
        // 【第1步】前置检查：选择率为0或1（解耦约束）
        logger.info("findValueBySelectivity: targetSelectivity={}, operator={}", targetSelectivity, operator);
        if (targetSelectivity != null) {
            if (targetSelectivity.compareTo(BigDecimal.ZERO) == 0 || targetSelectivity.compareTo(BigDecimal.ONE) == 0) {
                // ★★★ 修复：根据操作符类型处理边界情况 ★★★
                switch (operator) {
                    case LT, LE, GT, GE:
                        // 范围查询：返回边界的极端值（调整后的）
                        if (valueToCumulativeProbability.isEmpty()) {
                            throw new IllegalStateException("CDF is empty for column: " + columnName);
                        }
                        
                        ComparableValue boundaryValue;
                        if (operator == CompareOperator.LT || operator == CompareOperator.LE) {
                            // LT/LE: 当selectivity=1时，返回max的更大值；当selectivity=0时，返回min的更小值
                            if (targetSelectivity.compareTo(BigDecimal.ONE) == 0) {
                                // col < val 的selectivity=1 → val > max
                                boundaryValue = valueToCumulativeProbability.lastKey();
                                boundaryValue = adjustValueForStrictInequality(boundaryValue, false);  // 增大
                            } else {
                                // col < val 的selectivity=0 → val <= min
                                boundaryValue = valueToCumulativeProbability.firstKey();
                                boundaryValue = adjustValueForStrictInequality(boundaryValue, true);   // 减小
                            }
                        } else {
                            // GT/GE: 当selectivity=1时，返回min的更小值；当selectivity=0时，返回max的更大值
                            if (targetSelectivity.compareTo(BigDecimal.ONE) == 0) {
                                // col > val 的selectivity=1 → val < min
                                boundaryValue = valueToCumulativeProbability.firstKey();
                                boundaryValue = adjustValueForStrictInequality(boundaryValue, true);   // 减小
                            } else {
                                // col > val 的selectivity=0 → val >= max
                                boundaryValue = valueToCumulativeProbability.lastKey();
                                boundaryValue = adjustValueForStrictInequality(boundaryValue, false);  // 增大
                            }
                        }
                        
                if (enableLogging) {
                            logger.debug("列 {} 范围查询 {} 选择率为 {}: 返回边界值 {}", 
                                       columnName, operator, targetSelectivity, boundaryValue);
                        }
                        return boundaryValue;
                        
                    case EQ, LIKE, IN, NE, NOT_LIKE, NOT_IN:
                        // 等值查询：选择率为0或1不应该出现（这是异常情况）
                        logger.warn("列 {} 等值类型操作符 {} 的选择率为 {}，这通常表示约束解耦不当", 
                                   columnName, operator, targetSelectivity);
                return null;
                        
                    default:
                        if (enableLogging) {
                            logger.debug("列 {} 选择率为 {}（解耦约束），跳过值查找", columnName, targetSelectivity);
                        }
                        return null;
                }
            }
        }
        
        if (valueToCumulativeProbability.isEmpty()) {
            throw new IllegalStateException("CDF is empty for column: " + columnName);
        }
        
        // 根据操作符调整目标累积概率
        BigDecimal targetCumulativeProb;
        boolean needAdjustmentForStrictInequality = false;
        
        // ★★★ 特殊处理：GE操作符且targetSelectivity > 0.8时，使用逆反条件 ★★★
        if (operator == CompareOperator.GE && targetSelectivity != null) {
            targetCumulativeProb = BigDecimal.ONE.subtract(targetSelectivity);
            // 严格条件：只处理 targetSelectivity > 0.8 的情况
            if (targetSelectivity.compareTo(new BigDecimal("0.8")) > 0) {
                InverseConditionResult inverseResult = handleGEInverseCondition(targetSelectivity, targetCumulativeProb);
                if (inverseResult != null) {
                    // 使用逆反条件处理
                    String queryParamStr = inverseResult.queryParameter.getValue().toString();
                    String updateValueStr = inverseResult.updateValue.getValue().toString();
                    
                    // 标记更新值为已使用（不标记查询参数）
                    usedValues.add(updateValueStr);
                    if (enableLogging) {
                        logger.info("🔄 GE逆反条件: 查询参数={}, 更新值={}（已标记为已使用）, 单值频率={}", 
                                   queryParamStr, updateValueStr, inverseResult.singleValueFrequency);
                    }
                    
                    // 保存到 parameterConstraint：使用 EQ 操作符和单值频率
                    boolean shouldRecordRange = decideShouldRecordConstraintForRange(operator, targetSelectivity);
                    if (shouldRecordRange) {
                        if (this.parameterConstraint == null) {
                            // 第一个约束，使用 EQ 操作符和单值频率
                            this.parameterConstraint = new ParameterConstraint(updateValueStr, 
                                BigDecimal.valueOf(inverseResult.singleValueFrequency), CompareOperator.EQ);
                        } else {
                            // 检查更新值是否已存在（作为EQ约束）
                            boolean valueExists = this.parameterConstraint.valueToSelectivity.containsKey(updateValueStr) &&
                                                 this.parameterConstraint.getOperatorForValue(updateValueStr) == CompareOperator.EQ;
                            if (!valueExists) {
                                // 添加为 EQ 约束，使用单值频率
                                this.parameterConstraint.addValue(updateValueStr, 
                                    BigDecimal.valueOf(inverseResult.singleValueFrequency), CompareOperator.EQ);
                                if (enableLogging) {
                                    logger.info("📝 GE逆反条件保存: 更新值={}, operator=EQ, 单值频率={}, 类型=UPDATE_MCV", 
                                               updateValueStr, inverseResult.singleValueFrequency);
                                }
                            } else {
                                if (enableLogging) {
                                    logger.info("📝 GE逆反条件: 更新值={} 已存在，保留现有约束", updateValueStr);
                                }
                            }
                        }
                    }
                    
                    // 返回查询参数（用于写入查询）
                    if (enableLogging) {
                        logger.info("🎯 CDF参数选择（逆反条件） - 列 {}: {} -> 选择率 {}, 查询参数 {}, 更新值 {}", 
                                   columnName, operator, targetSelectivity, queryParamStr, updateValueStr);
                    }
                    return inverseResult.queryParameter;  // 返回查询参数
                }
            }
        }
        
        switch (operator) {
            case LT:  // col < val  => selectivity = CDF(val)
                targetCumulativeProb = targetSelectivity;
                if (targetSelectivity.compareTo(BigDecimal.ONE) == 0) {
                    needAdjustmentForStrictInequality = true;
                }
                break;
            case LE:  // col <= val => selectivity = CDF(val)
                targetCumulativeProb = targetSelectivity;
                break;
            case GT:  // col > val  => selectivity = 1 - CDF(val)
                targetCumulativeProb = BigDecimal.ONE.subtract(targetSelectivity);
                if (targetSelectivity.compareTo(BigDecimal.ONE) == 0) {
                    needAdjustmentForStrictInequality = true;
                }
                break;
            case GE:  // col >= val => selectivity = 1 - CDF(val)
                targetCumulativeProb = BigDecimal.ONE.subtract(targetSelectivity);
                break;
            case EQ:   // col = val  => 特殊处理（等值查找）
            case IN:   // col IN (...) => 特殊处理（等值查找）
            case LIKE: // col LIKE 'pattern' => 按照 Mirage 原逻辑，当作等值处理
                ComparableValue eqResult = findValueForEquality(targetSelectivity);
                // 【第2步】判断等值约束是否需要记录
                boolean shouldRecordEq = decideShouldRecordConstraintForEquality(eqResult, targetSelectivity);
                if (shouldRecordEq) {
                    // ★★★ 修复：如果已存在约束，追加值而不是覆盖 ★★★
                    if (this.parameterConstraint == null) {
                    this.parameterConstraint = new ParameterConstraint(eqResult.getValue().toString(), targetSelectivity, operator);
                    } else {
                        this.parameterConstraint.addValue(eqResult.getValue().toString(), targetSelectivity, operator);
                    }
                } else {
                    logger.debug("列 {} 等值约束: 频率匹配，无需调整MCV", columnName);
                }
                return eqResult;
            case NE:      // col != val  => 使用补集（1 - selectivity）
            case NOT_IN:  // col NOT IN (...) => 使用补集
            case NOT_LIKE:// col NOT LIKE 'pattern' => 使用补集
                BigDecimal complementSelectivity = BigDecimal.ONE.subtract(targetSelectivity);
                ComparableValue neResult = findValueForEquality(complementSelectivity);
                // 【第2步】判断非等值约束是否需要记录
                boolean shouldRecordNe = decideShouldRecordConstraintForEquality(neResult, complementSelectivity);
                if (shouldRecordNe) {
                    // ★★★ 修复：如果已存在约束，追加值而不是覆盖 ★★★
                    if (this.parameterConstraint == null) {
                    this.parameterConstraint = new ParameterConstraint(neResult.getValue().toString(), targetSelectivity, operator);
                    } else {
                        this.parameterConstraint.addValue(neResult.getValue().toString(), targetSelectivity, operator);
                    }
                } else {
                    logger.debug("列 {} 非等值约束: 频率匹配，无需调整MCV", columnName);
                }
                return neResult;
            default:
                if (enableLogging) {
                    logger.warn("Unsupported operator {}, using default logic", operator);
                }
                targetCumulativeProb = targetSelectivity;
        }
        
        // Find closest value in CDF
        ComparableValue result = findClosestValue(targetCumulativeProb, operator, needAdjustmentForStrictInequality);
        
        // ★★★ 关键修复：范围约束选择值后，标记为"已使用"，避免后续EQ约束重复选择 ★★★
        String resultValueStr = result.getValue().toString();
        if (operator == CompareOperator.GE || operator == CompareOperator.GT || 
            operator == CompareOperator.LE || operator == CompareOperator.LT) {
            usedValues.add(resultValueStr);
            if (enableLogging) {
                logger.debug("列 {} 范围约束 {} 选择值 '{}'，已标记为已使用", columnName, operator, resultValueStr);
            }
        }
        
        // 【第3步】判断范围约束是否需要记录
        boolean shouldRecordRange = decideShouldRecordConstraintForRange(operator, targetSelectivity);
        if (shouldRecordRange) {
            // ★★★ 优化：对于范围约束，检查是否已有相同操作符的约束，合并相近选择率或选择不同值 ★★★
            
            if (this.parameterConstraint == null) {
                // 第一个约束，直接创建
                this.parameterConstraint = new ParameterConstraint(resultValueStr, targetSelectivity, operator);
            } else {
                // 检查是否已有相同值和操作符的约束
                boolean valueExists = this.parameterConstraint.valueToSelectivity.containsKey(resultValueStr) &&
                                     this.parameterConstraint.getOperatorForValue(resultValueStr) == operator;
                
                if (valueExists) {
                    // 值已存在，检查选择率是否相近（相差<0.0001）
                    BigDecimal existingSelectivity = this.parameterConstraint.valueToSelectivity.get(resultValueStr);
                    BigDecimal diff = targetSelectivity.subtract(existingSelectivity).abs();
                    if (diff.compareTo(new BigDecimal("0.0001")) < 0) {
                        // 选择率相近，合并：使用较大的选择率
                        BigDecimal maxSelectivity = targetSelectivity.compareTo(existingSelectivity) > 0 
                                                   ? targetSelectivity : existingSelectivity;
                        this.parameterConstraint.valueToSelectivity.put(resultValueStr, maxSelectivity);
                        // 重新计算总selectivity
                        this.parameterConstraint.selectivity = this.parameterConstraint.valueToSelectivity.values().stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                        if (enableLogging) {
                            logger.info("🔄 合并相近选择率 - 列 {}: {} -> 值 {} 的选择率从 {} 更新为 {}",
                                       columnName, operator, resultValueStr, existingSelectivity, maxSelectivity);
                        }
                    } else {
                        // 选择率差异较大，需要选择不同的值
                        result = findDifferentValueForRangeConstraint(result, operator, targetSelectivity);
                        String newValueStr = result.getValue().toString();
                        
                        // ★★★ 修复：对于GE操作符，计算保存的频率（目标选择率减去更大值的频率）★★★
                        BigDecimal savedSelectivity = targetSelectivity;
                        if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
                            savedSelectivity = calculateGESavedSelectivity(newValueStr, targetSelectivity, operator);
                        }
                        
                        this.parameterConstraint.addValue(newValueStr, savedSelectivity, operator);
                        if (enableLogging) {
                            logger.info("🆕 选择不同值 - 列 {}: {} -> 值 {} 使用保存的选择率 {} (目标选择率: {})",
                                      columnName, operator, newValueStr, savedSelectivity, targetSelectivity);
                        }
                    }
                } else {
                    // 值不存在，直接添加
                    // ★★★ 修复：对于GE操作符，计算保存的频率（目标选择率减去更大值的频率）★★★
                    BigDecimal savedSelectivity = targetSelectivity;
                    if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
                        savedSelectivity = calculateGESavedSelectivity(resultValueStr, targetSelectivity, operator);
                    }
                    this.parameterConstraint.addValue(resultValueStr, savedSelectivity, operator);
                }
            }
        } else {
            logger.debug("列 {} 范围约束 {}: 无需调整MCV", columnName, operator);
        }

        // Log only during parameter instantiation, skip during data generation to avoid log bloat
        if (enableLogging) {
            logger.info("🎯 CDF参数选择 - 列 {}: {} -> 选择率 {}, 选中值 {}",
                        columnName, operator, targetSelectivity, result);
        }

        return result;
    }
    
    /**
     * 判断等值约束是否需要记录并调整MCV频率
     * 
     * 逻辑：
     * - 有MCV：如果找到的值频率 == 目标选择率 → 不需要；否则需要UPDATE_MCV
     * - 无MCV：需要ADD_MCV
     */
    private boolean decideShouldRecordConstraintForEquality(ComparableValue foundValue, BigDecimal targetSelectivity) {
        if (statistics == null || statistics.getMostCommonValues() == null) {
            // 没有MCV → 需要ADD_MCV
            logger.debug("列 {} 无MCV，需要ADD_MCV", columnName);
            return true;
        }
        
        List<String> mcvs = statistics.getMostCommonValues();
        List<Double> freqs = statistics.getMostCommonFrequencies();
        
        if (mcvs.isEmpty()) {
            // MCV为空 → 需要ADD_MCV
            logger.debug("列 {} MCV为空，需要ADD_MCV", columnName);
            return true;
        }
        
        // 检查找到的值是否是MCV
        String foundValueStr = foundValue.getValue().toString();
        for (int i = 0; i < mcvs.size(); i++) {
            if (mcvs.get(i).equals(foundValueStr)) {
                double freq = freqs.get(i);
                // 频率与目标选择率是否相等（允许小精度误差）
                if (Math.abs(freq - targetSelectivity.doubleValue()) < 0.0001) {
                    // 频率相等，无需调整
                    logger.debug("列 {} 值 '{}' 频率 {} == 目标选择率 {}，无需调整", 
                               columnName, foundValueStr, freq, targetSelectivity);
                    return false;
                } else {
                    // 频率不等，需要UPDATE_MCV
                    logger.debug("列 {} 值 '{}' 频率 {} ≠ 目标选择率 {}，需要UPDATE_MCV", 
                               columnName, foundValueStr, freq, targetSelectivity);
                    return true;
                }
            }
        }
        
        // 找到的值不是MCV（来自Histogram区域），不需要调整
        logger.debug("列 {} 找到的值来自Histogram区域（非MCV），无需调整", columnName);
        return false;
    }
    
    /**
     * 判断范围约束是否需要记录并调整MCV频率
     * 
     * 逻辑：
     * - 有Histogram：直方图覆盖范围，不需要调整 → false
     * - 无Histogram但有MCV：检查是否冲突 → 冲突则true，不冲突则false
     * - 无Histogram且无MCV：异常情况，记录WARN → false
     */
    private boolean decideShouldRecordConstraintForRange(CompareOperator operator, BigDecimal targetSelectivity) {
        if (!isRangeOperator(operator)) {
            return false;
        }
        
        boolean hasMCV = statistics != null && statistics.getMostCommonValues() != null && 
                        !statistics.getMostCommonValues().isEmpty();
        boolean hasHistogram = statistics != null && statistics.getHistogramBounds() != null && 
                              !statistics.getHistogramBounds().isEmpty();
        
        // 情况1：有Histogram → 不需要调整
        if (hasHistogram) {
            logger.debug("列 {} 范围约束: 有Histogram，分布均匀，无需调整MCV", columnName);
            return false;
        }
        
        // 情况2：无Histogram，有MCV → 检查是否冲突
        if (hasMCV) {
            boolean conflict = detectRangeConflict(operator, targetSelectivity);
            if (conflict) {
                logger.debug("列 {} 范围约束 {}: MCV分布与范围约束冲突，需要UPDATE_MCV", columnName, operator);
                return true;
            } else {
                logger.debug("列 {} 范围约束 {}: MCV分布不冲突，无需调整", columnName, operator);
                return false;
            }
        }
        
        // 情况3：无Histogram且无MCV → 异常情况
        logger.warn("列 {} 范围约束 {} 但无Histogram和MCV，这种情况不应该出现！", columnName, operator);
        return false;
    }
    
    /**
     * 检测范围约束是否与MCV分布冲突
     */
    private boolean detectRangeConflict(CompareOperator operator, BigDecimal targetSelectivity) {
        // 简化判断：如果所有MCV的频率总和能恰好匹配目标selectivity → 不冲突
        // 否则 → 冲突
        
        double mcvTotalProb = statistics.getMostCommonFrequencies().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        
        double targetSel = targetSelectivity.doubleValue();
        
        // 对于范围约束，cumulative probability应该能恰好表示selectivity
        // 如果MCV总概率无法恰好匹配，则存在冲突
        if (Math.abs(mcvTotalProb - targetSel) < 0.0001) {
            return false;  // 不冲突
        }
        
        // 检查目标selectivity是否落在MCV范围内
        if (targetSel <= mcvTotalProb) {
            // 目标在MCV范围内但无法恰好匹配 → 冲突
            return true;
        }
        
        // 目标超过MCV范围 → 不冲突（可从Histogram部分找到）
        return false;
    }
    
    private boolean isRangeOperator(CompareOperator op) {
        return op == CompareOperator.LE || op == CompareOperator.LT ||
               op == CompareOperator.GE || op == CompareOperator.GT;
    }
    
    /**
     * 逆反条件处理结果：包含查询参数和更新值
     */
    private static class InverseConditionResult {
        final ComparableValue queryParameter;  // 查询参数（例如 1993，用于写入查询）
        final ComparableValue updateValue;    // 更新值（例如 1992，用于更新MCV频率）
        final double singleValueFrequency;    // 单值频率（P(X = updateValue)）
        
        InverseConditionResult(ComparableValue queryParameter, ComparableValue updateValue, double singleValueFrequency) {
            this.queryParameter = queryParameter;
            this.updateValue = updateValue;
            this.singleValueFrequency = singleValueFrequency;
        }
    }
    
    /**
     * ★★★ 特殊处理：GE操作符且targetSelectivity > 0.8时，使用逆反条件 ★★★
     * 
     * 逻辑：
     * 1. 逆反条件：X < p，概率 = 1 - targetSelectivity
     * 2. 找到使 P(X < p) 最接近 (1 - targetSelectivity) 的 p（查询参数）
     * 3. 返回 p - 1（更新值），用于更新MCV频率
     * 
     * @param targetSelectivity 目标选择率（例如 0.8575899843）
     * @param targetCumulativeProb 目标累积概率
     * @return InverseConditionResult，包含查询参数和更新值，如果不符合条件返回null
     */
    private InverseConditionResult handleGEInverseCondition(BigDecimal targetSelectivity, 
                                                             BigDecimal targetCumulativeProb) {
        // ★★★ 严格条件：只处理 GE 操作符，且 targetSelectivity > 0.8 ★★★
        BigDecimal targetSel = BigDecimal.ONE.subtract(targetCumulativeProb);
        if (targetSel.compareTo(new BigDecimal("0.8")) <= 0) {
            return null;  // 不符合条件，返回null
        }
        
        // 计算逆反目标：P(X < p) = 1 - targetSelectivity
        BigDecimal inverseTargetSel = BigDecimal.ONE.subtract(targetSel);
        
        ComparableValue queryParam = null;  // 查询参数 p
        BigDecimal minDiff = null;
        
        // 如果有MCV信息，按值排序后计算 P(X < value)
        if (statistics != null && statistics.getMostCommonValues() != null &&
            statistics.getMostCommonFrequencies() != null && !statistics.getMostCommonValues().isEmpty()) {
            List<String> mcvs = statistics.getMostCommonValues();
            List<Double> freqs = statistics.getMostCommonFrequencies();
            
            // 创建 (value, freq) 对并按值排序
            List<Map.Entry<String, Double>> valueFreqPairs = new ArrayList<>();
            for (int i = 0; i < mcvs.size() && i < freqs.size(); i++) {
                valueFreqPairs.add(new java.util.AbstractMap.SimpleEntry<>(mcvs.get(i), freqs.get(i)));
            }
            valueFreqPairs.sort((a, b) -> {
                try {
                    double valA = Double.parseDouble(a.getKey());
                    double valB = Double.parseDouble(b.getKey());
                    return Double.compare(valA, valB);
                } catch (NumberFormatException e) {
                    return a.getKey().compareTo(b.getKey());
                }
            });
            
            // 检查已使用的值
            Set<String> usedValuesForGE = new HashSet<>();
            if (this.parameterConstraint != null) {
                for (Map.Entry<String, CompareOperator> entry : this.parameterConstraint.valueToOperator.entrySet()) {
                    if (entry.getValue() == CompareOperator.GE) {
                        usedValuesForGE.add(entry.getKey());
                    }
                }
            }
            usedValuesForGE.addAll(usedValues);
            
            // 找到使 P(X < value) 最接近 inverseTargetSel 的值
            for (int i = 0; i < valueFreqPairs.size(); i++) {
                String valueStr = valueFreqPairs.get(i).getKey();
                
                // 跳过已使用的值
                if (usedValuesForGE.contains(valueStr)) {
                    continue;
                }
                
                // 计算 P(X < value) = sum of freqs for values < value
                double pLt = 0.0;
                for (int j = 0; j < i; j++) {
                    pLt += valueFreqPairs.get(j).getValue();
                }
                BigDecimal pLtValue = BigDecimal.valueOf(pLt);
                BigDecimal diff = pLtValue.subtract(inverseTargetSel).abs();
                
                if (minDiff == null || diff.compareTo(minDiff) < 0) {
                    minDiff = diff;
                    try {
                        queryParam = parseValue(valueStr, dataType);
                    } catch (Exception e) {
                        logger.warn("无法解析值 {} 为 ComparableValue", valueStr, e);
                    }
                }
            }
        } else {
            // 没有MCV信息，使用CDF近似
            Set<String> usedValuesForGE = new HashSet<>();
            if (this.parameterConstraint != null) {
                for (Map.Entry<String, CompareOperator> entry : this.parameterConstraint.valueToOperator.entrySet()) {
                    if (entry.getValue() == CompareOperator.GE) {
                        usedValuesForGE.add(entry.getKey());
                    }
                }
            }
            usedValuesForGE.addAll(usedValues);
            
            for (Map.Entry<ComparableValue, BigDecimal> entry : valueToCumulativeProbability.entrySet()) {
                ComparableValue value = entry.getKey();
                String valueStr = value.getValue().toString();
                
                if (usedValuesForGE.contains(valueStr)) {
                    continue;
                }
                
                // P(X < value) = CDF(value)
                BigDecimal cdfValue = entry.getValue();
                BigDecimal diff = cdfValue.subtract(inverseTargetSel).abs();
                
                if (minDiff == null || diff.compareTo(minDiff) < 0) {
                    minDiff = diff;
                    queryParam = value;
                }
            }
        }
        
        if (queryParam == null) {
            return null;  // 未找到合适的值
        }
        
        // 计算更新值：queryParam - 1（对于整数类型）
        ComparableValue updateValue = null;
        double singleValueFreq = 0.0;
        
        try {
            String queryParamStr = queryParam.getValue().toString();
            if (dataType != null && (dataType.contains("int") || dataType.contains("numeric") || dataType.contains("decimal"))) {
                // 数值类型：减1
                double numValue = Double.parseDouble(queryParamStr);
                numValue -= 1.0;
                if (dataType.contains("int")) {
                    updateValue = parseValue(String.valueOf((int) numValue), dataType);
                } else {
                    updateValue = parseValue(String.valueOf(numValue), dataType);
                }
            } else {
                // 非数值类型：尝试找到前一个值
                if (statistics != null && statistics.getMostCommonValues() != null) {
                    List<String> mcvs = statistics.getMostCommonValues();
                    List<Double> freqs = statistics.getMostCommonFrequencies();
                    
                    List<Map.Entry<String, Double>> valueFreqPairs = new ArrayList<>();
                    for (int i = 0; i < mcvs.size() && i < freqs.size(); i++) {
                        valueFreqPairs.add(new java.util.AbstractMap.SimpleEntry<>(mcvs.get(i), freqs.get(i)));
                    }
                    valueFreqPairs.sort((a, b) -> {
                        try {
                            double valA = Double.parseDouble(a.getKey());
                            double valB = Double.parseDouble(b.getKey());
                            return Double.compare(valA, valB);
                        } catch (NumberFormatException e) {
                            return a.getKey().compareTo(b.getKey());
                        }
                    });
                    
                    int foundIdx = -1;
                    for (int i = 0; i < valueFreqPairs.size(); i++) {
                        if (valueFreqPairs.get(i).getKey().equals(queryParamStr)) {
                            foundIdx = i;
                            break;
                        }
                    }
                    if (foundIdx > 0) {
                        String prevValueStr = valueFreqPairs.get(foundIdx - 1).getKey();
                        updateValue = parseValue(prevValueStr, dataType);
                        singleValueFreq = valueFreqPairs.get(foundIdx - 1).getValue();
                    }
                }
            }
            
            // 如果 updateValue 已确定，尝试从MCV中获取单值频率
            if (updateValue != null && statistics != null && statistics.getMostCommonValues() != null) {
                List<String> mcvs = statistics.getMostCommonValues();
                List<Double> freqs = statistics.getMostCommonFrequencies();
                String updateValueStr = updateValue.getValue().toString();
                
                for (int i = 0; i < mcvs.size() && i < freqs.size(); i++) {
                    if (mcvs.get(i).equals(updateValueStr)) {
                        singleValueFreq = freqs.get(i);
                        break;
                    }
                }
            }
            
            if (updateValue == null) {
                logger.warn("GE逆反条件：无法计算更新值，queryParam={}", queryParamStr);
                return null;
            }
            
            logger.info("🔄 GE逆反条件处理: targetSelectivity={}, 逆反目标={}, 查询参数={}, 更新值={}, 单值频率={}", 
                       targetSel, inverseTargetSel, queryParam.getValue(), updateValue.getValue(), singleValueFreq);
            
            return new InverseConditionResult(queryParam, updateValue, singleValueFreq);
            
        } catch (Exception e) {
            logger.warn("GE逆反条件：处理失败，queryParam={}", queryParam.getValue(), e);
            return null;
        }
    }
    
    /**
     * 在 CDF 中查找累积概率最接近 target 的值
     * @param targetCumulativeProb 目标累积概率
     * @param operator 操作符（用于判断是否需要调整值）
     * @param needAdjustmentForStrictInequality 对于严格不等号（GT/LT）且选择率=1.0时，需要调整值
     */
    private ComparableValue findClosestValue(BigDecimal targetCumulativeProb, 
                                             CompareOperator operator,
                                             boolean needAdjustmentForStrictInequality) {
        // 对于严格不等号且选择率=1.0的特殊情况，需要选择边界外的值
        if (needAdjustmentForStrictInequality) {
            if (operator == CompareOperator.GT) {
                // GT 且选择率=1.0：需要选择比最小值更小的值
                ComparableValue minValue = valueToCumulativeProbability.firstKey();
                return adjustValueForStrictInequality(minValue, true);
            } else if (operator == CompareOperator.LT) {
                // LT 且选择率=1.0：需要选择比最大值更大的值
                ComparableValue maxValue = valueToCumulativeProbability.lastKey();
                return adjustValueForStrictInequality(maxValue, false);
            }
        }
        
        // ★ 特殊处理 GE 操作符：需要找到使 P(X >= value) 最接近 targetSelectivity 的值 ★
        // 对于 GE (>=)，我们需要找到使得 P(X >= value) = targetSelectivity 的 value
        // ★★★ 注意：targetSelectivity > 0.8 的情况已在 findValueBySelectivity 中使用逆反条件处理 ★★★
        // 这里只处理 targetSelectivity <= 0.8 的正常情况
        if (operator == CompareOperator.GE) {
            ComparableValue bestResult = null;
            BigDecimal minDiff = null;
            BigDecimal targetSel = BigDecimal.ONE.subtract(targetCumulativeProb); // 恢复目标选择率
            
            // 如果有MCV信息，按值排序后计算 P(X >= value)
            if (statistics != null && statistics.getMostCommonValues() != null &&
                statistics.getMostCommonFrequencies() != null && !statistics.getMostCommonValues().isEmpty()) {
                List<String> mcvs = statistics.getMostCommonValues();
                List<Double> freqs = statistics.getMostCommonFrequencies();
                
                // 创建 (value, freq) 对并按值排序
                List<Map.Entry<String, Double>> valueFreqPairs = new ArrayList<>();
                for (int i = 0; i < mcvs.size() && i < freqs.size(); i++) {
                    valueFreqPairs.add(new java.util.AbstractMap.SimpleEntry<>(mcvs.get(i), freqs.get(i)));
                }
                // 按值排序（假设值是数值或可比较的字符串）
                valueFreqPairs.sort((a, b) -> {
                    try {
                        // 尝试数值比较
                        double valA = Double.parseDouble(a.getKey());
                        double valB = Double.parseDouble(b.getKey());
                        return Double.compare(valA, valB);
                    } catch (NumberFormatException e) {
                        // 回退到字符串比较
                        return a.getKey().compareTo(b.getKey());
                    }
                });
                
                // 计算每个值的 P(X >= value) 并选择最接近的（排除已使用的值）
                // ★★★ 修复：检查值是否已被使用，如果已被使用则跳过，继续查找下一个 ★★★
                Set<String> usedValuesForGE = new HashSet<>();
                if (this.parameterConstraint != null) {
                    // 获取所有已使用的相同操作符的值
                    for (Map.Entry<String, CompareOperator> entry : this.parameterConstraint.valueToOperator.entrySet()) {
                        if (entry.getValue() == operator) {
                            usedValuesForGE.add(entry.getKey());
                        }
                    }
                }
                // 也检查 usedValues 集合
                usedValuesForGE.addAll(usedValues);
                
                for (int i = 0; i < valueFreqPairs.size(); i++) {
                    String valueStr = valueFreqPairs.get(i).getKey();
                    
                    // ★★★ 修复：如果值已被使用，跳过，继续查找下一个 ★★★
                    if (usedValuesForGE.contains(valueStr)) {
                        continue;
                    }
                    
                    // 正常条件：计算 P(X >= value) = sum of freqs for values >= value
                    double pGe = 0.0;
                    for (int j = i; j < valueFreqPairs.size(); j++) {
                        pGe += valueFreqPairs.get(j).getValue();
                    }
                    BigDecimal probValue = BigDecimal.valueOf(pGe);
                    BigDecimal diff = probValue.subtract(targetSel).abs();
                    
                    if (minDiff == null || diff.compareTo(minDiff) < 0) {
                        minDiff = diff;
                        try {
                            bestResult = parseValue(valueStr, dataType);
                        } catch (Exception e) {
                            logger.warn("无法解析值 {} 为 ComparableValue", valueStr, e);
                        }
                    }
                }
            } else {
                // 没有MCV信息，使用CDF近似：遍历所有值，计算 P(X >= value) ≈ 1 - CDF(value)
                // ★★★ 修复：检查值是否已被使用，如果已被使用则跳过 ★★★
                Set<String> usedValuesForGE = new HashSet<>();
                if (this.parameterConstraint != null) {
                    for (Map.Entry<String, CompareOperator> entry : this.parameterConstraint.valueToOperator.entrySet()) {
                        if (entry.getValue() == operator) {
                            usedValuesForGE.add(entry.getKey());
                        }
                    }
                }
                usedValuesForGE.addAll(usedValues);
                
                for (Map.Entry<ComparableValue, BigDecimal> entry : valueToCumulativeProbability.entrySet()) {
                    ComparableValue value = entry.getKey();
                    String valueStr = value.getValue().toString();
                    
                    // ★★★ 修复：如果值已被使用，跳过，继续查找下一个 ★★★
                    if (usedValuesForGE.contains(valueStr)) {
                        continue;
                    }
                    
                    BigDecimal cdfValue = entry.getValue();
                    // 正常条件：P(X >= value) = 1 - CDF(value)
                    BigDecimal probValue = BigDecimal.ONE.subtract(cdfValue);
                    BigDecimal diff = probValue.subtract(targetSel).abs();
                    if (minDiff == null || diff.compareTo(minDiff) < 0) {
                        minDiff = diff;
                        bestResult = value;
                    }
                }
            }
            
            if (bestResult == null) {
                // ★★★ 修复：如果所有值都被使用，对于GE操作符且targetSelectivity很小，返回比最大值更大的值 ★★★
                // 对于GE操作符，如果targetSelectivity很小（接近0），说明需要选择比最大值更大的值
                if (operator == CompareOperator.GE && targetSel.compareTo(new BigDecimal("0.1")) < 0) {
                    // 选择比最大值更大的值（例如，如果最大值是10，选择11）
                    ComparableValue maxValue = valueToCumulativeProbability.lastKey();
                    bestResult = adjustValueForStrictInequality(maxValue, false);  // 增大
                    logger.info("GE操作符：所有值都被使用，targetSelectivity={}很小，返回比最大值 {} 更大的值 {}", 
                               targetSel, maxValue, bestResult);
                } else {
                    // 其他情况，返回最小值
                    bestResult = valueToCumulativeProbability.firstKey();
                }
            }
            
            // 计算最终选中值的 P(X >= value) 用于日志
            BigDecimal finalPGe = BigDecimal.ONE;
            if (statistics != null && statistics.getMostCommonValues() != null) {
                List<String> mcvs = statistics.getMostCommonValues();
                List<Double> freqs = statistics.getMostCommonFrequencies();
                String valueStr = bestResult.getValue().toString();
                
                // 按值排序后计算
                List<Map.Entry<String, Double>> valueFreqPairs = new ArrayList<>();
                for (int i = 0; i < mcvs.size() && i < freqs.size(); i++) {
                    valueFreqPairs.add(new java.util.AbstractMap.SimpleEntry<>(mcvs.get(i), freqs.get(i)));
                }
                valueFreqPairs.sort((a, b) -> {
                    try {
                        double valA = Double.parseDouble(a.getKey());
                        double valB = Double.parseDouble(b.getKey());
                        return Double.compare(valA, valB);
                    } catch (NumberFormatException e) {
                        return a.getKey().compareTo(b.getKey());
                    }
                });
                
                int idx = -1;
                for (int i = 0; i < valueFreqPairs.size(); i++) {
                    if (valueFreqPairs.get(i).getKey().equals(valueStr)) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) {
                    double pGe = 0.0;
                    for (int i = idx; i < valueFreqPairs.size(); i++) {
                        pGe += valueFreqPairs.get(i).getValue();
                    }
                    finalPGe = BigDecimal.valueOf(pGe);
                }
            }
            
            logger.info("GE操作符查找: targetSelectivity={}, targetCumulativeProb={}, 选中值={}, P(>={})={}, 差值={}", 
                       targetSel, targetCumulativeProb, bestResult, bestResult, finalPGe, minDiff);
            return bestResult;
        }
        
        logger.info("findClosestValue: targetCumulativeProb={}, operator={}", targetCumulativeProb, operator);
        Map.Entry<ComparableValue, BigDecimal> floorEntry = null;
        Map.Entry<ComparableValue, BigDecimal> ceilingEntry = null;
        
        for (Map.Entry<ComparableValue, BigDecimal> entry : valueToCumulativeProbability.entrySet()) {
            if (entry.getValue().compareTo(targetCumulativeProb) <= 0) {
                floorEntry = entry;
            }
            if (entry.getValue().compareTo(targetCumulativeProb) >= 0 && ceilingEntry == null) {
                ceilingEntry = entry;
                break;
            }
        }
        
        // 选择更接近的值
        if (floorEntry == null) {
            return ceilingEntry != null ? ceilingEntry.getKey() : valueToCumulativeProbability.firstKey();
        }
        if (ceilingEntry == null) {
            return floorEntry.getKey();
        }
        
        BigDecimal distToFloor = targetCumulativeProb.subtract(floorEntry.getValue()).abs();
        BigDecimal distToCeil = ceilingEntry.getValue().subtract(targetCumulativeProb).abs();
        
        return distToFloor.compareTo(distToCeil) <= 0 ? floorEntry.getKey() : ceilingEntry.getKey();
    }
    
    /**
     * 为严格不等号调整值：生成一个比给定值更小（或更大）的值
     * @param value 原始值
     * @param makeSmaller true表示生成更小的值（用于GT），false表示生成更大的值（用于LT）
     * @return 调整后的值
     */
    private ComparableValue adjustValueForStrictInequality(ComparableValue value, boolean makeSmaller) {
        Object originalValue = value.getValue();
        String type = value.getDataType();
        
        try {
            switch (type.toLowerCase()) {
                case "int4":
                case "int8":
                case "integer":
                case "bigint":
                case "smallint": {
                    long longValue = ((Number) originalValue).longValue();
                    long adjustedValue = makeSmaller ? longValue - 1 : longValue + 1;
                    return new ComparableValue(adjustedValue, type);
                }
                case "numeric":
                case "decimal": {
                    BigDecimal bigDecimalValue = (BigDecimal) originalValue;
                    BigDecimal adjustment = makeSmaller ? 
                        bigDecimalValue.subtract(new BigDecimal("0.01")) : 
                        bigDecimalValue.add(new BigDecimal("0.01"));
                    return new ComparableValue(adjustment, type);
                }
                case "float":
                case "float4":
                case "float8":
                case "double":
                case "real": {
                    double doubleValue = ((Number) originalValue).doubleValue();
                    double adjustedValue = makeSmaller ? 
                        doubleValue - 0.01 : 
                        doubleValue + 0.01;
                    return new ComparableValue(adjustedValue, type);
                }
                case "date": {
                    Date dateValue = (Date) originalValue;
                    long timeInMillis = dateValue.getTime();
                    long oneDayInMillis = 24L * 60 * 60 * 1000;
                    long adjustedTime = makeSmaller ? 
                        timeInMillis - oneDayInMillis : 
                        timeInMillis + oneDayInMillis;
                    return new ComparableValue(new Date(adjustedTime), type);
                }
                case "timestamp":
                case "timestamptz": {
                    Timestamp timestampValue = (Timestamp) originalValue;
                    long timeInMillis = timestampValue.getTime();
                    long oneSecondInMillis = 1000L;
                    long adjustedTime = makeSmaller ? 
                        timeInMillis - oneSecondInMillis : 
                        timeInMillis + oneSecondInMillis;
                    return new ComparableValue(new Timestamp(adjustedTime), type);
                }
                default:
                    // 对于字符串类型，无法简单调整，返回原值并记录警告
                    logger.warn("无法为严格不等号调整字符串类型值，列: {}, 值: {}, 操作: {}", 
                               columnName, originalValue, makeSmaller ? "减小" : "增大");
                    return value;
            }
        } catch (Exception e) {
            logger.warn("调整值失败，列: {}, 值: {}, 类型: {}, 错误: {}", 
                       columnName, originalValue, type, e.getMessage());
            return value;
        }
    }
    
    /**
     * 为范围约束找到不同的值（避免重复选择同一个值）
     * 对于GE操作符，选择更小的值（使得选择率更大）
     * 对于LE操作符，选择更大的值（使得选择率更大）
     */
    private ComparableValue findDifferentValueForRangeConstraint(ComparableValue originalResult,
                                                                CompareOperator operator,
                                                                BigDecimal targetSelectivity) {
        if (this.parameterConstraint == null || valueToCumulativeProbability.isEmpty()) {
            return originalResult;
        }
        
        String originalValueStr = originalResult.getValue().toString();
        
        // 获取已存在的相同操作符的值
        Set<String> existingValues = new HashSet<>();
        for (Map.Entry<String, CompareOperator> entry : this.parameterConstraint.valueToOperator.entrySet()) {
            if (entry.getValue() == operator) {
                existingValues.add(entry.getKey());
            }
        }
        
        // 根据操作符类型选择不同的值
        if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
            // ★★★ 修复：根据是否只有MCV和选择率大小决定查找方向 ★★★
            boolean hasMCV = statistics != null && statistics.getMostCommonValues() != null &&
                            !statistics.getMostCommonValues().isEmpty();
            boolean hasHistogram = statistics != null && statistics.getHistogramBounds() != null &&
                                  !statistics.getHistogramBounds().isEmpty();
            boolean onlyMCV = hasMCV && !hasHistogram;
            boolean isSmallSelectivity = targetSelectivity.compareTo(new BigDecimal("0.5")) < 0;
            
            if (onlyMCV && isSmallSelectivity) {
                // 只有MCV且选择率小：从后面（大值）开始查找
                ComparableValue currentValue = originalResult;
                for (int i = 0; i < 20; i++) { // 最多尝试20次
                    // 尝试找到比当前值更大的值
                    ComparableValue largerValue = findLargerValue(currentValue);
                    if (largerValue == null) {
                        break; // 无法找到更大的值
                    }
                    String largerValueStr = largerValue.getValue().toString();
                    if (!existingValues.contains(largerValueStr)) {
                        // 验证这个值的选择率是否合理
                        BigDecimal cdf = valueToCumulativeProbability.getOrDefault(largerValue, BigDecimal.ZERO);
                        BigDecimal selectivity = BigDecimal.ONE.subtract(cdf);
                        // 对于GE，选择率应该 <= targetSelectivity（因为值更大）
                        if (selectivity.compareTo(targetSelectivity) <= 0) {
                            logger.debug("为GE操作符选择不同值（只有MCV+选择率小）: 从 {} 改为 {} (选择率 {} <= {})",
                                       originalValueStr, largerValueStr, selectivity, targetSelectivity);
                            return largerValue;
                        }
                    }
                    currentValue = largerValue;
                }
            } else {
                // 其他情况：从前面（小值）开始查找
                ComparableValue currentValue = originalResult;
                for (int i = 0; i < 20; i++) { // 最多尝试20次
                    // 尝试找到比当前值更小的值
                    ComparableValue smallerValue = findSmallerValue(currentValue);
                    if (smallerValue == null) {
                        break; // 无法找到更小的值
                    }
                    String smallerValueStr = smallerValue.getValue().toString();
                    if (!existingValues.contains(smallerValueStr)) {
                        // 验证这个值的选择率是否合理
                        BigDecimal cdf = valueToCumulativeProbability.getOrDefault(smallerValue, BigDecimal.ZERO);
                        BigDecimal selectivity = BigDecimal.ONE.subtract(cdf);
                        // 对于GE，选择率应该 >= targetSelectivity（因为值更小）
                        if (selectivity.compareTo(targetSelectivity) >= 0) {
                            logger.debug("为GE操作符选择不同值: 从 {} 改为 {} (选择率 {} >= {})",
                                       originalValueStr, smallerValueStr, selectivity, targetSelectivity);
                            return smallerValue;
                        }
                    }
                    currentValue = smallerValue;
                }
            }
        } else if (operator == CompareOperator.LE || operator == CompareOperator.LT) {
            // LE/LT: 选择更大的值（使得选择率更大）
            // 从原始值开始，向上查找（更大的值）
            ComparableValue currentValue = originalResult;
            for (int i = 0; i < 20; i++) { // 最多尝试20次
                // 尝试找到比当前值更大的值
                ComparableValue largerValue = findLargerValue(currentValue);
                if (largerValue == null) {
                    break; // 无法找到更大的值
                }
                String largerValueStr = largerValue.getValue().toString();
                if (!existingValues.contains(largerValueStr)) {
                    // 验证这个值的选择率是否合理
                    BigDecimal cdf = valueToCumulativeProbability.getOrDefault(largerValue, BigDecimal.ZERO);
                    BigDecimal selectivity = cdf; // 对于LE，选择率 = CDF
                    // 对于LE，选择率应该 >= targetSelectivity（因为值更大）
                    if (selectivity.compareTo(targetSelectivity) >= 0) {
                        logger.debug("为LE操作符选择不同值: 从 {} 改为 {} (选择率 {} >= {})",
                                   originalValueStr, largerValueStr, selectivity, targetSelectivity);
                        return largerValue;
                    }
                }
                currentValue = largerValue;
            }
        }
        
        // 如果无法找到不同的值，返回原值
        logger.debug("无法为范围约束找到不同的值，使用原值: {}", originalValueStr);
        return originalResult;
    }
    
    /**
     * 找到比给定值更小的值（在CDF中）
     */
    private ComparableValue findSmallerValue(ComparableValue value) {
        if (valueToCumulativeProbability.isEmpty()) {
            return null;
        }
        
        // 找到比当前值更小的最大键
        SortedMap<ComparableValue, BigDecimal> headMap = valueToCumulativeProbability.headMap(value);
        if (headMap.isEmpty()) {
            return null;
        }
        return headMap.lastKey();
    }
    
    /**
     * 找到比给定值更大的值（在CDF中）
     */
    private ComparableValue findLargerValue(ComparableValue value) {
        if (valueToCumulativeProbability.isEmpty()) {
            return null;
        }
        
        // 找到比当前值更大的最小键
        SortedMap<ComparableValue, BigDecimal> tailMap = valueToCumulativeProbability.tailMap(value, false);
        if (tailMap.isEmpty()) {
            return null;
        }
        return tailMap.firstKey();
    }
    
    private static final double MCV_MATCH_THRESHOLD = 0.00001;
    /**
     * 处理等值谓词的参数实例化
     * 对于 = 和 IN 操作符，选择频率最接近 targetSelectivity 的值
     */
    private ComparableValue findValueForEquality(BigDecimal targetSelectivity) {
        if (valueToCumulativeProbability.isEmpty()) {
            throw new IllegalStateException("CDF is empty for column: " + columnName);
        }

        double targetSel = targetSelectivity.doubleValue();

        // 特别关注o_orderstatus列的调试信息
        boolean isOrderStatus = "o_orderstatus".equalsIgnoreCase(columnName) ||
                               columnName.toLowerCase().contains("orderstatus");

        if (isOrderStatus) {
            logger.info("🔍 DEBUG: o_orderstatus参数实例化 - 目标选择率: {}, CDF大小: {}",
                       targetSelectivity, valueToCumulativeProbability.size());
        }

        // 【策略1】如果有MCV信息且targetSelectivity合理，优先选择最接近的MCV
        if (statistics != null && statistics.getMostCommonValues() != null &&
            statistics.getMostCommonFrequencies() != null && !statistics.getMostCommonValues().isEmpty()) {

            List<String> mcvs = statistics.getMostCommonValues();
            List<Double> freqs = statistics.getMostCommonFrequencies();

            if (isOrderStatus) {
                logger.info("🔍 DEBUG: o_orderstatus MCV信息 - 候选值: {}, 频率: {}",
                           mcvs, freqs);
            }

            // ★★★ 修复1：查找最接近的未使用过的MCV ★★★
            int bestIndex = -1;
            double minDiff = Double.MAX_VALUE;

            for (int i = 0; i < freqs.size(); i++) {
                String mcvValue = mcvs.get(i);
                
                // 跳过已使用过的值
                if (usedValues.contains(mcvValue)) {
                    logger.debug("Column {}: MCV值 '{}' 已被使用，跳过", columnName, mcvValue);
                    continue;
                }
                
                double freq = freqs.get(i);
                double diff = Math.abs(freq - targetSel);
                if (diff < minDiff) {
                    minDiff = diff;
                    bestIndex = i;
                }
            }

            if (isOrderStatus) {
                if (bestIndex >= 0) {
                logger.info("🔍 DEBUG: o_orderstatus MCV选择 - 最佳候选: {} (freq={}, diff={})",
                           mcvs.get(bestIndex), freqs.get(bestIndex), minDiff);
                } else {
                    logger.info("🔍 DEBUG: o_orderstatus 所有MCV都已被使用");
                }
            }

            // ★★★ 修复2：即使diff较大，也优先返回MCV而不是CDF值 ★★★
            if (bestIndex >= 0) {
                String mcvValue = mcvs.get(bestIndex);
                ComparableValue result = parseValue(mcvValue, dataType);
                
                // 标记该值已被使用
                usedValues.add(mcvValue);
                
                logger.info("Column {}: equality selectivity={}, using MCV value={} (freq={}, diff={})",
                           columnName, targetSelectivity, result, freqs.get(bestIndex), minDiff);
                if (isOrderStatus) {
                    logger.info("✅ FINAL: o_orderstatus选择MCV值: {}", result.getValue());
                }
                return result;
            }

            // 所有MCV都已被使用，记录警告
            logger.warn("Column {}: equality selectivity={}, all MCV values are already used, fallback to CDF",
                       columnName, targetSelectivity);
        }

        // 【策略2】基于CDF位置选择值
        // 对于等值查询，选择率含义需要特殊处理：
        // - 如果选择率很小（<0.01），应该选择一个出现频率较低的值
        // - 如果选择率中等，应该选择中等频率的值
        // - 我们使用更直接的方法：在CDF中找到累积概率对应的值

        // 对于等值查询，我们直接使用目标选择率作为累积概率位置
        // 但需要考虑MCV的影响：如果没有找到合适的MCV，我们在非MCV区域选择
        BigDecimal adjustedTarget = targetSelectivity;

        // 如果之前有MCV，我们需要避免选择MCV区域
        if (statistics != null && statistics.getMostCommonValues() != null) {
            // 计算MCV占据的总概率
            double mcvTotalProb = statistics.getMostCommonFrequencies().stream()
                .mapToDouble(Double::doubleValue)
                .sum();

            // 如果目标选择率在MCV区域之后，调整位置
            if (targetSel > mcvTotalProb) {
                // 在非MCV区域重新定位
                double remainingProb = 1.0 - mcvTotalProb;
                double adjustedSel = (targetSel - mcvTotalProb) / remainingProb;
                adjustedTarget = BigDecimal.valueOf(Math.max(0.001, Math.min(0.999, adjustedSel)));
            }
        }

        // 在CDF中找到对应的值
        for (Map.Entry<ComparableValue, BigDecimal> entry : valueToCumulativeProbability.entrySet()) {
            if (entry.getValue().compareTo(adjustedTarget) >= 0) {
                logger.debug("Column {}: equality selectivity={}, adjusted={}, found CDF value={}",
                           columnName, targetSelectivity, adjustedTarget, entry.getKey());
                if (isOrderStatus) {
                    logger.info("✅ FINAL: o_orderstatus选择CDF值: {} (adjusted selectivity={})",
                               entry.getKey().getValue(), adjustedTarget);
                }
                return entry.getKey();
            }
        }

        // 如果没找到，返回中间位置的值
        int midIndex = valueToCumulativeProbability.size() / 2;
        List<ComparableValue> values = new ArrayList<>(valueToCumulativeProbability.keySet());
        ComparableValue midValue = values.get(Math.min(midIndex, values.size() - 1));

        logger.warn("Column {}: equality selectivity={}, adjusted={}, CDF size={}, using middle value={}",
                   columnName, targetSelectivity, adjustedTarget, valueToCumulativeProbability.size(), midValue);

        if (isOrderStatus) {
            logger.info("✅ FINAL: o_orderstatus选择中间值: {} (因CDF查找失败)", midValue.getValue());
        }

        // 额外调试：打印CDF的一些样本点
        logger.debug("Column {}: CDF sample points: {}", columnName,
                    valueToCumulativeProbability.entrySet().stream()
                        .limit(5)
                        .map(e -> e.getKey() + "->" + e.getValue())
                        .toList());
        return midValue;
    }
    
    /**
     * 计算GE操作符保存的选择率
     * ★★★ 修复：对于GE操作符，直接保存累计选择率 P(X >= v)，而不是单值频率 ★★★
     * 这样 stage3 的 IPF 算法可以直接使用，无需累加计算
     * 
     * @param valueStr 当前值的字符串表示
     * @param targetSelectivity 目标选择率（即 P(X >= v)）
     * @param operator 操作符（GE或GT）
     * @return 应该保存的选择率（即 P(X >= v)，累计选择率）
     */
    public BigDecimal calculateGESavedSelectivity(String valueStr, BigDecimal targetSelectivity, CompareOperator operator) {
        // ★★★ 修复：直接返回累计选择率 P(X >= v)，无需计算单值频率 ★★★
        // IPF 算法需要的就是累计选择率，所以直接保存即可
        logger.info("📊 GE操作符保存累计选择率: 值 {}, P(X>={})={}", valueStr, valueStr, targetSelectivity);
        return targetSelectivity;
    }
    
    /**
     * 解析字符串值为对应的类型，包装为 ComparableValue
     */
    private ComparableValue parseValue(String valueStr, String dataType) {
        try {
            switch (dataType.toLowerCase()) {
                case "int4":
                case "int8":
                case "integer":
                case "bigint":
                case "smallint":
                    return new ComparableValue(Long.parseLong(valueStr), dataType);
                case "numeric":
                case "decimal":
                    return new ComparableValue(new BigDecimal(valueStr), dataType);
                case "float":
                case "float4":
                case "float8":
                case "double":
                case "real":
                    return new ComparableValue(Double.parseDouble(valueStr), dataType);
                case "date":
                    return new ComparableValue(Date.valueOf(valueStr), dataType);
                case "timestamp":
                case "timestamptz":
                    // 尝试解析 timestamp
                    try {
                        return new ComparableValue(Timestamp.valueOf(valueStr), dataType);
                    } catch (IllegalArgumentException e) {
                        // 如果失败，作为字符串处理
                        return new ComparableValue(valueStr, dataType);
                    }
                default:
                    // varchar, bpchar, text 等字符类型
                    return new ComparableValue(valueStr, dataType);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse value '{}' as type {}, treating as string", 
                       valueStr, dataType, e);
            return new ComparableValue(valueStr, dataType);
        }
    }
    
    /**
     * 获取 CDF 中的所有值（用于数据生成）
     * @return 所有值的列表
     */
    public List<ComparableValue> getAllValues() {
        if (valueToCumulativeProbability == null || valueToCumulativeProbability.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(valueToCumulativeProbability.keySet());
    }
    
    /**
     * 获取指定值的累积概率
     */
    public BigDecimal getCumulativeProbability(ComparableValue value) {
        if (valueToCumulativeProbability == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal prob = valueToCumulativeProbability.get(value);
        return prob != null ? prob : BigDecimal.ZERO;
    }
    
    // Getters
    public TreeMap<ComparableValue, BigDecimal> getValueToCumulativeProbability() {
        return valueToCumulativeProbability;
    }
    
    public long getTableSize() {
        return tableSize;
    }
    
    public String getDataType() {
        return dataType;
    }
    
    public String getColumnName() {
        return columnName;
    }
    
    /**
     * 包装类，用于在 TreeMap 中存储不同类型的值
     * 实现了 Comparable 接口以支持排序
     */
    public static class ComparableValue implements Comparable<ComparableValue> {
        private final Object value;
        private final String dataType;
        
        public ComparableValue(Object value, String dataType) {
            this.value = value;
            this.dataType = dataType;
        }
        
        public Object getValue() {
            return value;
        }
        
        public String getDataType() {
            return dataType;
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public int compareTo(ComparableValue other) {
            if (this.value == null && other.value == null) {
                return 0;
            }
            if (this.value == null) {
                return -1;
            }
            if (other.value == null) {
                return 1;
            }
            
            // 确保类型一致
            if (value instanceof Comparable) {
                try {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> comparableValue = (Comparable<Object>) value;
                    return comparableValue.compareTo(other.value);
                } catch (ClassCastException e) {
                    // 如果类型不匹配，转换为字符串比较
                    return value.toString().compareTo(other.value.toString());
                }
            }
            
            // 默认按字符串比较
            return value.toString().compareTo(other.value.toString());
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ComparableValue that = (ComparableValue) o;
            return Objects.equals(value, that.value);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
        
        @Override
        public String toString() {
            return value != null ? value.toString() : "null";
        }
    }
    
    /**
     * 将ruc.db.schema.EnhancedColumnStatistics转换为EnhancedStatsExtractor.EnhancedColumnStatistics
     */
    private EnhancedStatsExtractor.EnhancedColumnStatistics convertToRSGenStatistics(
            EnhancedColumnStatistics stats) {
        if (stats == null) {
            return null;
        }
        
        EnhancedStatsExtractor.EnhancedColumnStatistics rsgenStats = 
            new EnhancedStatsExtractor.EnhancedColumnStatistics();
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
        rsgenStats.setNDistinct(stats.getNdistinct()); // 注意：大小写差异
        rsgenStats.setMcvCount(stats.getMcvCount());
        rsgenStats.setHistogramBoundsCount(stats.getHistogramBoundsCount());
        rsgenStats.setPrimaryKey(stats.isPrimaryKey());
        rsgenStats.setForeignKey(stats.isForeignKey());
        rsgenStats.setDataPattern(stats.getDataPattern());
        
        return rsgenStats;
    }
    
    /**
     * 基于实际bucket分布构建CDF（精确估计）
     * 完全复用EnhancedBucketGenerator生成的buckets
     * 
     * @param buckets 已生成的bucket列表（已按值排序，包含NULL + MCV + Histogram）
     * @param tableSize 表大小
     */
    private void buildCDFFromBuckets(List<Bucket> buckets, long tableSize) {
        BigDecimal cumulative = BigDecimal.ZERO;
        
        // 注意：buckets已经按值排序（NULL在最前，然后是MCV和Histogram按值排序）
        // 但我们只需要非NULL的buckets来构建CDF
        for (Bucket bucket : buckets) {
            if (bucket.getType() == Bucket.BucketType.NULL) {
                continue; // NULL值不添加到CDF中（范围查询不考虑NULL）
            }
            
            // 计算bucket的概率（基于实际count）
            BigDecimal bucketProb = BigDecimal.valueOf(bucket.getCount())
                .divide(BigDecimal.valueOf(tableSize), 10, RoundingMode.HALF_UP);
            
            if (bucket.getType() == Bucket.BucketType.MCV) {
                // MCV bucket：单点值（low == high）
                ComparableValue value = convertBucketToComparableValue(bucket.getLow(), dataType);
                if (value != null) {
                    cumulative = cumulative.add(bucketProb);
                    valueToCumulativeProbability.put(value, cumulative);
                }
            } else if (bucket.getType() == Bucket.BucketType.HISTOGRAM) {
                // Histogram bucket：区间值
                // 使用high值作为边界（区间上界）
                ComparableValue highValue = convertBucketToComparableValue(bucket.getHigh(), dataType);
                if (highValue != null) {
                    cumulative = cumulative.add(bucketProb);
                    valueToCumulativeProbability.put(highValue, cumulative);
                }
                
                // 也添加low值（如果不存在），用于更精确的区间查询
                ComparableValue lowValue = convertBucketToComparableValue(bucket.getLow(), dataType);
                if (lowValue != null && !valueToCumulativeProbability.containsKey(lowValue)) {
                    // 使用累积概率（不包括当前bucket）
                    valueToCumulativeProbability.put(lowValue, cumulative.subtract(bucketProb));
                }
            }
        }
        
        // 确保最后一个值的累积概率为1.0
        if (!valueToCumulativeProbability.isEmpty()) {
            ComparableValue lastKey = valueToCumulativeProbability.lastKey();
            valueToCumulativeProbability.put(lastKey, BigDecimal.ONE);
        }
    }
    
    /**
     * 将Bucket的Datum转换为ComparableValue
     */
    private ComparableValue convertBucketToComparableValue(Datum datum, String dataType) {
        if (datum == null || datum.isNull()) {
            return null;
        }
        String valueStr = datum.toOutputString();
        if (valueStr == null || valueStr.isEmpty()) {
            return null;
        }
        return parseValue(valueStr, dataType);
    }
}

