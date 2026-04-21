package ruc.db.rsgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 分区表树节点
 * 表示分区表层次结构中的一个节点
 * 
 * @author RSGen Implementation
 */
public class PartitionTreeNode {
    
    /**
     * 节点类型枚举
     */
    public enum NodeType {
        ROOT,           // 根分区表
        INTERMEDIATE,   // 中间分区表
        LEAF,           // 叶子分区表（实际存储数据）
        NORMAL          // 普通表（非分区表）
    }
    
    private final String tableName;
    private NodeType nodeType;
    private PartitionTreeNode parent;
    private final List<PartitionTreeNode> children;
    private int tableSize;
    private boolean hasSchema;
    
    /**
     * 构造函数
     * 
     * @param tableName 表名
     * @param nodeType 节点类型
     */
    public PartitionTreeNode(String tableName, NodeType nodeType) {
        this.tableName = tableName;
        this.nodeType = nodeType;
        this.children = new ArrayList<>();
        this.parent = null;
        this.tableSize = 0;
        this.hasSchema = false;
    }
    
    /**
     * 添加子节点
     * 
     * @param child 子节点
     */
    public void addChild(PartitionTreeNode child) {
        if (child != null && !children.contains(child)) {
            children.add(child);
            child.parent = this;
        }
    }
    
    /**
     * 移除子节点
     * 
     * @param child 子节点
     */
    public void removeChild(PartitionTreeNode child) {
        if (children.remove(child)) {
            child.parent = null;
        }
    }
    
    /**
     * 获取所有叶子节点（递归）
     * 
     * @return 叶子节点列表
     */
    public List<PartitionTreeNode> getAllLeafNodes() {
        List<PartitionTreeNode> leafNodes = new ArrayList<>();
        collectLeafNodes(leafNodes);
        return leafNodes;
    }
    
    /**
     * 递归收集叶子节点
     * 
     * @param leafNodes 叶子节点集合
     */
    private void collectLeafNodes(List<PartitionTreeNode> leafNodes) {
        if (nodeType == NodeType.LEAF) {
            leafNodes.add(this);
        } else {
            for (PartitionTreeNode child : children) {
                child.collectLeafNodes(leafNodes);
            }
        }
    }
    
    /**
     * 获取所有中间节点（递归）
     * 
     * @return 中间节点列表
     */
    public List<PartitionTreeNode> getAllIntermediateNodes() {
        List<PartitionTreeNode> intermediateNodes = new ArrayList<>();
        collectIntermediateNodes(intermediateNodes);
        return intermediateNodes;
    }
    
    /**
     * 递归收集中间节点
     * 
     * @param intermediateNodes 中间节点集合
     */
    private void collectIntermediateNodes(List<PartitionTreeNode> intermediateNodes) {
        if (nodeType == NodeType.INTERMEDIATE) {
            intermediateNodes.add(this);
        }
        for (PartitionTreeNode child : children) {
            child.collectIntermediateNodes(intermediateNodes);
        }
    }
    
    /**
     * 获取树的深度
     * 
     * @return 树的深度
     */
    public int getDepth() {
        if (children.isEmpty()) {
            return 1;
        }
        int maxChildDepth = 0;
        for (PartitionTreeNode child : children) {
            maxChildDepth = Math.max(maxChildDepth, child.getDepth());
        }
        return maxChildDepth + 1;
    }
    
    /**
     * 获取节点级别（从根节点开始，根节点为0）
     * 
     * @return 节点级别
     */
    public int getLevel() {
        int level = 0;
        PartitionTreeNode current = parent;
        while (current != null) {
            level++;
            current = current.parent;
        }
        return level;
    }
    
    /**
     * 检查是否为根节点
     * 
     * @return 是否为根节点
     */
    public boolean isRoot() {
        return parent == null && nodeType == NodeType.ROOT;
    }
    
    /**
     * 检查是否为叶子节点
     * 
     * @return 是否为叶子节点
     */
    public boolean isLeaf() {
        return nodeType == NodeType.LEAF;
    }
    
    /**
     * 检查是否为中间节点
     * 
     * @return 是否为中间节点
     */
    public boolean isIntermediate() {
        return nodeType == NodeType.INTERMEDIATE;
    }
    
    /**
     * 检查是否为普通表
     * 
     * @return 是否为普通表
     */
    public boolean isNormal() {
        return nodeType == NodeType.NORMAL;
    }
    
    /**
     * 获取根节点
     * 
     * @return 根节点
     */
    public PartitionTreeNode getRoot() {
        PartitionTreeNode current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }
    
    /**
     * 获取路径（从根到当前节点）
     * 
     * @return 路径上的节点列表
     */
    public List<PartitionTreeNode> getPath() {
        List<PartitionTreeNode> path = new ArrayList<>();
        PartitionTreeNode current = this;
        while (current != null) {
            path.add(0, current);
            current = current.parent;
        }
        return path;
    }
    
    // Getters and Setters
    public String getTableName() {
        return tableName;
    }
    
    public NodeType getNodeType() {
        return nodeType;
    }
    
    public PartitionTreeNode getParent() {
        return parent;
    }
    
    public List<PartitionTreeNode> getChildren() {
        return new ArrayList<>(children);
    }
    
    public int getTableSize() {
        return tableSize;
    }
    
    public void setTableSize(int tableSize) {
        this.tableSize = tableSize;
    }
    
    public boolean hasSchema() {
        return hasSchema;
    }
    
    public void setHasSchema(boolean hasSchema) {
        this.hasSchema = hasSchema;
    }
    
    /**
     * 设置节点类型
     * 
     * @param nodeType 节点类型
     */
    public void setNodeType(NodeType nodeType) {
        this.nodeType = nodeType;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PartitionTreeNode that = (PartitionTreeNode) o;
        return Objects.equals(tableName, that.tableName);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(tableName);
    }
    
    @Override
    public String toString() {
        return String.format("PartitionTreeNode{tableName='%s', nodeType=%s, children=%d, tableSize=%d, hasSchema=%s}", 
                tableName, nodeType, children.size(), tableSize, hasSchema);
    }
    
    /**
     * 打印树结构（用于调试）
     * 
     * @param indent 缩进级别
     * @return 树结构字符串
     */
    public String printTree(int indent) {
        StringBuilder sb = new StringBuilder();
        String indentStr = "  ".repeat(indent);
        sb.append(String.format("%s%s [%s] (size: %d, schema: %s)\n", 
                indentStr, tableName, nodeType, tableSize, hasSchema));
        
        for (PartitionTreeNode child : children) {
            sb.append(child.printTree(indent + 1));
        }
        
        return sb.toString();
    }
}