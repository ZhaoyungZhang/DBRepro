package ruc.db.rsgen;

import java.util.*;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedTableStatistics;

/**
 * 测试集成清洗功能
 */
public class TestIntegratedCleaning {
    
    public static void main(String[] args) {
        System.out.println("🧪 测试集成的数据清洗功能...");
        
        // 创建EnhancedStatsExtractor实例
        EnhancedStatsExtractor extractor = new EnhancedStatsExtractor();
        
        // 创建测试数据
        Map<String, EnhancedTableStatistics> testStats = createTestData();
        
        // 调用集成的清洗处理方法
        try {
            // 使用反射调用私有方法进行测试
            java.lang.reflect.Method method = EnhancedStatsExtractor.class.getDeclaredMethod(
                "processDataQualityAndCleaning", Map.class);
            method.setAccessible(true);
            
            System.out.println("🔍 开始数据质量分析和清洗...");
            @SuppressWarnings("unchecked")
            Map<String, EnhancedTableStatistics> processedStats = 
                (Map<String, EnhancedTableStatistics>) method.invoke(extractor, testStats);
            System.out.println("✅ 数据质量分析和清洗完成");
            
            // 验证清洗结果
            verifyCleaningResults(testStats, processedStats);
            
        } catch (Exception e) {
            System.err.println("💥 测试过程出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 创建测试数据
     */
    private static Map<String, EnhancedTableStatistics> createTestData() {
        Map<String, EnhancedTableStatistics> testStats = new HashMap<>();
        
        EnhancedTableStatistics tableStats = new EnhancedTableStatistics();
        tableStats.setTableName("test.integrated_cleaning");
        tableStats.setTableSize(1000);
        
        Map<String, EnhancedColumnStatistics> columns = new HashMap<>();
        
        // 创建一个需要清洗的数值型字符串列
        EnhancedColumnStatistics dirtyCol = new EnhancedColumnStatistics();
        dirtyCol.setColumnName("test.integrated_cleaning.dirty_id");
        dirtyCol.setTableName("test.integrated_cleaning");
        dirtyCol.setShortColumnName("dirty_id");
        dirtyCol.setDataType("varchar(25)");
        dirtyCol.setNullFraction(0.0);
        dirtyCol.setAvgWidth(20);
        dirtyCol.setNDistinct(950.0);
        dirtyCol.setDataPattern("max_length=25,avg_width=20");
        
        // 包含脏数据的MCV
        List<String> dirtyMCV = Arrays.asList(
            "12345678901234567890",      // 正常20位
            "12345678901234567890abc",   // 20位+字母后缀
            "xyz12345678901234567890",   // 前缀+20位
            "123456789",                 // 异常短
            "random_text_123"            // 混合文本
        );
        dirtyCol.setMostCommonValues(dirtyMCV);
        dirtyCol.setMostCommonFrequencies(Arrays.asList(0.20, 0.15, 0.12, 0.10, 0.08));
        
        columns.put("test.integrated_cleaning.dirty_id", dirtyCol);
        
        // 创建一个不需要清洗的普通列
        EnhancedColumnStatistics normalCol = new EnhancedColumnStatistics();
        normalCol.setColumnName("test.integrated_cleaning.normal_name");
        normalCol.setTableName("test.integrated_cleaning");
        normalCol.setShortColumnName("normal_name");
        normalCol.setDataType("varchar(50)");
        normalCol.setNullFraction(0.05);
        normalCol.setAvgWidth(25);
        normalCol.setNDistinct(800.0);
        normalCol.setDataPattern("max_length=50,avg_width=25");
        
        List<String> normalMCV = Arrays.asList(
            "John Smith",
            "Jane Doe", 
            "Bob Johnson",
            "Alice Brown",
            "Charlie Wilson"
        );
        normalCol.setMostCommonValues(normalMCV);
        normalCol.setMostCommonFrequencies(Arrays.asList(0.18, 0.15, 0.12, 0.10, 0.08));
        
        columns.put("test.integrated_cleaning.normal_name", normalCol);
        
        tableStats.setColumns(columns);
        testStats.put("test.integrated_cleaning", tableStats);
        
        return testStats;
    }
    
    /**
     * 验证清洗结果
     */
    private static void verifyCleaningResults(Map<String, EnhancedTableStatistics> original,
                                            Map<String, EnhancedTableStatistics> processed) {
        System.out.println("\n📋 验证清洗结果:");
        
        for (String tableName : original.keySet()) {
            System.out.println("\n🔍 验证表: " + tableName);
            
            EnhancedTableStatistics originalTable = original.get(tableName);
            EnhancedTableStatistics processedTable = processed.get(tableName);
            
            for (String columnKey : originalTable.getColumns().keySet()) {
                EnhancedColumnStatistics originalCol = originalTable.getColumns().get(columnKey);
                EnhancedColumnStatistics processedCol = processedTable.getColumns().get(columnKey);
                
                System.out.println("\n📊 验证列: " + originalCol.getColumnName());
                
                // 检查是否为数值型字符串列
                boolean isDirtyId = originalCol.getColumnName().contains("dirty_id");
                
                if (isDirtyId) {
                    // 这个列应该被清洗了
                    List<String> originalMCV = originalCol.getMostCommonValues();
                    List<String> processedMCV = processedCol.getMostCommonValues();
                    
                    System.out.println("   原始MCV: " + originalMCV);
                    System.out.println("   处理后MCV: " + processedMCV);
                    
                    // 检查是否有清洗标记
                    String dataPattern = processedCol.getDataPattern();
                    if (dataPattern != null && dataPattern.contains("cleaned_numeric_string=true")) {
                        System.out.println("   ✅ 检测到清洗标记，清洗已应用");
                    } else {
                        System.out.println("   ❌ 未检测到清洗标记");
                    }
                    
                    // 检查MCV是否发生变化
                    if (!originalMCV.equals(processedMCV)) {
                        System.out.println("   ✅ MCV已被清洗，数据发生变化");
                    } else {
                        System.out.println("   ⚠️  MCV未发生变化，可能清洗未生效");
                    }
                } else {
                    // 这个列不应该被清洗
                    List<String> originalMCV = originalCol.getMostCommonValues();
                    List<String> processedMCV = processedCol.getMostCommonValues();
                    
                    if (originalMCV.equals(processedMCV)) {
                        System.out.println("   ✅ 普通列未被修改，符合预期");
                    } else {
                        System.out.println("   ❌ 普通列被意外修改");
                        System.out.println("   原始MCV: " + originalMCV);
                        System.out.println("   处理后MCV: " + processedMCV);
                    }
                }
            }
        }
        
        System.out.println("\n✅ 清洗结果验证完成！");
    }
} 