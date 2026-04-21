package ruc.db.rsgen;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 分区表树管理器
 * 使用多叉树结构管理分区表的层次关系
 * 
 * @author RSGen Implementation
 */
public class PartitionTreeManager {
    private static final Logger logger = LoggerFactory.getLogger(PartitionTreeManager.class);
    private static final PartitionTreeManager INSTANCE = new PartitionTreeManager();
    
    // 表名 -> 树节点的映射
    private final Map<String, PartitionTreeNode> tableNodes = new HashMap<>();
    
    // 根节点列表（可能有多个根分区表）
    private final List<PartitionTreeNode> rootNodes = new ArrayList<>();
    
    // 普通表节点列表
    private final List<PartitionTreeNode> normalNodes = new ArrayList<>();
    
    private PartitionTreeManager() {
    }
    
    public static PartitionTreeManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 添加分区关系
     * 
     * @param parentTableName 父表名
     * @param childTableName 子表名
     */
    public void addPartitionRelation(String parentTableName, String childTableName) {
        PartitionTreeNode parentNode = getOrCreateNode(parentTableName);
        PartitionTreeNode childNode = getOrCreateNode(childTableName);
        
        // 建立父子关系
        parentNode.addChild(childNode);
        
        logger.debug("添加分区关系: {} -> {}", parentTableName, childTableName);
    }
    
    /**
     * 重新分析所有节点的类型
     * 应在所有分区关系添加完毕后调用
     */
    public void finalizeNodeTypes() {
        logger.info("开始重新分析节点类型");
        
        // 清空分类列表
        rootNodes.clear();
        normalNodes.clear();
        
        // 重新分析每个节点的类型
        for (PartitionTreeNode node : tableNodes.values()) {
            PartitionTreeNode.NodeType correctType = determineNodeType(node);
            node.setNodeType(correctType);
            
            // 添加到相应的分类列表
            if (correctType == PartitionTreeNode.NodeType.ROOT) {
                rootNodes.add(node);
            } else if (correctType == PartitionTreeNode.NodeType.NORMAL) {
                normalNodes.add(node);
            }
        }
        
        logger.info("节点类型重新分析完成");
    }
    
    /**
     * 确定节点的正确类型
     * 
     * @param node 节点
     * @return 正确的节点类型
     */
    private PartitionTreeNode.NodeType determineNodeType(PartitionTreeNode node) {
        boolean hasParent = node.getParent() != null;
        boolean hasChildren = !node.getChildren().isEmpty();
        
        if (!hasParent && !hasChildren) {
            // 既没有父节点也没有子节点 -> 普通表
            return PartitionTreeNode.NodeType.NORMAL;
        } else if (!hasParent && hasChildren) {
            // 没有父节点但有子节点 -> 根分区表
            return PartitionTreeNode.NodeType.ROOT;
        } else if (hasParent && hasChildren) {
            // 既有父节点又有子节点 -> 中间分区表
            return PartitionTreeNode.NodeType.INTERMEDIATE;
        } else {
            // 有父节点但没有子节点 -> 叶子表
            return PartitionTreeNode.NodeType.LEAF;
        }
    }
    

    

    
    /**
     * 获取节点
     * 
     * @param tableName 表名
     * @return 节点，如果不存在返回null
     */
    public PartitionTreeNode getNode(String tableName) {
        return tableNodes.get(tableName);
    }
    
    /**
     * 获取或创建节点（公开方法）
     * 
     * @param tableName 表名
     * @return 节点
     */
    public PartitionTreeNode getOrCreateNode(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        if (node == null) {
            // 新节点默认为普通表
            node = new PartitionTreeNode(tableName, PartitionTreeNode.NodeType.NORMAL);
            tableNodes.put(tableName, node);
            normalNodes.add(node);
        }
        return node;
    }
    
    /**
     * 检查表是否存在
     * 
     * @param tableName 表名
     * @return 是否存在
     */
    public boolean containsTable(String tableName) {
        return tableNodes.containsKey(tableName);
    }
    
    /**
     * 获取表的类型
     * 
     * @param tableName 表名
     * @return 表类型，如果不存在返回null
     */
    public PartitionTreeNode.NodeType getTableType(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null ? node.getNodeType() : null;
    }
    
    /**
     * 检查是否为根分区表
     * 
     * @param tableName 表名
     * @return 是否为根分区表
     */
    public boolean isRootPartitionTable(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null && node.getNodeType() == PartitionTreeNode.NodeType.ROOT;
    }
    
    /**
     * 检查是否为中间分区表
     * 
     * @param tableName 表名
     * @return 是否为中间分区表
     */
    public boolean isIntermediatePartition(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null && node.getNodeType() == PartitionTreeNode.NodeType.INTERMEDIATE;
    }
    
    /**
     * 检查是否为叶子表
     * 
     * @param tableName 表名
     * @return 是否为叶子表
     */
    public boolean isLeafTable(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null && node.getNodeType() == PartitionTreeNode.NodeType.LEAF;
    }
    
    /**
     * 检查是否为普通表
     * 
     * @param tableName 表名
     * @return 是否为普通表
     */
    public boolean isNormalTable(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null && node.getNodeType() == PartitionTreeNode.NodeType.NORMAL;
    }
    
    /**
     * 检查是否为任何类型的分区表
     * 
     * @param tableName 表名
     * @return 是否为分区表
     */
    public boolean isPartitionTable(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null && (node.getNodeType() == PartitionTreeNode.NodeType.ROOT || 
                               node.getNodeType() == PartitionTreeNode.NodeType.INTERMEDIATE);
    }
    
    /**
     * 检查是否为子表
     * 
     * @param tableName 表名
     * @return 是否为子表
     */
    public boolean isChildTable(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null && node.getParent() != null;
    }
    
    /**
     * 获取父表名
     * 
     * @param tableName 表名
     * @return 父表名，如果没有父表返回null
     */
    public String getParentTable(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        if (node != null && node.getParent() != null) {
            return node.getParent().getTableName();
        }
        return null;
    }
    
    /**
     * 获取直接子表名列表
     * 
     * @param tableName 表名
     * @return 子表名列表
     */
    public List<String> getChildTables(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        if (node != null) {
            return node.getChildren().stream()
                    .map(PartitionTreeNode::getTableName)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取所有叶子表名列表（递归）
     * 
     * @param tableName 表名
     * @return 叶子表名列表
     */
    public List<String> getAllLeafTables(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        if (node != null) {
            return node.getAllLeafNodes().stream()
                    .map(PartitionTreeNode::getTableName)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取所有根分区表
     * 
     * @return 根分区表名列表
     */
    public List<String> getAllRootPartitionTables() {
        return rootNodes.stream()
                .map(PartitionTreeNode::getTableName)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有中间分区表
     * 
     * @return 中间分区表名列表
     */
    public List<String> getAllIntermediatePartitionTables() {
        return tableNodes.values().stream()
                .filter(node -> node.getNodeType() == PartitionTreeNode.NodeType.INTERMEDIATE)
                .map(PartitionTreeNode::getTableName)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有叶子表
     * 
     * @return 叶子表名列表
     */
    public List<String> getAllLeafTables() {
        return tableNodes.values().stream()
                .filter(node -> node.getNodeType() == PartitionTreeNode.NodeType.LEAF)
                .map(PartitionTreeNode::getTableName)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有普通表
     * 
     * @return 普通表名列表
     */
    public List<String> getAllNormalTables() {
        return normalNodes.stream()
                .map(PartitionTreeNode::getTableName)
                .collect(Collectors.toList());
    }
    
    /**
     * 按阶段分组表
     * 
     * @return 分组结果
     */
    public Map<String, List<String>> groupTablesByPhase() {
        Map<String, List<String>> groups = new HashMap<>();
        
        // 第一阶段：普通表 + 根分区表
        List<String> phase1 = new ArrayList<>();
        phase1.addAll(getAllNormalTables());
        phase1.addAll(getAllRootPartitionTables());
        groups.put("phase1", phase1);
        
        // 第二阶段：中间分区表
        groups.put("phase2", getAllIntermediatePartitionTables());
        
        // 第三阶段：叶子表
        groups.put("phase3", getAllLeafTables());
        
        return groups;
    }
    
    /**
     * 设置表的大小
     * 
     * @param tableName 表名
     * @param tableSize 表大小
     */
    public void setTableSize(String tableName, int tableSize) {
        PartitionTreeNode node = tableNodes.get(tableName);
        if (node != null) {
            node.setTableSize(tableSize);
        }
    }
    
    /**
     * 获取表的大小
     * 
     * @param tableName 表名
     * @return 表大小，如果表不存在返回0
     */
    public int getTableSize(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null ? node.getTableSize() : 0;
    }
    
    /**
     * 设置表是否有schema
     * 
     * @param tableName 表名
     * @param hasSchema 是否有schema
     */
    public void setHasSchema(String tableName, boolean hasSchema) {
        PartitionTreeNode node = tableNodes.get(tableName);
        if (node != null) {
            node.setHasSchema(hasSchema);
        }
    }
    
    /**
     * 检查表是否有schema
     * 
     * @param tableName 表名
     * @return 是否有schema
     */
    public boolean hasSchema(String tableName) {
        PartitionTreeNode node = tableNodes.get(tableName);
        return node != null && node.hasSchema();
    }
    
    /**
     * 清空所有数据
     */
    public void clear() {
        tableNodes.clear();
        rootNodes.clear();
        normalNodes.clear();
        logger.info("清空所有分区表树数据");
    }
    
    /**
     * 检查是否有分区表
     * 
     * @return 是否有分区表
     */
    public boolean hasPartitionTables() {
        return !rootNodes.isEmpty() || 
               tableNodes.values().stream().anyMatch(node -> 
                   node.getNodeType() == PartitionTreeNode.NodeType.INTERMEDIATE);
    }
    
    /**
     * 打印分区树信息（用于调试）
     */
    public void printPartitionTrees() {
        if (!hasPartitionTables() && normalNodes.isEmpty()) {
            logger.info("没有检测到任何表");
            return;
        }
        
        logger.info("=== 分区表树结构 ===");
        
        // 打印普通表
        if (!normalNodes.isEmpty()) {
            logger.info("普通表 ({}):", normalNodes.size());
            for (PartitionTreeNode node : normalNodes) {
                logger.info("  {}", node);
            }
        }
        
        // 打印分区树
        if (!rootNodes.isEmpty()) {
            logger.info("分区表树 ({}):", rootNodes.size());
            for (PartitionTreeNode rootNode : rootNodes) {
                logger.info("\n{}", rootNode.printTree(0));
            }
        }
        
        // 统计信息
        logger.info("=== 统计信息 ===");
        logger.info("总表数: {}", tableNodes.size());
        logger.info("普通表: {}", normalNodes.size());
        logger.info("根分区表: {}", rootNodes.size());
        logger.info("中间分区表: {}", getAllIntermediatePartitionTables().size());
        logger.info("叶子表: {}", getAllLeafTables().size());
    }
    
    /**
     * 保存到文件
     * 
     * @param outputDir 输出目录
     * @throws IOException IO异常
     */
    public void saveToFile(String outputDir) throws IOException {
        if (tableNodes.isEmpty()) {
            logger.info("没有分区表数据需要保存");
            return;
        }
        
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        Map<String, Object> treeData = new HashMap<>();
        
        // 保存节点信息
        Map<String, Map<String, Object>> nodeData = new HashMap<>();
        for (Map.Entry<String, PartitionTreeNode> entry : tableNodes.entrySet()) {
            PartitionTreeNode node = entry.getValue();
            Map<String, Object> nodeInfo = new HashMap<>();
            nodeInfo.put("nodeType", node.getNodeType().toString());
            nodeInfo.put("tableSize", node.getTableSize());
            nodeInfo.put("hasSchema", node.hasSchema());
            nodeInfo.put("parent", node.getParent() != null ? node.getParent().getTableName() : null);
            nodeInfo.put("children", node.getChildren().stream()
                    .map(PartitionTreeNode::getTableName)
                    .collect(Collectors.toList()));
            nodeData.put(entry.getKey(), nodeInfo);
        }
        treeData.put("nodes", nodeData);
        
        // 保存根节点列表
        treeData.put("rootNodes", rootNodes.stream()
                .map(PartitionTreeNode::getTableName)
                .collect(Collectors.toList()));
        
        File outputFile = new File(outputDir, "partition_tree.json");
        objectMapper.writeValue(outputFile, treeData);
        
        logger.info("分区表树数据已保存到文件: {}", outputFile.getAbsolutePath());
        logger.info("总节点数: {}", tableNodes.size());
    }
    
    /**
     * 从文件加载
     * 
     * @param inputDir 输入目录
     * @throws IOException IO异常
     */
    @SuppressWarnings("unchecked")
    public void loadFromFile(String inputDir) throws IOException {
        File inputFile = new File(inputDir, "partition_tree.json");
        if (!inputFile.exists()) {
            logger.info("分区表树文件不存在: {}", inputFile.getAbsolutePath());
            return;
        }
        
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> treeData = objectMapper.readValue(inputFile, Map.class);
        
        // 清空现有数据
        clear();
        
        // 加载节点数据
        Map<String, Map<String, Object>> nodeData = (Map<String, Map<String, Object>>) treeData.get("nodes");
        if (nodeData != null) {
            // 第一遍：创建所有节点
            for (Map.Entry<String, Map<String, Object>> entry : nodeData.entrySet()) {
                String tableName = entry.getKey();
                Map<String, Object> nodeInfo = entry.getValue();
                
                String nodeTypeStr = (String) nodeInfo.get("nodeType");
                PartitionTreeNode.NodeType nodeType = PartitionTreeNode.NodeType.valueOf(nodeTypeStr);
                
                PartitionTreeNode node = new PartitionTreeNode(tableName, nodeType);
                node.setTableSize((Integer) nodeInfo.get("tableSize"));
                node.setHasSchema((Boolean) nodeInfo.get("hasSchema"));
                
                tableNodes.put(tableName, node);
                
                // 添加到相应列表
                if (nodeType == PartitionTreeNode.NodeType.ROOT) {
                    rootNodes.add(node);
                } else if (nodeType == PartitionTreeNode.NodeType.NORMAL) {
                    normalNodes.add(node);
                }
            }
            
            // 第二遍：建立父子关系
            for (Map.Entry<String, Map<String, Object>> entry : nodeData.entrySet()) {
                String tableName = entry.getKey();
                Map<String, Object> nodeInfo = entry.getValue();
                
                PartitionTreeNode node = tableNodes.get(tableName);
                String parentName = (String) nodeInfo.get("parent");
                
                if (parentName != null) {
                    PartitionTreeNode parent = tableNodes.get(parentName);
                    if (parent != null) {
                        parent.addChild(node);
                    }
                }
            }
        }
        
        logger.info("从文件加载分区表树数据: {}", inputFile.getAbsolutePath());
        logger.info("总节点数: {}", tableNodes.size());
    }
}