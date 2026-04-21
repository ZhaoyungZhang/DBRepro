package ruc.db.rsgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnStatistics;
import ruc.db.schema.ColumnStatisticsManager;
import ruc.db.schema.ColumnType;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;

/**
 * RSGen外键关系处理器
 * 负责处理数据库中的外键依赖关系，确定数据生成的正确顺序
 * 
 * 核心功能：
 * 1. 分析外键依赖图
 * 2. 创建拓扑排序
 * 3. 管理bucket对齐过程
 * 4. 协调主外键数据生成
 * 
 * @author RSGen Implementation
 */
public class ForeignKeyHandler {
    private static final Logger logger = LoggerFactory.getLogger(ForeignKeyHandler.class);
    
    private final BucketAlignment bucketAlignment;
    private final Map<String, Set<String>> dependencyGraph; // table -> referenced tables
    private final Map<String, Set<String>> reverseDependencyGraph; // table -> referencing tables

    // 新增：存储所有被引用的列（不管是主键还是外键）
    private final Set<String> referencedColumns;
    
    public ForeignKeyHandler() {
        this.bucketAlignment = new BucketAlignment();
        this.dependencyGraph = new HashMap<>();
        this.reverseDependencyGraph = new HashMap<>();
        this.referencedColumns = new HashSet<>();
        buildDependencyGraphs();
    }
    
    /**
     * 构建依赖关系图
     */
    private void buildDependencyGraphs() {
        logger.info("开始构建外键依赖关系图");
        
        try {
            TableManager tableManager = TableManager.getInstance();
            Set<String> processedReferences = new HashSet<>(); // 移到外层，跟踪已处理的表间引用关系
            Set<String> tablesWithForeignKeys = new HashSet<>(); // 记录有外键关系的表
            Set<String> rootPartitionTables = new HashSet<>(); // 记录根分区表
            
            // 首先收集所有有外键关系的表和根分区表
            for (String tableName : tableManager.getSchemas().keySet()) {
                Table table = tableManager.getSchema(tableName);
                Map<String, String> foreignKeys = table.getForeignKeys();
                
                if (!foreignKeys.isEmpty()) {
                    tablesWithForeignKeys.add(tableName);
                    // 初始化有外键关系的表的空依赖集合
                    dependencyGraph.put(tableName, new HashSet<>());
                    reverseDependencyGraph.put(tableName, new HashSet<>());
                }
                
                // 检查是否是根分区表
                if (PartitionTableManager.getInstance().isRootPartitionTable(tableName)) {
                    rootPartitionTables.add(tableName);
                    // 确保根分区表也被加入依赖图
                    if (!dependencyGraph.containsKey(tableName)) {
                        dependencyGraph.put(tableName, new HashSet<>());
                        reverseDependencyGraph.put(tableName, new HashSet<>());
                    }
                }
            }
            
            // 然后构建依赖关系
            for (String tableName : tablesWithForeignKeys) {
                Table table = tableManager.getSchema(tableName);
                
                // 分析外键关系
                Map<String, String> foreignKeys = table.getForeignKeys();
                
                for (Map.Entry<String, String> fkEntry : foreignKeys.entrySet()) {
                    String referencedColumn = fkEntry.getValue();
                    String referencedTable = extractTableName(referencedColumn);
                    
                    if (referencedTable != null && !referencedTable.equals(tableName)) {
                        // 确保被引用的表也被加入依赖图
                        if (!dependencyGraph.containsKey(referencedTable)) {
                            dependencyGraph.put(referencedTable, new HashSet<>());
                            reverseDependencyGraph.put(referencedTable, new HashSet<>());
                        }
                        
                        // 检查是否已经添加了这个表间的依赖关系
                        String relationKey = tableName + " -> " + referencedTable;
                        if (!processedReferences.contains(relationKey)) {
                            dependencyGraph.get(tableName).add(referencedTable);
                            reverseDependencyGraph.get(referencedTable).add(tableName);
                            processedReferences.add(relationKey);
                            
                            logger.trace("发现外键关系: {} -> {}", tableName, referencedTable);
                        } else {
                            logger.debug("跳过重复的表间依赖关系: {} -> {} (多列外键)", tableName, referencedTable);
                        }
                        // 添加到被引用列集合
                        String referencedColumnName = extractColumnName(referencedColumn);
                        if (referencedColumnName != null) {
                            String standardReferencedKey = toStandardKey(referencedTable, referencedColumnName);
                            referencedColumns.add(standardReferencedKey);
                            logger.debug("记录被引用列: {} (标准格式: {})", referencedColumn, standardReferencedKey);
                        }
                    }
                }
            }
            
            logger.info("依赖关系图构建完成，涉及 {} 个表", dependencyGraph.size());
            logger.info("有外键关系的表: {} 个", tablesWithForeignKeys.size());
            logger.info("根分区表: {} 个", rootPartitionTables.size());
            logger.info("被引用的列: {}", referencedColumns);
            
        } catch (Exception e) {
            logger.error("构建依赖关系图时出错: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 从列的规范名称中提取表名
     */
    private String extractTableName(String canonicalColumnName) {
        if (canonicalColumnName == null) return null;
        
        String[] parts = canonicalColumnName.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return null;
    }

    /**
     * 转换为标准key格式：table.column
     */
    private String toStandardKey(String tableName, String columnName) {
        String cleanTable = cleanTableName(tableName);
        return cleanTable + "." + columnName;
    }

    /**
     * 清理表名，移除schema前缀
     */
    private String cleanTableName(String tableName) {
        if (tableName == null) return "";
        
        // 移除schema前缀 (public.table -> table)
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.");
            return parts[parts.length - 1]; // 取最后一部分作为表名
        }
        
        return tableName;
    }
    
    /**
     * 获取数据生成的拓扑顺序
     * 被引用的表应该先生成数据，包含所有表（普通表、分区表等）
     */
    public List<String> getGenerationOrder() {
        logger.info("计算数据生成的拓扑顺序");
        
        List<String> order = new ArrayList<>();
        TableManager tableManager = TableManager.getInstance();
        
        // 获取所有表，包括普通表、分区表等
        Set<String> allTables = new HashSet<>(tableManager.getSchemas().keySet());
        logger.info("包含所有表进行拓扑排序，总共 {} 个表", allTables.size());
        
        // 使用Kahn算法进行拓扑排序
        Map<String, Integer> inDegree = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        
        // 初始化所有表的入度为0
        for (String table : allTables) {
            inDegree.put(table, 0);
        }
        
        // 计算入度：如果table依赖于dependency，则table的入度增加
        for (String table : allTables) {
            Set<String> dependencies = dependencyGraph.getOrDefault(table, new HashSet<>());
            for (String dependency : dependencies) {
                if (allTables.contains(dependency)) {
                    inDegree.put(table, inDegree.get(table) + 1);
                }
            }
        }
        
        // 找到所有入度为0的表（没有外键依赖的表，应该先生成）
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }
        
        // 进行拓扑排序
        while (!queue.isEmpty()) {
            String current = queue.poll();
            order.add(current);
            
            // 找到所有引用当前表的表（即依赖于当前表的表）
            Set<String> dependents = reverseDependencyGraph.getOrDefault(current, new HashSet<>());
            for (String dependent : dependents) {
                if (allTables.contains(dependent)) {
                    int newInDegree = inDegree.get(dependent) - 1;
                    inDegree.put(dependent, newInDegree);
                    if (newInDegree == 0) {
                        queue.offer(dependent);
                    }
                }
            }
        }
        
        // 检查是否有循环依赖或遗漏的表
        logger.info("拓扑排序结果: 处理了 {} 个表，总共 {} 个表", order.size(), allTables.size());
        if (order.size() != allTables.size()) {
            logger.warn("检测到循环依赖或遗漏的表，已处理的表: {}", order);
            Set<String> remaining = new HashSet<>(allTables);
            remaining.removeAll(order);
            logger.warn("未处理的表: {}", remaining);
            // 将未处理的表添加到结果中
            order.addAll(remaining);
        }
        
        logger.info("拓扑排序完成，顺序: {}", order);
        return order;
    }

    /**
     * 获取所有被引用的列（标准格式：table.column）
     */
    public Set<String> getReferencedColumns() {
        return new HashSet<>(referencedColumns);
    }
    
    /**
     * 检查指定列是否被其他表引用
     */
    public boolean isColumnReferenced(String tableName, String columnName) {
        String standardKey = toStandardKey(tableName, columnName);
        return referencedColumns.contains(standardKey);
    }
    
    /**
     * 检查指定列是否被其他表引用（使用标准key）
     */
    public boolean isColumnReferencedByStandardKey(String standardKey) {
        return referencedColumns.contains(standardKey);
    }

    /**
     * 获取表的所有外键关系信息
     */
    public List<ForeignKeyRelation> getForeignKeyRelations(String tableName) {
        List<ForeignKeyRelation> relations = new ArrayList<>();
        
        try {
            TableManager tableManager = TableManager.getInstance();
            Table table = tableManager.getSchema(tableName);
            
            Map<String, String> foreignKeys = table.getForeignKeys();
            for (Map.Entry<String, String> fkEntry : foreignKeys.entrySet()) {
                String foreignKeyColumn = fkEntry.getKey();
                String referencedColumn = fkEntry.getValue();
                String referencedTable = extractTableName(referencedColumn);
                
                if (referencedTable != null) {
                    relations.add(new ForeignKeyRelation(
                        tableName, 
                        extractColumnName(foreignKeyColumn),
                        referencedTable, 
                        extractColumnName(referencedColumn)
                    ));
                }
            }
            
        } catch (Exception e) {
            logger.error("获取表 {} 的外键关系时出错: {}", tableName, e.getMessage());
        }
        
        return relations;
    }
    
    /**
     * 从规范列名中提取列名
     */
    private String extractColumnName(String canonicalColumnName) {
        if (canonicalColumnName == null) return null;
        
        String[] parts = canonicalColumnName.split("\\.");
        if (parts.length >= 3) {
            return parts[2];
        }
        return canonicalColumnName;
    }
    
    /**
     * 对齐外键关系中的buckets
     */
    public Map<String, List<Bucket>> alignForeignKeyBuckets(String tableName, 
                                                            Map<String, List<Bucket>> originalBuckets) {
        logger.info("开始对齐表 {} 的外键buckets", tableName);
        
        Map<String, List<Bucket>> alignedBuckets = new HashMap<>(originalBuckets);
        
        List<ForeignKeyRelation> fkRelations = getForeignKeyRelations(tableName);
        
        for (ForeignKeyRelation relation : fkRelations) {
            try {
                // 获取外键列的buckets
                List<Bucket> fkBuckets = alignedBuckets.get(relation.getForeignKeyColumn());
                if (fkBuckets == null) {
                    logger.warn("未找到外键列 {} 的buckets", relation.getForeignKeyColumn());
                    continue;
                }
                
                // 获取被引用列的buckets（从已生成的数据中）
                List<Bucket> referencedBuckets = getReferencedColumnBuckets(relation);
                if (referencedBuckets == null) {
                    logger.warn("未找到被引用列 {} 的buckets", relation.getReferencedColumn());
                    continue;
                }
                
                // 执行对齐
                BucketAlignment.AlignedBuckets aligned = bucketAlignment.alignBuckets(
                    fkBuckets, referencedBuckets,
                    relation.getForeignKeyColumn(), relation.getReferencedColumn()
                );
                
                // 更新外键列的buckets
                alignedBuckets.put(relation.getForeignKeyColumn(), aligned.getForeignKeyBuckets());
                
                logger.info("成功对齐外键关系: {}.{} -> {}.{}", 
                           relation.getTableName(), relation.getForeignKeyColumn(),
                           relation.getReferencedTable(), relation.getReferencedColumn());
                
            } catch (Exception e) {
                logger.error("对齐外键关系时出错: {}", e.getMessage(), e);
            }
        }
        
        return alignedBuckets;
    }
    
    /**
     * 获取被引用列的buckets
     * 这里需要从已经生成的统计信息中获取
     */
    private List<Bucket> getReferencedColumnBuckets(ForeignKeyRelation relation) {
        try {
            // 获取被引用列的统计信息
            ColumnStatisticsManager manager = ColumnStatisticsManager.getInstance();
            List<ColumnStatistics> refTableStats = manager.getTableColumnStatistics(relation.getReferencedTable());
            
            if (refTableStats == null) {
                return null;
            }
            
            // 查找被引用列的统计信息
            String referencedColumnCanonical = relation.getReferencedTable() + "." + relation.getReferencedColumn();
            ColumnStatistics refColumnStats = refTableStats.stream()
                .filter(stats -> stats.getColumnName().equals(referencedColumnCanonical))
                .findFirst()
                .orElse(null);
            
            if (refColumnStats == null) {
                return null;
            }
            
            // 已不支持 legacy stats，直接返回 null 并加日志
            logger.error("getReferencedColumnBuckets: 仅支持 EnhancedColumnStatistics，当前类型为 {}，未生成 buckets。", refColumnStats.getClass().getName());
            return null;
        } catch (Exception e) {
            logger.error("获取被引用列buckets时出错: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取列类型（简化实现）
     */
    private ColumnType getColumnType(String columnName) {
        try {
            return ColumnManager.getInstance().getColumnType(columnName);
        } catch (Exception e) {
            logger.debug("获取列类型失败，使用默认类型: {}", e.getMessage());
            return ColumnType.INTEGER; // 默认类型
        }
    }
    
    /**
     * 检查表是否有外键依赖
     */
    public boolean hasOutgoingForeignKeys(String tableName) {
        return dependencyGraph.containsKey(tableName) && !dependencyGraph.get(tableName).isEmpty();
    }
    
    /**
     * 检查表是否被其他表引用
     */
    public boolean hasIncomingForeignKeys(String tableName) {
        return reverseDependencyGraph.containsKey(tableName) && !reverseDependencyGraph.get(tableName).isEmpty();
    }
    
    /**
     * 外键关系数据类
     */
    public static class ForeignKeyRelation {
        private final String tableName;
        private final String foreignKeyColumn;
        private final String referencedTable;
        private final String referencedColumn;
        
        public ForeignKeyRelation(String tableName, String foreignKeyColumn, 
                                  String referencedTable, String referencedColumn) {
            this.tableName = tableName;
            this.foreignKeyColumn = foreignKeyColumn;
            this.referencedTable = referencedTable;
            this.referencedColumn = referencedColumn;
        }
        
        public String getTableName() { return tableName; }
        public String getForeignKeyColumn() { return foreignKeyColumn; }
        public String getReferencedTable() { return referencedTable; }
        public String getReferencedColumn() { return referencedColumn; }
        
        @Override
        public String toString() {
            return String.format("%s.%s -> %s.%s", 
                tableName, foreignKeyColumn, referencedTable, referencedColumn);
        }
    }
}
