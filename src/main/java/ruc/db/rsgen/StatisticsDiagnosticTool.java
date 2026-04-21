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
 * 统计信息诊断工具
 * 用于诊断和调试统计信息加载问题
 */
public class StatisticsDiagnosticTool {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsDiagnosticTool.class);
    
    /**
     * 诊断统计信息文件
     */
    public static void diagnoseStatisticsFile(String inputDir) {
        System.out.println("🔍 开始诊断统计信息文件...");
        
        try {
            File statsFile = new File(inputDir, "enhanced_column_statistics.json");
            
            if (!statsFile.exists()) {
                System.out.println("❌ 统计信息文件不存在: " + statsFile.getAbsolutePath());
                return;
            }
            
            System.out.println("📁 统计信息文件: " + statsFile.getAbsolutePath());
            System.out.println("📊 文件大小: " + statsFile.length() + " 字节");
            
            // 读取文件内容
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            
            String content = new String(java.nio.file.Files.readAllBytes(statsFile.toPath()), "UTF-8");
            
            // 检查文件是否为空
            if (content.trim().isEmpty()) {
                System.out.println("❌ 统计信息文件为空");
                return;
            }
            
            // 尝试解析JSON
            try {
                Map<String, EnhancedTableStatistics> stats = objectMapper.readValue(content, 
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, EnhancedTableStatistics.class));
                
                System.out.println("✅ JSON解析成功");
                System.out.println("📊 总表数: " + stats.size());
                
                // 分析统计信息
                analyzeStatistics(stats);
                
            } catch (Exception e) {
                System.out.println("❌ JSON解析失败: " + e.getMessage());
                
                // 尝试定位JSON错误
                locateJsonError(content);
            }
            
        } catch (Exception e) {
            System.out.println("❌ 诊断过程出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 分析统计信息
     */
    private static void analyzeStatistics(Map<String, EnhancedTableStatistics> stats) {
        System.out.println("\n📋 统计信息分析:");
        
        int nullStatsCount = 0;
        int emptyColumnsCount = 0;
        int zeroSizeCount = 0;
        List<String> problematicTables = new ArrayList<>();
        
        for (Map.Entry<String, EnhancedTableStatistics> entry : stats.entrySet()) {
            String tableName = entry.getKey();
            EnhancedTableStatistics tableStats = entry.getValue();
            
            if (tableStats == null) {
                nullStatsCount++;
                problematicTables.add(tableName + " (null)");
                continue;
            }
            
            // 检查表大小
            if (tableStats.getTableSize() <= 0) {
                zeroSizeCount++;
                problematicTables.add(tableName + " (size=" + tableStats.getTableSize() + ")");
            }
            
            // 检查列信息
            if (tableStats.getColumns() == null || tableStats.getColumns().isEmpty()) {
                emptyColumnsCount++;
                problematicTables.add(tableName + " (no columns)");
            }
            
            // 检查列统计信息
            if (tableStats.getColumns() != null) {
                for (Map.Entry<String, EnhancedColumnStatistics> colEntry : tableStats.getColumns().entrySet()) {
                    String columnName = colEntry.getKey();
                    EnhancedColumnStatistics colStats = colEntry.getValue();
                    
                    if (colStats == null) {
                        problematicTables.add(tableName + "." + columnName + " (null column stats)");
                    }
                }
            }
        }
        
        System.out.println("   正常表数: " + (stats.size() - nullStatsCount - emptyColumnsCount - zeroSizeCount));
        System.out.println("   Null统计信息: " + nullStatsCount);
        System.out.println("   空列信息: " + emptyColumnsCount);
        System.out.println("   零大小表: " + zeroSizeCount);
        
        if (!problematicTables.isEmpty()) {
            System.out.println("\n⚠️  问题表列表:");
            for (String table : problematicTables) {
                System.out.println("   - " + table);
            }
        }
        
        // 检查分区表
        checkPartitionTables(stats);
    }
    
    /**
     * 检查分区表
     */
    private static void checkPartitionTables(Map<String, EnhancedTableStatistics> stats) {
        System.out.println("\n🔍 分区表检查:");
        
        try {
            PartitionTableManager partitionManager = PartitionTableManager.getInstance();
            
            // 检查分区表管理器是否已加载
            if (partitionManager == null) {
                System.out.println("   ⚠️  分区表管理器未初始化");
                return;
            }
            
            List<String> rootPartitionTables = partitionManager.getAllRootPartitionTables();
            System.out.println("   根分区表数: " + rootPartitionTables.size());
            
            for (String rootTable : rootPartitionTables) {
                System.out.println("   - 根分区表: " + rootTable);
                
                // 检查根分区表是否有统计信息
                if (!stats.containsKey(rootTable)) {
                    System.out.println("     ⚠️  缺少统计信息");
                }
                
                // 检查子表
                Set<String> childTables = partitionManager.getChildTables(rootTable);
                if (childTables != null) {
                    System.out.println("     子表数: " + childTables.size());
                    for (String childTable : childTables) {
                        if (!stats.containsKey(childTable)) {
                            System.out.println("     ⚠️  子表缺少统计信息: " + childTable);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            System.out.println("   ❌ 分区表检查失败: " + e.getMessage());
        }
    }
    
    /**
     * 定位JSON错误
     */
    private static void locateJsonError(String content) {
        System.out.println("\n🔍 JSON错误定位:");
        
        try {
            // 尝试找到JSON错误的位置
            String[] lines = content.split("\n");
            for (int i = 0; i < lines.length; i++) {
                try {
                    // 尝试解析到第i行
                    String partialContent = String.join("\n", Arrays.copyOf(lines, i + 1));
                    new ObjectMapper().readTree(partialContent);
                } catch (Exception e) {
                    System.out.println("   ❌ 第 " + (i + 1) + " 行附近有JSON错误");
                    System.out.println("   错误行: " + lines[i]);
                    System.out.println("   错误信息: " + e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("   ❌ 无法定位JSON错误: " + e.getMessage());
        }
    }
    
    /**
     * 修复统计信息文件
     */
    public static void repairStatisticsFile(String inputDir, String outputDir) {
        System.out.println("🔧 开始修复统计信息文件...");
        
        try {
            File statsFile = new File(inputDir, "enhanced_column_statistics.json");
            File outputFile = new File(outputDir, "enhanced_column_statistics_repaired.json");
            
            if (!statsFile.exists()) {
                System.out.println("❌ 源文件不存在");
                return;
            }
            
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            
            // 读取原始统计信息
            Map<String, EnhancedTableStatistics> originalStats = objectMapper.readValue(statsFile, 
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, EnhancedTableStatistics.class));
            
            System.out.println("📊 原始表数: " + originalStats.size());
            
            // 修复统计信息
            Map<String, EnhancedTableStatistics> repairedStats = new HashMap<>();
            int repairedCount = 0;
            int removedCount = 0;
            
            for (Map.Entry<String, EnhancedTableStatistics> entry : originalStats.entrySet()) {
                String tableName = entry.getKey();
                EnhancedTableStatistics tableStats = entry.getValue();
                
                if (tableStats == null) {
                    System.out.println("   🗑️  移除null统计信息: " + tableName);
                    removedCount++;
                    continue;
                }
                
                // 修复表大小
                if (tableStats.getTableSize() <= 0) {
                    tableStats.setTableSize(1000); // 设置默认大小
                    System.out.println("   🔧 修复表大小: " + tableName + " -> 1000");
                    repairedCount++;
                }
                
                // 修复列信息
                if (tableStats.getColumns() == null) {
                    tableStats.setColumns(new HashMap<>());
                    System.out.println("   🔧 修复空列信息: " + tableName);
                    repairedCount++;
                }
                
                repairedStats.put(tableName, tableStats);
            }
            
            // 保存修复后的统计信息
            objectMapper.writeValue(outputFile, repairedStats);
            
            System.out.println("✅ 修复完成:");
            System.out.println("   修复表数: " + repairedCount);
            System.out.println("   移除表数: " + removedCount);
            System.out.println("   保留表数: " + repairedStats.size());
            System.out.println("   输出文件: " + outputFile.getAbsolutePath());
            
        } catch (Exception e) {
            System.out.println("❌ 修复失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 命令行接口
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java StatisticsDiagnosticTool <输入目录> [修复输出目录]");
            System.out.println("示例:");
            System.out.println("  java StatisticsDiagnosticTool /path/to/stats");
            System.out.println("  java StatisticsDiagnosticTool /path/to/stats /path/to/output");
            return;
        }
        
        String inputDir = args[0];
        
        // 诊断统计信息
        diagnoseStatisticsFile(inputDir);
        
        // 如果提供了输出目录，则进行修复
        if (args.length > 1) {
            String outputDir = args[1];
            System.out.println("\n" + "=".repeat(50));
            repairStatisticsFile(inputDir, outputDir);
        }
    }
} 