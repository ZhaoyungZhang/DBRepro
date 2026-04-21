package ruc.db.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 用于在Stage 3中传递bound约束信息
 * 确保多列的bound值在相同的行范围内
 * 
 * @author AI Assistant
 */
public class TableBoundInfo {
    private static final Logger logger = LoggerFactory.getLogger(TableBoundInfo.class);
    
    /**
     * Bound行范围信息
     * offset（概率） -> (开始行, 结束行, dataIndex)
     */
    private static class BoundRange {
        int startRow;
        int endRow;
        long dataIndex;
        
        BoundRange(int startRow, int endRow, long dataIndex) {
            this.startRow = startRow;
            this.endRow = endRow;
            this.dataIndex = dataIndex;
        }
        
        @Override
        public String toString() {
            return String.format("[%d, %d) -> dataIndex=%d", startRow, endRow, dataIndex);
        }
    }
    
    private List<BoundRange> boundRanges = new ArrayList<>();
    private Set<Integer> allBoundRows = new TreeSet<>();
    
    /**
     * 添加一个bound范围
     */
    public void addBoundRange(int startRow, int endRow, long dataIndex) {
        boundRanges.add(new BoundRange(startRow, endRow, dataIndex));
        for (int i = startRow; i < endRow; i++) {
            allBoundRows.add(i);
        }
        logger.info("🔗 BOUND INFO: 添加bound范围 [%d, %d) -> dataIndex=%d", startRow, endRow, dataIndex);
    }
    
    /**
     * 获取所有bound行的索引
     */
    public Set<Integer> getAllBoundRows() {
        return allBoundRows;
    }
    
    /**
     * 获取非bound行的索引
     */
    public Set<Integer> getNonBoundRows(int totalSize) {
        Set<Integer> nonBoundRows = new TreeSet<>();
        for (int i = 0; i < totalSize; i++) {
            if (!allBoundRows.contains(i)) {
                nonBoundRows.add(i);
            }
        }
        return nonBoundRows;
    }
    
    /**
     * 对于给定的行，返回对应的dataIndex（如果是bound行）
     */
    public Long getBoundDataIndex(int rowIndex) {
        for (BoundRange range : boundRanges) {
            if (rowIndex >= range.startRow && rowIndex < range.endRow) {
                return range.dataIndex;
            }
        }
        return null;
    }
    
    /**
     * 获取bound范围列表
     */
    public List<BoundRange> getBoundRanges() {
        return boundRanges;
    }
    
    /**
     * 检查是否有bound约束
     */
    public boolean hasBoundConstraints() {
        return !boundRanges.isEmpty();
    }
    
    /**
     * 获取bound行的总数
     */
    public int getTotalBoundRows() {
        return allBoundRows.size();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TableBoundInfo{");
        sb.append("ranges=[");
        for (int i = 0; i < boundRanges.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(boundRanges.get(i));
        }
        sb.append("], totalBoundRows=").append(allBoundRows.size());
        sb.append("}");
        return sb.toString();
    }
}


