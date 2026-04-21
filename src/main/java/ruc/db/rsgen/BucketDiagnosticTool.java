package ruc.db.rsgen;

import java.io.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedTableStatistics;
import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bucket诊断工具
 * 用于诊断和调试bucket生成问题，特别是数据生成全为\N的问题
 */
public class BucketDiagnosticTool {
    private static final Logger logger = LoggerFactory.getLogger(BucketDiagnosticTool.class);
    
    /**
     * 诊断统计信息文件中的bucket问题
     */
    public static void diagnoseBucketIssues(String inputDir) {
        System.out.println("🔍 开始诊断Bucket问题...");
        
        try {
            File statsFile = new File(inputDir, "enhanced_column_statistics.json");
            
            if (!statsFile.exists()) {
                System.out.println("❌ 统计信息文件不存在: " + statsFile.getAbsolutePath());
                return;
            }
            
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            
            Map<String, EnhancedTableStatistics> stats = objectMapper.readValue(statsFile, 
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, EnhancedTableStatistics.class));
            
            System.out.println("📊 总表数: " + stats.size());
            
            // 分析每个表的bucket问题
            analyzeBucketIssues(stats);
            
        } catch (Exception e) {
            System.out.println("❌ 诊断过程出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 分析bucket问题
     */
    private static void analyzeBucketIssues(Map<String, EnhancedTableStatistics> stats) {
        System.out.println("\n📋 Bucket问题分析:");
        
        int problematicTables = 0;
        int problematicColumns = 0;
        List<String> issues = new ArrayList<>();
        
        EnhancedBucketGenerator bucketGenerator = new EnhancedBucketGenerator();
        
        for (Map.Entry<String, EnhancedTableStatistics> entry : stats.entrySet()) {
            String tableName = entry.getKey();
            EnhancedTableStatistics tableStats = entry.getValue();
            
            if (tableStats == null || tableStats.getColumns() == null) {
                continue;
            }
            
            boolean tableHasIssues = false;
            
            for (Map.Entry<String, EnhancedColumnStatistics> colEntry : tableStats.getColumns().entrySet()) {
                String columnName = colEntry.getKey();
                EnhancedColumnStatistics colStats = colEntry.getValue();
                
                if (colStats == null) {
                    continue;
                }
                
                // 生成buckets并检查问题
                try {
                    List<Bucket> buckets = bucketGenerator.generateBuckets(colStats, tableStats.getTableSize());
                    List<String> columnIssues = analyzeColumnBuckets(tableName, columnName, colStats, buckets);
                    
                    if (!columnIssues.isEmpty()) {
                        tableHasIssues = true;
                        problematicColumns++;
                        issues.addAll(columnIssues);
                    }
                    
                } catch (Exception e) {
                    tableHasIssues = true;
                    problematicColumns++;
                    issues.add(String.format("表 %s 列 %s: Bucket生成异常 - %s", tableName, columnName, e.getMessage()));
                }
            }
            
            if (tableHasIssues) {
                problematicTables++;
            }
        }
        
        System.out.println("   问题表数: " + problematicTables);
        System.out.println("   问题列数: " + problematicColumns);
        
        if (!issues.isEmpty()) {
            System.out.println("\n⚠️  发现的问题:");
            for (String issue : issues) {
                System.out.println("   - " + issue);
            }
        }
        
        // 提供修复建议
        provideFixSuggestions(issues);
    }
    
    /**
     * 分析单个列的bucket问题
     */
    private static List<String> analyzeColumnBuckets(String tableName, String columnName, 
                                                   EnhancedColumnStatistics colStats, List<Bucket> buckets) {
        List<String> issues = new ArrayList<>();
        
        // 检查bucket数量
        if (buckets.isEmpty()) {
            issues.add(String.format("表 %s 列 %s: 没有生成任何bucket", tableName, columnName));
            return issues;
        }
        
        // 检查NULL bucket比例
        long nullCount = buckets.stream()
            .filter(b -> b.getType() == Bucket.BucketType.NULL)
            .mapToLong(Bucket::getCount)
            .sum();
        
        if (nullCount > 0) {
            // 从表统计信息中获取表大小
            double nullRatio = 0.0;
            try {
                // 这里需要从外部传入表大小，暂时使用估算值
                long estimatedTableSize = 1000; // 默认估算值
                nullRatio = (double) nullCount / estimatedTableSize;
                if (nullRatio > 0.5) {
                    issues.add(String.format("表 %s 列 %s: NULL值比例过高 (%.2f%%)", 
                                           tableName, columnName, nullRatio * 100));
                }
            } catch (Exception e) {
                issues.add(String.format("表 %s 列 %s: 无法计算NULL比例", tableName, columnName));
            }
        }
        
        // 检查histogram bucket的边界
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            
            if (bucket.getType() == Bucket.BucketType.HISTOGRAM) {
                if (bucket.getLow() == null || bucket.getHigh() == null) {
                    issues.add(String.format("表 %s 列 %s: Bucket[%d] 边界为null (low=%s, high=%s)", 
                                           tableName, columnName, i, bucket.getLow(), bucket.getHigh()));
                }
                
                if (bucket.getLow() != null && bucket.getHigh() != null) {
                    try {
                        Object lowVal = bucket.getLow().getValue();
                        Object highVal = bucket.getHigh().getValue();
                        
                        if (lowVal instanceof Number && highVal instanceof Number) {
                            double low = ((Number) lowVal).doubleValue();
                            double high = ((Number) highVal).doubleValue();
                            
                            if (low > high) {
                                issues.add(String.format("表 %s 列 %s: Bucket[%d] 边界顺序错误 (low=%.2f > high=%.2f)", 
                                                       tableName, columnName, i, low, high));
                            }
                        }
                    } catch (Exception e) {
                        issues.add(String.format("表 %s 列 %s: Bucket[%d] 边界值类型错误", 
                                               tableName, columnName, i));
                    }
                }
            }
        }
        
        // 检查bucket总数是否合理
        long totalCount = buckets.stream().mapToLong(Bucket::getCount).sum();
        if (totalCount <= 0) {
            issues.add(String.format("表 %s 列 %s: Bucket总数为0或负数 (bucket=%d)", 
                                   tableName, columnName, totalCount));
        }
        
        return issues;
    }
    
    /**
     * 提供修复建议
     */
    private static void provideFixSuggestions(List<String> issues) {
        if (issues.isEmpty()) {
            System.out.println("\n✅ 未发现明显的bucket问题");
            return;
        }
        
        System.out.println("\n🔧 修复建议:");
        
        boolean hasNullIssues = issues.stream().anyMatch(issue -> issue.contains("NULL值比例过高"));
        boolean hasBoundaryIssues = issues.stream().anyMatch(issue -> issue.contains("边界为null"));
        boolean hasCountIssues = issues.stream().anyMatch(issue -> issue.contains("总数不匹配"));
        
        if (hasNullIssues) {
            System.out.println("   1. NULL值比例过高问题:");
            System.out.println("      - 检查统计信息提取时的null_fraction计算");
            System.out.println("      - 考虑调整NULL bucket的生成逻辑");
        }
        
        if (hasBoundaryIssues) {
            System.out.println("   2. Bucket边界为null问题:");
            System.out.println("      - 检查histogram_bounds数据是否完整");
            System.out.println("      - 考虑为null边界生成合理的默认值");
            System.out.println("      - 检查数据类型转换是否正确");
        }
        
        if (hasCountIssues) {
            System.out.println("   3. Bucket总数不匹配问题:");
            System.out.println("      - 检查bucket count的计算逻辑");
            System.out.println("      - 确保所有bucket的count总和等于表大小");
        }
        
        System.out.println("\n   4. 通用建议:");
        System.out.println("      - 运行数据生成时启用详细日志");
        System.out.println("      - 检查统计信息提取的完整性");
        System.out.println("      - 验证数据类型映射是否正确");
    }
    
    /**
     * 测试bucket生成
     */
    public static void testBucketGeneration(String inputDir, String tableName, String columnName) {
        System.out.println("🧪 测试Bucket生成: 表=" + tableName + ", 列=" + columnName);
        
        try {
            File statsFile = new File(inputDir, "enhanced_column_statistics.json");
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            
            Map<String, EnhancedTableStatistics> stats = objectMapper.readValue(statsFile, 
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, EnhancedTableStatistics.class));
            
            EnhancedTableStatistics tableStats = stats.get(tableName);
            if (tableStats == null) {
                System.out.println("❌ 表 " + tableName + " 不存在");
                return;
            }
            
            EnhancedColumnStatistics colStats = tableStats.getColumns().get(columnName);
            if (colStats == null) {
                System.out.println("❌ 列 " + columnName + " 不存在");
                return;
            }
            
            EnhancedBucketGenerator bucketGenerator = new EnhancedBucketGenerator();
            List<Bucket> buckets = bucketGenerator.generateBuckets(colStats, tableStats.getTableSize());
            
            System.out.println("✅ Bucket生成成功，共 " + buckets.size() + " 个bucket:");
            
            for (int i = 0; i < buckets.size(); i++) {
                Bucket bucket = buckets.get(i);
                System.out.println(String.format("   Bucket[%d]: 类型=%s, count=%d, nDistinct=%d, low=%s, high=%s", 
                                               i, bucket.getType(), bucket.getCount(), bucket.getNDistinct(), 
                                               bucket.getLow(), bucket.getHigh()));
            }
            
        } catch (Exception e) {
            System.out.println("❌ 测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 命令行接口
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java BucketDiagnosticTool <输入目录> [表名] [列名]");
            System.out.println("示例:");
            System.out.println("  java BucketDiagnosticTool /path/to/stats");
            System.out.println("  java BucketDiagnosticTool /path/to/stats table_name column_name");
            return;
        }
        
        String inputDir = args[0];
        
        if (args.length == 1) {
            // 诊断所有bucket问题
            diagnoseBucketIssues(inputDir);
        } else if (args.length == 3) {
            // 测试特定表的特定列
            String tableName = args[1];
            String columnName = args[2];
            testBucketGeneration(inputDir, tableName, columnName);
        } else {
            System.out.println("❌ 参数数量错误");
        }
    }
} 