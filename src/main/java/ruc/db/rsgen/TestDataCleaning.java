package ruc.db.rsgen;

import java.io.File;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedTableStatistics;

/**
 * 测试数据清洗功能的工具类
 * 创建包含脏数据的测试统计信息，用于验证清洗机制
 */
public class TestDataCleaning {
    
    public static void main(String[] args) throws IOException {
        System.out.println("🧪 创建包含脏数据的测试统计信息...");
        
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // 创建测试数据
        Map<String, EnhancedTableStatistics> testStats = createDirtyTestData();
        
        // 保存到临时文件
        File testFile = new File("/tmp/dirty_test_statistics.json");
        objectMapper.writeValue(testFile, testStats);
        
        System.out.println("✅ 测试数据已保存到: " + testFile.getAbsolutePath());
        
        // 测试清洗功能
        testCleaningMechanism(testStats);
    }
    
    /**
     * 创建包含脏数据的测试统计信息
     */
    private static Map<String, EnhancedTableStatistics> createDirtyTestData() {
        Map<String, EnhancedTableStatistics> testStats = new HashMap<>();
        
        // 测试表1：包含数值型字符串脏数据
        EnhancedTableStatistics table1 = createTestTable1();
        testStats.put("test.dirty_numeric_strings", table1);
        
        // 测试表2：包含其他类型的脏数据
        EnhancedTableStatistics table2 = createTestTable2();
        testStats.put("test.other_dirty_data", table2);
        
        return testStats;
    }
    
    /**
     * 创建包含数值型字符串脏数据的测试表
     */
    private static EnhancedTableStatistics createTestTable1() {
        EnhancedTableStatistics tableStats = new EnhancedTableStatistics();
        tableStats.setTableName("test.dirty_numeric_strings");
        tableStats.setTableSize(10000);
        
        Map<String, EnhancedColumnStatistics> columns = new HashMap<>();
        
        // 列1：asset_id - 数值型字符串，包含脏数据
        EnhancedColumnStatistics assetIdCol = new EnhancedColumnStatistics();
        assetIdCol.setColumnName("test.dirty_numeric_strings.asset_id");
        assetIdCol.setTableName("test.dirty_numeric_strings");
        assetIdCol.setShortColumnName("asset_id");
        assetIdCol.setDataType("varchar(32)");
        assetIdCol.setNullFraction(0.0);
        assetIdCol.setAvgWidth(22);
        assetIdCol.setNDistinct(8500.0);
        assetIdCol.setDataPattern("max_length=32,avg_width=22");
        
        // MCV包含脏数据
        List<String> assetMCV = Arrays.asList(
            "51300010000004105390902",    // 正常22位数字
            "51409140600001133336440s",   // 21位数字+字母后缀
            "abc51300010000004105390902", // 前缀字母+22位数字
            "0519347983",                 // 异常短的10位数字
            "zzzz18"                      // 完全异常的数据
        );
        assetIdCol.setMostCommonValues(assetMCV);
        assetIdCol.setMostCommonFrequencies(Arrays.asList(0.15, 0.12, 0.10, 0.08, 0.05));
        
        // 直方图边界也包含脏数据
        List<String> assetHistogram = Arrays.asList(
            "51300010000000000000000",
            "51300010000004105390902",
            "51409140600001133336440s",
            "abc51300010000004105390902",
            "51500000000000000000000",
            "0519347983",
            "xyz123456789012345678901",
            "51700000000000000000000",
            "zzzz18",
            "51900000000000000000000"
        );
        assetIdCol.setHistogramBounds(assetHistogram);
        assetIdCol.setMinValue("0519347983");
        assetIdCol.setMaxValue("zzzz18");
        
        columns.put("test.dirty_numeric_strings.asset_id", assetIdCol);
        
        // 列2：account_no - 另一个数值型字符串列
        EnhancedColumnStatistics accountCol = new EnhancedColumnStatistics();
        accountCol.setColumnName("test.dirty_numeric_strings.account_no");
        accountCol.setTableName("test.dirty_numeric_strings");
        accountCol.setShortColumnName("account_no");
        accountCol.setDataType("varchar(20)");
        accountCol.setNullFraction(0.02);
        accountCol.setAvgWidth(18);
        accountCol.setNDistinct(9500.0);
        accountCol.setDataPattern("max_length=20,avg_width=18");
        
        List<String> accountMCV = Arrays.asList(
            "123456789012345678",      // 正常18位
            "12345678901234567890123", // 超长23位
            "123456789012345678x",     // 18位+字母
            "acc123456789012345678",   // 前缀+数字
            "12345"                    // 异常短
        );
        accountCol.setMostCommonValues(accountMCV);
        accountCol.setMostCommonFrequencies(Arrays.asList(0.20, 0.15, 0.12, 0.10, 0.08));
        
        columns.put("test.dirty_numeric_strings.account_no", accountCol);
        
        tableStats.setColumns(columns);
        return tableStats;
    }
    
    /**
     * 创建包含其他类型脏数据的测试表
     */
    private static EnhancedTableStatistics createTestTable2() {
        EnhancedTableStatistics tableStats = new EnhancedTableStatistics();
        tableStats.setTableName("test.other_dirty_data");
        tableStats.setTableSize(5000);
        
        Map<String, EnhancedColumnStatistics> columns = new HashMap<>();
        
        // 列1：description - 长度变化很大的文本列
        EnhancedColumnStatistics descCol = new EnhancedColumnStatistics();
        descCol.setColumnName("test.other_dirty_data.description");
        descCol.setTableName("test.other_dirty_data");
        descCol.setShortColumnName("description");
        descCol.setDataType("varchar(200)");
        descCol.setNullFraction(0.1);
        descCol.setAvgWidth(45);
        descCol.setNDistinct(4500.0);
        descCol.setDataPattern("max_length=200,avg_width=45");
        
        List<String> descMCV = Arrays.asList(
            "Short",  // 异常短
            "This is a very long description that contains a lot of text and should demonstrate the length variation problem in our data quality analysis system which is designed to detect such issues",  // 异常长
            "Normal description text",
            "Text with special chars: @#$%^&*()",
            "   Leading and trailing spaces   "
        );
        descCol.setMostCommonValues(descMCV);
        descCol.setMostCommonFrequencies(Arrays.asList(0.15, 0.12, 0.20, 0.10, 0.08));
        
        columns.put("test.other_dirty_data.description", descCol);
        
        // 列2：name - 包含编码问题的列
        EnhancedColumnStatistics nameCol = new EnhancedColumnStatistics();
        nameCol.setColumnName("test.other_dirty_data.name");
        nameCol.setTableName("test.other_dirty_data");
        nameCol.setShortColumnName("name");
        nameCol.setDataType("varchar(50)");
        nameCol.setNullFraction(0.05);
        nameCol.setAvgWidth(25);
        nameCol.setNDistinct(3000.0);
        nameCol.setDataPattern("max_length=50,avg_width=25");
        
        List<String> nameMCV = Arrays.asList(
            "John Smith",
            "Jane Doe�",  // 包含编码问题字符
            "李明",       // 中文字符
            "José García", // 带重音符号
            "User\\u0020Name"  // Unicode转义
        );
        nameCol.setMostCommonValues(nameMCV);
        nameCol.setMostCommonFrequencies(Arrays.asList(0.18, 0.15, 0.12, 0.10, 0.08));
        
        columns.put("test.other_dirty_data.name", nameCol);
        
        tableStats.setColumns(columns);
        return tableStats;
    }
    
    /**
     * 测试清洗机制
     */
    private static void testCleaningMechanism(Map<String, EnhancedTableStatistics> testStats) {
        System.out.println("\n🧪 开始测试清洗机制...");
        
        NumericStringDetector detector = new NumericStringDetector();
        NumericStringDataCleaner cleaner = new NumericStringDataCleaner();
        DataQualityAnalyzer analyzer = new DataQualityAnalyzer();
        
        for (EnhancedTableStatistics tableStats : testStats.values()) {
            System.out.println("\n📊 测试表: " + tableStats.getTableName());
            
            for (EnhancedColumnStatistics colStats : tableStats.getColumns().values()) {
                System.out.println("\n🔍 测试列: " + colStats.getColumnName());
                
                // 1. 数据质量分析
                analyzer.analyzeColumnDataQuality(colStats);
                
                // 2. 数值型字符串检测
                NumericStringDetector.DetectionResult detection = detector.detectNumericStringColumn(colStats);
                
                if (detection.isNumericString()) {
                    System.out.println("✅ 检测到数值型字符串列，开始清洗...");
                    
                    // 3. 执行清洗
                    try {
                        NumericStringDataCleaner.CleanedStatistics cleaned = cleaner.cleanNumericStringData(colStats);
                        NumericStringDataCleaner.CleaningReport report = cleaned.getQualityReport();
                        
                        System.out.println("📋 清洗报告:");
                        System.out.println("   保留率: " + String.format("%.1f%%", report.getRetentionRate() * 100));
                        System.out.println("   一致性: " + String.format("%.1f%%", report.getConsistencyRate() * 100));
                        System.out.println("   主导长度: " + report.getDominantLength());
                        System.out.println("   主导前缀: " + report.getDominantPrefix());
                        System.out.println("   清洗操作: " + report.getCleaningActions());
                        
                        if (report.shouldApplyCleaning()) {
                            System.out.println("✅ 清洗质量达标，建议应用");
                            
                            // 显示清洗前后对比
                            System.out.println("🔄 清洗前后对比:");
                            System.out.println("   原始MCV: " + colStats.getMostCommonValues());
                            System.out.println("   清洗后MCV: " + cleaned.getCleanedStatistics().getMostCommonValues());
                        } else {
                            System.out.println("❌ 清洗质量不达标，不建议应用");
                        }
                    } catch (Exception e) {
                        System.out.println("💥 清洗过程出错: " + e.getMessage());
                    }
                } else {
                    System.out.println("❌ 未检测到数值型字符串列");
                }
            }
        }
        
        System.out.println("\n✅ 清洗机制测试完成！");
    }
} 