package ruc.db.rsgen;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;
import ruc.db.schema.ColumnManager;
import ruc.db.utils.CommonUtils;
import ruc.db.utils.VarcharPatternManager;

/**
 * 字符串（varchar/bpchar）数据生成器
 */
public class VarcharDataGenerator {
    private static final Logger logger = LoggerFactory.getLogger(VarcharDataGenerator.class);

    /**
     * 智能varchar/bpchar列数据生成
     * 生成逻辑：
     * 1. 检查有没有MCV
     *    1.1 如果有MCV，检查是否满足频率相加接近1
     *        1.1.1 如果频率相加接近1（大于0.8），用MCV的频率来生成
     *        1.1.2 如果频率相加不接近1（小于0.2），则不使用MCV
     * 2. 检查有没有直方图
     *    2.1 如果直方图边界数量 = tablesize，直接用边界值生成
     *    2.2 bpchar类型且有直方图，按bucket建模生成
     *    2.3 如果是 varchar，且直方图边界 != 表大小，则使用词频生成
     * 3. 其余情况随机生成，保证长度在min/max范围，平均长度符合avg_width
     */
    public Object[] generateSmartVarcharColumnData(EnhancedColumnStatistics colStats, long tableSize) {
        logger.debug("开始智能varchar/bpchar列生成，列: {}, 表大小: {}", colStats.getColumnName(), tableSize);
        String dataType = colStats.getDataType().toLowerCase();
        boolean isBpchar = "bpchar".equals(dataType);

        // ★★★ t4: prefix_int varcharpattern 列专用生成路径（先生成后缀 int，再拼接 prefix）★★★
        // 目的：
        // - 避免字符串 histogramBounds 乱序导致范围谓词失真
        // - 避免 MCV 频率在 Stage3 IPF 调整后被归一化到 1.0，触发“只用MCV采样”的错误分支
        if (colStats.getColumnName() != null) {
            try {
                // Stage3 时 EnhancedBucketGenerator 已尝试加载；这里再兜底：当前 result 或工程下 distribution/
                VarcharPatternManager.tryLoadForCurrentRun();
                VarcharPatternManager.PrefixIntSpec spec = VarcharPatternManager.getPrefixIntSpec(colStats.getColumnName());
                if (spec != null && spec.mcvValues != null && spec.histogramBounds != null) {
                    logger.info("🎯 [Stage3 prefix_int生成] 检测到 prefix_int varcharpattern 列: {}, prefix: {}, 后缀范围: [{}, {}], MCV数量: {}, Histogram bounds数量: {}, 表大小: {}",
                            colStats.getColumnName(), spec.prefix, spec.min, spec.max,
                            spec.mcvValues.size(), spec.histogramBounds.size(), tableSize);
                    return generatePrefixIntPatternColumnData(colStats, tableSize, spec);
                } else if (spec != null) {
                    logger.warn("⚠️ [Stage3 prefix_int生成] 列 {} 的 spec 存在但缺少必要数据 (mcvValues={}, histogramBounds={})，回退到默认逻辑",
                            colStats.getColumnName(), (spec.mcvValues != null), (spec.histogramBounds != null));
                }
            } catch (Exception e) {
                logger.warn("⚠️ [Stage3 prefix_int生成] prefix_int 专用生成路径失败，回退到默认逻辑，列={}, 原因={}", colStats.getColumnName(), e.getMessage(), e);
            }
        }

        if (isBpchar) {
            logger.debug("检测到bpchar类型列 {}，将使用去空格策略", colStats.getColumnName());
        }

        // 1. 检查MCV
        logger.debug("检查列 {} 的MCV数据", colStats.getColumnName());
        logger.debug("MCV值列表: {}", colStats.getMostCommonValues());
        logger.debug("MCV频率列表: {}", colStats.getMostCommonFrequencies());
        
        if (colStats.getMostCommonValues() != null && !colStats.getMostCommonValues().isEmpty()) {
            List<String> mcvValues = colStats.getMostCommonValues();
            List<Double> mcvFreqs = colStats.getMostCommonFrequencies();

            if (mcvFreqs != null && !mcvFreqs.isEmpty()) {
                double totalFreq = mcvFreqs.stream().mapToDouble(Double::doubleValue).sum();
                logger.debug("检测到MCV数据 ({} 个值)，频率总和: {}", mcvValues.size(), totalFreq);

                if (totalFreq >= 0.2) {
                    int maxLength = getColumnMaxLength(colStats);
                    
                    // ★★★ 如果频率总和 < 1.0，需要混合生成：MCV + 随机生成 ★★★
                    if (totalFreq < 1.0) {
                        logger.debug("列:{} 频率总和={} < 1.0，使用混合生成策略（MCV + 随机生成）", 
                                   colStats.getColumnName(), totalFreq);
                        return generateVarcharFromMCVWithRandomFill(mcvValues, mcvFreqs, tableSize, maxLength, 
                                                                    colStats, isBpchar, totalFreq);
                    } else {
                        // 频率总和 >= 1.0，完全使用MCV生成
                        logger.debug("列:{} 频率总和={} >= 1.0，使用MCV按频率采样生成varchar数据", 
                                   colStats.getColumnName(), totalFreq);
                        return generateVarcharFromMCV(mcvValues, mcvFreqs, tableSize, maxLength);
                    }
                } else if (totalFreq < 0.2 && totalFreq > 0) {
                    // ★★★ 修复：即使频率总和 < 0.2，如果MCV存在（可能是从cdfConstraints.json添加的ADD_MCV），也应该使用 ★★★
                    // 因为这是为了满足约束而添加的值，必须生成。使用混合生成策略：MCV + 随机填充
                    logger.debug("列:{} 频率总和={} < 0.2，但MCV存在（可能是ADD_MCV约束），使用混合生成策略", 
                               colStats.getColumnName(), totalFreq);
                    int maxLength = getColumnMaxLength(colStats);
                    return generateVarcharFromMCVWithRandomFill(mcvValues, mcvFreqs, tableSize, maxLength, 
                                                                colStats, isBpchar, totalFreq);
                } else if (totalFreq == 0) {
                    logger.debug("列:{} 频率总和=0，不使用MCV数据",colStats.getColumnName());
                }
            } else {
                logger.debug("列 {} 的MCV频率列表为空", colStats.getColumnName());
            }
        } else {
            logger.debug("列 {} 没有MCV数据可用", colStats.getColumnName());
        }

        // 2. 检查直方图
        if (colStats.getHistogramBounds() != null && !colStats.getHistogramBounds().isEmpty()) {
            List<String> bounds = colStats.getHistogramBounds();
            logger.debug("列:{} 检测到直方图边界数据 ({} 个边界)", colStats.getColumnName(),bounds.size());
            
            // 2.1 优先检测是否为数值型字符串列（如asset_no等编号列）
            if (!isBpchar && isNumericStringColumn(colStats)) {
                logger.debug("列:{} 检测到数值型字符串列，使用数值型字符串生成策略", colStats.getColumnName());
                return generateNumericStringFromHistogram(colStats, tableSize, bounds);
            }
            
            // 2.2 有直方图，且边界数量等于表大小
            if (bounds.size() == tableSize) {
                logger.debug("列:{} 边界数量等于表大小 ({}=={})，直接使用边界值生成varchar/bpchar数据", colStats.getColumnName(),bounds.size(), tableSize);
                return generateVarcharFromHistogramBounds(colStats, bounds);
            }
            // 2.3 如果有直方图，且列的类型是bpchar，则按照直方图建模bucket生成
            if (isBpchar) {
                logger.debug("列:{} bpchar类型列且有直方图边界，使用bucket建模生成数据", colStats.getColumnName());
                return generateBpcharFromHistogramBuckets(colStats, tableSize, bounds);
            }
            // 2.4 如果是 varchar，且直方图边界 != 表大小，则使用词频生成
            if (!isBpchar && bounds.size() != tableSize) {
                // 2.4.1 检测是否为有规律的varchar（如Customer#000000001格式）
                if (isPatternedVarchar(colStats)) {
                    logger.debug("列:{} 检测到有规律的varchar格式，使用模式生成", colStats.getColumnName());
                    return generatePatternedVarcharData(colStats, tableSize);
                }
                // 2.4.2 其他情况使用词频生成
                logger.debug("列:{} varchar类型列且直方图边界不等于表大小，则使用词频生成", colStats.getColumnName());
                return generateVarcharFromWordFrequency(colStats, tableSize, bounds);
            }
        } else {
            logger.debug("没有直方图边界数据");
        }

        // 3. 其余情况随机生成
        logger.debug("使用随机生成策略生成varchar/bpchar数据");
        return generateRandomVarcharData(colStats, tableSize, isBpchar);
    }

    /**
     * prefix_int 模式列生成：按“虚拟后缀 int 列”的统计信息与约束生成后缀，再拼回字符串。
     *
     * 约束来源：distribution/cdfConstraints.json（若存在该列）
     * - EQ：强制点频率（精确计数分配）
     * - GE/GT/LE/LT：强制范围选择率（按二段式分配，先扣除已固定的EQ行）
     *
     * 注意：这里不依赖 colStats.getMostCommonFrequencies() 的 IPF 归一化结果，完全以 varcharpatterns.json 的 virtualStats 为准。
     */
    private Object[] generatePrefixIntPatternColumnData(EnhancedColumnStatistics colStats,
                                                        long tableSize,
                                                        VarcharPatternManager.PrefixIntSpec spec) throws Exception {
        String columnName = colStats.getColumnName();
        int min = spec.min != null ? spec.min : (spec.histogramBounds.get(0));
        int max = spec.max != null ? spec.max : (spec.histogramBounds.get(spec.histogramBounds.size() - 1));
        if (max < min) {
            int tmp = min; min = max; max = tmp;
        }
        logger.info("📊 [Stage3 prefix_int生成] 列: {}, 使用后缀范围: [{}, {}]", columnName, min, max);

        // 1) 读取 cdfConstraints.json（可选）
        Map<String, Object> colConstraints = loadCdfConstraintsForColumn(columnName);
        if (colConstraints != null) {
            logger.info("📊 [Stage3 prefix_int生成] 列: {}, 从 cdfConstraints.json 加载了 {} 个约束", columnName, colConstraints.size());
        } else {
            logger.info("📊 [Stage3 prefix_int生成] 列: {}, 未找到 cdfConstraints.json 约束，将按虚拟统计信息生成", columnName);
        }

        // 2) 固定 EQ 行
        // value(string) -> count
        Map<Integer, Integer> fixedEqCounts = new HashMap<>();
        int fixedTotal = 0;
        if (colConstraints != null) {
            for (Map.Entry<String, Object> e : colConstraints.entrySet()) {
                String rawValue = e.getKey();
                if (!(e.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> vc = (Map<String, Object>) e.getValue();
                String op = String.valueOf(vc.get("operator"));
                if (!"EQ".equalsIgnoreCase(op)) continue;
                String selStr = String.valueOf(vc.get("selectivity"));
                double sel = Double.parseDouble(selStr);
                Integer suffix = VarcharPatternManager.parsePrefixIntSuffix(rawValue, spec);
                if (suffix == null) continue;
                int cnt = (int) Math.round(sel * tableSize);
                if (cnt <= 0) continue;
                fixedEqCounts.merge(suffix, cnt, Integer::sum);
                fixedTotal += cnt;
            }
        }
        if (fixedTotal > tableSize) {
            // 防御：缩放回 tableSize
            double scale = (double) tableSize / (double) fixedTotal;
            logger.warn("⚠️ [Stage3 prefix_int生成] 列: {}, EQ固定行数 {} > 表大小 {}，缩放因子: {}", columnName, fixedTotal, tableSize, scale);
            fixedTotal = 0;
            for (Map.Entry<Integer, Integer> e : new HashMap<>(fixedEqCounts).entrySet()) {
                int cnt = (int) Math.round(e.getValue() * scale);
                if (cnt <= 0) fixedEqCounts.remove(e.getKey());
                else fixedEqCounts.put(e.getKey(), cnt);
            }
            for (int c : fixedEqCounts.values()) fixedTotal += c;
        }
        if (!fixedEqCounts.isEmpty()) {
            logger.info("📊 [Stage3 prefix_int生成] 列: {}, EQ固定值: {} 个，总行数: {}", columnName, fixedEqCounts.size(), fixedTotal);
            for (Map.Entry<Integer, Integer> e : fixedEqCounts.entrySet()) {
                logger.info("  - 后缀: {}, 值: {}, 行数: {}", e.getKey(), spec.formatValue(e.getKey()), e.getValue());
            }
        }

        // 3) 处理一个范围约束（目前只取“最强”的那个；SSB p_brand 场景足够）
        // 对于多个范围约束，需要更复杂的组合/求交，后续再扩展。
        RangeConstraint range = null;
        if (colConstraints != null) {
            for (Map.Entry<String, Object> e : colConstraints.entrySet()) {
                String rawValue = e.getKey();
                if (!(e.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> vc = (Map<String, Object>) e.getValue();
                String op = String.valueOf(vc.get("operator"));
                if (!("GE".equalsIgnoreCase(op) || "GT".equalsIgnoreCase(op) || "LE".equalsIgnoreCase(op) || "LT".equalsIgnoreCase(op))) {
                    continue;
                }
                String selStr = String.valueOf(vc.get("selectivity"));
                double sel = Double.parseDouble(selStr);
                Integer suffix = VarcharPatternManager.parsePrefixIntSuffix(rawValue, spec);
                if (suffix == null) continue;
                RangeConstraint cand = new RangeConstraint(op.toUpperCase(), suffix, sel);
                if (range == null) range = cand;
                else {
                    // 选择“更强”的（更小的选择率通常更强；同样选择率取更大阈值）
                    if (cand.selectivity < range.selectivity) range = cand;
                }
            }
        }
        if (range != null) {
            logger.info("📊 [Stage3 prefix_int生成] 列: {}, 范围约束: {} {}, 阈值后缀: {}, 值: {}, 目标选择率: {}",
                    columnName, range.op, range.threshold, range.threshold, spec.formatValue(range.threshold), range.selectivity);
        } else {
            logger.info("📊 [Stage3 prefix_int生成] 列: {}, 无范围约束，将按虚拟统计信息（MCV+均匀）生成", columnName);
        }

        // 4) 生成数组：先放 EQ 固定值，再按范围约束分配剩余
        Object[] data = new Object[(int) tableSize];
        int idx = 0;
        for (Map.Entry<Integer, Integer> e : fixedEqCounts.entrySet()) {
            String s = spec.formatValue(e.getKey());
            for (int i = 0; i < e.getValue() && idx < data.length; i++) {
                data[idx++] = s;
            }
        }

        long remaining = tableSize - idx;
        if (remaining <= 0) {
            logger.info("✅ [Stage3 prefix_int生成] 列: {}，全部由 EQ 固定值覆盖，行数={}", columnName, tableSize);
            return data;
        }

        if (range == null) {
            // 无范围约束：按虚拟 stats 混合生成（MCV + uniform）
            logger.info("📊 [Stage3 prefix_int生成] 列: {}，无范围约束，剩余 {} 行按虚拟统计信息（MCV+均匀）生成", columnName, remaining);
            fillPrefixIntByVirtualStats(data, idx, spec, min, max);
            logger.info("✅ [Stage3 prefix_int生成] 列: {}，生成完成，总行数: {}", columnName, tableSize);
            return data;
        }

        // 有范围约束：二段式生成满足范围选择率
        int totalTailTarget = (int) Math.round(range.selectivity * tableSize);
        logger.info("📊 [Stage3 prefix_int生成] 列: {}，范围约束目标: {} 行应满足 {} {} {} (选择率: {})",
                columnName, totalTailTarget, range.op, range.threshold, spec.formatValue(range.threshold), range.selectivity);

        // 已经固定的 EQ 里，有多少落在 tail 集合
        int alreadyTail = 0;
        for (Map.Entry<Integer, Integer> e : fixedEqCounts.entrySet()) {
            if (range.matches(e.getKey())) alreadyTail += e.getValue();
        }
        int needTail = Math.max(0, totalTailTarget - alreadyTail);
        int tailToGen = Math.min((int) remaining, needTail);
        int headToGen = (int) remaining - tailToGen;
        logger.info("📊 [Stage3 prefix_int生成] 列: {}，EQ固定值中已有 {} 行满足范围，还需生成 tail: {} 行, head: {} 行",
                columnName, alreadyTail, tailToGen, headToGen);

        // 先生成 tail（确保在有效范围内）
        int tailMin = Math.max(min, range.tailMin(min));
        int tailMax = Math.min(max, range.tailMax(max));
        if (tailMin > tailMax) {
            logger.warn("⚠️ [Stage3 prefix_int生成] 列: {}，tail范围无效 [{}, {}]，使用默认范围 [{}, {}]", columnName, tailMin, tailMax, min, max);
            tailMin = min;
            tailMax = max;
        }
        // ★★★ 修复：排除已固定的 EQ 值（MCV 值），避免重复生成 ★★★
        Set<Integer> fixedEqSuffixes = new HashSet<>(fixedEqCounts.keySet());
        logger.info("📊 [Stage3 prefix_int生成] 列: {}，生成 tail 行: 后缀范围 [{}, {}] (值范围: [{}, {}])，排除已固定的 EQ 值: {}", 
                columnName, tailMin, tailMax, spec.formatValue(tailMin), spec.formatValue(tailMax), fixedEqSuffixes);
        for (int i = 0; i < tailToGen && idx < data.length; i++) {
            int n = sampleInRange(tailMin, tailMax);
            // ★★★ 避免生成已固定的 EQ 值（MCV 值可能落在 tail 范围内）★★★
            int attempts = 0;
            while (fixedEqSuffixes.contains(n) && attempts < 100) {
                n = sampleInRange(tailMin, tailMax);
                attempts++;
            }
            // 如果尝试100次后还是命中固定值，跳过这个值（概率极低）
            if (fixedEqSuffixes.contains(n)) {
                logger.warn("⚠️ [Stage3 prefix_int生成] 列: {}，生成 tail 时多次命中固定 EQ 值 {}，跳过", columnName, n);
                continue;
            }
            data[idx++] = spec.formatValue(n);
        }
        // 再生成 head（非 tail，确保在有效范围内）
        // ★★★ 关键修复：排除已固定的 EQ 值，避免重复生成 ★★★
        // 注意：fixedEqSuffixes 已在生成 tail 时定义，这里直接使用
        logger.info("📊 [Stage3 prefix_int生成] 列: {}，生成 head 行: 后缀范围 [{}, {}] 排除 tail 和已固定的 EQ 值: {}", 
                columnName, min, max, fixedEqSuffixes);
        for (int i = 0; i < headToGen && idx < data.length; i++) {
            int n = sampleOutOfTail(range, min, max);
            // 确保生成的值在有效范围内
            n = Math.max(min, Math.min(max, n));
            // ★★★ 避免生成已固定的 EQ 值（可能不在虚拟 MCV 中，但已在 EQ 固定值中生成）★★★
            int attempts = 0;
            while (fixedEqSuffixes.contains(n) && attempts < 100) {
                n = sampleOutOfTail(range, min, max);
                n = Math.max(min, Math.min(max, n));
                attempts++;
            }
            // 如果尝试100次后还是命中固定值，跳过这个值（概率极低）
            if (fixedEqSuffixes.contains(n)) {
                logger.warn("⚠️ [Stage3 prefix_int生成] 列: {}，生成 head 时多次命中固定 EQ 值 {}，跳过", columnName, n);
                continue;
            }
            data[idx++] = spec.formatValue(n);
        }

        logger.info("✅ [Stage3 prefix_int生成] 列: {}，生成完成，总行数: {}, EQ固定: {}, tail: {}, head: {}",
                columnName, tableSize, fixedTotal, tailToGen, headToGen);
        return data;
    }

    private int sampleInRange(int lo, int hi) {
        if (hi < lo) return lo;
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private int sampleOutOfTail(RangeConstraint range, int min, int max) {
        // 目前按“从 complement 区间均匀采样”
        if (range.op.equals("GE") || range.op.equals("GT")) {
            int hi = Math.min(max, range.threshold - 1);
            return sampleInRange(min, hi);
        } else {
            int lo = Math.max(min, range.threshold + 1);
            return sampleInRange(lo, max);
        }
    }

    private void fillPrefixIntByVirtualStats(Object[] data, int startIdx,
                                             VarcharPatternManager.PrefixIntSpec spec,
                                             int min, int max) {
        // 简化：用“MCV + uniform 填充”，MCV 频率来自 varcharpatterns 的虚拟统计
        List<Integer> mcv = spec.mcvValues;
        List<Double> freqs = spec.mcvFrequencies;
        double total = 0.0;
        int n = Math.min(mcv.size(), freqs.size());
        for (int i = 0; i < n; i++) total += freqs.get(i);
        total = Math.max(0.0, Math.min(0.999999, total)); // 保留随机填充空间

        int idx = startIdx;
        long tableSize = data.length;
        // 先按频率分配 MCV 行数
        for (int i = 0; i < n && idx < data.length; i++) {
            int cnt = (int) Math.round(freqs.get(i) * tableSize);
            String s = spec.formatValue(mcv.get(i));
            for (int j = 0; j < cnt && idx < data.length; j++) data[idx++] = s;
        }
        // 剩余均匀
        while (idx < data.length) {
            int v = sampleInRange(min, max);
            data[idx++] = spec.formatValue(v);
        }
    }

    private Map<String, Object> loadCdfConstraintsForColumn(String canonicalColumnName) {
        try {
            String resultDir = ColumnManager.getInstance().getResultDirPath();
            File f = null;
            if (resultDir != null) {
                f = new File(resultDir + "/distribution/cdfConstraints.json");
            }
            if (f == null || !f.exists()) {
                f = new File(System.getProperty("user.dir") + "/distribution/cdfConstraints.json");
            }
            if (!f.exists()) return null;
            String content = CommonUtils.readFile(f.getAbsolutePath());
            Map<String, Object> root = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {});
            Object colObj = root.get(canonicalColumnName);
            if (!(colObj instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) colObj;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class RangeConstraint {
        final String op;      // GE/GT/LE/LT
        final int threshold;  // 数值阈值
        final double selectivity; // 目标选择率

        RangeConstraint(String op, int threshold, double selectivity) {
            this.op = op;
            this.threshold = threshold;
            this.selectivity = selectivity;
        }

        boolean matches(int v) {
            return switch (op) {
                case "GE" -> v >= threshold;
                case "GT" -> v > threshold;
                case "LE" -> v <= threshold;
                case "LT" -> v < threshold;
                default -> false;
            };
        }

        int tailMin(int min) {
            return switch (op) {
                case "GE" -> Math.max(min, threshold);
                case "GT" -> Math.max(min, threshold + 1);
                default -> min; // for LE/LT tail is low side
            };
        }

        int tailMax(int max) {
            return switch (op) {
                case "LE" -> Math.min(max, threshold);
                case "LT" -> Math.min(max, threshold - 1);
                default -> max; // for GE/GT tail is high side
            };
        }
    }

    /**
     * 根据MCV生成varchar数据
     * 使用精确计数分配，而不是随机采样，以确保精确匹配期望的频率
     */
    public Object[] generateVarcharFromMCV(List<String> mcvValues, List<Double> mcvFreqs, long tableSize) {
        // 委托给带maxLength的版本，传入Integer.MAX_VALUE表示不限制长度
        return generateVarcharFromMCV(mcvValues, mcvFreqs, tableSize, Integer.MAX_VALUE);
    }

    /**
     * 根据MCV生成varchar数据（带长度约束）
     * 使用精确计数分配，而不是随机采样，以确保精确匹配期望的频率
     */
    public Object[] generateVarcharFromMCV(List<String> mcvValues, List<Double> mcvFreqs, long tableSize, int maxLength) {
        Object[] data = new Object[(int) tableSize];
        
        // ★★★ 使用精确计数分配，确保每个MCV值的行数精确匹配期望频率 ★★★
        List<Integer> rowCounts = new ArrayList<>();
        long totalAllocated = 0;
        
        // 计算每个MCV值应该生成多少行（使用累积误差修正算法）
        double accumulatedError = 0.0;
        for (int i = 0; i < mcvValues.size(); i++) {
            double freq = mcvFreqs.get(i);
            double exactCount = tableSize * freq;
            
            // 累积误差修正（类似Bresenham算法）
            double adjustedCount = exactCount + accumulatedError;
            long roundedCount = Math.round(adjustedCount);
            accumulatedError = adjustedCount - roundedCount;
            
            rowCounts.add((int) roundedCount);
            totalAllocated += roundedCount;
            
            // logger.debug("MCV值[{}] '{}' 频率={}, 精确count={}, 分配行数={}", 
            //             i, mcvValues.get(i), freq, exactCount, roundedCount);
        }
        
        // 调整行数分配，确保总和等于tableSize（处理累积误差）
        long difference = tableSize - totalAllocated;
        if (difference != 0 && !rowCounts.isEmpty()) {
            // 将差异加到最大的那个值上（或从最大的值减去）
            int maxIndex = 0;
            int maxCount = rowCounts.get(0);
            for (int i = 1; i < rowCounts.size(); i++) {
                if (rowCounts.get(i) > maxCount) {
                    maxCount = rowCounts.get(i);
                    maxIndex = i;
                }
            }
            int oldCount = rowCounts.get(maxIndex);
            rowCounts.set(maxIndex, (int) (rowCounts.get(maxIndex) + difference));
            logger.debug("调整行数分配，差异={}, 调整MCV值[{}] '{}' 的行数: {} -> {}", 
                        difference, maxIndex, mcvValues.get(maxIndex), oldCount, rowCounts.get(maxIndex));
        }
        
        // 生成MCV值数据
        int dataIndex = 0;
        for (int i = 0; i < mcvValues.size(); i++) {
            String value = mcvValues.get(i);
            int count = rowCounts.get(i);
            String constrainedValue = applyLengthConstraint(value, maxLength);
            for (int j = 0; j < count; j++) {
                data[dataIndex++] = constrainedValue;
            }
            // logger.debug("为MCV值 '{}' 生成了 {} 行", value, count);
        }
        
        // 随机打乱数组，使MCV值随机分布
        // shuffleArray(data);
        
        logger.debug("基于MCV生成了 {} 个varchar值，最大长度约束: {}", tableSize, maxLength);
        return data;
    }
    
    /**
     * 根据MCV生成varchar数据，频率总和 < 1.0 时，剩余部分用随机生成填充
     * 
     * @param mcvValues MCV值列表
     * @param mcvFreqs MCV频率列表
     * @param tableSize 表大小
     * @param maxLength 最大长度约束
     * @param colStats 列统计信息（用于随机生成）
     * @param isBpchar 是否为bpchar类型
     * @param totalFreq MCV频率总和
     * @return 生成的数据数组
     */
    public Object[] generateVarcharFromMCVWithRandomFill(List<String> mcvValues, List<Double> mcvFreqs, 
                                                         long tableSize, int maxLength,
                                                         EnhancedColumnStatistics colStats, boolean isBpchar, 
                                                         double totalFreq) {
        Object[] data = new Object[(int) tableSize];
        
        // ★★★ 修复：直接按频率分配总行数，避免多次round导致的累积舍入误差 ★★★
        // 计算MCV总行数（基于totalFreq）
        long mcvRowCountLong = Math.round(tableSize * totalFreq);
        long randomRowCountLong = tableSize - mcvRowCountLong;
        
        // 安全性检查：确保不会超过表大小
        if (mcvRowCountLong < 0) mcvRowCountLong = 0;
        if (randomRowCountLong < 0) randomRowCountLong = 0;
        if (mcvRowCountLong + randomRowCountLong > tableSize) {
            mcvRowCountLong = tableSize - randomRowCountLong;
        }
        
        int mcvRowCount = (int) mcvRowCountLong;
        int randomRowCount = (int) randomRowCountLong;
        
        logger.debug("MCV频率总和: {}, 表大小: {}, MCV行数: {}, 随机行数: {}", 
                   totalFreq, tableSize, mcvRowCount, randomRowCount);
        
        // ★★★ 第一步：按频率比例分配每个MCV值的行数，基于mcvRowCount而不是tableSize ★★★
        List<Integer> rowCounts = new ArrayList<>();
        int allocatedRows = 0;
        
        // 计算每个MCV值应该生成多少行（相对于总MCV行数的比例）
        for (int i = 0; i < mcvValues.size(); i++) {
            double freq = mcvFreqs.get(i);
            // 重要：计算行数时除以totalFreq，这样频率会相对于MCV总体计算
            int rowCount = (int) Math.round(mcvRowCount * freq / totalFreq);
            rowCounts.add(rowCount);
            allocatedRows += rowCount;
            //logger.debug("MCV值[{}] '{}' 频率={}, 分配行数={}", i, mcvValues.get(i), freq, rowCount);
        }
        
        // 调整行数分配，确保总和等于 mcvRowCount（处理四舍五入误差）
        int difference = mcvRowCount - allocatedRows;
        if (difference != 0) {
            // 将差异加到最后一个值上（或从最后一个值减去）
            int lastIndex = Math.max(0, rowCounts.size() - 1);
            int oldCount = rowCounts.get(lastIndex);
            rowCounts.set(lastIndex, oldCount + difference);
            logger.debug("调整行数分配，差异={}, 调整MCV值[{}]的行数: {} -> {}", 
                       difference, lastIndex, oldCount, rowCounts.get(lastIndex));
        }
        
        // 生成MCV值数据
        int dataIndex = 0;
        for (int i = 0; i < mcvValues.size(); i++) {
            String value = mcvValues.get(i);
            int count = rowCounts.get(i);
            String constrainedValue = applyLengthConstraint(value, maxLength);
            for (int j = 0; j < count; j++) {
                data[dataIndex++] = constrainedValue;
            }
            //logger.debug("为MCV值 '{}' 生成了 {} 行", value, count);
        }
        
        // 第二步：剩余行用随机生成填充
        if (randomRowCount > 0) {
            Object[] randomData = generateRandomVarcharData(colStats, randomRowCount, isBpchar);
            System.arraycopy(randomData, 0, data, mcvRowCount, randomRowCount);
            logger.debug("使用随机生成填充了 {} 行数据", randomRowCount);
        }
        
        // 第三步：随机打乱数组，使MCV值和随机值混合分布
        shuffleArray(data);
        
        logger.debug("混合生成完成：MCV行数={}, 随机行数={}, 总行数={}", 
                   mcvRowCount, randomRowCount, tableSize);
        return data;
    }
    
    /**
     * 随机打乱数组
     */
    private void shuffleArray(Object[] array) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Object temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }
    }

    /**
     * 直接使用直方图边界值生成varchar数据
     */
    public Object[] generateVarcharFromHistogramBounds(List<String> histogramBounds) {
        Object[] data = new Object[histogramBounds.size()];
        for (int i = 0; i < histogramBounds.size(); i++) {
            data[i] = histogramBounds.get(i);
        }
        logger.debug("使用直方图边界生成了 {} 个varchar值", data.length);
        return data;
    }

    /**
     * 直接使用直方图边界值生成varchar数据（带长度约束）
     */
    public Object[] generateVarcharFromHistogramBounds(EnhancedColumnStatistics colStats, List<String> histogramBounds) {
        int maxLength = getColumnMaxLength(colStats);
        Object[] data = new Object[histogramBounds.size()];
        for (int i = 0; i < histogramBounds.size(); i++) {
            String value = histogramBounds.get(i);
            data[i] = applyLengthConstraint(value, maxLength);
        }
        logger.debug("使用直方图边界生成了 {} 个varchar值，最大长度约束: {}", data.length, maxLength);
        return data;
    }

    /**
     * 随机生成varchar/bpchar数据
     * 根据数据模式信息生成，保证长度范围和平均长度
     * 
     * @param colStats 列统计信息
     * @param tableSize 表大小
     * @param isBpchar 是否为bpchar类型（如果是，则不去除尾部空格）
     */
    public Object[] generateRandomVarcharData(EnhancedColumnStatistics colStats, long tableSize, boolean isBpchar) {
        Object[] data = new Object[(int) tableSize];
        
        // 获取列的最大长度约束
        int columnMaxLength = getColumnMaxLength(colStats);
        
        // 解析数据模式以获取长度信息
        int minLength = 1;
        int maxLength = Math.min(50, columnMaxLength); // 默认最大长度，但不超过列约束
        int avgWidth = 10;  // 默认平均长度
        
        String dataPattern = colStats.getDataPattern();
        if (dataPattern != null && !dataPattern.isEmpty()) {
            // 解析类似 "max_length=152,avg_width=67" 的模式
            if (dataPattern.contains("max_length=")) {
                try {
                    String maxLenStr = dataPattern.substring(dataPattern.indexOf("max_length=") + 11);
                    maxLenStr = maxLenStr.split(",")[0].trim();
                    int parsedMaxLength = Integer.parseInt(maxLenStr);
                    maxLength = Math.min(parsedMaxLength, columnMaxLength); // 确保不超过列约束
                } catch (Exception e) {
                    logger.debug("解析max_length失败，使用默认值: {}", e.getMessage());
                }
            }
            
            if (dataPattern.contains("avg_width=")) {
                try {
                    String avgWidthStr = dataPattern.substring(dataPattern.indexOf("avg_width=") + 10);
                    avgWidthStr = avgWidthStr.split(",")[0].trim();
                    avgWidth = Integer.parseInt(avgWidthStr);
                } catch (Exception e) {
                    logger.debug("解析avg_width失败，使用默认值: {}", e.getMessage());
                }
            }
        }
        
        // 如果有avg_width统计信息，优先使用
        if (colStats.getAvgWidth() > 0) {
            avgWidth = colStats.getAvgWidth();
        }
        
        // 调整长度范围，确保平均长度合理且不超过列约束
        if (avgWidth > maxLength) {
            maxLength = Math.min(avgWidth + 10, columnMaxLength);
        }
        if (avgWidth < minLength) {
            minLength = 1;
            avgWidth = Math.max(avgWidth, 1);
        }
        
        logger.debug("varchar/bpchar生成参数: minLength={}, maxLength={}, avgWidth={}, isBpchar={}, columnMaxLength={}", 
                    minLength, maxLength, avgWidth, isBpchar, columnMaxLength);
        
        // 生成随机字符串
        for (int i = 0; i < tableSize; i++) {
            String generatedString = generateRandomString(minLength, maxLength, avgWidth, isBpchar);
            // 应用长度约束
            data[i] = applyLengthConstraint(generatedString, columnMaxLength);
        }
        
        logger.debug("随机生成了 {} 个varchar/bpchar值，长度范围: {}-{}", tableSize, minLength, maxLength);
        return data;
    }

    /**
     * 基于列统计信息生成随机字符串
     * 从 EnhancedColumnStatistics 中提取长度和模式信息
     * 
     * @param colStats 列统计信息
     * @return 生成的随机字符串
     */
    public String generateRandomString(EnhancedColumnStatistics colStats) {
        if (colStats == null) {
            return generateRandomString(1, 10, 5, false); // 默认值
        }
        
        boolean isBpchar = "bpchar".equals(colStats.getDataType());
        int avgWidth = colStats.getAvgWidth() > 0 ? colStats.getAvgWidth() : 5;
        
        // 尝试从 minValue/maxValue 推断长度范围
        int minLength = 1;
        int maxLength = avgWidth * 2;
        
        if (colStats.getMinValue() != null && colStats.getMaxValue() != null) {
            try {
                minLength = Math.max(1, colStats.getMinValue().length());
                maxLength = Math.max(minLength, colStats.getMaxValue().length());
                // 如果最大长度太大，限制一下
                if (maxLength > avgWidth * 3) {
                    maxLength = avgWidth * 2;
                }
            } catch (Exception e) {
                // 如果解析失败，使用默认值
                logger.debug("解析字符串长度失败，使用默认值: {}", e.getMessage());
            }
        }
        
        return generateRandomString(minLength, maxLength, avgWidth, isBpchar);
    }

    /**
     * 获取列的最大长度约束
     */
    private int getColumnMaxLength(EnhancedColumnStatistics colStats) {
        // 优先从dataPattern中解析max_length，这是从数据库元数据中提取的真实长度
        String dataPattern = colStats.getDataPattern();
        if (dataPattern != null && dataPattern.contains("max_length=")) {
            try {
                String maxLenStr = dataPattern.substring(dataPattern.indexOf("max_length=") + 11);
                maxLenStr = maxLenStr.split(",")[0].trim();
                int maxLength = Integer.parseInt(maxLenStr);
                logger.debug("从dataPattern解析到列 {} 的最大长度: {}", colStats.getColumnName(), maxLength);
                return maxLength;
            } catch (Exception e) {
                logger.debug("从dataPattern解析max_length失败: {}", e.getMessage());
            }
        }
        
        // 回退：从数据类型中解析长度，如 varchar(32) -> 32
        String dataType = colStats.getDataType();
        if (dataType.contains("(") && dataType.contains(")")) {
            try {
                int start = dataType.indexOf("(") + 1;
                int end = dataType.indexOf(")");
                int maxLength = Integer.parseInt(dataType.substring(start, end));
                logger.debug("从dataType解析到列 {} 的最大长度: {}", colStats.getColumnName(), maxLength);
                return maxLength;
            } catch (Exception e) {
                logger.debug("解析数据类型长度失败: {}", e.getMessage());
            }
        }
        
        // 默认值
        logger.debug("列 {} 无法解析最大长度，使用默认值50", colStats.getColumnName());
        return 50;
    }

    /**
     * 应用长度约束到生成的字符串
     */
    private String applyLengthConstraint(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    /**
     * 检测是否为数值型字符串列（如编号、ID等）
     */
    private boolean isNumericStringColumn(EnhancedColumnStatistics colStats) {
        // 检查列名模式
        String columnName = colStats.getColumnName().toLowerCase();
        if (columnName.contains("no") || columnName.contains("id") || 
            columnName.contains("code") || columnName.contains("number") ||
            columnName.contains("key") || columnName.contains("seq")) {
            return true;
        }
        
        // 检查直方图边界的数据特征
        if (colStats.getHistogramBounds() != null && !colStats.getHistogramBounds().isEmpty()) {
            int numericCount = 0;
            int totalCount = 0;
            
            for (String bound : colStats.getHistogramBounds()) {
                if (bound != null && bound.matches("\\d+")) {
                    numericCount++;
                }
                totalCount++;
            }
            
            // 如果超过60%的边界都是纯数字，认为是数值型字符串
            return totalCount > 0 && (double) numericCount / totalCount >= 0.6;
        }
        
        return false;
    }

    /**
     * 生成随机字符串，长度倾向于平均值
     * 
     * @param minLength 最小长度
     * @param maxLength 最大长度  
     * @param avgWidth 平均长度
     * @param isBpchar 是否为bpchar类型（如果是，则不去除尾部空格）
     * @return 生成的随机字符串
     */
    public String generateRandomString(int minLength, int maxLength, int avgWidth, boolean isBpchar) {
        // 计算目标长度，倾向于平均值
        int targetLength;
        if (ThreadLocalRandom.current().nextDouble() < 0.7) {
            // 70%的概率在平均值附近生成
            int variation = Math.max(1, avgWidth / 4);
            targetLength = Math.max(minLength, Math.min(maxLength, 
                avgWidth + ThreadLocalRandom.current().nextInt(-variation, variation + 1)));
        } else {
            // 30%的概率在整个范围内随机生成
            targetLength = ThreadLocalRandom.current().nextInt(minLength, maxLength + 1);
        }
        
        // 生成字符串
        StringBuilder sb = new StringBuilder(targetLength);
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ";
        
        for (int i = 0; i < targetLength; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        
        // 对于bpchar类型，不去除尾部空格，让数据库自动处理空格补充
        // 对于varchar类型，去除尾部空格
        if (isBpchar) {
            return sb.toString(); // bpchar类型保留原始字符串
        } else {
            return sb.toString().trim(); // varchar类型去除尾部空格
        }
    }

    /**
     * 根据直方图边界为bpchar类型生成bucket数据
     * bpchar类型列一般不是comment这种无意义的列，需要按照直方图建模bucket
     */
    public Object[] generateBpcharFromHistogramBuckets(EnhancedColumnStatistics colStats, long tableSize, List<String> histogramBounds) {
        logger.debug("开始为bpchar类型列 {} 使用直方图bucket生成数据", colStats.getColumnName());
        
        int maxLength = getColumnMaxLength(colStats);
        logger.debug("列 {} 的最大长度约束: {}", colStats.getColumnName(), maxLength);
        
        // 为bpchar类型创建bucket
        List<Bucket> buckets = createHistogramBuckets(colStats, tableSize, histogramBounds);
        // 直接使用基础的bucket生成逻辑，避免递归调用到generateSmartVarcharColumnData
        Object[] data = new Object[(int) tableSize];
        int index = 0;
        for (Bucket bucket : buckets) {
            int count = (int) bucket.getCount();
            // 根据论文逻辑为每个bucket预生成固定的distinct值
            List<Object> distinctValues = generateVarcharDistinctValuesForBucket(bucket, colStats);
            // 循环使用这些distinct值填充count个数据
            for (int i = 0; i < count && index < tableSize; i++) {
                Object value = distinctValues.get(i % distinctValues.size());
                // 应用长度约束
                if (value != null) {
                    value = applyLengthConstraint(value.toString(), maxLength);
                }
                data[index++] = value;
            }
        }
        // 确保数组完全填充
        if (index < tableSize) {
            logger.warn("生成的bpchar数据不足，补充随机数据");
            for (int i = index; i < tableSize; i++) {
                data[i] = generateRandomString(1, maxLength, maxLength/2, true);
            }
        }
        return data;
    }

    /**
     * 根据直方图边界创建bucket
     */
    public List<Bucket> createHistogramBuckets(EnhancedColumnStatistics colStats, long tableSize, List<String> histogramBounds) {
        List<Bucket> buckets = new java.util.ArrayList<>();
        if (histogramBounds.size() < 2) {
            logger.warn("直方图边界数量少于2，无法创建bucket");
            return buckets;
        }
        // 计算每个bucket的数据量（等深度分布）
        int numBuckets = histogramBounds.size() - 1;
        long baseCount = tableSize / numBuckets;
        long remainder = tableSize % numBuckets;
        // 估算每个bucket的nDistinct（假设均匀分布）
        double totalNDistinct = Math.abs(colStats.getNDistinct());
        if (totalNDistinct <= 1.0) {
            // 如果nDistinct是负数或比例，转换为绝对数量
            totalNDistinct = totalNDistinct * tableSize;
        }
        // 确保总nDistinct不超过表大小
        totalNDistinct = Math.min(totalNDistinct, tableSize);
        // 计算每个bucket的基础nDistinct
        int baseNDistinct = Math.max(1, (int) Math.ceil(totalNDistinct / numBuckets));
        logger.debug("创建 {} 个bucket，总数据量: {}, 每个bucket基础数据量: {}, 总distinct: {}", numBuckets, tableSize, baseCount, totalNDistinct);
        for (int i = 0; i < numBuckets; i++) {
            String low = histogramBounds.get(i);
            String high = histogramBounds.get(i + 1);
            // 分配数据量，前面的bucket多分配余数
            long count = baseCount + (i < remainder ? 1 : 0);
            // 估算这个bucket的nDistinct
            int nDistinct = Math.min(baseNDistinct, (int)count);
            // 创建Datum对象
            Datum lowDatum = Datum.createVarchar(low);
            Datum highDatum = Datum.createVarchar(high);
            Bucket bucket = new Bucket(lowDatum, highDatum, count, nDistinct, Bucket.BucketType.HISTOGRAM);
            buckets.add(bucket);
            // logger.debug("Bucket {}: [{}, {}], count={}, nDistinct={}", i, low, high, count, nDistinct);
        }
        return buckets;
    }

    /**
     * 为bucket生成distinct值  
     */
    private List<Object> generateVarcharDistinctValuesForBucket(Bucket bucket, EnhancedColumnStatistics colStats) {
        List<Object> distinctValues = new ArrayList<>();
        
        // logger.info("generateVarcharDistinctValuesForBucket调用: 列={}, 数据类型={}, bucket类型={}, low={}, high={}", 
        //             colStats.getColumnName(), colStats.getDataType(), bucket.getType(), 
        //             bucket.getLow(), bucket.getHigh());
        
        if (bucket.getType() == Bucket.BucketType.NULL) {
            // NULL bucket只返回null值
            distinctValues.add(null);
            return distinctValues;
        }
        
        if (bucket.getType() == Bucket.BucketType.MCV) {
            // MCV bucket返回确切的值
            Object mcvValue = convertDatumToObject(bucket.getLow(), colStats.getDataType());
            distinctValues.add(mcvValue);
            return distinctValues;
        }
        
        // Histogram bucket: 将区间分成nDistinct个子区间，每个子区间取固定点
        int nDistinct = Math.max(1, (int) bucket.getNDistinct());
        
        // 如果low和high相同，直接返回该值
        if (bucket.getLow() != null && bucket.getHigh() != null && 
            bucket.getLow().equals(bucket.getHigh())) {
            Object fixedValue = convertDatumToObject(bucket.getLow(), colStats.getDataType());
            distinctValues.add(fixedValue);
            return distinctValues;
        }
        
        // 根据数据类型生成固定的子区间中点值
        Datum.DatumType inferredType = inferDataTypeFromString(colStats.getDataType());
        // logger.info("推断数据类型: {} -> {}", colStats.getDataType(), inferredType);
        
        try {
            switch (inferredType) {
                case VARCHAR -> {
                    // bpchar 和 varchar 都使用 VARCHAR 类型处理
                    if ("bpchar".equals(colStats.getDataType().toLowerCase())) {
                        distinctValues.addAll(generateBpcharDistinctValues(bucket, nDistinct, colStats));
                    } else {
                        distinctValues.addAll(generateVarcharDistinctValues(bucket, nDistinct, colStats));
                    }
                }
                default -> logger.debug("生成bucket distinct值时出错");
            }
        } catch (Exception e) {
            // fallback: 返回bucket的低值
            if (bucket.getLow() != null) {
                distinctValues.add(convertDatumToObject(bucket.getLow(), colStats.getDataType()));
            } else {
                distinctValues.add(getDefaultValue(colStats));
            }
        }
        
        // 确保至少有一个值
        if (distinctValues.isEmpty()) {
            distinctValues.add(getDefaultValue(colStats));
        }
        
        return distinctValues;
    }

    /**
     * 生成默认值  
     */
    private Object getDefaultValue(EnhancedColumnStatistics colStats) {
        String dataType = colStats.getDataType().toLowerCase();
        
        if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            return "";
        } else {
            return "";
        }
    }

    /**
     * 推断数据类型  
     */
    private Datum.DatumType inferDataTypeFromString(String dataType) {
        if (dataType == null) return Datum.DatumType.VARCHAR;
        
        String type = dataType.toLowerCase();
        if (type.contains("varchar") || type.contains("char") || type.contains("text")) {
            // bpchar 和 varchar 都映射到 VARCHAR
            return Datum.DatumType.VARCHAR;
        } else { // 出错
            logger.warn("无法推断数据类型: {}, 默认使用NULL", dataType);
            return Datum.DatumType.NULL;
        }
    }

    /**
     * 从Datum对象转换为Java对象  
     */
    private Object convertDatumToObject(Datum datum, String dataType) {
        if (datum == null || datum.isNull()) return null;
        
        try {
            if(inferDataTypeFromString(dataType) == Datum.DatumType.VARCHAR){
                return datum.getValue().toString();
            } else {
                logger.warn("不支持的数据类型转换: {}, 使用字符串值", dataType);
                return datum.toOutputString();
            }
        } catch (Exception e) {
            logger.warn("转换Datum对象失败: {}, 使用字符串值", e.getMessage());
            return datum.toOutputString();
        }
    }

    /**
     * 为varchar类型bucket生成distinct值
     */
    private List<Object> generateVarcharDistinctValues(Bucket bucket, int nDistinct, EnhancedColumnStatistics colStats) {
        List<Object> values = new ArrayList<>();
        
        // 使用字符串生成器生成固定的不同字符串
        // 如果bucket有边界信息，可以考虑使用边界信息影响生成
        for (int i = 0; i < nDistinct; i++) {
            String generatedString = generateRandomString(colStats);
            values.add(generatedString);
        }
        
        return values;
    }

    /**
     * 为bpchar类型bucket生成distinct值
     * 特殊处理类似"Supplier#000000001"这样的模式
     */
    private List<Object> generateBpcharDistinctValues(Bucket bucket, int nDistinct, EnhancedColumnStatistics colStats) {
        List<Object> values = new ArrayList<>();
        // logger.info("generateBpcharDistinctValues调用: 列={}, nDistinct={}, low={}, high={}", 
        //            colStats.getColumnName(), nDistinct, bucket.getLow(), bucket.getHigh());
        
        if (bucket.getLow() == null || bucket.getHigh() == null) {
            // 如果没有边界信息，fallback到普通字符串生成
            logger.debug("没有边界信息，使用fallback字符串生成");
            for (int i = 0; i < nDistinct; i++) {
                String generatedString = generateRandomString(colStats);
                values.add(generatedString);
            }
            return values;
        }
        
        String lowStr = bucket.getLow().getValue().toString();
        String highStr = bucket.getHigh().getValue().toString();
        
        //logger.info("提取的边界字符串: low='{}', high='{}'", lowStr, highStr);
        
        // 检测是否是类似"Supplier#000000001"的模式
        if (isSequentialPattern(lowStr, highStr)) {
            if(colStats.getColumnName().equals("o_clerk")) {
                logger.info("检测到序列模式，从 {} 到 {}，生成 {} 个distinct值", lowStr, highStr, nDistinct);
            }
            values.addAll(generateSequentialBpcharValues(lowStr, highStr, nDistinct));
        } else if (isPhoneNumberPattern(lowStr, highStr)) {
            // 检测是否是电话号码模式（如10-102-116-6785）
            // logger.info("检测到电话号码模式，从 {} 到 {}，生成 {} 个distinct值", lowStr, highStr, nDistinct);
            values.addAll(generatePhoneNumberValues(lowStr, highStr, nDistinct));
        } else {
            // 不是特殊模式，使用字符串插值
            logger.info("列 {} 不是特殊模式，使用字符串插值", colStats.getColumnName());
            values.addAll(generateInterpolatedStringValues(lowStr, highStr, nDistinct));
        }
        
        // 确保至少有一个值
        if (values.isEmpty()) {
            logger.info("生成的values为空，使用lowStr作为fallback");
            values.add(lowStr);
        }
        
        // logger.info("最终生成了 {} 个bpchar值: {}", values.size(), values.subList(0, Math.min(5, values.size())));
        
        return values;
    }

    /**
     * 检测是否是序列模式（如Supplier#000000001, Supplier#000000002等）
     */
    private boolean isSequentialPattern(String low, String high) {
        if (low == null || high == null) return false;
        
        // 检查是否都包含'#'分隔符
        if (!low.contains("#") || !high.contains("#")) return false;
        
        // 提取前缀和数字部分
        String[] lowParts = low.split("#");
        String[] highParts = high.split("#");
        
        if (lowParts.length != 2 || highParts.length != 2) return false;
        
        // 检查前缀是否相同
        if (!lowParts[0].equals(highParts[0])) return false;
        
        // 检查数字部分是否都是纯数字
        try {
            // 验证数字部分的有效性
            long lowNum = Long.parseLong(lowParts[1]);
            long highNum = Long.parseLong(highParts[1]);
            // 如果解析成功且都是有效数字，返回true
            return lowNum >= 0 && highNum >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 生成序列模式的bpchar值
     */
    private List<Object> generateSequentialBpcharValues(String low, String high, int nDistinct) {
        List<Object> values = new ArrayList<>();
        
        String[] lowParts = low.split("#");
        String[] highParts = high.split("#");
        
        String prefix = lowParts[0];
        long lowNum = Long.parseLong(lowParts[1]);
        long highNum = Long.parseLong(highParts[1]);
        
        // 获取数字部分的格式（补零位数）
        int digitLength = lowParts[1].length();
        String formatStr = "%0" + digitLength + "d";
        
        // 在范围内均匀分布生成nDistinct个值
        if (highNum <= lowNum) {
            // 边界相同或无效，只返回低值
            values.add(low);
        } else {
            for (int i = 0; i < nDistinct; i++) {
                long targetNum = lowNum + (long) ((double) i / (nDistinct - 1) * (highNum - lowNum));
                String formattedNum = String.format(formatStr, targetNum);
                String generatedValue = prefix + "#" + formattedNum;
                values.add(generatedValue);
            }
        }
        
        return values;
    }

    /**
     * 生成插值字符串值（非序列模式）
     */
    private List<Object> generateInterpolatedStringValues(String low, String high, int nDistinct) {
        List<Object> values = new ArrayList<>();
        // 简单策略：生成介于low和high之间的字符串
        for (int i = 0; i < nDistinct; i++) {
            if (i == 0) {
                values.add(low);
            } else if (i == nDistinct - 1 && nDistinct > 1) {
                values.add(high);
            } else {
                // 生成中间值，这里简化为基于索引的变化
                String interpolated = generateInterpolatedString(low, high, (double) i / (nDistinct - 1));
                values.add(interpolated);
            }
        }
        
        return values;
    }

    /**
     * 生成两个字符串之间的插值
     */
    private String generateInterpolatedString(String low, String high, double ratio) {
        if (ratio <= 0) return low;
        if (ratio >= 1) return high;
        
        // 简单实现：基于字符ASCII值插值
        StringBuilder result = new StringBuilder();
        int maxLen = Math.max(low.length(), high.length());
        
        for (int i = 0; i < maxLen; i++) {
            char lowChar = i < low.length() ? low.charAt(i) : ' ';
            char highChar = i < high.length() ? high.charAt(i) : ' ';
            
            if (lowChar == highChar) {
                result.append(lowChar);
            } else {
                int interpolatedChar = (int) (lowChar + (highChar - lowChar) * ratio);
                result.append((char) Math.max(32, Math.min(126, interpolatedChar))); // 确保在可打印字符范围内
            }
        }
        
        return result.toString().trim();
    }

    /**
     * 根据直方图生成数值型字符串数据
     * 使用bucket生成逻辑：将[low, high]区间划分为nDistinct个子区间，每个子区间取代表值
     */
    public Object[] generateNumericStringFromHistogram(EnhancedColumnStatistics colStats, long tableSize, List<String> histogramBounds) {
        logger.debug("开始为数值型字符串列 {} 使用直方图bucket生成数据", colStats.getColumnName());
        
        int maxLength = getColumnMaxLength(colStats);
        logger.debug("列 {} 的最大长度约束: {}", colStats.getColumnName(), maxLength);
        
        // 数据清洗：清洗直方图边界
        List<String> cleanedBounds = cleanHistogramBounds(histogramBounds, maxLength);
        logger.debug("数据清洗完成，原始边界数: {}, 清洗后边界数: {}", histogramBounds.size(), cleanedBounds.size());
        
        // 为数值型字符串创建bucket
        List<Bucket> buckets = createNumericStringBuckets(colStats, tableSize, cleanedBounds);
        
        Object[] data = new Object[(int) tableSize];
        int index = 0;
        
        for (Bucket bucket : buckets) {
            int count = (int) bucket.getCount();
            long nDistinct = (long) bucket.getNDistinct();
            
            // 为每个bucket生成nDistinct个不同的数值型字符串
            List<Object> distinctValues = generateNumericStringDistinctValues(bucket, nDistinct, maxLength);
            
            // 循环使用这些distinct值填充count个数据
            for (int i = 0; i < count && index < tableSize; i++) {
                Object value = distinctValues.get(i % distinctValues.size());
                data[index++] = value;
            }
        }
        
        // 确保数组完全填充
        if (index < tableSize) {
            logger.warn("生成的数值型字符串数据不足，补充随机数据");
            for (int i = index; i < tableSize; i++) {
                data[i] = generateRandomNumericString(maxLength);
            }
        }
        
        logger.debug("基于直方图生成了 {} 个数值型字符串值", tableSize);
        return data;
    }

    /**
     * 为数值型字符串创建bucket
     */
    private List<Bucket> createNumericStringBuckets(EnhancedColumnStatistics colStats, long tableSize, List<String> histogramBounds) {
        List<Bucket> buckets = new ArrayList<>();
        if (histogramBounds.size() < 2) {
            logger.warn("直方图边界数量少于2，无法创建bucket");
            return buckets;
        }
        
        // 计算每个bucket的数据量（等深度分布）
        int numBuckets = histogramBounds.size() - 1;
        long baseCount = tableSize / numBuckets;
        long remainder = tableSize % numBuckets;
        
        // 估算每个bucket的nDistinct
        double totalNDistinct = Math.abs(colStats.getNDistinct());
        if (totalNDistinct <= 1.0) {
            totalNDistinct = totalNDistinct * tableSize;
        }
        totalNDistinct = Math.min(totalNDistinct, tableSize);
        int baseNDistinct = Math.max(1, (int) Math.ceil(totalNDistinct / numBuckets));
        
        logger.debug("创建 {} 个数值型字符串bucket，总数据量: {}, 每个bucket基础数据量: {}, 总distinct: {}", 
                    numBuckets, tableSize, baseCount, totalNDistinct);
        
        for (int i = 0; i < numBuckets; i++) {
            String low = histogramBounds.get(i);
            String high = histogramBounds.get(i + 1);
            
            // 分配数据量，前面的bucket多分配余数
            long count = baseCount + (i < remainder ? 1 : 0);
            int nDistinct = Math.min(baseNDistinct, (int)count);
            
            // 创建Datum对象
            Datum lowDatum = Datum.createVarchar(low);
            Datum highDatum = Datum.createVarchar(high);
            Bucket bucket = new Bucket(lowDatum, highDatum, count, nDistinct, Bucket.BucketType.HISTOGRAM);
            buckets.add(bucket);
            
            // logger.debug("数值型字符串Bucket {}: [{}, {}], count={}, nDistinct={}", i, low, high, count, nDistinct);
        }
        
        return buckets;
    }

    /**
     * 为数值型字符串bucket生成distinct值
     */
    private List<Object> generateNumericStringDistinctValues(Bucket bucket, long nDistinct, int maxLength) {
        List<Object> distinctValues = new ArrayList<>();
        
        if (bucket.getType() == Bucket.BucketType.NULL) {
            distinctValues.add(null);
            return distinctValues;
        }
        
        if (bucket.getType() == Bucket.BucketType.MCV) {
            Object mcvValue = convertDatumToObject(bucket.getLow(), "varchar");
            distinctValues.add(applyLengthConstraint(mcvValue.toString(), maxLength));
            return distinctValues;
        }
        
        // Histogram bucket: 将区间分成nDistinct个子区间，每个子区间取固定点
        if (bucket.getLow() != null && bucket.getHigh() != null) {
            // 尝试解析为数值，使用BigInteger处理大数值
            try {
                java.math.BigInteger lowVal = new java.math.BigInteger(bucket.getLow().getValue().toString());
                java.math.BigInteger highVal = new java.math.BigInteger(bucket.getHigh().getValue().toString());
                
                // 使用BigInteger进行数值生成
                for (long index = 0; index < nDistinct; index++) {
                    java.math.BigInteger value = generateBigIntegerValueFromSubInterval(lowVal, highVal, index, nDistinct);
                    String formattedValue = value.toString();
                    
                    // 应用长度约束
                    formattedValue = applyLengthConstraint(formattedValue, maxLength);
                    distinctValues.add(formattedValue);
                }
            } catch (NumberFormatException e) {
                // 如果解析失败，使用字符串插值
                logger.debug("无法解析为数值，使用字符串插值: {}", e.getMessage());
                String lowStr = bucket.getLow().getValue().toString();
                String highStr = bucket.getHigh().getValue().toString();
                
                for (long index = 0; index < nDistinct; index++) {
                    String interpolatedValue = generateInterpolatedString(lowStr, highStr, (double) index / nDistinct);
                    interpolatedValue = applyLengthConstraint(interpolatedValue, maxLength);
                    distinctValues.add(interpolatedValue);
                }
            }
        } else {
            // 如果没有边界信息，生成随机数值型字符串
            for (long index = 0; index < nDistinct; index++) {
                distinctValues.add(generateRandomNumericString(maxLength));
            }
        }
        
        // 确保至少有一个值
        if (distinctValues.isEmpty()) {
            distinctValues.add(generateRandomNumericString(maxLength));
        }
        
        return distinctValues;
    }

    /**
     * 根据子区间索引生成BigInteger数值
     * 处理大数值，避免溢出
     */
    private java.math.BigInteger generateBigIntegerValueFromSubInterval(java.math.BigInteger lowVal, java.math.BigInteger highVal, long index, long nDistinct) {
        // 特殊处理：当nDistinct等于区间范围时，直接生成区间内的所有整数值
        java.math.BigInteger range = highVal.subtract(lowVal).add(java.math.BigInteger.ONE);
        if (nDistinct == range.longValue() && range.compareTo(java.math.BigInteger.ZERO) > 0) {
            java.math.BigInteger value = lowVal.add(java.math.BigInteger.valueOf(index));
            if (value.compareTo(highVal) <= 0) {
                return value;
            } else {
                // 如果超出范围，循环使用
                return lowVal.add(java.math.BigInteger.valueOf(index % range.longValue()));
            }
        }
        
        // 使用BigInteger进行精确计算
        java.math.BigInteger rangeBigInt = highVal.subtract(lowVal);
        java.math.BigInteger intervalSize = rangeBigInt.divide(java.math.BigInteger.valueOf(nDistinct));
        java.math.BigInteger subIntervalStart = lowVal.add(intervalSize.multiply(java.math.BigInteger.valueOf(index)));
        java.math.BigInteger subIntervalEnd = lowVal.add(intervalSize.multiply(java.math.BigInteger.valueOf(index + 1)));
        
        // 取子区间中点
        java.math.BigInteger midPoint = subIntervalStart.add(subIntervalEnd.subtract(subIntervalStart).divide(java.math.BigInteger.valueOf(2)));
        
        // 确保结果在范围内
        if (midPoint.compareTo(highVal) > 0) {
            midPoint = highVal;
        } else if (midPoint.compareTo(lowVal) < 0) {
            midPoint = lowVal;
        }
        
        return midPoint;
    }
    
    /**
     * 根据子区间索引生成数值（long版本，保持兼容性）
     * 参考RSGenDataGeneratorRefactored中的generateValueFromSubInterval逻辑
     */
    private long generateNumericValueFromSubInterval(long lowVal, long highVal, long index, long nDistinct) {
        // 特殊处理：当nDistinct等于区间范围时，直接生成区间内的所有整数值
        long range = highVal - lowVal + 1;
        if (nDistinct == range && range > 0) {
            long value = lowVal + index;
            if (value <= highVal) {
                return value;
            } else {
                // 如果超出范围，循环使用
                return lowVal + (index % range);
            }
        }
        
        // 使用浮点数计算避免整数除法精度丢失
        double rangeDouble = (double) (highVal - lowVal);
        double intervalSize = rangeDouble / nDistinct;
        double subIntervalStart = lowVal + (index * intervalSize);
        double subIntervalEnd = lowVal + ((index + 1) * intervalSize);
        // 取子区间中点
        return Math.round(subIntervalStart + (subIntervalEnd - subIntervalStart) / 2.0);
    }

    /**
     * 生成随机数值型字符串
     */
    private String generateRandomNumericString(int maxLength) {
        int length = ThreadLocalRandom.current().nextInt(1, Math.min(maxLength + 1, 20));
        StringBuilder sb = new StringBuilder(length);
        
        // 第一位不能是0
        sb.append(ThreadLocalRandom.current().nextInt(1, 10));
        
        // 其余位可以是0-9
        for (int i = 1; i < length; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(0, 10));
        }
        
        return sb.toString();
    }

    /**
     * 基于直方图边界的词频生成varchar数据
     * 1. 从直方图边界提取词汇
     * 2. 停用词处理
     * 3. 计算词频并归一化
     * 4. 根据词频和平均长度要求生成数据
     * 
     * @param colStats 列统计信息
     * @param tableSize 表大小
     * @param histogramBounds 直方图边界
     * @return 生成的varchar数据数组
     */
    public Object[] generateVarcharFromWordFrequency(EnhancedColumnStatistics colStats, long tableSize, List<String> histogramBounds) {
        logger.debug("开始基于词频生成varchar数据，列: {}, 表大小: {}, 边界数量: {}", 
                    colStats.getColumnName(), tableSize, histogramBounds.size());
        
        // 1. 从直方图边界提取词汇和计算词频
        Map<String, Double> wordFrequencies = extractAndCalculateWordFrequencies(histogramBounds);
        
        // 2. 停用词处理
        Map<String, Double> filteredWordFreqs = removeStopWords(wordFrequencies);
        
        // 3. 归一化词频
        Map<String, Double> normalizedWordFreqs = normalizeWordFrequencies(filteredWordFreqs);
        
        // 4. 获取目标平均长度
        int targetAvgLength = getTargetAverageLength(colStats);
        
        logger.debug("提取到 {} 个原始词汇，过滤后 {} 个词汇，目标平均长度: {}", 
                    wordFrequencies.size(), normalizedWordFreqs.size(), targetAvgLength);
        
        // 5. 根据词频和平均长度生成数据
        Object[] data = generateDataFromWordFrequencies(normalizedWordFreqs, tableSize, targetAvgLength);
        
        // 6. 应用长度约束
        int maxLength = getColumnMaxLength(colStats);
        for (int i = 0; i < data.length; i++) {
            if (data[i] != null) {
                data[i] = applyLengthConstraint(data[i].toString(), maxLength);
            }
        }
        
        logger.debug("基于词频生成了 {} 个varchar值，最大长度约束: {}", tableSize, maxLength);
        return data;
    }
    /**
     * 从直方图边界提取词汇并计算词频
     */
    private Map<String, Double> extractAndCalculateWordFrequencies(List<String> histogramBounds) {
        Map<String, Integer> wordCounts = new HashMap<>();
        int totalWords = 0;
        
        // 定义词分隔符
        String wordSeparators = "[\\s,\\.;:!?\\-_#()\\[\\]{}\"'`~@$%^&*+=|\\\\/<>]+";
        
        for (String bound : histogramBounds) {
            if (bound == null || bound.trim().isEmpty()) {
                continue;
            }
            
            // 分割字符串提取词汇
            String[] words = bound.split(wordSeparators);
            
            for (String word : words) {
                word = word.trim();
                if (word.length() >= 2) { // 只保留长度>=2的词
                    wordCounts.put(word, wordCounts.getOrDefault(word, 0) + 1);
                    totalWords++;
                }
            }
        }
        
        // 转换为频率
        Map<String, Double> frequencies = new HashMap<>();
        for (Map.Entry<String, Integer> entry : wordCounts.entrySet()) {
            double frequency = (double) entry.getValue() / totalWords;
            frequencies.put(entry.getKey(), frequency);
        }
        
        logger.debug("从边界提取到 {} 个词汇，总词数: {}", frequencies.size(), totalWords);
        return frequencies;
    }
    
    /**
     * 停用词处理
     */
    private Map<String, Double> removeStopWords(Map<String, Double> wordFrequencies) {
        // 定义英文停用词列表
        Set<String> stopWords = new HashSet<>(Arrays.asList(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", 
            "on", "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", 
            "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", 
            "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which", 
            "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know", 
            "take", "people", "into", "year", "your", "good", "some", "could", "them", "see", 
            "other", "than", "then", "now", "look", "only", "come", "its", "over", "think", 
            "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", 
            "way", "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
            // 添加一些常见的无意义词汇
            "are", "is", "was", "were", "been", "being", "has", "had", "having", "does", "did", 
            "done", "doing", "will", "would", "could", "should", "may", "might", "must", "shall",
            "very", "too", "much", "many", "more", "most", "less", "few", "little", "big", "small"
        ));
        
        Map<String, Double> filtered = new HashMap<>();
        double removedFrequency = 0.0;
        
        for (Map.Entry<String, Double> entry : wordFrequencies.entrySet()) {
            String word = entry.getKey();
            double freq = entry.getValue();
            
            if (!stopWords.contains(word) && word.length() >= 2) {
                // 过滤掉纯数字词汇（通常不是有意义的词汇）
                if (!word.matches("\\d+")) {
                    filtered.put(word, freq);
                } else {
                    removedFrequency += freq;
                }
            } else {
                removedFrequency += freq;
            }
        }
        
        logger.debug("停用词过滤：移除了频率总和为 {} 的词汇，保留 {} 个词汇", 
                    removedFrequency, filtered.size());
        
        return filtered;
    }
    
    /**
     * 归一化词频
     */
    private Map<String, Double> normalizeWordFrequencies(Map<String, Double> wordFrequencies) {
        if (wordFrequencies.isEmpty()) {
            logger.warn("词频映射为空，返回空的归一化结果");
            return new HashMap<>();
        }
        
        // 计算频率总和
        double totalFreq = wordFrequencies.values().stream().mapToDouble(Double::doubleValue).sum();
        
        if (totalFreq <= 0) {
            logger.warn("词频总和为0，均匀分布词频");
            Map<String, Double> uniform = new HashMap<>();
            double uniformFreq = 1.0 / wordFrequencies.size();
            for (String word : wordFrequencies.keySet()) {
                uniform.put(word, uniformFreq);
            }
            return uniform;
        }
        
        // 归一化
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : wordFrequencies.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / totalFreq);
        }
        
        logger.debug("词频归一化完成，原总频率: {}，归一化后验证: {}", 
                    totalFreq, normalized.values().stream().mapToDouble(Double::doubleValue).sum());
        
        return normalized;
    }
    
    /**
     * 获取目标平均长度
     */
    private int getTargetAverageLength(EnhancedColumnStatistics colStats) {
        int avgLength = 10; // 默认值
        
        // 优先使用统计信息中的平均宽度
        if (colStats.getAvgWidth() > 0) {
            avgLength = colStats.getAvgWidth();
        } else if (colStats.getDataPattern() != null) {
            // 尝试从数据模式中解析
            String pattern = colStats.getDataPattern();
            if (pattern.contains("avg_width=")) {
                try {
                    String avgStr = pattern.substring(pattern.indexOf("avg_width=") + 10);
                    avgStr = avgStr.split(",")[0].trim();
                    avgLength = Integer.parseInt(avgStr);
                } catch (Exception e) {
                    logger.debug("从数据模式解析平均长度失败: {}", e.getMessage());
                }
            }
        }
        
        // 确保平均长度在合理范围内
        avgLength = Math.max(5, Math.min(100, avgLength));
        
        return avgLength;
    }
    
    /**
     * 根据词频和平均长度生成数据
     */
    private Object[] generateDataFromWordFrequencies(Map<String, Double> wordFrequencies, 
                                                   long tableSize, int targetAvgLength) {
        Object[] data = new Object[(int) tableSize];
        
        if (wordFrequencies.isEmpty()) {
            logger.warn("词频为空，使用随机字符串生成");
            for (int i = 0; i < tableSize; i++) {
                data[i] = generateRandomString(1, targetAvgLength * 2, targetAvgLength, false);
            }
            return data;
        }
        
        // 准备词汇列表和累积概率
        List<String> words = new ArrayList<>(wordFrequencies.keySet());
        List<Double> cumulativeProbs = new ArrayList<>();
        double cumulative = 0.0;
        
        for (String word : words) {
            cumulative += wordFrequencies.get(word);
            cumulativeProbs.add(cumulative);
        }
        
        logger.debug("准备了 {} 个词汇用于生成，累积概率: {}", words.size(), cumulative);
        
        // 生成数据
        for (int i = 0; i < tableSize; i++) {
            data[i] = generateStringFromWordFreq(words, cumulativeProbs, targetAvgLength);
        }
        
        return data;
    }
    
    /**
     * 基于词频生成单个字符串
     */
    private String generateStringFromWordFreq(List<String> words, List<Double> cumulativeProbs, int targetAvgLength) {
        StringBuilder result = new StringBuilder();
        int currentLength = 0;
        
        // 生成字符串直到接近目标长度
        while (currentLength < targetAvgLength) {
            // 根据词频选择词汇
            String selectedWord = selectWordByFrequency(words, cumulativeProbs);
            
            // 检查添加这个词是否会超出目标长度太多
            if (currentLength + selectedWord.length() <= targetAvgLength * 1.3) {
                if (result.length() > 0) {
                    result.append(" "); // 添加空格分隔符
                    currentLength++;
                }
                result.append(selectedWord);
                currentLength += selectedWord.length();
            } else {
                // 如果当前字符串太短，添加一个短词或字符
                if (currentLength < targetAvgLength * 0.7) {
                    // 选择最短的词或添加字符
                    String shortestWord = words.stream()
                        .min((w1, w2) -> Integer.compare(w1.length(), w2.length()))
                        .orElse("x");
                    
                    if (currentLength + shortestWord.length() + 1 <= targetAvgLength * 1.2) {
                        if (result.length() > 0) {
                            result.append(" ");
                        }
                        result.append(shortestWord);
                    }
                }
                break;
            }
        }
        
        // 如果生成的字符串太短，用随机字符补充
        if (result.length() < targetAvgLength * 0.5) {
            while (result.length() < targetAvgLength * 0.8) {
                if (result.length() > 0) {
                    result.append(" ");
                }
                // 添加一个随机选择的词
                String randomWord = words.get(ThreadLocalRandom.current().nextInt(words.size()));
                if (result.length() + randomWord.length() <= targetAvgLength * 1.2) {
                    result.append(randomWord);
                } else {
                    break;
                }
            }
        }
        
        return result.toString().trim();
    }
    
    /**
     * 根据累积概率选择词汇
     */
    private String selectWordByFrequency(List<String> words, List<Double> cumulativeProbs) {
        double random = ThreadLocalRandom.current().nextDouble();
        
        for (int i = 0; i < cumulativeProbs.size(); i++) {
            if (random <= cumulativeProbs.get(i)) {
                return words.get(i);
            }
        }
        
        // 如果没有匹配（理论上不应该发生），返回最后一个词
        return words.get(words.size() - 1);
    }

    /**
     * 检测varchar列是否有规律性（如Customer#000000001格式）
     * @param colStats 列统计信息
     * @return 如果有规律则返回true
     */
    private boolean isPatternedVarchar(EnhancedColumnStatistics colStats) {
        if (colStats.getHistogramBounds() == null || colStats.getHistogramBounds().isEmpty()) {
            return false;
        }
        
        List<String> bounds = colStats.getHistogramBounds();
        String minValue = colStats.getMinValue();
        String maxValue = colStats.getMaxValue();
        
        // 检查样本边界值和min/max值是否有相同的模式
        if (minValue == null || maxValue == null) {
            return false;
        }
        
        // 检查是否符合 "前缀#数字" 的模式
        if (isNumberPatternFormat(minValue) && isNumberPatternFormat(maxValue)) {
            // 进一步检查直方图边界是否都符合这个模式
            int samePatternCount = 0;
            for (String bound : bounds) {
                if (isNumberPatternFormat(bound)) {
                    samePatternCount++;
                }
            }
            // 如果超过80%的边界都符合模式，认为是有规律的
            return samePatternCount >= bounds.size() * 0.8;
        }
        
        return false;
    }
    
    /**
     * 检查字符串是否符合 "前缀#数字" 的格式
     */
    private boolean isNumberPatternFormat(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        
        // 查找 # 分隔符
        int hashIndex = value.lastIndexOf('#');
        if (hashIndex < 0 || hashIndex >= value.length() - 1) {
            return false;
        }
        
        String prefix = value.substring(0, hashIndex);
        String suffix = value.substring(hashIndex + 1);
        
        // 检查前缀是否包含字母，后缀是否全是数字
        boolean hasLetter = prefix.matches(".*[a-zA-Z].*");
        boolean isNumber = suffix.matches("\\d+");
        
        return hasLetter && isNumber;
    }
    
    /**
     * 从有规律的varchar数据中提取数字范围并生成数据
     */
    private Object[] generatePatternedVarcharData(EnhancedColumnStatistics colStats, long tableSize) {
        String minValue = colStats.getMinValue();
        String maxValue = colStats.getMaxValue();
        int maxLength = getColumnMaxLength(colStats);
        
        // 提取前缀和数字范围
        int minHashIndex = minValue.lastIndexOf('#');
        int maxHashIndex = maxValue.lastIndexOf('#');
        
        String prefix = minValue.substring(0, minHashIndex + 1);
        
        long minNumber = Long.parseLong(minValue.substring(minHashIndex + 1));
        long maxNumber = Long.parseLong(maxValue.substring(maxHashIndex + 1));
        
        // 根据ndistinct决定生成策略
        if (colStats.getNDistinct() == -1.0) {
            // unique列，从数字范围生成
            logger.info("列 {} 为unique的patterned varchar，从数字范围生成", colStats.getColumnName());
            minNumber = Math.max(1, minNumber);
            maxNumber = Math.max(maxNumber, tableSize);
            
            Object[] data = new Object[(int) tableSize];
            for (int i = 0; i < tableSize; i++) {
                long number = minNumber + i;
                // 保持原有的数字位数格式
                String numberStr = String.format("%0" + String.valueOf(maxNumber).length() + "d", number);
                String value = prefix + numberStr;
                data[i] = applyLengthConstraint(value, maxLength);
            }
            return data;
        } else {
            // 非unique列，按直方图生成
            logger.info("列 {} 为非unique的patterned varchar，按直方图bucket生成", colStats.getColumnName());
            return generatePatternedVarcharFromHistogram(colStats, tableSize, prefix);
        }
    }
    
    /**
     * 根据直方图生成有规律的varchar数据
     */
    private Object[] generatePatternedVarcharFromHistogram(EnhancedColumnStatistics colStats, long tableSize, String prefix) {
        List<String> bounds = colStats.getHistogramBounds();
        Object[] data = new Object[(int) tableSize];
        int maxLength = getColumnMaxLength(colStats);
        
        if (bounds.size() <= 1) {
            return generateRandomVarcharData(colStats, tableSize, false);
        }
        
        int bucketCount = bounds.size() - 1;
        long valuesPerBucket = tableSize / bucketCount;
        long remainingValues = tableSize % bucketCount;
        
        int currentIndex = 0;
        for (int bucketIdx = 0; bucketIdx < bucketCount; bucketIdx++) {
            String lowBound = bounds.get(bucketIdx);
            String highBound = bounds.get(bucketIdx + 1);
            
            // 提取数字范围
            long lowNumber = extractNumberFromPattern(lowBound);
            long highNumber = extractNumberFromPattern(highBound);
            
            long currentBucketSize = valuesPerBucket + (bucketIdx < remainingValues ? 1 : 0);
            
            // 在bucket内生成均匀分布的值
            for (int i = 0; i < currentBucketSize && currentIndex < tableSize; i++) {
                long number = lowNumber + (long) ((double) i / currentBucketSize * (highNumber - lowNumber));
                String numberStr = String.format("%0" + String.valueOf(highNumber).length() + "d", number);
                String value = prefix + numberStr;
                data[currentIndex] = applyLengthConstraint(value, maxLength);
                currentIndex++;
            }
        }
        
        return data;
    }
    
    /**
     * 从有规律的字符串中提取数字部分
     */
    private long extractNumberFromPattern(String value) {
        int hashIndex = value.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex < value.length() - 1) {
            return Long.parseLong(value.substring(hashIndex + 1));
        }
        return 0;
    }

    /**
     * 检测是否是电话号码模式（如10-102-116-6785）
     */
    private boolean isPhoneNumberPattern(String low, String high) {
        if (low == null || high == null) return false;
        
        // 检查是否都包含3个'-'分隔符
        if (countChar(low, '-') != 3 || countChar(high, '-') != 3) return false;
        
        // 检查格式：XX-XXX-XXX-XXXX
        String[] lowParts = low.split("-");
        String[] highParts = high.split("-");
        
        if (lowParts.length != 4 || highParts.length != 4) return false;
        
        // 检查每个部分是否都是数字
        try {
            for (String part : lowParts) {
                Integer.parseInt(part);
            }
            for (String part : highParts) {
                Integer.parseInt(part);
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * 计算字符串中指定字符的出现次数
     */
    private int countChar(String str, char target) {
        int count = 0;
        for (char c : str.toCharArray()) {
            if (c == target) count++;
        }
        return count;
    }
    
    /**
     * 生成电话号码格式的bpchar值
     */
    private List<Object> generatePhoneNumberValues(String low, String high, int nDistinct) {
        List<Object> values = new ArrayList<>();
        
        String[] lowParts = low.split("-");
        String[] highParts = high.split("-");
        
        // 解析各部分的范围
        int[] lowRanges = new int[4];
        int[] highRanges = new int[4];
        
        for (int i = 0; i < 4; i++) {
            lowRanges[i] = Integer.parseInt(lowParts[i]);
            highRanges[i] = Integer.parseInt(highParts[i]);
        }
        
        // 在范围内均匀分布生成nDistinct个值
        if (nDistinct <= 1) {
            values.add(low);
            return values;
        }
        
        for (int i = 0; i < nDistinct; i++) {
            double ratio = (double) i / (nDistinct - 1);
            
            // 为每个部分生成插值
            StringBuilder phoneNumber = new StringBuilder();
            for (int j = 0; j < 4; j++) {
                int interpolatedValue = (int) (lowRanges[j] + (highRanges[j] - lowRanges[j]) * ratio);
                
                // 根据位置确定格式
                if (j == 0) {
                    // 第一部分：2位数字
                    phoneNumber.append(String.format("%02d", interpolatedValue));
                } else if (j == 1 || j == 2) {
                    // 第二、三部分：3位数字
                    phoneNumber.append(String.format("%03d", interpolatedValue));
                } else {
                    // 第四部分：4位数字
                    phoneNumber.append(String.format("%04d", interpolatedValue));
                }
                
                if (j < 3) {
                    phoneNumber.append("-");
                }
            }
            
            values.add(phoneNumber.toString());
        }
        
        return values;
    }
    
    /**
     * 清洗直方图边界数据
     * 处理脏数据，如"zzz18"这种非纯数值的边界
     */
    private List<String> cleanHistogramBounds(List<String> histogramBounds, int maxLength) {
        List<String> cleanedBounds = new ArrayList<>();
        
        for (int i = 0; i < histogramBounds.size(); i++) {
            String bound = histogramBounds.get(i);
            
            if (bound == null) {
                // 处理null值，使用前后边界进行插值
                String cleanedBound = interpolateNullBound(histogramBounds, i, maxLength);
                cleanedBounds.add(cleanedBound);
                continue;
            }
            
            // 检查是否为纯数值
            if (bound.matches("\\d+")) {
                // 纯数值，检查长度约束
                if (bound.length() <= maxLength) {
                    cleanedBounds.add(bound);
                } else {
                    // 长度超限，截断到最大长度
                    String truncated = bound.substring(0, maxLength);
                    cleanedBounds.add(truncated);
                    logger.debug("边界值长度超限，截断: {} -> {}", bound, truncated);
                }
            } else {
                // 非纯数值，尝试修复
                String cleanedBound = repairNonNumericBound(bound, histogramBounds, i, maxLength);
                cleanedBounds.add(cleanedBound);
                logger.debug("修复非数值边界: {} -> {}", bound, cleanedBound);
            }
        }
        
        return cleanedBounds;
    }
    
    /**
     * 修复非数值边界
     */
    private String repairNonNumericBound(String dirtyBound, List<String> histogramBounds, int index, int maxLength) {
        // 尝试提取数字部分
        String numericPart = extractNumericPart(dirtyBound);
        if (!numericPart.isEmpty()) {
            return applyLengthConstraint(numericPart, maxLength);
        }
        
        // 尝试从前后边界推断
        String inferredBound = inferBoundFromNeighbors(histogramBounds, index, maxLength);
        if (inferredBound != null) {
            return inferredBound;
        }
        
        // 生成随机数值作为默认值
        return generateRandomNumericString(maxLength);
    }
    
    /**
     * 提取字符串中的数字部分
     */
    private String extractNumericPart(String value) {
        StringBuilder numericPart = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                numericPart.append(c);
            }
        }
        return numericPart.toString();
    }
    
    /**
     * 从相邻边界推断当前边界值
     */
    private String inferBoundFromNeighbors(List<String> histogramBounds, int index, int maxLength) {
        // 寻找前后最近的数值边界
        String prevNumeric = findPreviousNumericBound(histogramBounds, index);
        String nextNumeric = findNextNumericBound(histogramBounds, index);
        
        if (prevNumeric != null && nextNumeric != null) {
            // 插值计算
            try {
                java.math.BigInteger prev = new java.math.BigInteger(prevNumeric);
                java.math.BigInteger next = new java.math.BigInteger(nextNumeric);
                java.math.BigInteger interpolated = prev.add(next).divide(java.math.BigInteger.valueOf(2));
                return applyLengthConstraint(interpolated.toString(), maxLength);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if (prevNumeric != null) {
            // 只有前一个数值边界，递增
            try {
                java.math.BigInteger prev = new java.math.BigInteger(prevNumeric);
                java.math.BigInteger incremented = prev.add(java.math.BigInteger.ONE);
                return applyLengthConstraint(incremented.toString(), maxLength);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if (nextNumeric != null) {
            // 只有后一个数值边界，递减
            try {
                java.math.BigInteger next = new java.math.BigInteger(nextNumeric);
                java.math.BigInteger decremented = next.subtract(java.math.BigInteger.ONE);
                return applyLengthConstraint(decremented.toString(), maxLength);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }
    
    /**
     * 查找前一个数值边界
     */
    private String findPreviousNumericBound(List<String> histogramBounds, int index) {
        for (int i = index - 1; i >= 0; i--) {
            String bound = histogramBounds.get(i);
            if (bound != null && bound.matches("\\d+")) {
                return bound;
            }
        }
        return null;
    }
    
    /**
     * 查找后一个数值边界
     */
    private String findNextNumericBound(List<String> histogramBounds, int index) {
        for (int i = index + 1; i < histogramBounds.size(); i++) {
            String bound = histogramBounds.get(i);
            if (bound != null && bound.matches("\\d+")) {
                return bound;
            }
        }
        return null;
    }
    
    /**
     * 插值null边界
     */
    private String interpolateNullBound(List<String> histogramBounds, int index, int maxLength) {
        String inferredBound = inferBoundFromNeighbors(histogramBounds, index, maxLength);
        if (inferredBound != null) {
            return inferredBound;
        }
        return generateRandomNumericString(maxLength);
    }
}
