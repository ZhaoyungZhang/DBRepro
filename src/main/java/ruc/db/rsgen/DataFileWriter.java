package ruc.db.rsgen;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedTableStatistics;

/**
 * 数据文件与DDL文件写入工具类
 * 专注于文件I/O和DDL生成
 */
public class DataFileWriter {
    private static final Logger logger = LoggerFactory.getLogger(DataFileWriter.class);

    /**
     * 写入数据到文件
     * @param tableName 表名
     * @param tableStats 表统计信息
     * @param generatedData 列数据
     * @param tableSize 行数
     * @param outputDir 输出目录
     * @throws IOException 文件写入异常
     */
    public void writeDataToFiles(String tableName, EnhancedTableStatistics tableStats,
                                 Map<String, Object[]> generatedData, long tableSize, String outputDir) throws IOException {
        // 创建data目录
        File dataDir = new File(outputDir, "data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        // 使用TPC-H标准的.tbl格式：table.tbl
        String simpleTableName = tableName.split("\\.")[1]; // 提取表名部分
        File dataFile = new File(dataDir, simpleTableName + ".tbl");
        logger.info("写入数据到文件: {}", dataFile.getAbsolutePath());
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(dataFile))) {
            // 获取列顺序
            List<String> columnOrder = getColumnOrder(tableStats);
            // 逐行写入数据（不包含头）
            for (int i = 0; i < tableSize; i++) {
                StringBuilder row = new StringBuilder();
                for (int j = 0; j < columnOrder.size(); j++) {
                    String columnName = columnOrder.get(j);
                    Object[] columnData = generatedData.get(columnName);
                    if (columnData != null && i < columnData.length) {
                        Object value = columnData[i];
                        if (value != null) {
                            row.append(escapeTblValue(value.toString()));
                        } else {
                            row.append("\\N"); // PostgreSQL的NULL表示
                        }
                    } else {
                        row.append("\\N");
                    }
                    if (j < columnOrder.size() - 1) {
                        row.append("|"); // 使用|分隔符
                    }
                }
                writer.write(row.toString());
                writer.newLine();
            }
        }
        logger.info("成功写入 {} 行数据到 {}", tableSize, dataFile.getName());
    }

    /**
     * 转义TBL值（TPC-H格式）
     */
    public String escapeTblValue(String value) {
        if (value == null) return "";
        // TBL格式不需要特殊转义，直接返回原始值
        return value;
    }

    /**
     * 获取列的正确顺序
     * 可直接用于数据写入
     */
    public List<String> getColumnOrder(EnhancedTableStatistics tableStats) {
        // 首先尝试从TableManager获取正确的列顺序
        try {
            String tableName = tableStats.getTableName();
            ruc.db.schema.Table table = ruc.db.schema.TableManager.getInstance().getSchemas().get(tableName);
            if (table != null && table.getCanonicalColumnNames() != null) {
                logger.debug("使用schema.json中定义的列顺序: {}", table.getCanonicalColumnNames());
                return new java.util.ArrayList<>(table.getCanonicalColumnNames());
            }
        } catch (Exception e) {
            logger.warn("无法从TableManager获取列顺序，使用fallback方式: {}", e.getMessage());
        }
        // Fallback：如果无法从TableManager获取，则使用原有的逻辑
        logger.debug("使用fallback列顺序生成逻辑");
        java.util.List<String> primaryKeys = new java.util.ArrayList<>();
        java.util.List<String> foreignKeys = new java.util.ArrayList<>();
        java.util.List<String> regularColumns = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics> entry : tableStats.getColumns().entrySet()) {
            String columnName = entry.getKey();
            ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics colStats = entry.getValue();
            if (colStats.isPrimaryKey()) {
                primaryKeys.add(columnName);
            } else if (colStats.isForeignKey()) {
                foreignKeys.add(columnName);
            } else {
                regularColumns.add(columnName);
            }
        }
        java.util.List<String> columnOrder = new java.util.ArrayList<>();
        columnOrder.addAll(primaryKeys);
        columnOrder.addAll(foreignKeys);
        columnOrder.addAll(regularColumns);
        return columnOrder;
    }
}
