package ruc.db.rsgen;

import java.util.*;
import java.util.stream.Collectors;
import java.util.IntSummaryStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;

/**
 * 数据质量分析器
 * 用于分析各种数据质量问题并记录标志性日志，帮助识别需要清洗的列
 */
public class DataQualityAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(DataQualityAnalyzer.class);
    
    /**
     * 分析列的数据质量问题
     */
    public void analyzeColumnDataQuality(EnhancedColumnStatistics colStats) {
        String columnName = colStats.getColumnName();
        String dataType = colStats.getDataType();
        
        logger.debug("🔍 开始分析列 {} 的数据质量", columnName);
        
        // 分析各种可能的数据质量问题
        analyzeStringLengthVariation(colStats);
        analyzeSpecialCharacterPatterns(colStats);
        analyzeEncodingIssues(colStats);
        analyzeNullAndEmptyPatterns(colStats);
        analyzeDataTypeConsistency(colStats);
        analyzeDuplicatePatterns(colStats);
    }
    
    /**
     * 分析字符串长度变化异常
     */
    private void analyzeStringLengthVariation(EnhancedColumnStatistics colStats) {
        if (!isStringType(colStats.getDataType())) return;
        
        List<String> samples = collectAllSamples(colStats);
        if (samples.isEmpty()) return;
        
        IntSummaryStatistics lengthStats = samples.stream()
            .mapToInt(String::length)
            .summaryStatistics();
        
        if (lengthStats.getCount() < 2) return;
        
        double lengthVariation = (double)(lengthStats.getMax() - lengthStats.getMin()) / lengthStats.getAverage();
        
        if (lengthVariation > 2.0) { // 长度变化很大
            logger.warn("🔍 [LENGTH_VARIATION] 列 {} 长度变化异常大: min={}, max={}, avg={}, 变化系数={}", 
                       colStats.getColumnName(), 
                       lengthStats.getMin(), 
                       lengthStats.getMax(), 
                       lengthStats.getAverage(),
                       lengthVariation);
            logger.warn("   样本: {}", samples.stream().limit(5).collect(Collectors.toList()));
        }
        
        // 检查是否有异常短或异常长的值
        long veryShortCount = samples.stream().mapToInt(String::length).filter(len -> len <= 2).count();
        long veryLongCount = samples.stream().mapToInt(String::length).filter(len -> len >= lengthStats.getAverage() * 3).count();
        
        if (veryShortCount > samples.size() * 0.1) {
            logger.warn("🔍 [SHORT_VALUES] 列 {} 包含大量异常短值: {} 个 (占比 {}%)", 
                       colStats.getColumnName(), veryShortCount, (double)veryShortCount / samples.size() * 100);
        }
        
        if (veryLongCount > samples.size() * 0.1) {
            logger.warn("🔍 [LONG_VALUES] 列 {} 包含大量异常长值: {} 个 (占比 {}%)", 
                       colStats.getColumnName(), veryLongCount, (double)veryLongCount / samples.size() * 100);
        }
    }
    
    /**
     * 分析特殊字符模式
     */
    private void analyzeSpecialCharacterPatterns(EnhancedColumnStatistics colStats) {
        if (!isStringType(colStats.getDataType())) return;
        
        List<String> samples = collectAllSamples(colStats);
        if (samples.isEmpty()) return;
        
        int specialCharCount = 0;
        int mixedCaseCount = 0;
        int leadingTrailingSpaceCount = 0;
        int controlCharCount = 0;
        List<String> specialCharSamples = new ArrayList<>();
        
        for (String sample : samples) {
            if (sample == null) continue;
            
            // 检查特殊字符
            if (sample.matches(".*[^a-zA-Z0-9\\s].*")) {
                specialCharCount++;
                if (specialCharSamples.size() < 3) {
                    specialCharSamples.add(sample);
                }
            }
            
            // 检查大小写混合
            if (sample.matches(".*[a-z].*") && sample.matches(".*[A-Z].*")) {
                mixedCaseCount++;
            }
            
            // 检查前后空格
            if (!sample.equals(sample.trim())) {
                leadingTrailingSpaceCount++;
            }
            
            // 检查控制字符
            if (sample.matches(".*[\\x00-\\x1F\\x7F-\\x9F].*")) {
                controlCharCount++;
            }
        }
        
        if (specialCharCount > samples.size() * 0.1) {
            logger.warn("🔍 [SPECIAL_CHARS] 列 {} 包含大量特殊字符: {}% ({}/{} 个样本)", 
                       colStats.getColumnName(), 
                       (double)specialCharCount / samples.size() * 100,
                       specialCharCount, samples.size());
            logger.warn("   特殊字符样本: {}", specialCharSamples);
        }
        
        if (leadingTrailingSpaceCount > 0) {
            logger.warn("🔍 [WHITESPACE_ISSUE] 列 {} 存在前后空格问题: {} 个样本 (占比 {}%)", 
                       colStats.getColumnName(), 
                       leadingTrailingSpaceCount,
                       (double)leadingTrailingSpaceCount / samples.size() * 100);
        }
        
        if (controlCharCount > 0) {
            logger.warn("🔍 [CONTROL_CHARS] 列 {} 包含控制字符: {} 个样本", 
                       colStats.getColumnName(), controlCharCount);
        }
    }
    
    /**
     * 分析编码问题
     */
    private void analyzeEncodingIssues(EnhancedColumnStatistics colStats) {
        if (!isStringType(colStats.getDataType())) return;
        
        List<String> samples = collectAllSamples(colStats);
        if (samples.isEmpty()) return;
        
        int encodingIssueCount = 0;
        List<String> suspiciousSamples = new ArrayList<>();
        
        for (String sample : samples) {
            if (sample == null) continue;
            
            // 检测可能的编码问题
            if (sample.contains("�") || 
                sample.matches(".*[\\x00-\\x1F\\x7F-\\x9F].*") ||
                sample.matches(".*\\\\u[0-9a-fA-F]{4}.*") ||
                sample.matches(".*[\\uFFFD].*")) {
                encodingIssueCount++;
                if (suspiciousSamples.size() < 3) {
                    suspiciousSamples.add(sample);
                }
            }
        }
        
        if (encodingIssueCount > 0) {
            logger.warn("🔍 [ENCODING_ISSUE] 列 {} 可能存在编码问题: {} 个样本 (占比 {}%)", 
                       colStats.getColumnName(), 
                       encodingIssueCount,
                       (double)encodingIssueCount / samples.size() * 100);
            logger.warn("   可疑样本: {}", suspiciousSamples);
        }
    }
    
    /**
     * 分析NULL和空值模式
     */
    private void analyzeNullAndEmptyPatterns(EnhancedColumnStatistics colStats) {
        double nullFraction = colStats.getNullFraction();
        
        if (nullFraction > 0.5) {
            logger.warn("🔍 [HIGH_NULL_RATE] 列 {} NULL值比例过高: {}%", 
                       colStats.getColumnName(), nullFraction * 100);
        }
        
        // 检查空字符串
        if (isStringType(colStats.getDataType())) {
            List<String> samples = collectAllSamples(colStats);
            long emptyStringCount = samples.stream().filter(s -> s != null && s.trim().isEmpty()).count();
            
            if (emptyStringCount > samples.size() * 0.1) {
                logger.warn("🔍 [EMPTY_STRINGS] 列 {} 包含大量空字符串: {} 个 (占比 {}%)", 
                           colStats.getColumnName(), 
                           emptyStringCount,
                           (double)emptyStringCount / samples.size() * 100);
            }
        }
    }
    
    /**
     * 分析数据类型一致性
     */
    private void analyzeDataTypeConsistency(EnhancedColumnStatistics colStats) {
        if (!isStringType(colStats.getDataType())) return;
        
        List<String> samples = collectAllSamples(colStats);
        if (samples.isEmpty()) return;
        
        int numericCount = 0;
        int dateCount = 0;
        int booleanCount = 0;
        int mixedCount = 0;
        
        for (String sample : samples) {
            if (sample == null || sample.trim().isEmpty()) continue;
            
            sample = sample.trim();
            
            if (sample.matches("\\d+")) {
                numericCount++;
            } else if (sample.matches("\\d{4}-\\d{2}-\\d{2}.*") || 
                      sample.matches("\\d{2}/\\d{2}/\\d{4}.*")) {
                dateCount++;
            } else if (sample.toLowerCase().matches("true|false|yes|no|y|n|1|0")) {
                booleanCount++;
            } else if (sample.matches(".*\\d.*") && sample.matches(".*[a-zA-Z].*")) {
                mixedCount++;
            }
        }
        
        // 检查是否应该使用其他数据类型
        if (numericCount > samples.size() * 0.8) {
            logger.info("🔍 [TYPE_MISMATCH] 列 {} 主要包含数值数据 ({}%)，但定义为字符串类型", 
                       colStats.getColumnName(), (double)numericCount / samples.size() * 100);
        }
        
        if (dateCount > samples.size() * 0.8) {
            logger.info("🔍 [TYPE_MISMATCH] 列 {} 主要包含日期数据 ({}%)，但定义为字符串类型", 
                       colStats.getColumnName(), (double)dateCount / samples.size() * 100);
        }
        
        if (booleanCount > samples.size() * 0.8) {
            logger.info("🔍 [TYPE_MISMATCH] 列 {} 主要包含布尔数据 ({}%)，但定义为字符串类型", 
                       colStats.getColumnName(), (double)booleanCount / samples.size() * 100);
        }
    }
    
    /**
     * 分析重复模式
     */
    private void analyzeDuplicatePatterns(EnhancedColumnStatistics colStats) {
        List<String> samples = collectAllSamples(colStats);
        if (samples.isEmpty()) return;
        
        // 计算重复率
        Set<String> uniqueValues = new HashSet<>(samples);
        double duplicateRate = 1.0 - (double)uniqueValues.size() / samples.size();
        
        if (duplicateRate > 0.9) {
            logger.info("🔍 [HIGH_DUPLICATE_RATE] 列 {} 重复率极高: {}% (唯一值: {}, 总样本: {})", 
                       colStats.getColumnName(), duplicateRate * 100, uniqueValues.size(), samples.size());
        }
        
        // 检查是否有单一值占主导
        Map<String, Long> valueFrequency = samples.stream()
            .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        
        Optional<Map.Entry<String, Long>> mostCommon = valueFrequency.entrySet().stream()
            .max(Map.Entry.comparingByValue());
        
        if (mostCommon.isPresent()) {
            double dominanceRate = (double)mostCommon.get().getValue() / samples.size();
            if (dominanceRate > 0.8) {
                logger.info("🔍 [DOMINANT_VALUE] 列 {} 存在主导值: '{}' 占比 {}%", 
                           colStats.getColumnName(), 
                           mostCommon.get().getKey(), 
                           dominanceRate * 100);
            }
        }
    }
    
    /**
     * 收集所有样本数据
     */
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
                     .filter(s -> s != null)
                     .collect(Collectors.toList());
    }
    
    /**
     * 判断是否为字符串类型
     */
    private boolean isStringType(String dataType) {
        if (dataType == null) return false;
        String lowerType = dataType.toLowerCase();
        return lowerType.contains("varchar") || lowerType.contains("bpchar") || 
               lowerType.contains("char") || lowerType.contains("text");
    }
} 