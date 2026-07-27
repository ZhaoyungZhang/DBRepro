package ruc.db.utils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.LanguageManager;
import ruc.db.generator.constraintchain.filter.operation.CompareOperator;
import ruc.db.rsgen.DistributionAdjuster;
import ruc.db.rsgen.EnhancedStatsExtractor;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnCDF;
import ruc.db.schema.EnhancedColumnStatistics;

/**
 * CDF约束应用器：处理 ADD_MCV 和 UPDATE_MCV 约束
 * 
 * JSON 结构：
 * {
 *   "public.part.p_type": {
 *     "VALUE1": { "selectivity": "0.006835", "operator": "EQ", "constraintType": "UPDATE_MCV" },
 *     "VALUE2": { "selectivity": "0.00416", "operator": "LIKE", "constraintType": "ADD_MCV" }
 *   }
 * }
 */
public class CdfConstraintsApplier {
    private static final Logger logger = LoggerFactory.getLogger(CdfConstraintsApplier.class);
    private static final LanguageManager LM = LanguageManager.getInstance();

    /**
     * 将单列 {@code cdfConstraints.json} 的 values 映射合并进列的 {@link ColumnCDF#getParameterConstraint()}，
     * 供数据生成阶段 {@code UniVarFilterOperation.amendParameters} 使用。这里必须覆盖 EQ/NE/LIKE 等全部
     * 查询操作符，否则 UPDATE_MCV 已调整的目标字面量会在生成阶段被 dataIndex 映射覆盖。
     */
    public static void mergeLikeParameterConstraintFromValuesMap(Column column, Map<String, Object> valuesMap) {
        if (column == null || valuesMap == null || column.getColumnCDF() == null) {
            return;
        }
        for (Map.Entry<String, Object> valueEntry : valuesMap.entrySet()) {
            String valueKey = valueEntry.getKey();
            Object valueObj = valueEntry.getValue();
            if (!(valueObj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> valueConstraint = (Map<String, Object>) valueObj;
            String operatorStr = (String) valueConstraint.get("operator");
            if (operatorStr == null) {
                continue;
            }
            CompareOperator op;
            try {
                op = CompareOperator.valueOf(operatorStr.trim());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String selectivityStr = (String) valueConstraint.get("selectivity");
            if (selectivityStr == null || selectivityStr.isBlank()) {
                continue;
            }
            BigDecimal sel;
            try {
                sel = new BigDecimal(selectivityStr);
            } catch (NumberFormatException e) {
                continue;
            }
            ColumnCDF cdf = column.getColumnCDF();
            ColumnCDF.ParameterConstraint pc = cdf.getParameterConstraint();
            if (pc == null) {
                cdf.setParameterConstraint(new ColumnCDF.ParameterConstraint(valueKey, sel, op));
            } else {
                pc.addValue(valueKey, sel, op);
            }
        }
    }
    
    /**
     * 应用约束到统计信息对象（用于 ColumnManager）
     *
     * @param stats 列统计信息对象（会被修改）
     * @param valuesMap 列的所有值约束
     * @return 是否成功应用了约束
     */
    public static boolean applyConstraintToStatistics(EnhancedColumnStatistics stats, Map<String, Object> valuesMap) {
        if (valuesMap == null || valuesMap.isEmpty()) {
            return false;
        }
        
        // 分离 ADD_MCV 和 UPDATE_MCV 约束
        List<Map<String, Object>> addMcvConstraints = new ArrayList<>();
        List<Map<String, Object>> updateMcvConstraints = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : valuesMap.entrySet()) {
            String pattern = entry.getKey();
            Object valueObj = entry.getValue();
            
            if (!(valueObj instanceof Map)) {
                continue;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> valueConstraint = (Map<String, Object>) valueObj;
            String constraintType = (String) valueConstraint.get("constraintType");
            
            valueConstraint.put("_pattern", pattern);
            
            if ("ADD_MCV".equals(constraintType)) {
                addMcvConstraints.add(valueConstraint);
            } else if ("UPDATE_MCV".equals(constraintType)) {
                updateMcvConstraints.add(valueConstraint);
            }
        }
        
        // 先处理 ADD_MCV
        List<String> mcvs = stats.getMostCommonValues() != null ? 
            new ArrayList<>(stats.getMostCommonValues()) : new ArrayList<>();
        List<Double> mcfs = stats.getMostCommonFrequencies() != null ? 
            new ArrayList<>(stats.getMostCommonFrequencies()) : new ArrayList<>();
        
        if (!addMcvConstraints.isEmpty()) {
            applyAddMcvConstraints(mcvs, mcfs, addMcvConstraints, stats.getDataType());
            stats.setMostCommonValues(mcvs);
            stats.setMostCommonFrequencies(mcfs);
            stats.setMcvCount(mcvs.size());
    }

        // 再处理 UPDATE_MCV（使用IPF算法）
        if (!updateMcvConstraints.isEmpty() && !mcvs.isEmpty()) {
            Map<String, Double> adjustedFrequencies = applyUpdateMcvConstraintsForSchema(
                mcvs, mcfs, updateMcvConstraints, stats);
            
            // 更新 stats
            List<String> updatedMcvs = new ArrayList<>();
            List<Double> updatedMcfs = new ArrayList<>();
            for (Map.Entry<String, Double> entry : adjustedFrequencies.entrySet()) {
                updatedMcvs.add(entry.getKey());
                updatedMcfs.add(entry.getValue());
            }
            stats.setMostCommonValues(updatedMcvs);
            stats.setMostCommonFrequencies(updatedMcfs);
            stats.setMcvCount(updatedMcvs.size());
        }
        
        return !addMcvConstraints.isEmpty() || !updateMcvConstraints.isEmpty();
    }

    /**
     * 应用约束并返回调整后的频率映射
     * 先处理 ADD_MCV，再处理 UPDATE_MCV（使用IPF算法）
     * 
     * @param stats 列统计信息对象
     * @param valuesMap 列的所有值约束
     * @return 调整后的频率映射（值 -> 频率），如果没有约束则返回空Map
     */
    public static Map<String, Double> applyConstraintsAndGetAdjustedFrequencies(
            EnhancedStatsExtractor.EnhancedColumnStatistics stats, Map<String, Object> valuesMap) {
        if (valuesMap == null || valuesMap.isEmpty()) {
            return new HashMap<>();
        }

        // 分离 ADD_MCV 和 UPDATE_MCV 约束
        List<Map<String, Object>> addMcvConstraints = new ArrayList<>();
        List<Map<String, Object>> updateMcvConstraints = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : valuesMap.entrySet()) {
            String pattern = entry.getKey();
            Object valueObj = entry.getValue();
            
            if (!(valueObj instanceof Map)) {
                continue;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> valueConstraint = (Map<String, Object>) valueObj;
            String constraintType = (String) valueConstraint.get("constraintType");
            
            valueConstraint.put("_pattern", pattern);
            
            if ("ADD_MCV".equals(constraintType)) {
                addMcvConstraints.add(valueConstraint);
            } else if ("UPDATE_MCV".equals(constraintType)) {
                updateMcvConstraints.add(valueConstraint);
            }
        }
        
        // ★★★ 检查是否是 prefix_int 列 ★★★
        ruc.db.utils.VarcharPatternManager.PrefixIntSpec prefixIntSpec = null;
        try {
            prefixIntSpec = ruc.db.utils.VarcharPatternManager.getPrefixIntSpec(stats.getColumnName());
        } catch (Exception ignore) {
            // 不是 prefix_int 列，继续正常流程
        }
        
        // 先处理 ADD_MCV（添加新值到MCV）
        List<String> mcvs = stats.getMostCommonValues() != null ? 
            new ArrayList<>(stats.getMostCommonValues()) : new ArrayList<>();
        List<Double> mcfs = stats.getMostCommonFrequencies() != null ? 
            new ArrayList<>(stats.getMostCommonFrequencies()) : new ArrayList<>();
        
        if (!addMcvConstraints.isEmpty()) {
            applyAddMcvConstraints(mcvs, mcfs, addMcvConstraints, stats.getDataType());
        }
        
        // ★★★ 对于 prefix_int 列，确保 UPDATE_MCV 约束中的值也被添加到 MCV 列表（如果不存在）★★★
        if (prefixIntSpec != null && !updateMcvConstraints.isEmpty()) {
            for (Map<String, Object> constraintMap : updateMcvConstraints) {
                String pattern = (String) constraintMap.get("_pattern");
                if (pattern != null && pattern.startsWith(prefixIntSpec.prefix)) {
                    Integer suffix = ruc.db.utils.VarcharPatternManager.parsePrefixIntSuffix(pattern, prefixIntSpec);
                    if (suffix != null && !mcvs.contains(pattern)) {
                        // 值不在 MCV 列表中，添加它（频率暂时设为0，后续IPF会调整）
                        mcvs.add(pattern);
                        mcfs.add(0.0);
                        logger.debug(LM.formatBilingual("CdfDebugPrefixIntMcvAdded", stats.getColumnName(), pattern));
                    }
                }
            }
        }
        
        // 再处理 UPDATE_MCV（使用IPF算法）
        Map<String, Double> adjustedFrequencies = new HashMap<>();
        if (!updateMcvConstraints.isEmpty() && !mcvs.isEmpty()) {
            adjustedFrequencies = applyUpdateMcvConstraints(mcvs, mcfs, updateMcvConstraints, stats);
        } else if (!mcvs.isEmpty()) {
            // 没有 UPDATE_MCV，返回当前 MCV 频率
                for (int i = 0; i < mcvs.size() && i < mcfs.size(); i++) {
                    adjustedFrequencies.put(mcvs.get(i), mcfs.get(i));
            }
        }
        
        return adjustedFrequencies;
    }

    /**
     * 处理 ADD_MCV 约束：添加新值到MCV列表
     * 直接按照选择率添加，如果总和超过1.0，则保持ADD_MCV频率不变，调整其他值
     */
    @SuppressWarnings("unchecked")
    private static void applyAddMcvConstraints(List<String> mcvs, List<Double> mcfs,
                                               List<Map<String, Object>> addMcvConstraints, String dataType) {
        // 记录ADD_MCV的值，用于后续归一化时保持其频率不变
        Set<String> addMcvValues = new HashSet<>();
        
        // 先添加所有ADD_MCV值，使用目标选择率
        for (Map<String, Object> constraint : addMcvConstraints) {
            String pattern = (String) constraint.get("_pattern");
            String operator = (String) constraint.get("operator");
            String selectivityStr = (String) constraint.get("selectivity");
            
            if (selectivityStr == null) {
                continue;
            }
            
            double selectivity = Double.parseDouble(selectivityStr);
            String valueOperator = effectiveOperatorForMatchedBucket(operator, pattern);
            double valueFrequency = effectiveSelectivityForMatchedBucket(operator, selectivity);
            String value = "LIKE".equalsIgnoreCase(valueOperator) ?
                generateValueForLikePattern(pattern, dataType) : pattern;
            if (("NE".equalsIgnoreCase(operator) || "NOT_IN".equalsIgnoreCase(operator) || "NOT_LIKE".equalsIgnoreCase(operator))
                    && Math.abs(valueFrequency - selectivity) > 1e-12) {
                logger.info("否定谓词ADD_MCV按补集频率应用: {} {} selectivity={} -> matchedBucketFrequency={}",
                        operator, pattern, selectivity, valueFrequency);
            }

            int existingIndex = mcvs.indexOf(value);
            if (existingIndex >= 0) {
                mcfs.set(existingIndex, valueFrequency);
            } else {
                mcvs.add(value);
                mcfs.add(valueFrequency);
            }
            addMcvValues.add(value);
        }
        
        // 计算总和
        double totalFreq = mcfs.stream().mapToDouble(Double::doubleValue).sum();
        
        // 如果总和超过1.0，需要调整：保持ADD_MCV频率不变，调整其他值
        if (totalFreq > 1.0 + 1e-9) {
            // 计算ADD_MCV值的频率总和
            double addMcvFreqSum = 0.0;
            for (int i = 0; i < mcvs.size(); i++) {
                if (addMcvValues.contains(mcvs.get(i))) {
                    addMcvFreqSum += mcfs.get(i);
                }
            }
            
            // 计算其他值的频率总和
            double otherFreqSum = totalFreq - addMcvFreqSum;
            
            // 如果其他值频率总和 > 0，按比例缩放使其总和 = 1.0 - addMcvFreqSum
            if (otherFreqSum > 1e-9) {
                double targetOtherFreqSum = 1.0 - addMcvFreqSum;
                double scaleFactor = targetOtherFreqSum / otherFreqSum;
                
                for (int i = 0; i < mcvs.size(); i++) {
                    if (!addMcvValues.contains(mcvs.get(i))) {
                        mcfs.set(i, mcfs.get(i) * scaleFactor);
                    }
                }
            } else {
                // 如果没有其他值，只能归一化所有值（包括ADD_MCV）
                // 这种情况不应该发生，但为了安全起见还是处理一下
                normalizeFrequencies(mcfs);
            }
        }
        // 如果总和 <= 1.0，不需要调整，直接使用原始频率
    }
    
    /**
     * UPDATE_MCV 在 IPF 中使用的操作符：无通配符的 LIKE（cdf 键为纯字面量，如 514013202）按 EQ 与 MCV 桶精确匹配调频，
     * 等价于常见 SQL {@code col LIKE '514013202%'} 且取值域就是该字面量；含 %/_ 时仍走 LIKE 匹配器。
     */
    private static double effectiveSelectivityForMatchedBucket(String operator, double selectivity) {
        if (operator == null) {
            return selectivity;
        }
        String op = operator.trim().toUpperCase();
        if ("NE".equals(op) || "NOT_IN".equals(op) || "NOT_LIKE".equals(op)) {
            return Math.max(0.0, Math.min(1.0, 1.0 - selectivity));
        }
        return selectivity;
    }

    private static String effectiveOperatorForMatchedBucket(String operator, String pattern) {
        if (operator == null) {
            return operator;
        }
        String op = operator.trim().toUpperCase();
        if ("NE".equals(op) || "NOT_IN".equals(op)) {
            return "EQ";
        }
        if ("NOT_LIKE".equals(op)) {
            return "LIKE";
        }
        if ("LIKE".equals(op) && pattern != null
                && pattern.indexOf('%') < 0 && pattern.indexOf('_') < 0) {
            return "EQ";
        }
        return operator;
    }

    private static String effectiveIpfOperatorForUpdateMcv(String operator, String pattern) {
        return effectiveOperatorForMatchedBucket(operator, pattern);
    }

    /**
     * 处理 UPDATE_MCV 约束：使用IPF算法调整频率分布
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> applyUpdateMcvConstraints(
            List<String> mcvs, List<Double> mcfs,
            List<Map<String, Object>> updateMcvConstraints,
            EnhancedStatsExtractor.EnhancedColumnStatistics stats) {
        
        // 创建分布桶
        List<DistributionAdjuster.DistributionBucket> buckets = new ArrayList<>();
        for (int i = 0; i < mcvs.size(); i++) {
            buckets.add(new DistributionAdjuster.DistributionBucket(mcvs.get(i), mcfs.get(i)));
        }
        
        // 创建 IPF 约束
        List<DistributionAdjuster.Constraint> ipfConstraints = new ArrayList<>();
        for (Map<String, Object> constraintMap : updateMcvConstraints) {
            String pattern = (String) constraintMap.get("_pattern");
            String operator = (String) constraintMap.get("operator");
            String selectivityStr = (String) constraintMap.get("selectivity");
            
            if (selectivityStr == null) {
                continue;
            }
            
            double selectivity = Double.parseDouble(selectivityStr);
            String valueForMatcher = pattern;
            String ipfOperator = effectiveIpfOperatorForUpdateMcv(operator, pattern);

            // 否定谓词的 pattern 是被排除/不匹配的桶，IPF 应约束该桶的补集频率。
            double targetFrequency = effectiveSelectivityForMatchedBucket(operator, selectivity);

            java.util.function.Predicate<String> matcher = DistributionAdjuster.createMatcher(ipfOperator, valueForMatcher, stats.getColumnName());
            String description = "LIKE".equalsIgnoreCase(operator) && "EQ".equals(ipfOperator)
                    ? String.format("LIKE %s (IPF按EQ匹配MCV字面量) -> selectivity=%.6f", pattern, targetFrequency)
                    : String.format("%s %s -> selectivity=%.6f", operator, pattern, targetFrequency);
            if ("GE".equals(operator) || "GT".equals(operator)) {
                logger.debug(LM.formatBilingual("CdfDebugColumnGeConstraint",
                            stats.getColumnName(), pattern, pattern, targetFrequency));
            }
            ipfConstraints.add(new DistributionAdjuster.Constraint(matcher, targetFrequency, description));
        }
        
        // 应用 IPF 算法
        DistributionAdjuster adjuster = new DistributionAdjuster();
        List<DistributionAdjuster.DistributionBucket> adjustedBuckets = adjuster.adjustDistribution(buckets, ipfConstraints);
        
        // 转换为 Map
        Map<String, Double> adjustedFrequencies = new HashMap<>();
        for (DistributionAdjuster.DistributionBucket bucket : adjustedBuckets) {
            adjustedFrequencies.put(bucket.value, bucket.frequency);
        }
        
        logger.debug(LM.formatBilingual("CdfDebugColumnIpfUpdateMcvCount", stats.getColumnName(), updateMcvConstraints.size()));
        
        return adjustedFrequencies;
    }

    /**
     * 处理 UPDATE_MCV 约束：使用IPF算法调整频率分布（用于 schema.EnhancedColumnStatistics）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> applyUpdateMcvConstraintsForSchema(
            List<String> mcvs, List<Double> mcfs,
            List<Map<String, Object>> updateMcvConstraints,
            EnhancedColumnStatistics stats) {
        
        // 创建分布桶
        List<DistributionAdjuster.DistributionBucket> buckets = new ArrayList<>();
        for (int i = 0; i < mcvs.size(); i++) {
            buckets.add(new DistributionAdjuster.DistributionBucket(mcvs.get(i), mcfs.get(i)));
        }
        
        // 创建 IPF 约束
        List<DistributionAdjuster.Constraint> ipfConstraints = new ArrayList<>();
        
        // ★★★ 修复：对于GE操作符，stage2已经保存了累计选择率 P(X >= v)，直接使用即可 ★★★
        // 处理所有约束（包括GE和其他约束）
        for (Map<String, Object> constraintMap : updateMcvConstraints) {
            String pattern = (String) constraintMap.get("_pattern");
            String operator = (String) constraintMap.get("operator");
            String selectivityStr = (String) constraintMap.get("selectivity");

            if (selectivityStr == null) {
                continue;
            }

            double selectivity = Double.parseDouble(selectivityStr);
            String valueForMatcher = pattern;
            String ipfOperator = effectiveIpfOperatorForUpdateMcv(operator, pattern);
            double targetFrequency = effectiveSelectivityForMatchedBucket(operator, selectivity);

            java.util.function.Predicate<String> matcher = DistributionAdjuster.createMatcher(ipfOperator, valueForMatcher, stats.getColumnName());

            // 对于否定谓词，selectivity 是谓词为真的概率；匹配桶频率需要取补集。
            String description = "LIKE".equalsIgnoreCase(operator) && "EQ".equals(ipfOperator)
                    ? String.format("LIKE %s (IPF按EQ匹配MCV字面量) -> selectivity=%.6f", pattern, targetFrequency)
                    : String.format("%s %s -> matchedBucketFrequency=%.6f", operator, pattern, targetFrequency);
            if ("GE".equals(operator) || "GT".equals(operator)) {
                logger.debug(LM.formatBilingual("CdfDebugColumnGeConstraint",
                            stats.getColumnName(), pattern, pattern, targetFrequency));
            }
            ipfConstraints.add(new DistributionAdjuster.Constraint(matcher, targetFrequency, description));
        }
        
        // 应用 IPF 算法
        DistributionAdjuster adjuster = new DistributionAdjuster();
        List<DistributionAdjuster.DistributionBucket> adjustedBuckets = adjuster.adjustDistribution(buckets, ipfConstraints);
        
        // 转换为 Map
        Map<String, Double> adjustedFrequencies = new HashMap<>();
        for (DistributionAdjuster.DistributionBucket bucket : adjustedBuckets) {
            adjustedFrequencies.put(bucket.value, bucket.frequency);
        }
        
        logger.debug(LM.formatBilingual("CdfDebugColumnIpfUpdateMcvCount", stats.getColumnName(), updateMcvConstraints.size()));
        
        return adjustedFrequencies;
    }
    
    /**
     * 归一化频率列表，使总和为1.0
     */
    private static void normalizeFrequencies(List<Double> frequencies) {
        double total = frequencies.stream().mapToDouble(Double::doubleValue).sum();
        if (total > 1e-9) {
            double scale = 1.0 / total;
            for (int i = 0; i < frequencies.size(); i++) {
                frequencies.set(i, frequencies.get(i) * scale);
                }
        }
    }
    
    /**
     * 为LIKE模式生成包含该模式的值
     */
    private static String generateValueForLikePattern(String pattern, String dataType) {
        if (pattern == null || pattern.isEmpty()) {
            return pattern;
        }
        
        int maxLength = 55;
        if (dataType != null && dataType.contains("(")) {
            try {
                String lengthStr = dataType.substring(dataType.indexOf("(") + 1, dataType.indexOf(")"));
                maxLength = Integer.parseInt(lengthStr);
            } catch (Exception e) {
                // 使用默认值
            }
        }
        
        if (pattern.length() >= maxLength * 0.9) {
            return pattern;
        }
        
        int remainingLength = maxLength - pattern.length() - 1;
        if (remainingLength <= 0) {
            return pattern;
        }
        
        int prefixLength = Math.min(5, Math.max(2, remainingLength / 3));
        String prefix = generateRandomPrefix(prefixLength);
        return prefix + " " + pattern;
    }
    
    /**
     * 生成随机前缀
     */
    private static String generateRandomPrefix(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
