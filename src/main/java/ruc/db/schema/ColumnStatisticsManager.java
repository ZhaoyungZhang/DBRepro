package ruc.db.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列统计信息管理器
 * 单例模式，管理所有表的列统计信息
 * 
 * @author mirage
 */
public class ColumnStatisticsManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ColumnStatisticsManager.class);
    private static volatile ColumnStatisticsManager instance;
    
    // 存储结构：tableName -> columnName -> ColumnStatistics
    private final Map<String, Map<String, ColumnStatistics>> tableColumnStats;
    
    private ColumnStatisticsManager() {
        this.tableColumnStats = new ConcurrentHashMap<>();
    }
    
    /**
     * 获取单例实例
     */
    public static ColumnStatisticsManager getInstance() {
        if (instance == null) {
            synchronized (ColumnStatisticsManager.class) {
                if (instance == null) {
                    instance = new ColumnStatisticsManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 添加表的列统计信息
     */
    public void addTableColumnStatistics(String tableName, List<ColumnStatistics> columnStatsList) {
        Map<String, ColumnStatistics> columnStatsMap = new HashMap<>();
        for (ColumnStatistics stats : columnStatsList) {
            columnStatsMap.put(stats.getColumnName(), stats);
        }
        tableColumnStats.put(tableName, columnStatsMap);
        logger.info("已添加表 {} 的 {} 个列统计信息", tableName, columnStatsList.size());
    }
    
    /**
     * 添加单个列的统计信息
     */
    public void addColumnStatistics(String tableName, ColumnStatistics stats) {
        tableColumnStats.computeIfAbsent(tableName, k -> new HashMap<>())
                       .put(stats.getColumnName(), stats);
        logger.debug("已添加列 {} 的统计信息", stats.getColumnName());
    }
    
    /**
     * 获取指定列的统计信息
     */
    public ColumnStatistics getColumnStatistics(String tableName, String columnName) {
        Map<String, ColumnStatistics> columnStatsMap = tableColumnStats.get(tableName);
        if (columnStatsMap != null) {
            return columnStatsMap.get(columnName);
        }
        return null;
    }
    
    /**
     * 获取指定表的所有列统计信息
     */
    public List<ColumnStatistics> getTableColumnStatistics(String tableName) {
        Map<String, ColumnStatistics> columnStatsMap = tableColumnStats.get(tableName);
        if (columnStatsMap != null) {
            return new ArrayList<>(columnStatsMap.values());
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取所有表的统计信息
     */
    public Map<String, List<ColumnStatistics>> getAllTableColumnStatistics() {
        Map<String, List<ColumnStatistics>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, ColumnStatistics>> entry : tableColumnStats.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue().values()));
        }
        return result;
    }
    
    /**
     * 检查是否包含指定表的统计信息
     */
    public boolean containsTable(String tableName) {
        return tableColumnStats.containsKey(tableName);
    }
    
    /**
     * 检查是否包含指定列的统计信息
     */
    public boolean containsColumn(String tableName, String columnName) {
        Map<String, ColumnStatistics> columnStatsMap = tableColumnStats.get(tableName);
        return columnStatsMap != null && columnStatsMap.containsKey(columnName);
    }
    
    /**
     * 获取指定表的列数量
     */
    public int getColumnCount(String tableName) {
        Map<String, ColumnStatistics> columnStatsMap = tableColumnStats.get(tableName);
        return columnStatsMap != null ? columnStatsMap.size() : 0;
    }
    
    /**
     * 获取所有已加载的表名
     */
    public Set<String> getAllTableNames() {
        return new HashSet<>(tableColumnStats.keySet());
    }
    
    /**
     * 清除指定表的统计信息
     */
    public void removeTable(String tableName) {
        tableColumnStats.remove(tableName);
        logger.info("已移除表 {} 的统计信息", tableName);
    }
    
    /**
     * 清除所有统计信息
     */
    public void clear() {
        tableColumnStats.clear();
        logger.info("已清除所有列统计信息");
    }
    
    /**
     * 获取统计信息摘要
     */
    public String getStatisticsSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("列统计信息摘要:\n");
        summary.append(String.format("总表数: %d\n", tableColumnStats.size()));
        
        int totalColumns = 0;
        for (Map.Entry<String, Map<String, ColumnStatistics>> entry : tableColumnStats.entrySet()) {
            String tableName = entry.getKey();
            int columnCount = entry.getValue().size();
            totalColumns += columnCount;
            summary.append(String.format("  - %s: %d 列\n", tableName, columnCount));
        }
        summary.append(String.format("总列数: %d", totalColumns));
        
        return summary.toString();
    }
    
    /**
     * 查找具有高NDV的列（用于索引建议等）
     */
    public List<ColumnStatistics> getHighNdvColumns(double threshold) {
        List<ColumnStatistics> highNdvColumns = new ArrayList<>();
        
        for (Map<String, ColumnStatistics> tableStats : tableColumnStats.values()) {
            for (ColumnStatistics stats : tableStats.values()) {
                Double ndv = stats.getNDistinctAsDouble();
                if (ndv != null && ndv >= threshold) {
                    highNdvColumns.add(stats);
                }
            }
        }
        
        return highNdvColumns;
    }
    
    /**
     * 查找NULL值比例高的列
     */
    public List<ColumnStatistics> getHighNullColumns(double threshold) {
        List<ColumnStatistics> highNullColumns = new ArrayList<>();
        
        for (Map<String, ColumnStatistics> tableStats : tableColumnStats.values()) {
            for (ColumnStatistics stats : tableStats.values()) {
                Double nullFrac = stats.getNullFractionAsDouble();
                if (nullFrac != null && nullFrac >= threshold) {
                    highNullColumns.add(stats);
                }
            }
        }
        
        return highNullColumns;
    }
}