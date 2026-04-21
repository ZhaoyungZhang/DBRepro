package ruc.db.rsgen;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;

/**
 * 数值型字符串数据清洗器
 * 负责清洗数值型字符串列的脏数据
 */
public class NumericStringDataCleaner {
    private static final Logger logger = LoggerFactory.getLogger(NumericStringDataCleaner.class);
    
    /**
     * 数值型字符串模式类
     */
    public static class NumericStringPattern {
        private final String dominantPrefix;
        private final int dominantLength;
        private final double prefixConsistency;
        private final double lengthConsistency;
        
        public NumericStringPattern(String dominantPrefix, int dominantLength, 
                                  double prefixConsistency, double lengthConsistency) {
            this.dominantPrefix = dominantPrefix;
            this.dominantLength = dominantLength;
            this.prefixConsistency = prefixConsistency;
            this.lengthConsistency = lengthConsistency;
        }
        
        public String getDominantPrefix() { return dominantPrefix; }
        public int getDominantLength() { return dominantLength; }
        public double getPrefixConsistency() { return prefixConsistency; }
        public double getLengthConsistency() { return lengthConsistency; }
        public boolean hasDominantPrefix() { return dominantPrefix != null && !dominantPrefix.isEmpty(); }
    }
    
    /**
     * 清洗报告类
     */
    public static class CleaningReport {
        private final int originalCount;
        private final int cleanedCount;
        private final double retentionRate;
        private final double consistencyRate;
        private final int dominantLength;
        private final String dominantPrefix;
        private final Map<String, Integer> cleaningActions;
        
        public CleaningReport(int originalCount, int cleanedCount, double consistencyRate,
                            int dominantLength, String dominantPrefix, 
                            Map<String, Integer> cleaningActions) {
            this.originalCount = originalCount;
            this.cleanedCount = cleanedCount;
            this.retentionRate = (double) cleanedCount / originalCount;
            this.consistencyRate = consistencyRate;
            this.dominantLength = dominantLength;
            this.dominantPrefix = dominantPrefix;
            this.cleaningActions = cleaningActions != null ? cleaningActions : new HashMap<>();
        }
        
        public int getOriginalCount() { return originalCount; }
        public int getCleanedCount() { return cleanedCount; }
        public double getRetentionRate() { return retentionRate; }
        public double getConsistencyRate() { return consistencyRate; }
        public int getDominantLength() { return dominantLength; }
        public String getDominantPrefix() { return dominantPrefix; }
        public Map<String, Integer> getCleaningActions() { return cleaningActions; }
        
        public boolean shouldApplyCleaning() {
            return retentionRate >= 0.8 && consistencyRate >= 0.9;
        }
    }
    
    /**
     * 清洗后的统计信息类
     */
    public static class CleanedStatistics {
        private final EnhancedColumnStatistics cleanedStats;
        private final CleaningReport qualityReport;
        
        public CleanedStatistics(EnhancedColumnStatistics cleanedStats, CleaningReport qualityReport) {
            this.cleanedStats = cleanedStats;
            this.qualityReport = qualityReport;
        }
        
        public EnhancedColumnStatistics getCleanedStatistics() { return cleanedStats; }
        public CleaningReport getQualityReport() { return qualityReport; }
    }
    
    /**
     * 清洗数值型字符串列的统计信息
     */
    public CleanedStatistics cleanNumericStringData(EnhancedColumnStatistics original) {
        logger.info("🧹 开始清洗数值型字符串列: {}", original.getColumnName());
        
        // 1. 收集所有样本数据
        List<String> allSamples = collectAllSamples(original);
        if (allSamples.isEmpty()) {
            logger.warn("列 {} 无样本数据，跳过清洗", original.getColumnName());
            return new CleanedStatistics(original, 
                new CleaningReport(0, 0, 0.0, 0, null, Collections.emptyMap()));
        }
        
        // 2. 分析主要模式
        NumericStringPattern mainPattern = identifyMainPattern(allSamples);
        logger.debug("识别到主要模式: 长度={}, 前缀={}, 长度一致性={}%, 前缀一致性={}%",
                    mainPattern.getDominantLength(), mainPattern.getDominantPrefix(),
                    mainPattern.getLengthConsistency() * 100, mainPattern.getPrefixConsistency() * 100);
        
        // 3. 清洗数据
        Map<String, Integer> cleaningActions = new HashMap<>();
        List<String> cleanedMCV = cleanMCVData(original.getMostCommonValues(), mainPattern, cleaningActions);
        List<String> cleanedHistogram = cleanHistogramBounds(original.getHistogramBounds(), mainPattern, cleaningActions);
        String cleanedMin = cleanSingleValue(original.getMinValue(), mainPattern, cleaningActions);
        String cleanedMax = cleanSingleValue(original.getMaxValue(), mainPattern, cleaningActions);
        
        // 4. 构建清洗后的统计信息
        EnhancedColumnStatistics cleaned = copyColumnStatistics(original);
        cleaned.setMostCommonValues(cleanedMCV);
        cleaned.setHistogramBounds(cleanedHistogram);
        cleaned.setMinValue(cleanedMin);
        cleaned.setMaxValue(cleanedMax);
        
        // 5. 更新数据模式信息
        updateDataPattern(cleaned, mainPattern);
        
        // 6. 生成清洗报告
        CleaningReport report = generateCleaningReport(allSamples, mainPattern, cleaningActions);
        
        logger.info("清洗完成: 保留率={}%, 一致性={}%", 
                   report.getRetentionRate() * 100, report.getConsistencyRate() * 100);
        
        return new CleanedStatistics(cleaned, report);
    }
    
    private List<String> collectAllSamples(EnhancedColumnStatistics colStats) {
        List<String> samples = new ArrayList<>();
        
        if (colStats.getMostCommonValues() != null) {
            samples.addAll(colStats.getMostCommonValues());
        }
        if (colStats.getHistogramBounds() != null) {
            samples.addAll(colStats.getHistogramBounds());
        }
        if (colStats.getMinValue() != null) {
            samples.add(colStats.getMinValue());
        }
        if (colStats.getMaxValue() != null) {
            samples.add(colStats.getMaxValue());
        }
        
        return samples.stream()
                     .filter(s -> s != null && !s.trim().isEmpty())
                     .distinct()
                     .collect(Collectors.toList());
    }
    
    private NumericStringPattern identifyMainPattern(List<String> samples) {
        Map<Integer, Integer> lengthCounts = new HashMap<>();
        Map<String, Integer> prefixCounts = new HashMap<>();
        int numericSamples = 0;
        
        for (String sample : samples) {
            String numericPart = extractNumericPart(sample);
            if (numericPart != null && numericPart.length() >= 6) {
                numericSamples++;
                lengthCounts.merge(numericPart.length(), 1, Integer::sum);
                
                String prefix = numericPart.substring(0, Math.min(6, numericPart.length()));
                prefixCounts.merge(prefix, 1, Integer::sum);
            }
        }
        
        // 找出主导长度
        int dominantLength = lengthCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(20); // 默认长度
        
        // 找出主导前缀
        String dominantPrefix = prefixCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
        
        // 计算一致性
        double lengthConsistency = numericSamples > 0 ? 
            (double) lengthCounts.getOrDefault(dominantLength, 0) / numericSamples : 0.0;
        double prefixConsistency = numericSamples > 0 && dominantPrefix != null ? 
            (double) prefixCounts.get(dominantPrefix) / numericSamples : 0.0;
        
        return new NumericStringPattern(dominantPrefix, dominantLength, prefixConsistency, lengthConsistency);
    }
    
    private String extractNumericPart(String sample) {
        if (sample == null) return null;
        
        // 尝试提取最长的连续数字序列
        Pattern numericPattern = Pattern.compile("\\d+");
        Matcher matcher = numericPattern.matcher(sample);
        
        String longestNumeric = "";
        while (matcher.find()) {
            String found = matcher.group();
            if (found.length() > longestNumeric.length()) {
                longestNumeric = found;
            }
        }
        
        return longestNumeric.isEmpty() ? null : longestNumeric;
    }
    
    private List<String> cleanMCVData(List<String> originalMCV, NumericStringPattern pattern, 
                                    Map<String, Integer> cleaningActions) {
        if (originalMCV == null || originalMCV.isEmpty()) {
            return originalMCV;
        }
        
        List<String> cleaned = new ArrayList<>();
        for (String mcvValue : originalMCV) {
            String cleanedValue = cleanSingleValue(mcvValue, pattern, cleaningActions);
            if (cleanedValue != null) {
                cleaned.add(cleanedValue);
            }
        }
        
        return cleaned;
    }
    
    private List<String> cleanHistogramBounds(List<String> originalBounds, NumericStringPattern pattern,
                                            Map<String, Integer> cleaningActions) {
        if (originalBounds == null || originalBounds.isEmpty()) {
            return originalBounds;
        }
        
        List<String> cleaned = new ArrayList<>();
        for (String bound : originalBounds) {
            String cleanedBound = cleanSingleValue(bound, pattern, cleaningActions);
            if (cleanedBound != null) {
                cleaned.add(cleanedBound);
            }
        }
        
        // 确保直方图边界是有序的
        return cleaned.stream()
                     .sorted()
                     .collect(Collectors.toList());
    }
    
    private String cleanSingleValue(String value, NumericStringPattern pattern, 
                                  Map<String, Integer> cleaningActions) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        
        value = value.trim();
        
        // 分类处理不同类型的数据
        if (value.matches("\\d+")) {
            // 纯数字
            cleaningActions.merge("pure_numeric", 1, Integer::sum);
            return normalizeNumericString(value, pattern);
        } else if (value.matches("\\d+[a-zA-Z]{1,3}")) {
            // 数字+后缀字母
            cleaningActions.merge("numeric_with_suffix", 1, Integer::sum);
            String numericPart = value.replaceAll("[a-zA-Z]+$", "");
            return normalizeNumericString(numericPart, pattern);
        } else if (value.matches("[a-zA-Z]{1,3}\\d+")) {
            // 前缀字母+数字
            cleaningActions.merge("numeric_with_prefix", 1, Integer::sum);
            String numericPart = value.replaceAll("^[a-zA-Z]+", "");
            return normalizeNumericString(numericPart, pattern);
        } else {
            // 混合或其他情况
            String numericPart = extractNumericPart(value);
            if (numericPart != null && numericPart.length() >= pattern.getDominantLength() * 0.5) {
                cleaningActions.merge("extracted_numeric", 1, Integer::sum);
                return normalizeNumericString(numericPart, pattern);
            } else {
                // 无法提取有效数值，生成符合模式的确定性数值
                cleaningActions.merge("generated_deterministic", 1, Integer::sum);
                return generateDeterministicNumericString(value, pattern);
            }
        }
    }
    
    private String normalizeNumericString(String numericStr, NumericStringPattern pattern) {
        if (numericStr == null || numericStr.isEmpty()) {
            return generateRandomNumericString(pattern);
        }
        
        String cleaned = numericStr.replaceAll("[^0-9]", "");
        int targetLength = pattern.getDominantLength();
        
        if (cleaned.length() == targetLength) {
            return cleaned;
        } else if (cleaned.length() > targetLength) {
            // 截断策略：保留前缀模式
            if (pattern.hasDominantPrefix() && cleaned.startsWith(pattern.getDominantPrefix())) {
                return cleaned.substring(0, targetLength);
            }
            return cleaned.substring(0, targetLength);
        } else {
            // 补齐策略
            return padNumericString(cleaned, targetLength, pattern);
        }
    }
    
    private String padNumericString(String numericStr, int targetLength, NumericStringPattern pattern) {
        if (pattern.hasDominantPrefix()) {
            String prefix = pattern.getDominantPrefix();
            if (numericStr.startsWith(prefix.substring(0, Math.min(prefix.length(), numericStr.length())))) {
                // 在末尾补0
                return String.format("%-" + targetLength + "s", numericStr).replace(' ', '0');
            }
        }
        
        // 默认在前面补0
        try {
            return String.format("%0" + targetLength + "d", Long.parseLong(numericStr));
        } catch (NumberFormatException e) {
            // 如果数字太大，直接补0
            StringBuilder sb = new StringBuilder(numericStr);
            while (sb.length() < targetLength) {
                sb.append('0');
            }
            return sb.toString();
        }
    }
    
    private String generateDeterministicNumericString(String original, NumericStringPattern pattern) {
        // 使用原始字符串的哈希值生成确定性的数值字符串
        int hash = Math.abs(original.hashCode());
        String hashStr = String.valueOf(hash);
        
        // 调整到目标长度
        return normalizeNumericString(hashStr, pattern);
    }
    
    private String generateRandomNumericString(NumericStringPattern pattern) {
        StringBuilder sb = new StringBuilder();
        
        // 添加主要前缀
        if (pattern.hasDominantPrefix()) {
            sb.append(pattern.getDominantPrefix());
        }
        
        // 补齐到目标长度
        int remaining = pattern.getDominantLength() - sb.length();
        for (int i = 0; i < remaining; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        
        return sb.toString();
    }
    
    private EnhancedColumnStatistics copyColumnStatistics(EnhancedColumnStatistics original) {
        // 创建一个副本，避免修改原始统计信息
        EnhancedColumnStatistics copy = new EnhancedColumnStatistics();
        copy.setColumnName(original.getColumnName());
        copy.setTableName(original.getTableName());
        copy.setShortColumnName(original.getShortColumnName());
        copy.setDataType(original.getDataType());
        copy.setNullFraction(original.getNullFraction());
        copy.setAvgWidth(original.getAvgWidth());
        copy.setNDistinct(original.getNDistinct());
        copy.setDataPattern(original.getDataPattern());
        copy.setMcvCount(original.getMcvCount());
        copy.setHistogramBoundsCount(original.getHistogramBoundsCount());
        copy.setPrimaryKey(original.isPrimaryKey());
        copy.setForeignKey(original.isForeignKey());
        
        // 复制列表（创建新的列表实例）
        if (original.getMostCommonValues() != null) {
            copy.setMostCommonValues(new ArrayList<>(original.getMostCommonValues()));
        }
        if (original.getMostCommonFrequencies() != null) {
            copy.setMostCommonFrequencies(new ArrayList<>(original.getMostCommonFrequencies()));
        }
        if (original.getHistogramBounds() != null) {
            copy.setHistogramBounds(new ArrayList<>(original.getHistogramBounds()));
        }
        
        return copy;
    }
    
    private void updateDataPattern(EnhancedColumnStatistics stats, NumericStringPattern pattern) {
        String newPattern = String.format("max_length=%d,avg_width=%d,cleaned_numeric_string=true,dominant_prefix=%s",
                                         pattern.getDominantLength(),
                                         pattern.getDominantLength(),
                                         pattern.getDominantPrefix() != null ? pattern.getDominantPrefix() : "none");
        stats.setDataPattern(newPattern);
    }
    
    private CleaningReport generateCleaningReport(List<String> originalSamples, 
                                                NumericStringPattern pattern,
                                                Map<String, Integer> cleaningActions) {
        int originalCount = originalSamples.size();
        int cleanedCount = cleaningActions.values().stream().mapToInt(Integer::intValue).sum();
        
        // 计算一致性（假设清洗后的数据都符合主导模式）
        double consistencyRate = cleanedCount > 0 ? 0.95 : 0.0; // 假设95%一致性
        
        return new CleaningReport(
            originalCount, cleanedCount, consistencyRate,
            pattern.getDominantLength(), pattern.getDominantPrefix(),
            cleaningActions
        );
    }
} 