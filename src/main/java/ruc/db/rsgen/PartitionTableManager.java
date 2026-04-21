package ruc.db.rsgen;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 分区表元数据管理器
 * 负责管理分区表与子表的映射关系
 * 
 * @author RSGen Implementation
 */
public class PartitionTableManager {
    private static final Logger logger = LoggerFactory.getLogger(PartitionTableManager.class);
    private static final PartitionTableManager INSTANCE = new PartitionTableManager();
    
    // 分区表名 -> 子表名集合的映射
    private final Map<String, Set<String>> partitionToChildren = new HashMap<>();
    
    // 子表名 -> 父表名的映射
    private final Map<String, String> childToParent = new HashMap<>();
    
    private PartitionTableManager() {
    }
    
    public static PartitionTableManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 添加分区表及其子表关系
     * 
     * @param partitionTableName 分区表名
     * @param childTableName 子表名
     */
    public void addPartitionRelation(String partitionTableName, String childTableName) {
        // 添加分区表到子表的映射
        partitionToChildren.computeIfAbsent(partitionTableName, k -> new HashSet<>()).add(childTableName);
        
        // 添加子表到分区表的映射
        childToParent.put(childTableName, partitionTableName);
        
        logger.debug("添加分区关系: 分区表 {} -> 子表 {}", partitionTableName, childTableName);
    }
    
    /**
     * 检查表是否为分区表
     * 
     * @param tableName 表名
     * @return 是否为分区表
     */
    public boolean isPartitionTable(String tableName) {
        return partitionToChildren.containsKey(tableName);
    }
    
    /**
     * 检查表是否为子表
     * 
     * @param tableName 表名
     * @return 是否为子表
     */
    public boolean isChildTable(String tableName) {
        return childToParent.containsKey(tableName);
    }
    
    /**
     * 获取分区表的所有子表
     * 
     * @param partitionTableName 分区表名
     * @return 子表名集合
     */
    public Set<String> getChildTables(String partitionTableName) {
        return partitionToChildren.getOrDefault(partitionTableName, new HashSet<>());
    }
    
    /**
     * 获取子表的父表
     * 
     * @param childTableName 子表名
     * @return 父表名，如果不是子表则返回null
     */
    public String getParentTable(String childTableName) {
        return childToParent.get(childTableName);
    }
    
    /**
     * 获取所有分区表名
     * 
     * @return 分区表名集合
     */
    public Set<String> getAllPartitionTables() {
        return new HashSet<>(partitionToChildren.keySet());
    }
    
    /**
     * 检查是否有分区表
     * 
     * @return 是否存在分区表
     */
    public boolean hasPartitionTables() {
        return !partitionToChildren.isEmpty();
    }
    
    /**
     * 清空所有分区关系
     */
    public void clear() {
        partitionToChildren.clear();
        childToParent.clear();
        logger.info("清空所有分区表关系");
    }
    
    /**
     * 将分区关系保存到文件
     * 
     * @param outputDir 输出目录
     * @throws IOException 文件操作异常
     */
    public void saveToFile(String outputDir) throws IOException {
        if (!hasPartitionTables()) {
            logger.info("没有分区表关系需要保存");
            return;
        }
        
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        Map<String, Object> partitionInfo = new HashMap<>();
        partitionInfo.put("partitionToChildren", partitionToChildren);
        partitionInfo.put("childToParent", childToParent);
        
        File outputFile = new File(outputDir, "partition_relations.json");
        objectMapper.writeValue(outputFile, partitionInfo);
        
        logger.info("分区表关系已保存到文件: {}", outputFile.getAbsolutePath());
        logger.info("分区表数量: {}, 子表数量: {}", partitionToChildren.size(), childToParent.size());
    }
    
    /**
     * 从文件加载分区关系
     * 
     * @param inputDir 输入目录
     * @throws IOException 文件操作异常
     */
    @SuppressWarnings("unchecked")
    public void loadFromFile(String inputDir) throws IOException {
        File inputFile = new File(inputDir, "partition_relations.json");
        if (!inputFile.exists()) {
            logger.info("分区关系文件不存在: {}", inputFile.getAbsolutePath());
            return;
        }
        
        // 如果已经有分区表数据，跳过重复加载
        if (hasPartitionTables()) {
            logger.info("分区表关系信息已存在，跳过重复加载: {}", inputFile.getAbsolutePath());
            return;
        }
        
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> partitionInfo = objectMapper.readValue(inputFile, Map.class);
        
        // 清空现有关系（此时应该是空的）
        clear();
        
        // 加载分区表到子表的映射
        Map<String, Object> partitionToChildrenData = (Map<String, Object>) partitionInfo.get("partitionToChildren");
        if (partitionToChildrenData != null) {
            for (Map.Entry<String, Object> entry : partitionToChildrenData.entrySet()) {
                String partitionTable = entry.getKey();
                @SuppressWarnings("unchecked")
                List<String> childrenList = (List<String>) entry.getValue();
                Set<String> children = new HashSet<>(childrenList);
                partitionToChildren.put(partitionTable, children);
            }
        }
        
        // 加载子表到父表的映射
        Map<String, String> childToParentData = (Map<String, String>) partitionInfo.get("childToParent");
        if (childToParentData != null) {
            childToParent.putAll(childToParentData);
        }
        
        logger.info("从文件加载分区表关系: {}", inputFile.getAbsolutePath());
        logger.info("分区表数量: {}, 子表数量: {}", partitionToChildren.size(), childToParent.size());
    }
    
    /**
     * 打印分区关系信息（用于调试）
     */
    public void printPartitionInfo() {
        if (!hasPartitionTables()) {
            logger.info("没有检测到分区表");
            return;
        }
        
        logger.info("=== 分区表关系信息 ===");
        for (Map.Entry<String, Set<String>> entry : partitionToChildren.entrySet()) {
            String partitionTable = entry.getKey();
            Set<String> children = entry.getValue();
            logger.info("分区表: {} -> 子表: {}", partitionTable, children);
        }
    }
    
    /**
     * 检查给定的表是否为叶子表（最底层的子表，实际存储数据）
     * 叶子表既不是分区表父表，也不是中间分区表
     * 
     * @param tableName 表名
     * @return 如果是叶子表返回true
     */
    public boolean isLeafTable(String tableName) {
        // 如果是分区表父表，则不是叶子表
        if (isPartitionTable(tableName)) {
            return false;
        }
        
        // 如果是子表但同时也是分区表（中间分区），则不是叶子表
        if (isChildTable(tableName) && isPartitionTable(tableName)) {
            return false;
        }
        
        // 如果是子表但不是分区表，则是叶子表
        if (isChildTable(tableName)) {
            return true;
        }
        
        // 如果既不是分区表也不是子表，则是普通表（也算叶子表）
        return true;
    }
    
    /**
     * 检查给定的表是否为中间分区表（既是子表又是分区表）
     * 
     * @param tableName 表名
     * @return 如果是中间分区表返回true
     */
    public boolean isIntermediatePartition(String tableName) {
        return isChildTable(tableName) && isPartitionTable(tableName);
    }
    
    /**
     * 检查给定的表是否为任何类型的分区表（包括根分区表和中间分区表）
     * 
     * @param tableName 表名
     * @return 如果是任何类型的分区表返回true
     */
    public boolean isAnyPartitionTable(String tableName) {
        return isPartitionTable(tableName);
    }
    
    /**
     * 检查给定的表是否为根分区表（是分区表但不是子表）
     * 
     * @param tableName 表名
     * @return 如果是根分区表返回true
     */
    public boolean isRootPartitionTable(String tableName) {
        return isPartitionTable(tableName) && !isChildTable(tableName);
    }
    
    /**
     * 获取所有根分区表
     * 
     * @return 根分区表列表
     */
    public List<String> getAllRootPartitionTables() {
        List<String> rootTables = new ArrayList<>();
        for (String partitionTable : partitionToChildren.keySet()) {
            if (isRootPartitionTable(partitionTable)) {
                rootTables.add(partitionTable);
            }
        }
        return rootTables;
    }
} 