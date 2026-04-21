package ruc.db.schema;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

import ruc.db.LanguageManager;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.filter.operation.CompareOperator;
import ruc.db.utils.CdfConstraintsApplier;
import ruc.db.utils.CommonUtils;
import ruc.db.utils.DataExportConstants;
import static ruc.db.utils.CommonUtils.CANONICAL_NAME_SPLIT_REGEX;
import static ruc.db.utils.CommonUtils.CSV_MAPPER;
import static ruc.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;
import static ruc.db.utils.CommonUtils.INPUT_FMT;
import ruc.db.utils.exception.TouchstoneException;

public class ColumnManager {
    public static final String COLUMN_STRING_INFO = "/stringTemplate.json";
    public static final String COLUMN_DISTRIBUTION_INFO = "/distribution.json";
    public static final String COLUMN_BOUND_PARA_INFO = "/boundPara.json";
    public static final String COLUMN_METADATA_INFO = "/column.csv";
    private static final ColumnManager INSTANCE = new ColumnManager();
    private static final CsvSchema columnSchema = CSV_MAPPER.schemaFor(Column.class);
    private final LinkedHashMap<String, Column> columns = new LinkedHashMap<>();

    private final List<Column> attributeColumns = new LinkedList<>();
    private int currentBatchSize = 0;

    private File distributionInfoPath;
    private final Logger logger = LoggerFactory.getLogger(ColumnManager.class);
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    // 记录统计信息加载状态，避免重复构建 CDF
    private String loadedStatisticsPath;
    private final Set<String> statisticsLoadedColumns = new HashSet<>();
    private boolean cdfConstraintsApplied = false;

    /**
     * Stage2（instantiate）阶段的“同一约束复用”缓存。
     *
     * 背景：同一列在不同查询/不同约束链里可能出现完全相同的选择率（例如 s_region = ? 的 selectivity 都是 0.189）。
     * 如果每次都强制选择不同的 MCV，会导致：
     * - 不必要的 UPDATE_MCV 写入（重复且可能相互冲突）
     * - 参数值不稳定，影响后续 join/filter 的一致性
     *
     * key = canonicalColumnName + "|" + operator + "|" + normalizedProbability
     * value = 已格式化（适配列类型/操作符）的参数字面量
     */
    private final Map<String, String> instantiateConstraintValueCache = new HashMap<>();
    
    // ★★★ 新增：BoundGroup级别的TableBoundInfo管理 ★★★
    // 关键：同一个BoundGroup中的所有列共享一个TableBoundInfo，确保bound行对齐
    private final Map<Integer, TableBoundInfo> boundGroupToBoundInfo = new HashMap<>();

    // Private constructor suppresses
    // default public constructor
    private ColumnManager() {
    }

    public static ColumnManager getInstance() {
        return INSTANCE;
    }

    public void setSpecialValue(String columnName, int specialValue) {
        Column column = getColumn(columnName);
        if (column != null) {
            column.setSpecialValue(specialValue);
        } else {
            logger.warn("无法为列 {} 设置特殊值，因为该列不存在于ColumnManager中", columnName);
        }
    }

    public void applyUniVarConstraint(String columnName, BigDecimal probability, CompareOperator operator, List<Parameter> parameters) {
        Column column = getColumn(columnName);
        if (column == null) {
            logger.warn("列 {} 不存在，跳过约束应用", columnName);
            return;
        }

        boolean hasInlineValue = parameters != null && parameters.stream()
                .anyMatch(parameter -> parameter.getDataValue() != null && !parameter.getDataValue().isEmpty());
        boolean preferStatistics = column.hasCDF() || hasInlineValue;

        if (preferStatistics) {
            try {
                applyUniVarConstraintWithStatistics(columnName, probability, operator, parameters);
                return;
            } catch (Exception e) {
                logger.error("使用统计信息实例化参数失败，列: {}, 回退到原始方法", columnName, e);
            }
        }

        applyUniVarConstraintOriginal(columnName, probability, operator, parameters);
        boolean recordFallback = shouldRecordConstraint(column, operator, probability, parameters);
        finalizeParameterValues(column, operator, probability, parameters, null, recordFallback);
    }
    
    /**
     * 使用统计信息（CDF 或已知实际值）实例化谓词参数
     */
    private void applyUniVarConstraintWithStatistics(String columnName,
                                                     BigDecimal probability,
                                                     CompareOperator operator,
                                                     List<Parameter> parameters) {
        Column column = getColumn(columnName);
        IntFunction<String> valueProvider = resolveActualValueProvider(column, probability, operator, parameters);

        if (valueProvider == null) {
            logger.warn("列 {} 无法从统计信息解析实际值，回退到原始方法", columnName);
            applyUniVarConstraintOriginal(columnName, probability, operator, parameters);
            boolean recordFallback = shouldRecordConstraint(column, operator, probability, parameters);
            finalizeParameterValues(column, operator, probability, parameters, null, recordFallback);
            return;
        }

        try {
            column.getDistribution().applyUniVarConstraint(probability, operator, parameters);
        } catch (Exception e) {
            logger.error("列 {} 使用统计信息应用约束失败，回退到原始方法", columnName, e);
            applyUniVarConstraintOriginal(columnName, probability, operator, parameters);
            boolean recordFallback = shouldRecordConstraint(column, operator, probability, parameters);
            finalizeParameterValues(column, operator, probability, parameters, null, recordFallback);
            return;
        }

        // ★★★ 修复：调用 shouldRecordConstraint() 检查是否应该记录范围约束 ★★★
        boolean shouldRecord = shouldRecordConstraint(column, operator, probability, parameters);
        finalizeParameterValues(column, operator, probability, parameters, valueProvider, shouldRecord);
    }
    
    /**
     * 将实际值转换为虚拟 dataIndex
     * 使用哈希确保相同值映射到相同的 index
     *
     * @param column 列对象
     * @param value CDF 中的实际值
     * @return 虚拟的 dataIndex（0 到 range-1 之间）
     */
    private long convertValueToVirtualIndex(Column column, ColumnCDF.ComparableValue value) {
        if (value == null || value.getValue() == null) {
            return 0;
        }
        
        // 使用哈希值映射到 [0, range) 范围
        long hash = Math.abs(value.getValue().toString().hashCode());
        long range = column.getRange();
        
        if (range <= 0) {
            return hash % 1000000; // 防御性编程
        }
        
        return hash % range;
    }
    
    /**
     * 原始的约束应用方法（作为备用）
     */
    private void applyUniVarConstraintOriginal(String columnName, BigDecimal probability, 
                                                CompareOperator operator, List<Parameter> parameters) {
        try {
            getColumn(columnName).getDistribution().applyUniVarConstraint(probability, operator, parameters);
        } catch (TouchstoneException e) {
            logger.error(columnName, e);
        }
    }

    /**
     * 根据 CDF 或参数本身解析实际值
     * 当有多个参数时，确保为每个参数分配不同的值
     */
    private IntFunction<String> resolveActualValueProvider(Column column,
                                                           BigDecimal probability,
                                                           CompareOperator operator,
                                                           List<Parameter> parameters) {
        // ★★★ 修复：对于范围约束且 probability=0 或 1 的边界情况，不进行CDF查找 ★★★
        // 这些是解耦约束，不需要参数化
        // ★★★ 但对于 prefix_int 列，即使 probability=1，也应该用 prefix_int 方法选择参数（选择 max 或 min）★★★
        String canonical = column.getStatistics() != null ? column.getStatistics().getColumnName() : null;
        boolean isPrefixInt = false;
        if (canonical != null) {
            ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec =
                    ruc.db.utils.VarcharPatternManager.getPrefixIntSpec(canonical);
            if (spec != null && spec.prefix != null && spec.min != null && spec.max != null) {
                isPrefixInt = true;
            }
        }
        
        if ((operator == CompareOperator.LE || operator == CompareOperator.LT ||
             operator == CompareOperator.GE || operator == CompareOperator.GT) &&
            probability != null && 
            (probability.compareTo(BigDecimal.ZERO) == 0 || probability.compareTo(BigDecimal.ONE) == 0)) {
            // 对于 prefix_int 列且 probability=1，用 prefix_int 方法选择参数
            if (isPrefixInt && probability.compareTo(BigDecimal.ONE) == 0 &&
                parameters != null && parameters.size() == 1) {
                ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec =
                        ruc.db.utils.VarcharPatternManager.getPrefixIntSpec(canonical);
                if (spec != null) {
                    String chosen = choosePrefixIntForBoundary(spec, operator);
                    if (chosen != null) {
                        logger.info("✅ [Stage2 prefix_int 边界选参] 列: {}, operator: {}, probability=1, 选择的阈值: {}", 
                                canonical, operator, chosen);
                        return index -> chosen;
                    }
                }
            }
            logger.debug("列 {} 范围约束 {} 的 probability={} 是边界值，不进行CDF查找",
                        canonical != null ? canonical : "unknown", operator, probability);
            return null;  // 不提供CDF值，使用默认值
        }
        
        if (column.hasCDF()) {
            try {
                // ★★★ varcharpatterns（例如 MFGR#<num>）加载（Stage2 兜底）★★★
                // Stage3 会在 EnhancedBucketGenerator 中加载；但 Stage2 可能只跑 instantiate，
                // 这里做一次“尝试加载”，不依赖外部顺序。
                try {
                    ruc.db.utils.VarcharPatternManager.tryLoadForCurrentRun();
                } catch (Exception ignore) {
                    // ignore
                }

                // ★★★ t3: prefix_int 列（如 p_brand=MFGR#<num>）按“子串数值列”选参 ★★★
                // - EQ：只用虚拟 MCV（后缀 int）挑最接近 target selectivity 的值
                // - 范围：用虚拟 histogramBounds（数值递增）按分位选择阈值
                if (canonical != null) {
                    ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec =
                            ruc.db.utils.VarcharPatternManager.getPrefixIntSpec(canonical);
                    if (spec != null && spec.prefix != null && spec.min != null && spec.max != null) {
                        logger.info("🎯 [Stage2 prefix_int选参] 列: {}, operator: {}, probability: {}, prefix: {}, 后缀范围: [{}, {}], MCV数量: {}, Histogram bounds数量: {}",
                                canonical, operator, probability, spec.prefix, spec.min, spec.max,
                                (spec.mcvValues != null ? spec.mcvValues.size() : 0),
                                (spec.histogramBounds != null ? spec.histogramBounds.size() : 0));
                        // 仅处理单参数谓词（SSB 的 p_brand >= ? / = ?）
                        if (parameters != null && parameters.size() == 1 && probability != null) {
                            if (operator == CompareOperator.EQ) {
                                String chosen = choosePrefixIntForEquality(column, spec, probability.doubleValue());
                                if (chosen != null) {
                                    logger.info("✅ [Stage2 prefix_int EQ选参] 列: {}, 目标选择率: {}, 选择的值: {}", canonical, probability, chosen);
                                    // ★★★ 关键修复：如果值来自原始MCV，使用查询推导的选择率而不是原始频率 ★★★
                                    // 因为查询推导的选择率更准确地反映了查询的实际需求
                                    // 原始频率可能来自不同的表大小或数据分布，不应该直接使用
                                    // 注意：这里不需要更新 ParameterConstraint，因为后续的 updateParameterConstraint 会使用查询推导的选择率
                                    return index -> chosen;
                                }
                            } else if (operator == CompareOperator.GE || operator == CompareOperator.GT
                                    || operator == CompareOperator.LE || operator == CompareOperator.LT) {
                                String chosen = choosePrefixIntForRange(spec, operator, probability.doubleValue());
                                if (chosen != null) {
                                    logger.info("✅ [Stage2 prefix_int 范围选参] 列: {}, operator: {}, 目标选择率: {}, 选择的阈值: {}", canonical, operator, probability, chosen);
                                    return index -> chosen;
                                }
                            }
                        }
                    }
                }

                // ★★★ 修复：同一列 + 同一operator + 同一选择率（probability）应复用同一个值 ★★★
                // 典型场景：多个查询都出现 s_region = ?，选择率都为 0.189。
                // 这种情况下，复用同一个 MCV（如 AMERICA）更合理，也避免把“值已被使用”误判为需要找别的值。
                if ((operator == CompareOperator.EQ || operator == CompareOperator.LIKE) &&
                    probability != null &&
                    parameters != null && parameters.size() == 1) {
                    String normalizedProb = probability.stripTrailingZeros().toPlainString();
                    String columnKey = (column.getStatistics() != null ? column.getStatistics().getColumnName() : "unknown");
                    String cacheKey = columnKey + "|" + operator + "|" + normalizedProb;
                    String cached = instantiateConstraintValueCache.get(cacheKey);
                    if (cached != null && !cached.isEmpty()) {
                        logger.debug("列 {} 命中实例化缓存: operator={}, probability={}, value={}",
                                     columnKey, operator, normalizedProb, cached);
                        return index -> cached;
                    }
                }

                // ★★★ 对于 IN/EQ/LIKE 等可能有多参数的操作符，为每个参数查找值 ★★★
                if ((operator == CompareOperator.IN || operator == CompareOperator.EQ || 
                     operator == CompareOperator.LIKE) && parameters != null && parameters.size() > 1) {
                    List<String> resolvedValues = new ArrayList<>();
                    for (Parameter parameter : parameters) {
                        ColumnCDF.ComparableValue value = column.findParameterValue(probability, operator);
                        if (value != null) {
                            String formatted = formatValueForParameter(value, column.getColumnType(), operator);
                            resolvedValues.add(formatted);
                        }
                    }
                    if (!resolvedValues.isEmpty()) {
                        return index -> index < resolvedValues.size()
                                ? resolvedValues.get(index)
                                : resolvedValues.get(resolvedValues.size() - 1);
                    }
                } else {
                    // 单参数或非IN/EQ/LIKE操作符，只调用一次
                    ColumnCDF.ComparableValue value = column.findParameterValue(probability, operator);
                    if (value != null) {
                        String formatted = formatValueForParameter(value, column.getColumnType(), operator);
                        // 写入缓存（只对单参数 EQ/LIKE 做复用；IN 多值不缓存）
                        if ((operator == CompareOperator.EQ || operator == CompareOperator.LIKE) && probability != null) {
                            String normalizedProb = probability.stripTrailingZeros().toPlainString();
                            String columnKey = (column.getStatistics() != null ? column.getStatistics().getColumnName() : "unknown");
                            String cacheKey = columnKey + "|" + operator + "|" + normalizedProb;
                            instantiateConstraintValueCache.putIfAbsent(cacheKey, formatted);
                        }
                        return index -> formatted;
                    }
                }
            } catch (Exception e) {
                String columnLabel = column.getStatistics() != null ? column.getStatistics().getColumnName() : "unknown";
                logger.warn("列 {} 通过 CDF 查找实际值失败", columnLabel, e);
            }
        }

        return null;
    }

    /**
     * prefix_int 等值选参：优先从 enhanced_column_statistics.json 的 MCV 中选择（如果存在且匹配），
     * 否则从 varcharpatterns.json 的虚拟 MCV 中选择。
     * 
     * @return 选中的值，如果值来自原始MCV，则同时更新 column 的 CDF 约束以使用原始频率
     */
    private String choosePrefixIntForEquality(Column column,
                                              ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec,
                                              double targetSelectivity) {
        // 优先尝试从 enhanced_column_statistics.json 的 MCV 中选择
        if (column != null && column.getStatistics() != null) {
            List<String> originalMcvs = column.getStatistics().getMostCommonValues();
            List<Double> originalMcfs = column.getStatistics().getMostCommonFrequencies();
            if (originalMcvs != null && originalMcfs != null && !originalMcvs.isEmpty()) {
                // 检查哪些 MCV 符合 prefix_int pattern
                int bestIdx = -1;
                double bestDiff = Double.MAX_VALUE;
                for (int i = 0; i < originalMcvs.size() && i < originalMcfs.size(); i++) {
                    String mcv = originalMcvs.get(i);
                    if (mcv != null && mcv.startsWith(spec.prefix)) {
                        Integer suffix = ruc.db.utils.VarcharPatternManager.parsePrefixIntSuffix(mcv, spec);
                        if (suffix != null) {
                            double f = originalMcfs.get(i);
                            double diff = Math.abs(f - targetSelectivity);
                            if (diff < bestDiff) {
                                bestDiff = diff;
                                bestIdx = i;
                            }
                        }
                    }
                }
                if (bestIdx >= 0) {
                    String chosen = originalMcvs.get(bestIdx);
                    double chosenFreq = originalMcfs.get(bestIdx);
                    // 确保格式一致：如果原MCV值已经是格式化后的，直接返回；否则重新格式化
                    Integer suffix = ruc.db.utils.VarcharPatternManager.parsePrefixIntSuffix(chosen, spec);
                    String formattedValue;
                    if (suffix != null) {
                        formattedValue = spec.formatValue(suffix);
                    } else {
                        formattedValue = chosen;
                    }
                    
                    // ★★★ 关键修复：如果值来自原始MCV，使用查询推导的选择率而不是原始频率 ★★★
                    // 因为查询推导的选择率更准确地反映了查询的实际需求
                    // 原始频率可能来自不同的表大小或数据分布，不应该直接使用
                    if (column.getColumnCDF() != null && column.getColumnCDF().getParameterConstraint() != null) {
                        ColumnCDF.ParameterConstraint constraint = column.getColumnCDF().getParameterConstraint();
                        // 如果这个值已经在约束中，更新其选择率为原始频率
                        if (constraint.selectedValues.contains(formattedValue)) {
                            constraint.valueToSelectivity.put(formattedValue, BigDecimal.valueOf(targetSelectivity));
                            constraint.valueToSelectivity.put(formattedValue, BigDecimal.valueOf(targetSelectivity));
                            logger.info("📊 [Stage2 prefix_int EQ选参详情] 更新选择率: 值={}, 使用查询推导的选择率 {} (原始频率: {})", 
                                    formattedValue, targetSelectivity, chosenFreq);
                        }
                    }
                    
                    logger.info("📊 [Stage2 prefix_int EQ选参详情] 从原统计信息MCV选择: 目标选择率: {}, 从 {} 个MCV中匹配到: 原值={}, 格式化后={}, 原始频率={}, 差值={}",
                            targetSelectivity, originalMcvs.size(), chosen, formattedValue, chosenFreq, bestDiff);
                    return formattedValue;
                }
            }
        }
        
        // 回退到 varcharpatterns.json 的虚拟 MCV
        if (spec.mcvValues == null || spec.mcvFrequencies == null) {
            logger.warn("⚠️ [Stage2 prefix_int EQ] 虚拟MCV数据缺失，无法选参");
            return null;
        }
        int n = Math.min(spec.mcvValues.size(), spec.mcvFrequencies.size());
        if (n <= 0) {
            logger.warn("⚠️ [Stage2 prefix_int EQ] 虚拟MCV列表为空，无法选参");
            return null;
        }
        int bestIdx = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double f = spec.mcvFrequencies.get(i);
            double diff = Math.abs(f - targetSelectivity);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        int suffix = spec.mcvValues.get(bestIdx);
        double chosenFreq = spec.mcvFrequencies.get(bestIdx);
        String result = spec.formatValue(suffix);
        logger.info("📊 [Stage2 prefix_int EQ选参详情] 从虚拟MCV选择: 目标选择率: {}, 从 {} 个MCV中选择: 后缀={}, 频率={}, 差值={}, 最终值: {} (注意：此值不在原统计信息MCV中，将使用ADD_MCV)",
                targetSelectivity, n, suffix, chosenFreq, bestDiff, result);
        return result;
    }

    /**
     * prefix_int 边界选参：对于 probability=1 的范围约束，选择 max 或 min。
     */
    private String choosePrefixIntForBoundary(ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec,
                                              CompareOperator operator) {
        int suffix;
        if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
            // P(X >= threshold) = 1 => threshold = min
            suffix = spec.min-1;
        } else if (operator == CompareOperator.LE || operator == CompareOperator.LT) {
            // P(X <= threshold) = 1 => threshold = max
            suffix = spec.max+1;
        } else {
            return null;
        }
        String result = spec.formatValue(suffix);
        logger.info("📊 [Stage2 prefix_int 边界选参详情] operator: {}, probability=1, 后缀范围: [{}, {}], 选择后缀: {}, 最终值: {}",
                operator, spec.min-1, spec.max+1, suffix, result);
        return result;
    }

    /**
     * prefix_int 范围选参：用虚拟 histogramBounds（单调递增的 int 边界）按目标选择率选择阈值。
     *
     * 约定：
     * - 对 GE/GT：probability = P(X >= threshold) 或 P(X > threshold)（按 Mirage 的范围含义）
     * - 对 LE/LT：probability = P(X <= threshold) 或 P(X < threshold)
     *
     * 我们用 bounds 做一个“可比较的近似 CDF”，再选最近的边界作为阈值，最后拼接 prefix。
     */
    private String choosePrefixIntForRange(ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec,
                                           CompareOperator operator,
                                           double targetSelectivity) {
        if (spec.histogramBounds == null || spec.histogramBounds.size() < 2) {
            logger.warn("⚠️ [Stage2 prefix_int 范围] Histogram bounds缺失或不足，无法选参");
            return null;
        }
        List<Integer> bounds = spec.histogramBounds;
        int min = spec.min;
        int max = spec.max;
        if (max <= min) {
            logger.warn("⚠️ [Stage2 prefix_int 范围] 后缀范围无效 [{}, {}]，使用最小值", min, max);
            return spec.prefix + min;
        }

        // 目标分位：把“选择率”转成 CDF 分位
        double targetCdf;
        if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
            // P(X >= t) = s  =>  CDF(t) ~= 1 - s
            targetCdf = 1.0 - targetSelectivity;
        } else {
            // P(X <= t) = s  =>  CDF(t) ~= s
            targetCdf = targetSelectivity;
        }
        // clamp
        targetCdf = Math.max(0.0, Math.min(1.0, targetCdf));

        // 先用均匀分布在 [min,max] 的近似求一个 raw 阈值
        int raw = min + (int) Math.round((max - min) * targetCdf);
        raw = Math.max(min, Math.min(max, raw));

        // 在 histogram bounds 里选一个最接近 raw 的边界（确保是递增可比较的）
        int best = bounds.get(0);
        int lo = 0, hi = bounds.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = bounds.get(mid);
            if (v == raw) { best = v; break; }
            if (v < raw) { best = v; lo = mid + 1; }
            else { hi = mid - 1; }
        }
        // 如果 best 太小/太大，做一次邻近纠偏
        if (lo < bounds.size()) {
            int cand = bounds.get(lo);
            if (Math.abs(cand - raw) < Math.abs(best - raw)) best = cand;
        }

        // 严格不等号的微调：GT/LT 需要避开边界
        int finalSuffix = best;
        if (operator == CompareOperator.GT) {
            finalSuffix = Math.min(max, best + 1);
        } else if (operator == CompareOperator.LT) {
            finalSuffix = Math.max(min, best - 1);
        }
        String result = spec.formatValue(finalSuffix);
        logger.info("📊 [Stage2 prefix_int 范围选参详情] operator: {}, 目标选择率: {}, 目标CDF: {}, 后缀范围: [{}, {}], 均匀近似阈值: {}, 从 {} 个bounds中选择: 最终后缀={}, 最终值: {}",
                operator, targetSelectivity, targetCdf, min, max, raw, bounds.size(), finalSuffix, result);
        return result;
    }

    /**
     * 统一设置 dataValue、记录 substring 索引、建立 dataIndex→实际值 映射以及 ParameterConstraint
     */
    private void finalizeParameterValues(Column column,
                                         CompareOperator operator,
                                         BigDecimal probability,
                                         List<Parameter> parameters,
                                         IntFunction<String> preferredValueProvider,
                                         boolean recordConstraint) {
        if (parameters == null || parameters.isEmpty()) {
            return;
        }

        List<String> resolvedValues = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            recordSubstringIndex(column, parameter);

            String resolvedValue = preferredValueProvider != null ? preferredValueProvider.apply(i) : null;
            if (resolvedValue == null || resolvedValue.isEmpty()) {
                String originalValue = parameter.getDataValue();
                resolvedValue = (originalValue != null && !originalValue.isEmpty())
                        ? originalValue
                        : column.transferDataToValue(parameter.getData());
            }
            parameter.setDataValue(resolvedValue);

            // ★★★ 关键修复：即使 recordConstraint=false（范围约束），也要建立 dataIndex → actualValue 映射 ★★★
            // 这样数据生成阶段才能正确使用 CDF 查找的值，而不是 bin-packing 计算的值
            // ★★★ Bug修复：对于dataIndex=-1的情况（如GE selectivity=1），使用虚拟dataIndex建立映射 ★★★
            if (resolvedValue != null) {
                long dataIndex = parameter.getData();
                if (dataIndex == -1) {
                    // 对于dataIndex=-1的情况（解耦约束），使用虚拟dataIndex：-parameterId
                    // 这样可以在cdfMapping.json中保存映射，数据生成阶段可以根据parameter ID查找
                    dataIndex = -parameter.getId();
                    logger.debug("参数 ID {} (列 {}): dataIndex=-1，使用虚拟dataIndex={} 保存映射 value={}", 
                               parameter.getId(), column.getStatistics() != null ? 
                               column.getStatistics().getColumnName() : "unknown", 
                               dataIndex, resolvedValue);
                }
                column.getDataIndex2ActualValue().put(dataIndex, resolvedValue);
                if (recordConstraint) {
                    resolvedValues.add(resolvedValue);
                }
            }
        }

        if (recordConstraint && !resolvedValues.isEmpty()) {
            logger.debug("updateParameterConstraint: operator={}, 值个数={}", operator, resolvedValues.size());
            updateParameterConstraint(column, operator, probability, resolvedValues);
        }
    }

    private void recordSubstringIndex(Column column, Parameter parameter) {
        if (parameter.isSubString()) {
            column.addSubStringIndex(parameter.getData());
        }
    }

    private void updateParameterConstraint(Column column,
                                           CompareOperator operator,
                                           BigDecimal probability,
                                           List<String> values) {
        // ★★★ 修复：范围约束且概率为1时不记录（边界情况），其他范围约束仍需记录 ★★★
        if ((operator == CompareOperator.LE || operator == CompareOperator.LT ||
             operator == CompareOperator.GE || operator == CompareOperator.GT) &&
            probability != null && probability.compareTo(BigDecimal.ONE) == 0) {
            String columnName = column.getStatistics() != null ? column.getStatistics().getColumnName() : "unknown";
            logger.debug("列 {} 的范围约束 {} 概率为1，不记录为参数约束，直接返回", columnName, operator);
            return;
        }
        
        if (values == null || values.isEmpty()) {
            return;
        }

        ColumnCDF columnCDF = column.getColumnCDF();
        if (columnCDF == null) {
            columnCDF = new ColumnCDF();
            column.setColumnCDF(columnCDF);
        }

        ColumnCDF.ParameterConstraint constraint = columnCDF.getParameterConstraint();
        Map<String, BigDecimal> selectivityPerValue = calculateValueSelectivity(probability, values);
        if (selectivityPerValue.isEmpty()) {
            return;
        }

        if (constraint == null) {
            // 创建新约束
            String firstValue = selectivityPerValue.keySet().iterator().next();
            constraint = new ColumnCDF.ParameterConstraint(firstValue, selectivityPerValue.get(firstValue), operator);
            columnCDF.setParameterConstraint(constraint);
            
            // 添加剩余值
            for (Map.Entry<String, BigDecimal> entry : selectivityPerValue.entrySet()) {
                if (!entry.getKey().equals(firstValue)) {
                    constraint.addValue(entry.getKey(), entry.getValue(), operator);
                }
            }
        } else {
            // 追加值（每个值保存其独立的operator）
            // ★★★ 修复：对于GE操作符，如果值已存在，使用已存在的值（因为它是经过calculateGESavedSelectivity计算过的）★★★
            for (Map.Entry<String, BigDecimal> entry : selectivityPerValue.entrySet()) {
                String value = entry.getKey();
                BigDecimal selectivity = entry.getValue();
                
                // 检查值是否已存在（相同操作符）
                boolean valueExists = constraint.valueToSelectivity.containsKey(value) &&
                                    constraint.getOperatorForValue(value) == operator;
                
                if (valueExists) {
                    // 值已存在，检查新选择率是否与已有选择率相近
                    BigDecimal existingSelectivity = constraint.valueToSelectivity.get(value);
                    BigDecimal diff = selectivity.subtract(existingSelectivity).abs();
                    if (diff.compareTo(new BigDecimal("0.0001")) < 0) {
                        // 选择率很接近，更新为新的选择率（第二个选择率）
                        BigDecimal savedSelectivity = selectivity;
                        if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
                            savedSelectivity = columnCDF.calculateGESavedSelectivity(value, selectivity, operator);
                        }
                        constraint.valueToSelectivity.put(value, savedSelectivity);
                        logger.debug("updateParameterConstraint: 值 {} 已存在，选择率 {} 与已有选择率 {} 相近（差值 {}），更新为新的选择率 {}", 
                                   value, selectivity, existingSelectivity, diff, savedSelectivity);
                    } else {
                        // 选择率差异较大，保留已计算的选择率
                        logger.debug("updateParameterConstraint: 值 {} 已存在，保留已计算的选择率 {}", 
                                   value, existingSelectivity);
                    }
                } else {
                    // 值不存在，对于GE操作符需要计算保存频率
                    BigDecimal savedSelectivity = selectivity;
                    if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
                        savedSelectivity = columnCDF.calculateGESavedSelectivity(value, selectivity, operator);
                    }
                    constraint.addValue(value, savedSelectivity, operator);
                }
            }
        }
    }

    private boolean shouldRecordConstraint(Column column,
        CompareOperator operator,
        BigDecimal probability,
        List<Parameter> parameters) {
        if (column == null || operator == null) {
            return false;
        }
    
        // ★★★ 修复：范围约束且概率为1时不记录（边界情况），其他范围约束还是需要记录 ★★★
        if (operator == CompareOperator.LE || operator == CompareOperator.LT ||
            operator == CompareOperator.GE || operator == CompareOperator.GT) {
            // 范围约束只在概率不等于1时才需要记录
            // 当概率=1时，说明是边界情况，不需要记录
            if (probability != null && probability.compareTo(BigDecimal.ONE) == 0) {
                logger.debug("范围约束 {} 概率为1，不需要记录", operator);
                return false;
            }
            logger.debug("范围约束 {} 需要记录（概率!=1）", operator);
            return true;
        }
        
        // 只记录相等性约束
        if (operator.isEqual()) {
            return true;
        }

        
        if (parameters != null) {
            return parameters.stream()
                    .anyMatch(parameter -> parameter.getDataValue() != null && !parameter.getDataValue().isEmpty());
        }
        return false;
    }

    private Map<String, BigDecimal> calculateValueSelectivity(BigDecimal probability, List<String> values) {
        Map<String, BigDecimal> selectivityMap = new LinkedHashMap<>();
        if (probability == null || values == null || values.isEmpty()) {
            return selectivityMap;
        }

        if (values.size() == 1) {
            selectivityMap.put(values.get(0), probability);
            return selectivityMap;
        }

        BigDecimal perValue = probability.divide(BigDecimal.valueOf(values.size()), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
        for (String value : values) {
            selectivityMap.merge(value, perValue, BigDecimal::add);
        }
        return selectivityMap;
    }

    /**
     * 将 CDF 中的实际值格式化为参数字符串
     * 
     * 与 Mirage 原始的 transferDataToValue() 的区别：
     * - transferDataToValue(): dataIndex（虚拟整数）→ 根据 min/max/specialValue 计算 → 实际值
     * - formatValueForParameter(): CDF 的真实值 → 直接格式化 → 参数字符串
     */
    private String formatValueForParameter(ColumnCDF.ComparableValue value, 
                                          ColumnType columnType, 
                                          CompareOperator operator) {
        Object rawValue = value.getValue();
        
        try {
            switch (columnType) {
                case INTEGER:
                    // 整数类型：直接转字符串
                    if (rawValue instanceof Number) {
                        return String.valueOf(((Number) rawValue).longValue());
                    }
                    break;
                    
                case DECIMAL:
                    // 小数类型：保持精度
                    if (rawValue instanceof BigDecimal) {
                        return ((BigDecimal) rawValue).toPlainString();
                    } else if (rawValue instanceof Number) {
                        return new BigDecimal(rawValue.toString()).toPlainString();
                    }
                    break;
                    
                case DATE:
                    // 日期类型：格式化为 yyyy-MM-dd
                    if (rawValue instanceof java.sql.Date) {
                        return rawValue.toString();  // Date.toString() 已经是 yyyy-MM-dd 格式
                    }
                    break;
                    
                case DATETIME:
                    // 时间戳类型：格式化为 yyyy-MM-dd HH:mm:ss
                    if (rawValue instanceof java.sql.Timestamp) {
                        String timestampStr = rawValue.toString();
                        // 去掉毫秒部分（如果有）
                        if (timestampStr.contains(".")) {
                            timestampStr = timestampStr.substring(0, timestampStr.indexOf('.'));
                        }
                        return timestampStr;
                    }
                    break;
                    
                case VARCHAR:
                    // 字符类型：直接返回字符串值
                    return rawValue.toString();
                    
                default:
                    logger.warn("Unsupported column type for parameter formatting: {}", columnType);
            }
        } catch (Exception e) {
            logger.error("Error formatting value {} for column type {}", 
                        rawValue, columnType, e);
        }
        
        // 如果格式化失败，返回字符串表示
        return rawValue != null ? rawValue.toString() : "";
    }

    public void setResultDir(String resultDir) {
        this.distributionInfoPath = new File(resultDir);
    }

    public String getResultDirPath() {
        return distributionInfoPath != null ? distributionInfoPath.getPath() : null;
    }

    /**
     * 每次重新加载列元数据时，重置统计信息缓存状态
     */
    private void resetStatisticsCache() {
        loadedStatisticsPath = null;
        statisticsLoadedColumns.clear();
        cdfConstraintsApplied = false;
    }

    public Column getColumn(String columnName) {
        return columns.get(columnName);
    }

    /**
     * 获取所有已加载的列名
     * @return 已加载的列名集合
     */
    public Set<String> getAllLoadedColumnNames() {
        return new HashSet<>(columns.keySet());
    }

    public void initAllParameters() {
        for (Map.Entry<String, Column> columnName2Column : columns.entrySet()) {
            Distribution distribution = columnName2Column.getValue().getDistribution();
            long appendRow = distribution.initAllParameters();
            if (appendRow > 0) {
                logger.error(rb.getString("cardinalityNotEnough"), columnName2Column.getKey(), appendRow);
            }
        }
    }
    
    /**
     * 初始化所有参数（支持 CDF）
     * 
     * ★★★ 关键修改：所有列都必须调用 initAllParameters() ★★★
     * 因为它负责：
     * 1. 清空并重建 paraData2Probability（generate 阶段需要）
     * 2. 为参数分配最终的 dataIndex（boundParas 需要）
     * 3. 清空并重建 offset2Pv（boundParas 需要）
     * 
     * 对于 CDF 列，后续会用实际值替换 dataValue
     */
    public void initAllParametersWithCDFSupport() {
        for (Map.Entry<String, Column> columnName2Column : columns.entrySet()) {
            Column column = columnName2Column.getValue();
            Distribution distribution = column.getDistribution();
            
            // ★★★ 所有列都必须调用 initAllParameters()！★★★
            long appendRow = distribution.initAllParameters();
            if (appendRow > 0) {
                logger.error(rb.getString("cardinalityNotEnough"), columnName2Column.getKey(), appendRow);
            }
            
            // ★★★ 对于 CDF 列，用实际值替换 dataValue（dataIndex 保持不变）★★★
            if (column.hasCDF()) {
                // paraData2Probability 已经由 initAllParameters() 构建完成
                // 现在建立 dataIndex → actualValue 的映射
                updateDataIndexToActualValueMapping(column);
                logger.debug("列 {} 使用 CDF，已建立 dataIndex → actualValue 映射", 
                            columnName2Column.getKey());
            }
        }
    }
    
    /**
     * 为所有 CDF 列建立 dataIndex → actualValue 映射（数据生成阶段使用）
     * 
     * 与 initAllParametersWithCDFSupport() 的区别：
     * - 不重新分配 dataIndex（因为参数已经从约束链文件加载）
     * - 只基于已加载的 paraData2Probability 建立映射
     */
    public void buildDataIndexToActualValueMappingForCDFColumns() {
        for (Map.Entry<String, Column> columnName2Column : columns.entrySet()) {
            Column column = columnName2Column.getValue();
            if (column.hasCDF()) {
                updateDataIndexToActualValueMapping(column);
                logger.debug("列 {} 使用 CDF，已建立 dataIndex → actualValue 映射（数据生成阶段）", 
                            columnName2Column.getKey());
            }
        }
    }
    
    /**
     * 为 CDF 列更新 dataIndex → actualValue 的映射
     * 
     * 从 paraData2Probability 中获取所有 dataIndex，
     * 从 CDF 中查找对应的实际值
     */
    private void updateDataIndexToActualValueMapping(Column column) {
        SortedMap<Long, BigDecimal> paraData2Prob = column.getDistribution().getParaData2Probability();
        if (paraData2Prob == null || paraData2Prob.isEmpty()) {
            return;
        }
        
        // 为每个 dataIndex 查找对应的实际值
        for (Long dataIndex : paraData2Prob.keySet()) {
            // 如果已经有映射（在 applyUniVarConstraintWithStatistics 中设置），跳过
            if (column.getDataIndex2ActualValue().containsKey(dataIndex)) {
                continue;
            }
            
            // 否则，从 CDF 中随机选择一个值作为该 dataIndex 的映射
            // 这是为了那些没有查询约束但需要生成数据的 dataIndex
            List<ColumnCDF.ComparableValue> allValues = column.getColumnCDF().getAllValues();
            if (!allValues.isEmpty()) {
                // 使用 dataIndex 作为种子，确保确定性
                Random rand = new Random(dataIndex);
                ColumnCDF.ComparableValue value = allValues.get(rand.nextInt(allValues.size()));
                String actualValue = formatValueForParameter(value, column.getColumnType(), CompareOperator.EQ);
                column.getDataIndex2ActualValue().put(dataIndex, actualValue);
            }
        }
    }

    public String[] generateAttRows(int range) {
        String[] result = new String[range];
        IntStream.range(0, range).parallel().forEach(rowId -> {
            String[] buffers = new String[attributeColumns.size()];
            for (int i = 0; i < attributeColumns.size(); i++) {
                buffers[i] = attributeColumns.get(i).output(rowId);
            }
            result[rowId] = String.join(DataExportConstants.FIELD_DELIMITER, buffers);
        });
        return result;
    }

    public long getMin(String columnName) {
        if (!columns.containsKey(columnName)) {
            return 0;
        }
        return columns.get(columnName).getMin();
    }

    public boolean[] evaluate(String columnName, CompareOperator operator, List<Parameter> parameters) {
        Column column = columns.get(columnName);
        if (column == null) {
            logger.warn("列 {} 在 ColumnManager 中不存在，evaluate 返回全 true（长度={}）", columnName, currentBatchSize);
            boolean[] ret = new boolean[currentBatchSize];
            java.util.Arrays.fill(ret, true);
            return ret;
        }
        if (!column.hasDataForEvaluation()) {
            logger.warn("列 {} 无可用数据数组（可能是主键/分区键列），evaluate 返回全 true（长度={}）", columnName, currentBatchSize);
            boolean[] ret = new boolean[currentBatchSize];
            java.util.Arrays.fill(ret, true);
            return ret;
        }
        return column.evaluate(operator, parameters);
    }

    public BigDecimal getNullPercentage(String columnName) {
        Column column = getColumn(columnName);
        if (column != null) {
            return column.getNullPercentage();
        } else {
            logger.debug("列 {} 不存在于ColumnManager中，返回默认null百分比 0", columnName);
            return BigDecimal.ZERO;
        }
    }

    public double[] calculate(String columnName) {
        return calculate(columnName, -1);
    }
    
    /**
     * ★★★ 新增：支持指定采样大小的 calculate 方法 ★★★
     * 
     * 只在有 --statistics 参数（即列有统计信息）时才使用新的采样方法
     * 否则使用原始的 mirage 逻辑，不干扰原始代码
     * 
     * @param columnName 列名
     * @param sampleSize 采样大小（-1 表示使用默认方法）
     * @return 数值数组
     */
    public double[] calculate(String columnName, int sampleSize) {
        Column column = getColumn(columnName);
        
        // ★★★ 关键判断：只有在有统计信息参数（statistics != null）时才使用新方法 ★★★
        if (sampleSize > 0 && column.getStatistics() != null) {
            // 检查是否有 MCV 或直方图统计信息
            EnhancedColumnStatistics stats = column.getStatistics();
            boolean hasStats = (stats.getHistogramBounds() != null && !stats.getHistogramBounds().isEmpty()) ||
                              (stats.getMostCommonValues() != null && !stats.getMostCommonValues().isEmpty());
            if (hasStats) {
                // 使用专门的采样数据生成方法（只在有 --statistics 参数时）
                return column.generateSampleDataForACC(sampleSize);
            }
        }
        
        // ★★★ 没有统计信息参数，使用原始的 mirage 逻辑 ★★★
        return column.calculate();
    }

    public ColumnType getColumnType(String columnName) {
        return columns.get(columnName).getColumnType();
    }

    public boolean isDateColumn(String columnName) {
        return columns.containsKey(columnName) && columns.get(columnName).getColumnType() == ColumnType.DATE;
    }

    public int getNdv(String columnName) {
        Column column = getColumn(columnName);
        if (column != null) {
            return (int) column.getRange();
        }
        
        // 如果列不存在，尝试从 TableManager 获取外键对应的主键表 size
        // 这通常发生在统计信息模式下，只加载了查询涉及的列，但外键列不在其中
        try {
            // 从列名中提取表名（格式：public.table.column）
            String[] parts = columnName.split(CANONICAL_NAME_SPLIT_REGEX);
            if (parts.length == 3) {
                String schemaName = parts[0];
                String tableName = parts[1];
                String fullTableName = schemaName + "." + tableName;
                
                // 尝试从 TableManager 获取外键对应的主键表 size
                // getFk2PkTableSize 接受的是表名（schema.table）
                SortedMap<String, Long> fk2PkTableSize = TableManager.getInstance().getFk2PkTableSize(fullTableName);
                if (fk2PkTableSize != null && fk2PkTableSize.containsKey(columnName)) {
                    long tableSize = fk2PkTableSize.get(columnName);
                    logger.debug("列 {} 不存在于ColumnManager中，从TableManager获取NDV: {}", columnName, tableSize);
                    return (int) tableSize;
                }
            }
        } catch (Exception e) {
            logger.warn("无法从TableManager获取列 {} 的NDV: {}", columnName, e.getMessage());
        }
        
        // 如果都获取不到，记录错误并返回0
        logger.error("列 {} 不存在于ColumnManager中，且无法从TableManager获取NDV，返回0", columnName);
        return 0;
    }

    public void addColumn(String columnName, Column column) throws TouchstoneException {
        if (columnName.split(CANONICAL_NAME_SPLIT_REGEX).length != 3) {
            throw new TouchstoneException("非canonicalColumnName格式");
        }
        columns.put(columnName, column);
    }

    public void storeColumnMetaData() throws IOException {
        try (StringWriter writer = new StringWriter()) {
            writer.write("ColumnName");
            for (int i = 0; i < columnSchema.size(); i++) {
                writer.write("," + columnSchema.columnName(i));
            }
            writer.write("\n");
            SequenceWriter seqW = CSV_MAPPER.writerFor(Column.class).with(columnSchema).writeValues(writer);
            for (var column : columns.entrySet()) {
                writer.write(column.getKey() + ",");
                seqW.write(column.getValue());
            }
            CommonUtils.writeFile(distributionInfoPath.getPath() + COLUMN_METADATA_INFO, writer.toString());
        }
    }

    public void storeColumnDistribution() throws IOException {
        File distribution = new File(distributionInfoPath + "/distribution");
        if (!distribution.exists()) {
            distribution.mkdir();
        }
        Map<String, Set<Long>> columName2StringTemplate = new HashMap<>();
        for (Map.Entry<String, Column> column : columns.entrySet()) {
            if (column.getValue().getColumnType() == ColumnType.VARCHAR &&
                    column.getValue().getStringTemplate().getLikeIndex2Status() != null) {
                columName2StringTemplate.put(column.getKey(), column.getValue().getStringTemplate().getLikeIndex2Status());
            }
        }
        String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(columName2StringTemplate);
        CommonUtils.writeFile(distribution.getPath() + COLUMN_STRING_INFO, content);
        Map<String, Map<Long, BigDecimal>> paraData2Probability = new TreeMap<>();
        Map<String, SortedMap<BigDecimal, Long>> offset2Pvs = new TreeMap<>();
        Map<String, Map<Long, String>> dataIndex2ActualValues = new TreeMap<>();  // CDF 映射
        
        for (Map.Entry<String, Column> column : columns.entrySet()) {
            Column col = column.getValue();
            Distribution columnDistribution = col.getDistribution();
            String columnName = column.getKey();
            
            // ★★★ 存储 dataIndex2ActualValue 映射（修复：即使 CDF 为空，只要有映射就保存）★★★
                // 对于有统计信息对象但没有 MCV/Histogram 的列，映射是在 applyUniVarConstraintWithStatistics 中建立的
            // 这些映射必须被保存，否则数据生成阶段无法找到 bound 值
            if (!col.getDataIndex2ActualValue().isEmpty()) {
                dataIndex2ActualValues.put(columnName, col.getDataIndex2ActualValue());
                boolean hasCDF = col.hasCDF();
                logger.info("列 {} 存储 {} 个 dataIndex → actualValue 映射 (hasCDF={})", 
                            columnName, col.getDataIndex2ActualValue().size(), hasCDF);
            }
            
            // ★★★ 所有列都使用原版 bin-packing 的 paraData2Probability ★★★
            boolean shouldStoreProbability = columnDistribution.hasConstraints() ||
                (columnDistribution.getParaData2Probability() != null && 
                 columnDistribution.getParaData2Probability().size() > 1);
            
            if (shouldStoreProbability) {
                SortedMap<Long, BigDecimal> probs = columnDistribution.getParaData2Probability();
                
                // ★★★ 确保所有 offset2Pv 中的 dataIndex 也在 paraData2Probability 中 ★★★
                if (!columnDistribution.getOffset2Pv().isEmpty()) {
                    for (Long boundDataIndex : columnDistribution.getOffset2Pv().values()) {
                        if (!probs.containsKey(boundDataIndex)) {
                            // 添加一个最小概率
                            probs.put(boundDataIndex, new BigDecimal("0.000001"));
                            logger.warn("列 {} 的 bound dataIndex {} 不在 bin-packing 结果中，添加最小概率",
                                       columnName, boundDataIndex);
                        }
                    }
                    
                    // 重新归一化
                    BigDecimal totalProb = probs.values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (totalProb.compareTo(BigDecimal.ONE) != 0 && totalProb.compareTo(BigDecimal.ZERO) > 0) {
                        SortedMap<Long, BigDecimal> normalized = new TreeMap<>();
                        for (Map.Entry<Long, BigDecimal> entry : probs.entrySet()) {
                            normalized.put(entry.getKey(), 
                                          entry.getValue().divide(totalProb, 8, RoundingMode.HALF_UP));
                        }
                        probs = normalized;
                        columnDistribution.setParaData2Probability(probs);
                    }
                    
                    offset2Pvs.put(columnName, columnDistribution.getOffset2Pv());
                }
                
                paraData2Probability.put(columnName, probs);
            } else if (!columnDistribution.getOffset2Pv().isEmpty()) {
                // 如果没有 paraData2Probability 但有 offset2Pv，也需要处理
                offset2Pvs.put(columnName, columnDistribution.getOffset2Pv());
            }
        }
        content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(paraData2Probability);
        CommonUtils.writeFile(distribution.getPath() + COLUMN_DISTRIBUTION_INFO, content);
        content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(offset2Pvs);
        CommonUtils.writeFile(distribution.getPath() + COLUMN_BOUND_PARA_INFO, content);
        
        // ★★★ 保存 dataIndex2ActualValue 映射（修复：即使 CDF 为空也保存）★★★
        if (!dataIndex2ActualValues.isEmpty()) {
            content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(dataIndex2ActualValues);
            String cdfMappingPath = distribution.getPath() + "/cdfMapping.json";
            CommonUtils.writeFile(cdfMappingPath, content);
            logger.info("保存 dataIndex2ActualValue 映射到 {}: 共 {} 个列", 
                       cdfMappingPath, dataIndex2ActualValues.size());
            for (var entry : dataIndex2ActualValues.entrySet()) {
                logger.info("  列 {}: {} 个映射", entry.getKey(), entry.getValue().size());
            }
        } else {
            logger.warn("没有 dataIndex2ActualValue 映射需要保存到 cdfMapping.json");
        }
    }

    /**
     * 保存CDF约束信息到JSON文件（用于数据生成阶段调整频率分布）
     */
    /**
     * 保存CDF约束信息到cdfConstraints.json
     * 
     * ★★★ 只保存需要调整频率的约束 ★★★
     * 1. 选择率为1的约束不保存（已被解耦）
     * 2. 有Histogram的列不保存（理论上总能找到合适的值，不需要调整MCV频率）
     * 3. 只有MCV的列才保存（需要调整MCV频率以满足约束）
     */
    public void saveCdfConstraints() throws IOException {
        File distribution = new File(distributionInfoPath + "/distribution");
        if (!distribution.exists()) {
            distribution.mkdir();
        }

        Map<String, Object> constraintsMap = new HashMap<>();
        int skippedCount = 0;

        for (Map.Entry<String, Column> entry : columns.entrySet()) {
            String columnName = entry.getKey();
            Column column = entry.getValue();

            // ★★★ 修复：获取约束信息（可能来自 CDF 或手动设置）★★★
            ColumnCDF.ParameterConstraint constraint = null;
            if (column.getColumnCDF() != null) {
                constraint = column.getColumnCDF().getParameterConstraint();
            }
            
            if (constraint == null) {
                continue;
            }

            logger.debug("列 {} 有 {} 个约束值待保存", columnName, constraint.selectedValues.size());
            
            // ★★★ 调试：打印约束详情 ★★★
            for (String v : constraint.selectedValues) {
                BigDecimal sel = constraint.valueToSelectivity.get(v);
                logger.debug("  → 值 '{}': selectivity={}, operator={}", v, sel, constraint.getOperatorForValue(v));
            }

            EnhancedColumnStatistics stats = column.getStatistics();
            boolean hasMCV = stats != null && stats.getMostCommonValues() != null && !stats.getMostCommonValues().isEmpty();
            
            // ★★★ 修复：对于 prefix_int 列，如果选出的值不在原 MCV 列表中，使用 ADD_MCV ★★★
            boolean isPrefixInt = false;
            String canonical = stats != null ? stats.getColumnName() : null;
            if (canonical != null) {
                ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec =
                        ruc.db.utils.VarcharPatternManager.getPrefixIntSpec(canonical);
                if (spec != null) {
                    isPrefixInt = true;
                }
            }
            
            // 检查每个值是否在原 MCV 列表中
            boolean allValuesInMcv = true;
            if (hasMCV && isPrefixInt) {
                List<String> originalMcvs = stats.getMostCommonValues();
                for (String value : constraint.selectedValues) {
                    if (!originalMcvs.contains(value)) {
                        allValuesInMcv = false;
                        logger.debug("列 {} 值 '{}' 不在原MCV列表中，将使用 ADD_MCV", columnName, value);
                        break;
                    }
                }
            }
            
            String constraintType = (hasMCV && allValuesInMcv) ? "UPDATE_MCV" : "ADD_MCV";

            // ★★★ 新结构：每个值单独保存其属性 ★★★
            Map<String, Object> valuesMap = new LinkedHashMap<>();
            int validValueCount = 0;
            
            for (String value : constraint.selectedValues) {
                BigDecimal selectivity = constraint.valueToSelectivity.get(value);
                CompareOperator valueOperator = constraint.getOperatorForValue(value);
                
                if (selectivity == null) {
                    continue;
                }
                
                // ★★★ 跳过选择率为0或1的值（边界值，不需要调整MCV）★★★
                if (selectivity.compareTo(BigDecimal.ZERO) == 0 || selectivity.compareTo(BigDecimal.ONE) == 0) {
                    logger.debug("列 {} 值 '{}' 选择率为 {}，跳过（边界值）", columnName, value, selectivity);
                    skippedCount++;
                    continue;
                }
                
                // ★★★ 处理 NOT_LIKE：转换为 LIKE，选择率 = 1 - 原选择率 ★★★
                CompareOperator savedOperator = valueOperator;
                BigDecimal savedSelectivity = selectivity;
                if (valueOperator == CompareOperator.NOT_LIKE) {
                    savedOperator = CompareOperator.LIKE;
                    savedSelectivity = BigDecimal.ONE.subtract(selectivity);
                    logger.debug("列 {} 值 '{}' NOT_LIKE 转换为 LIKE，选择率: {} -> {}", 
                               columnName, value, selectivity, savedSelectivity);
                }
                
                // ★★★ 每个值单独保存：selectivity、operator、constraintType ★★★
                Map<String, Object> valueConstraint = new LinkedHashMap<>();
                valueConstraint.put("selectivity", savedSelectivity.toPlainString());
                valueConstraint.put("operator", savedOperator.toString());
                valueConstraint.put("constraintType", constraintType);
                
                valuesMap.put(value, valueConstraint);
                validValueCount++;
                logger.debug("列 {} 保存值 '{}': selectivity={}, operator={}, type={}", 
                           columnName, value, savedSelectivity.toPlainString(), savedOperator, constraintType);
            }
            
            if (valuesMap.isEmpty()) {
                logger.debug("列 {} 没有有效值需要保存", columnName);
                continue;
            }

            constraintsMap.put(columnName, valuesMap);
            logger.info("列 {} 保存 {} 个值的约束信息（{}）", columnName, validValueCount, constraintType);
        }

        if (!constraintsMap.isEmpty()) {
            String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(constraintsMap);
            CommonUtils.writeFile(distribution.getPath() + "/cdfConstraints.json", content);
            logger.info("保存了 {} 个列的CDF约束信息到 cdfConstraints.json（跳过了 {} 个不需要调整的约束）", 
                       constraintsMap.size(), skippedCount);
        } else {
            logger.info("没有需要调整的CDF约束（跳过了 {} 个不需要调整的约束）", skippedCount);
        }
    }

    public void loadColumnMetaData() throws IOException {
        loadColumnMetaData(null);
    }

    /**
     * 加载列元数据，可选择只加载指定的列
     * @param involvedColumns 如果为null，加载所有列；否则只加载指定的列
     */
    public void loadColumnMetaData(Set<String> involvedColumns) throws IOException {
        // 元数据重载时清空统计信息缓存，确保后续按新列集重新加载
        resetStatisticsCache();
        columns.clear();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(distributionInfoPath.getPath() + COLUMN_METADATA_INFO))) {
            bufferedReader.readLine();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                int commaIndex = line.indexOf(",");
                String columnName = line.substring(0, commaIndex);

                // 如果指定了involvedColumns，只加载包含在内的列
                if (involvedColumns != null && !involvedColumns.contains(columnName)) {
                    continue;
                }

                String columnData = line.substring(commaIndex + 1);
                Column column = CSV_MAPPER.readerFor(Column.class).with(columnSchema).readValue(columnData);
                column.init();
                columns.put(columnName, column);
            }
        }

        if (involvedColumns != null) {
            logger.info("只加载查询涉及的列: {} 个列已加载", columns.size());
        } else {
            logger.info("加载所有列: {} 个列已加载", columns.size());
        }
    }

    public void loadColumnDistribution() throws IOException {
        File distribution = new File(distributionInfoPath + "/distribution");
        String content = CommonUtils.readFile(distribution.getPath() + COLUMN_STRING_INFO);
        Map<String, TreeSet<Long>> columName2StringTemplate = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (Map.Entry<String, TreeSet<Long>> template : columName2StringTemplate.entrySet()) {
            Column column = columns.get(template.getKey());
            if (column != null) {
                column.getStringTemplate().setLikeIndex2Status(template.getValue());
            }
        }
        content = CommonUtils.readFile(distribution.getPath() + COLUMN_DISTRIBUTION_INFO);
        Map<String, SortedMap<Long, BigDecimal>> paraData2Probability = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (Map.Entry<String, SortedMap<Long, BigDecimal>> paraData : paraData2Probability.entrySet()) {
            Column column = columns.get(paraData.getKey());
            if (column != null) {
                column.getDistribution().setParaData2Probability(paraData.getValue());
            }
        }
        content = CommonUtils.readFile(distribution.getPath() + COLUMN_BOUND_PARA_INFO);
        Map<String, SortedMap<BigDecimal, Long>> boundPv2Offsets = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (var boundPara : boundPv2Offsets.entrySet()) {
            Column column = columns.get(boundPara.getKey());
            if (column != null) {
                column.getDistribution().setOffset2Pv(boundPara.getValue());
            }
        }
        
        // 加载 CDF 的 dataIndex → actualValue 映射
        File cdfMappingFile = new File(distribution.getPath() + "/cdfMapping.json");
        if (cdfMappingFile.exists()) {
            content = CommonUtils.readFile(cdfMappingFile.getPath());
            Map<String, Map<Long, String>> dataIndex2ActualValues = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
            });
            logger.info("从 cdfMapping.json 加载映射: 共 {} 个列", dataIndex2ActualValues.size());
            for (var entry : dataIndex2ActualValues.entrySet()) {
                Column column = columns.get(entry.getKey());
                if (column != null) {
                    column.setDataIndex2ActualValue(entry.getValue());
                    // logger.info("列 {} 加载了 {} 个 dataIndex → actualValue 映射", 
                    //            entry.getKey(), entry.getValue().size());
                } else {
                    logger.warn("列 {} 在 cdfMapping.json 中有映射，但在 ColumnManager 中不存在", entry.getKey());
                }
            }
        } else {
            logger.warn("cdfMapping.json 文件不存在: {}", cdfMappingFile.getPath());
        }
    }

    public void storeColumnName2IdList(Map<String, List<List<Integer>>> columnName2IdList) throws IOException {
        String resultDir = distributionInfoPath.getPath();
        String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(columnName2IdList);
        CommonUtils.writeFile(resultDir + "/column2IdList", content);
    }

    public void loadColumnName2IdList() throws IOException {
        String resultDir = distributionInfoPath.getPath();
        String content = CommonUtils.readFile(resultDir + "/column2IdList");
        Map<String, List<List<Integer>>> column2IdList = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        for (Map.Entry<String, List<List<Integer>>> stringListEntry : column2IdList.entrySet()) {
            String columnName = stringListEntry.getKey();
            List<List<Integer>> idList = stringListEntry.getValue();
            columns.get(columnName).getDistribution().setIdList(idList);
        }
    }

    /**
     * 提取col的range信息(最大值，最小值)
     *
     * @param canonicalColumnNames 需要设置的col
     * @param sqlResult            有关的SQL结果(由AbstractDbConnector.getDataRange返回)
     * @throws TouchstoneException 设置失败
     */
    public void setDataRangeBySqlResult(List<String> canonicalColumnNames, String[] sqlResult) throws TouchstoneException {
        int index = 0;
        for (String canonicalColumnName : canonicalColumnNames) {
            Column column = columns.get(canonicalColumnName);
            String minResult = sqlResult[index++];
            String maxResult = sqlResult[index++];
            long min;
            long range;
            long specialValue;
            if (minResult == null) {
                min = -1;
                range = -1;
                specialValue = 0;
                if (column.getColumnType().isHasCardinalityConstraint()) {
                    index++;
                }
            } else {
                switch (column.getColumnType()) {
                    case INTEGER -> {
                        min = Long.parseLong(minResult);
                        long maxBound = Long.parseLong(maxResult);
                        range = Long.parseLong(sqlResult[index++]);
                        specialValue = (int) ((maxBound - min + 1) / range);
                    }
                    case VARCHAR -> {
                        column.setAvgLength((int) Math.round(Double.parseDouble(minResult)));
                        column.setMaxLength(Integer.parseInt(maxResult));
                        min = 0;
                        range = Integer.parseInt(sqlResult[index++]);
                        specialValue = ThreadLocalRandom.current().nextInt();
                    }
                    case DECIMAL -> {
                        specialValue = column.getSpecialValue();
                        min = (long) (Double.parseDouble(minResult) * specialValue);
                        range = (long) (Double.parseDouble(maxResult) * specialValue) - min + 1;
                    }
                    case DATE -> {
                        min = LocalDateTime.parse(minResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC) / (24 * 60 * 60);
                        range = LocalDateTime.parse(maxResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC) / (24 * 60 * 60) - min + 1;
                        specialValue = 0;
                    }
                    case DATETIME -> {
                        min = LocalDateTime.parse(minResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC);
                        range = LocalDateTime.parse(maxResult, INPUT_FMT).toEpochSecond(ZoneOffset.UTC) - min + 1;
                        specialValue = 0;
                    }
                    default -> throw new TouchstoneException("未匹配到的类型");
                }
            }
            column.setMin(min);
            column.setRange(range);
            column.setSpecialValue(specialValue);
            String[] tags = canonicalColumnName.split("\\.");
            String tableName = tags[0] + "." + tags[1];
            BigDecimal tableSize = BigDecimal.valueOf(TableManager.getInstance().getTableSize(tableName));
            column.setNullPercentage(new BigDecimal(sqlResult[index++]).divide(tableSize, DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP));
            column.init();
        }
    }

    public void cacheAttributeColumn(Collection<String> columnNames) {
        attributeColumns.clear();
        List<Column> validColumns = new ArrayList<>();
        List<String> missingColumns = new ArrayList<>();

        for (String columnName : columnNames) {
            Column column = getColumn(columnName);
            if (column != null) {
                validColumns.add(column);
            } else {
                missingColumns.add(columnName);
            }
        }

        attributeColumns.addAll(validColumns);
        logger.info("成功缓存 {} 个属性列", validColumns.size());

        if (!missingColumns.isEmpty()) {
            logger.warn("以下列在ColumnManager中不存在，将被跳过: {}", String.join(", ", missingColumns));
        }
    }

    public void prepareGeneration(int size) {
        prepareGeneration(size, java.util.Collections.emptyList());
    }

    /**
     * 对属性列与额外列（如约束链过滤中的主键列）去重后统一 prepare，不改变 attributeColumns / generateAttRows 顺序。
     */
    public void prepareGeneration(int size, java.util.Collection<String> extraPrepareColumns) {
        this.currentBatchSize = size;
        java.util.LinkedHashSet<Column> toPrepare = new java.util.LinkedHashSet<>(attributeColumns);
        if (extraPrepareColumns != null) {
            for (String name : extraPrepareColumns) {
                Column c = getColumn(name);
                if (c != null) {
                    toPrepare.add(c);
                }
            }
        }
        if (extraPrepareColumns != null && !extraPrepareColumns.isEmpty()) {
            logger.info("prepareGeneration: {} 个属性列 + 额外 {} 个列名 -> 共 {} 列执行 prepareTupleData",
                    attributeColumns.size(), extraPrepareColumns.size(), toPrepare.size());
        }
        toPrepare.parallelStream().forEach(column -> column.prepareTupleData(size));
    }

    public int getCurrentBatchSize() {
        return currentBatchSize;
    }
    
    /**
     * 从 enhanced_column_statistics.json 加载统计信息并构建 CDF，并可选择是否应用 cdfConstraints.json
     * @param statisticsPath 统计信息 JSON 文件的路径
     * @param applyCdfConstraints 是否应用 cdfConstraints.json（生成阶段需要，实例化阶段跳过）
     */
    public void loadStatisticsAndBuildCDF(String statisticsPath, boolean applyCdfConstraints) throws IOException {
        if (statisticsPath == null || statisticsPath.isBlank()) {
            logger.warn("统计信息路径为空，跳过加载");
            return;
        }

        // 每次进入（尤其是 Stage2 instantiate）都清空“同一约束复用”缓存，避免跨次运行污染
        // 例如连续 instantiate 两次时，不应该复用上一次 run 的参数值选择结果
        instantiateConstraintValueCache.clear();

        boolean samePath = statisticsPath.equals(this.loadedStatisticsPath);
        boolean allColumnsAlreadyBuilt = samePath
                && !columns.isEmpty()
                && columns.keySet().stream().allMatch(statisticsLoadedColumns::contains);

        // 如果路径未变且所有列都已构建过，则直接复用（但仍按需应用约束）
        if (allColumnsAlreadyBuilt) {
            logger.info("统计信息已从 {} 加载，复用已构建的 CDF（{} 列）", statisticsPath, statisticsLoadedColumns.size());
        } else {
            logger.info("开始从 {} 加载统计信息并构建 CDF", statisticsPath);
            // 读取 JSON 文件
            Map<String, Map<String, Object>> allStats = CommonUtils.MAPPER.readValue(
                new File(statisticsPath), 
                new TypeReference<Map<String, Map<String, Object>>>() {}
            );
            
            int successCount = 0;
            int skipCount = 0;
            int reuseCount = 0;

            // 重新构建时清空记录，防止旧列残留
            if (!samePath) {
                statisticsLoadedColumns.clear();
                cdfConstraintsApplied = false;
            }
        
            for (Map.Entry<String, Map<String, Object>> tableEntry : allStats.entrySet()) {
                String tableName = tableEntry.getKey();
                Map<String, Object> tableData = tableEntry.getValue();
                
                // 获取表大小
                Object tableSizeObj = tableData.get("tableSize");
                long tableSize = tableSizeObj instanceof Number ? 
                               ((Number) tableSizeObj).longValue() : 0;
                
                // 获取列统计信息
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> columnsData = 
                    (Map<String, Map<String, Object>>) tableData.get("columns");
                
                if (columnsData == null) {
                    logger.warn("表 {} 没有列统计信息", tableName);
                    continue;
                }
                
                for (Map.Entry<String, Map<String, Object>> columnEntry : columnsData.entrySet()) {
                    String columnName = columnEntry.getKey();
                    Map<String, Object> columnData = columnEntry.getValue();
                    
                    try {
                        Column column = getColumn(columnName);
                        if (column == null) {
                            skipCount++;
                            logger.debug("跳过列 {} (不在 ColumnManager 中)", columnName);
                            continue;
                        }
                        // 已经基于同一路径构建过且仍存在 CDF，直接复用
                        if (samePath && statisticsLoadedColumns.contains(columnName) && column.hasCDF()) {
                            reuseCount++;
                            continue;
                        }

                        // 转换为 EnhancedColumnStatistics
                        EnhancedColumnStatistics stats = CommonUtils.MAPPER.convertValue(
                            columnData, 
                            EnhancedColumnStatistics.class
                        );
                        stats.setTableSize(tableSize);
                        
                        // 在加载阶段强校验/补全 VARCHAR 等类型的真实长度信息，避免后续 RSGen 退化为默认 maxLength=50
                        ensureStringLengthInfo(stats, column);
                        column.buildCDFFromStatistics(stats);
                        statisticsLoadedColumns.add(columnName);
                        successCount++;
                        logger.debug("成功为列 {} 构建 CDF (MCV: {}, Histogram: {})", 
                                    columnName, 
                                    stats.getMcvCount(), 
                                    stats.getHistogramBoundsCount());
                    } catch (Exception e) {
                        logger.error("为列 {} 构建 CDF 失败", columnName, e);
                    }
                }
            }
            
            this.loadedStatisticsPath = statisticsPath;
            logger.info("统计信息加载完成：成功 {} 个列，跳过 {} 个列，复用 {} 个列", successCount, skipCount, reuseCount);
        }
        
        // 实例化阶段不应用约束；生成阶段按需应用且只应用一次
        if (applyCdfConstraints) {
            applyCdfConstraintsToStatisticsIfNeeded();
        } else {
            logger.debug("跳过应用 cdfConstraints.json（实例化阶段）");
        }
    }

    /**
     * 向后兼容：默认应用 cdfConstraints.json（用于数据生成阶段）
     */
    public void loadStatisticsAndBuildCDF(String statisticsPath) throws IOException {
        loadStatisticsAndBuildCDF(statisticsPath, true);
    }

    /**
     * 仅在需要时应用 cdfConstraints.json，避免重复应用
     * 注意：只应用 ADD_MCV 类型，UPDATE_MCV 在数据生成阶段通过 IPF 算法应用
     */
    private void applyCdfConstraintsToStatisticsIfNeeded() {
        if (cdfConstraintsApplied) {
            logger.debug("cdfConstraints.json 已应用过，跳过重复应用");
            return;
        }
        applyCdfConstraintsToStatistics();
        cdfConstraintsApplied = true;
    }
    
    /**
     * 加载 cdfConstraints.json 并应用到统计信息中（更新MCV）
     * 
     * 新的 JSON 结构：每个值有独立的 selectivity、operator、constraintType
     * {
     *   "public.part.p_type": {
     *     "VALUE1": { "selectivity": "0.006835", "operator": "EQ", "constraintType": "UPDATE_MCV" },
     *     "VALUE2": { "selectivity": "0.00416", "operator": "LIKE", "constraintType": "ADD_MCV" }
     *   }
     * }
     */
    private void applyCdfConstraintsToStatistics() {
        try {
            // 查找 cdfConstraints.json 文件
            File constraintsFile = findCdfConstraintsFile();
            if (constraintsFile == null || !constraintsFile.exists()) {
                logger.debug("cdfConstraints.json 文件不存在，跳过应用约束到统计信息");
                return;
            }
            
            String content = CommonUtils.readFile(constraintsFile.getPath());
            Map<String, Object> constraintsMap = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {});
            
            int appliedCount = 0;
            for (Map.Entry<String, Object> entry : constraintsMap.entrySet()) {
                String columnName = entry.getKey();
                Column column = getColumn(columnName);
                if (column == null) {
                    logger.debug("列 {} 不存在，跳过", columnName);
                    continue;
                }
                
                EnhancedColumnStatistics stats = column.getStatistics();
                if (stats == null) {
                    logger.debug("列 {} 没有统计信息，跳过", columnName);
                    continue;
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> valuesMap = (Map<String, Object>) entry.getValue();

                CdfConstraintsApplier.mergeLikeParameterConstraintFromValuesMap(column, valuesMap);
                
                // ★★★ 保存原始MCV频率信息，用于日志输出 ★★★
                List<String> originalMcvs = stats.getMostCommonValues();
                List<Double> originalMcfs = stats.getMostCommonFrequencies();
                Map<String, Double> originalFreqMap = new HashMap<>();
                if (originalMcvs != null && originalMcfs != null) {
                    for (int i = 0; i < originalMcvs.size() && i < originalMcfs.size(); i++) {
                        originalFreqMap.put(originalMcvs.get(i), originalMcfs.get(i));
                    }
                }
                
                // ★★★ 使用工具类应用约束（新结构：每个值有独立的属性）★★★
                if (CdfConstraintsApplier.applyConstraintToStatistics(stats, valuesMap)) {
                    appliedCount++;
                    
                    // ★★★ 打印详细的更新日志 ★★★
                    List<String> updatedMcvs = stats.getMostCommonValues();
                    List<Double> updatedMcfs = stats.getMostCommonFrequencies();
                    logger.info("列 {} 应用约束：更新了 {} 个MCV值", columnName, valuesMap.size());
                    
                    // 遍历所有约束值，打印每个值的更新信息
                    for (Map.Entry<String, Object> valueEntry : valuesMap.entrySet()) {
                        String valueKey = valueEntry.getKey();
                        Object valueObj = valueEntry.getValue();
                        
                        if (!(valueObj instanceof Map)) {
                            continue;
                        }
                        
                        @SuppressWarnings("unchecked")
                        Map<String, Object> valueConstraint = (Map<String, Object>) valueObj;
                        String constraintType = (String) valueConstraint.get("constraintType");
                        String operator = (String) valueConstraint.get("operator");
                        String selectivityStr = (String) valueConstraint.get("selectivity");
                        
                        if (selectivityStr == null) {
                            continue;
                        }
                        
                        double targetSelectivity = Double.parseDouble(selectivityStr);
                        
                        // 对于LIKE操作符，需要找到实际生成的值
                        String actualValue = valueKey;
                        if ("LIKE".equals(operator) && updatedMcvs != null) {
                            // LIKE操作符可能生成包含模式的值，尝试在MCV中查找
                            for (String mcv : updatedMcvs) {
                                if (mcv.contains(valueKey)) {
                                    actualValue = mcv;
                                    break;
                                }
                            }
                        }
                        
                        // 查找更新后的频率
                        double updatedFreq = -1.0;
                        if (updatedMcvs != null && updatedMcfs != null) {
                            int index = updatedMcvs.indexOf(actualValue);
                            if (index >= 0 && index < updatedMcfs.size()) {
                                updatedFreq = updatedMcfs.get(index);
                            }
                        }
                        
                        // 获取原始频率
                        double originalFreq = originalFreqMap.getOrDefault(actualValue, -1.0);
                        
                        // 打印日志
                        if ("UPDATE_MCV".equals(constraintType)) {
                            if (originalFreq >= 0) {
                                logger.info("  UPDATE_MCV - 值 '{}': 原始频率 {} -> 更新频率 {} (目标选择率: {})", 
                                           actualValue, originalFreq, updatedFreq >= 0 ? updatedFreq : targetSelectivity, targetSelectivity);
                            } else {
                                logger.info("  UPDATE_MCV - 值 '{}': (新添加) -> 更新频率 {} (目标选择率: {})", 
                                           actualValue, updatedFreq >= 0 ? updatedFreq : targetSelectivity, targetSelectivity);
                            }
                        } else if ("ADD_MCV".equals(constraintType)) {
                            logger.info("  ADD_MCV - 值 '{}': (新添加) -> 频率 {} (目标选择率: {})", 
                                       actualValue, updatedFreq >= 0 ? updatedFreq : targetSelectivity, targetSelectivity);
                        }
                    }
                }
            }
            
            if (appliedCount > 0) {
                logger.info("成功应用 {} 个列的约束到统计信息", appliedCount);
            }
        } catch (Exception e) {
            logger.warn("加载并应用 cdfConstraints.json 失败: {}", e.getMessage());
        }
    }
    
    /**
     * 查找 cdfConstraints.json 文件
     */
    private File findCdfConstraintsFile() {
        List<String> candidatePaths = new java.util.ArrayList<>();
        if (distributionInfoPath != null) {
            candidatePaths.add(distributionInfoPath.getPath() + "/distribution/cdfConstraints.json");
        }
        String cwd = System.getProperty("user.dir");
        candidatePaths.add(cwd + "/distribution/cdfConstraints.json");
        candidatePaths.add("distribution/cdfConstraints.json");
        
        for (String path : candidatePaths) {
            File file = new File(path);
            if (file.exists()) {
                logger.debug("找到 cdfConstraints.json: {}", file.getAbsolutePath());
                return file;
            }
        }
        logger.debug("未找到 cdfConstraints.json, 已尝试: {}", candidatePaths);
        return null;
    }
    
    /**
     * 方案 C：在统计信息加载阶段保证“字符串列长度”信息可被后续 RSGen 正确解析。
     *
     * 触发背景：
     * - RSGen 的 varchar 生成器优先从 stats.dataPattern 解析 max_length；
     * - 若缺失，则退化为从 stats.dataType 解析 varchar(n)；
     * - 若仍缺失，会使用默认 50，导致像 p_type(varchar(25)) 这种列生成超长字符串，COPY 失败。
     *
     * 这里做两层兜底：
     * - 若 stats.dataPattern 缺失 max_length=，则从 Column.originalType 解析并补上；
     * - 同时若 stats.dataType 不带 (n)，也补成 varchar(n)/char(n) 形式，避免 dataPattern 在后续链路丢失时仍可解析。
     */
    private void ensureStringLengthInfo(EnhancedColumnStatistics stats, Column column) {
        if (stats == null || column == null) {
            return;
        }
        // 仅对字符串/定长字符列处理；其他类型不应引入 max_length
        ColumnType ct = column.getColumnType();
        if (ct != ColumnType.VARCHAR) {
            return;
        }

        Integer maxLen = parseMaxLengthFromType(column.getOriginalType());
        if (maxLen == null || maxLen <= 0) {
            return;
        }

        String dp = stats.getDataPattern();
        if (dp == null || !dp.contains("max_length=")) {
            // 仅在缺失时补全，避免覆盖 extractor 已生成的更丰富 pattern
            String newDp = "max_length=" + maxLen;
            if (dp != null && !dp.isBlank()) {
                newDp = newDp + "," + dp;
            }
            stats.setDataPattern(newDp);
            logger.debug("补全列 {} 的 dataPattern: {}", stats.getColumnName(), stats.getDataPattern());
        }

        String dt = stats.getDataType();
        if (dt != null) {
            String lower = dt.toLowerCase();
            boolean isStringType = lower.contains("varchar") || lower.contains("character varying")
                    || lower.contains("char") || lower.contains("character") || lower.contains("bpchar");
            if (isStringType && !dt.contains("(")) {
                // 不强制改变原有类型名，仅补一个长度后缀供 getColumnMaxLength 解析
                stats.setDataType(dt + "(" + maxLen + ")");
                logger.debug("补全列 {} 的 dataType 长度信息: {}", stats.getColumnName(), stats.getDataType());
            }
        }
    }

    private Integer parseMaxLengthFromType(String type) {
        if (type == null) {
            return null;
        }
        // 支持：varchar(25)、character varying(25)、char(10)、character(10)、bpchar(10)
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(character\\s+varying|varchar|character|char|bpchar)\\s*\\(\\s*(\\d+)\\s*\\)")
                .matcher(type);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 基于 CDF 统计信息构建 paraData2Probability
     * 
     * 从 CDF 中提取所有值（MCVs + Histogram）及其频率，
     * 转换为 paraData2Probability 格式（dataIndex → probability）
     */
    private SortedMap<Long, BigDecimal> buildParaData2ProbabilityFromCDF(Column column) {
        ColumnCDF cdf = column.getColumnCDF();
        if (cdf == null || cdf.isEmpty()) {
            return null;
        }
        
        SortedMap<Long, BigDecimal> result = new TreeMap<>();
        
        // 从 CDF 获取所有值
        List<ColumnCDF.ComparableValue> allValues = cdf.getAllValues();
        
        // 为每个值计算频率
        BigDecimal previousCumulativeProb = BigDecimal.ZERO;
        for (int i = 0; i < allValues.size(); i++) {
            ColumnCDF.ComparableValue value = allValues.get(i);
            
            // 计算该值的频率（cumulative probability 的差）
            BigDecimal currentCumulativeProb = cdf.getCumulativeProbability(value);
            BigDecimal frequency = currentCumulativeProb.subtract(previousCumulativeProb);
            
            if (frequency.compareTo(BigDecimal.ZERO) > 0) {
                // 使用哈希作为 virtual dataIndex
                // ★★★ 确保 dataIndex >= 1（避免 Distribution.generateAttributeData 的 bug）★★★
                int hashCode = value.toString().hashCode();
                long range = column.getRange();
                if (range <= 0) {
                    range = 1000000;
                }
                long dataIndex = (Math.abs(hashCode) % range) + 1;  // +1 确保 >= 1
                
                result.put(dataIndex, frequency);
                
                // 同时建立 dataIndex → actualValue 的映射
                String actualValue = formatValueForParameter(value, column.getColumnType(), CompareOperator.EQ);
                column.getDataIndex2ActualValue().put(dataIndex, actualValue);
            }
            
            previousCumulativeProb = currentCumulativeProb;
        }
        
        // 归一化概率和为 1.0
        BigDecimal totalProb = result.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        if (totalProb.compareTo(BigDecimal.ZERO) > 0 && 
            totalProb.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.01")) > 0) {
            SortedMap<Long, BigDecimal> normalized = new TreeMap<>();
            for (Map.Entry<Long, BigDecimal> entry : result.entrySet()) {
                normalized.put(entry.getKey(), 
                              entry.getValue().divide(totalProb, 8, RoundingMode.HALF_UP));
            }
            return normalized;
        }
        
        return result;
    }
    
    /**
     * 检查指定列是否已构建 CDF
     */
    public boolean hasCDF(String columnName) {
        Column column = getColumn(columnName);
        return column != null && column.hasCDF();
    }
    
    /**
     * ★★★ 新增：清理所有列的 ACC 采样数据缓存
     * 在 QueryInstantiate 完成后调用，为下一次查询做准备
     */
    public void clearAccSampleDataCache() {
        logger.info("清理所有列的 ACC 采样数据缓存");
        for (Column column : columns.values()) {
            column.clearAccSampleDataCache();
        }
    }
    
    // ★★★ 新增：BoundGroup TableBoundInfo管理方法 ★★★
    public void setBoundGroupTableBoundInfo(int boundGroupId, TableBoundInfo tableBoundInfo) {
        boundGroupToBoundInfo.put(boundGroupId, tableBoundInfo);
        logger.info("🔗 BOUND DEBUG: 为 Bound Group {} 设置全局 TableBoundInfo", boundGroupId);
    }
    
    public TableBoundInfo getBoundGroupTableBoundInfo(int boundGroupId) {
        return boundGroupToBoundInfo.get(boundGroupId);
    }
    
    public void clearBoundGroupTableBoundInfo() {
        boundGroupToBoundInfo.clear();
        logger.info("🔗 BOUND DEBUG: 清理所有 Bound Group 的 TableBoundInfo");
    }
}
