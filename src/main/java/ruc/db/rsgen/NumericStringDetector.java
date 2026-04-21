package ruc.db.rsgen;

import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;

/**
 * 数值型字符串列检测器
 * 用于识别需要进行数据清洗的数值型字符串列
 */
public class NumericStringDetector {
    private static final Logger logger = LoggerFactory.getLogger(NumericStringDetector.class);
    
    /**
     * 检测结果类
     */
    public static class DetectionResult {
        private final boolean isNumericString;
        private final double numericRatio;
        private final String detectionReason;
        private final List<String> evidenceSamples;
        
        public DetectionResult(boolean isNumericString, double numericRatio, 
                             String detectionReason, List<String> evidenceSamples) {
            this.isNumericString = isNumericString;
            this.numericRatio = numericRatio;
            this.detectionReason = detectionReason;
            this.evidenceSamples = evidenceSamples != null ? evidenceSamples : Collections.emptyList();
        }
        
        public boolean isNumericString() { return isNumericString; }
        public double getNumericRatio() { return numericRatio; }
        public String getDetectionReason() { return detectionReason; }
        public List<String> getEvidenceSamples() { return evidenceSamples; }
    }
    
    /**
     * 数值分析结果类
     */
    public static class NumericAnalysisResult {
        private final double numericRatio;
        private final double longNumericRatio;
        private final int pureNumericCount;
        private final int numericWithJunkCount;
        private final Map<Integer, Integer> lengthDistribution;
        private final Map<String, Integer> prefixDistribution;
        private final List<String> evidenceSamples;
        private String detectionReason;
        
        public NumericAnalysisResult(double numericRatio, double longNumericRatio,
                                   int pureNumericCount, int numericWithJunkCount,
                                   Map<Integer, Integer> lengthDistribution,
                                   Map<String, Integer> prefixDistribution,
                                   List<String> evidenceSamples) {
            this.numericRatio = numericRatio;
            this.longNumericRatio = longNumericRatio;
            this.pureNumericCount = pureNumericCount;
            this.numericWithJunkCount = numericWithJunkCount;
            this.lengthDistribution = lengthDistribution != null ? lengthDistribution : new HashMap<>();
            this.prefixDistribution = prefixDistribution != null ? prefixDistribution : new HashMap<>();
            this.evidenceSamples = evidenceSamples != null ? evidenceSamples : new ArrayList<>();
        }
        
        public double getNumericRatio() { return numericRatio; }
        public double getLongNumericRatio() { return longNumericRatio; }
        public int getPureNumericCount() { return pureNumericCount; }
        public int getNumericWithJunkCount() { return numericWithJunkCount; }
        public Map<Integer, Integer> getLengthDistribution() { return lengthDistribution; }
        public Map<String, Integer> getPrefixDistribution() { return prefixDistribution; }
        public List<String> getEvidenceSamples() { return evidenceSamples; }
        public String getDetectionReason() { return detectionReason; }
        public void setDetectionReason(String reason) { this.detectionReason = reason; }
    }
    
    /**
     * 检测是否为数值型字符串列
     */
    public DetectionResult detectNumericStringColumn(EnhancedColumnStatistics colStats) {
        String columnName = colStats.getColumnName();
        
        // 1. 快速排除：非varchar/bpchar类型
        if (!isStringType(colStats.getDataType())) {
            return new DetectionResult(false, 0.0, "非字符串类型", Collections.emptyList());
        }
        
        // 2. 列名启发式检测
        boolean hasNumericName = hasNumericColumnNamePattern(columnName);
        if (hasNumericName) {
            logger.info("🔍 列 {} 通过列名模式检测为潜在数值型字符串列", columnName);
        }
        
        // 3. 收集所有样本数据
        List<String> allSamples = collectAllSamples(colStats);
        if (allSamples.isEmpty()) {
            return new DetectionResult(false, 0.0, "无样本数据", Collections.emptyList());
        }
        
        // 4. 分析数值型特征
        NumericAnalysisResult analysis = analyzeNumericCharacteristics(allSamples);
        
        // 5. 判断是否为数值型字符串
        boolean isNumericString = shouldTriggerCleaning(analysis, columnName, hasNumericName);
        
        // 6. 记录检测日志
        logDetectionResult(columnName, analysis, isNumericString);
        
        return new DetectionResult(
            isNumericString, 
            analysis.getNumericRatio(),
            analysis.getDetectionReason(),
            analysis.getEvidenceSamples()
        );
    }
    
    private boolean isStringType(String dataType) {
        if (dataType == null) return false;
        String lowerType = dataType.toLowerCase();
        return lowerType.contains("varchar") || lowerType.contains("bpchar") || 
               lowerType.contains("char") || lowerType.contains("text");
    }
    
    private boolean hasNumericColumnNamePattern(String columnName) {
        if (columnName == null) return false;
        String lowerName = columnName.toLowerCase();
        return lowerName.contains("id") || 
               lowerName.contains("no") || 
               lowerName.contains("code") || 
               lowerName.contains("number") ||
               lowerName.contains("asset") ||
               lowerName.contains("account") ||
               lowerName.contains("serial") ||
               lowerName.contains("barcode") ||
               lowerName.contains("key") ||
               lowerName.contains("ref");
    }
    
    private List<String> collectAllSamples(EnhancedColumnStatistics colStats) {
        List<String> samples = new ArrayList<>();
        
        // 收集MCV样本
        if (colStats.getMostCommonValues() != null) {
            samples.addAll(colStats.getMostCommonValues());
        }
        
        // 收集直方图边界样本
        if (colStats.getHistogramBounds() != null) {
            samples.addAll(colStats.getHistogramBounds());
        }
        
        // 添加min/max值
        if (colStats.getMinValue() != null) {
            samples.add(colStats.getMinValue());
        }
        if (colStats.getMaxValue() != null) {
            samples.add(colStats.getMaxValue());
        }
        
        // 去重并过滤空值
        return samples.stream()
                     .filter(s -> s != null && !s.trim().isEmpty())
                     .distinct()
                     .collect(Collectors.toList());
    }
    
    private NumericAnalysisResult analyzeNumericCharacteristics(List<String> samples) {
        int totalSamples = samples.size();
        int pureNumericCount = 0;
        int numericWithJunkCount = 0;
        int longNumericStringCount = 0;
        
        List<String> evidenceSamples = new ArrayList<>();
        Map<Integer, Integer> lengthDistribution = new HashMap<>();
        Map<String, Integer> prefixDistribution = new HashMap<>();
        
        for (String sample : samples) {
            if (sample == null || sample.trim().isEmpty()) continue;
            
            sample = sample.trim();
            lengthDistribution.merge(sample.length(), 1, Integer::sum);
            
            if (sample.matches("\\d+")) {
                // 纯数字
                pureNumericCount++;
                if (sample.length() >= 10) { // 长数值字符串
                    longNumericStringCount++;
                    if (evidenceSamples.size() < 5) {
                        evidenceSamples.add(sample);
                    }
                }
                
                // 分析前缀模式
                if (sample.length() >= 6) {
                    String prefix = sample.substring(0, Math.min(6, sample.length()));
                    prefixDistribution.merge(prefix, 1, Integer::sum);
                }
            } else if (sample.matches("\\d+[a-zA-Z]{1,3}") || 
                      sample.matches("[a-zA-Z]{1,3}\\d+") ||
                      sample.replaceAll("[^0-9]", "").length() >= sample.length() * 0.8) {
                // 主要是数字，但混入了少量字母
                numericWithJunkCount++;
                if (evidenceSamples.size() < 5) {
                    evidenceSamples.add(sample);
                }
            }
        }
        
        double numericRatio = (double)(pureNumericCount + numericWithJunkCount) / totalSamples;
        double longNumericRatio = (double)longNumericStringCount / totalSamples;
        
        return new NumericAnalysisResult(
            numericRatio, longNumericRatio, pureNumericCount, numericWithJunkCount,
            lengthDistribution, prefixDistribution, evidenceSamples
        );
    }
    
    private boolean shouldTriggerCleaning(NumericAnalysisResult analysis, String columnName, boolean hasNumericName) {
        boolean trigger = false;
        String reason = "";
        
        // 条件1: 高比例数值型 + 长字符串
        if (analysis.getNumericRatio() >= 0.7 && analysis.getLongNumericRatio() >= 0.3) {
            trigger = true;
            reason = "高比例长数值字符串";
        }
        
        // 条件2: 列名模式 + 中等比例数值型
        if (hasNumericName && analysis.getNumericRatio() >= 0.5) {
            trigger = true;
            reason = "列名模式匹配 + 中等数值比例";
        }
        
        // 条件3: 存在明显的脏数据模式（数字+字母混合）
        if (analysis.getNumericWithJunkCount() > 0 && analysis.getNumericRatio() >= 0.6) {
            trigger = true;
            reason = "检测到数字字母混合的脏数据";
        }
        
        analysis.setDetectionReason(reason);
        return trigger;
    }
    
    private void logDetectionResult(String columnName, NumericAnalysisResult analysis, boolean willClean) {
        if (willClean) {
            logger.info("✅ 数值型字符串列检测: {} - 将进行清洗", columnName);
            logger.info("   📊 数值比例: {}%, 长数值比例: {}%", 
                       analysis.getNumericRatio() * 100, 
                       analysis.getLongNumericRatio() * 100);
            logger.info("   🔧 触发原因: {}", analysis.getDetectionReason());
            logger.info("   📝 样本证据: {}", analysis.getEvidenceSamples().stream()
                       .limit(3).collect(Collectors.toList()));
        } else {
            // 记录未触发清洗的列，用于分析
            logger.debug("❌ 列 {} 未触发数值型字符串清洗 - 数值比例: {}%", 
                        columnName, analysis.getNumericRatio() * 100);
            
            // 如果有一定的数值特征但未触发清洗，记录警告日志
            if (analysis.getNumericRatio() >= 0.3) {
                logger.warn("⚠️  列 {} 具有一定数值特征但未触发清洗 - 请人工检查", columnName);
                logger.warn("   📊 数值比例: {}%, 样本: {}", 
                           analysis.getNumericRatio() * 100,
                           analysis.getEvidenceSamples().stream().limit(3).collect(Collectors.toList()));
            }
        }
    }
} 