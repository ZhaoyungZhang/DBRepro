package ruc.db.generator.constraintchain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;

import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintChainPkJoinNode;

/**
 * @author wangqingshuai
 */
public class ConstraintChain {
    
    private static final Logger logger = LoggerFactory.getLogger(ConstraintChain.class);

    private final List<ConstraintChainNode> nodes = new ArrayList<>();

    @JsonIgnore
    private final Set<String> joinTables = new HashSet<>();
    private String tableName;

    @JsonIgnore
    private int chainIndex;

    public ConstraintChain() {
    }

    public ConstraintChain(String tableName) {
        this.tableName = tableName;
    }

    public void addJoinTable(String tableName) {
        joinTables.add(tableName);
    }

    public Set<String> getJoinTables() {
        return joinTables;
    }

    public void addNode(ConstraintChainNode node) {
        nodes.add(node);
    }

    public List<ConstraintChainNode> getNodes() {
        return nodes;
    }

    private boolean involvedNode(ConstraintChainNode node, List<String> fkCols) {
        boolean involvedFk = node instanceof ConstraintChainFkJoinNode fkNode
                && fkNode.requiresPhysicalForeignKeyGeneration()
                && fkCols.contains(fkNode.getLocalCols());
        // todo 处理复合的groupby key
        boolean involvedAgg = node instanceof ConstraintChainAggregateNode aggNode && aggNode.getGroupKey() != null && fkCols.contains(aggNode.getGroupKey().get(0));
        return involvedFk || involvedAgg;
    }

    public List<ConstraintChainNode> getInvolvedNodes(List<String> fkCols) {
        return nodes.stream().filter(node -> involvedNode(node, fkCols)).toList();
    }

    private boolean involvedJoinKeyNode(ConstraintChainNode node, List<String> fkCols) {
        boolean involvedFk = node instanceof ConstraintChainFkJoinNode fkNode
                && fkCols.contains(fkNode.getLocalCols());
        boolean involvedAgg = node instanceof ConstraintChainAggregateNode aggNode
                && aggNode.getGroupKey() != null
                && fkCols.contains(aggNode.getGroupKey().get(0));
        return involvedFk || involvedAgg;
    }

    public List<ConstraintChainNode> getInvolvedJoinKeyNodes(List<String> fkCols) {
        return nodes.stream().filter(node -> involvedJoinKeyNode(node, fkCols)).toList();
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    @JsonIgnore
    public List<Parameter> getParameters() {
        return nodes.stream().filter(ConstraintChainFilterNode.class::isInstance)
                .map(ConstraintChainFilterNode.class::cast)
                .map(ConstraintChainFilterNode::getParameters)
                .flatMap(Collection::stream).toList();
    }

    @Override
    public String toString() {
        return "{tableName:" + tableName + ",nodes:" + nodes + "}";
    }

    /**
     * 给定range空间 计算filter的状态
     *
     * @param range 批大小
     * @return filter状态
     */
    public boolean[] evaluateFilterStatus(int range) {
        if (nodes.get(0) instanceof ConstraintChainFilterNode filterNode) {
            boolean[] statusVector = filterNode.evaluate();
            // 记录评估结果用于调试
            if (logger.isDebugEnabled()) {
                int trueCount = 0;
                for (boolean b : statusVector) {
                    if (b) trueCount++;
                }
                // String filterCondition = filterNode.getRoot() != null ? filterNode.getRoot().toString() : "无过滤条件";
                // logger.info("约束链[{}]评估结果: 表={}, 过滤条件={}, 返回长度={}, true数量={}, 期望range={}", 
                //     chainIndex, tableName, filterCondition, statusVector.length, trueCount, range);
            }
            // 如果返回的数组长度与期望的range不匹配，需要调整
            if (statusVector.length != range) {
                boolean[] adjustedVector = new boolean[range];
                if (statusVector.length < range) {
                    // 如果返回的数组更小（例如，引用了其他表的列，该表的columnActualData长度更小）
                    // 需要扩展数组：重复现有模式或填充
                    // 策略：循环重复现有数组，以保持分布特性
                    int sourceIndex = 0;
                    for (int i = 0; i < range; i++) {
                        adjustedVector[i] = statusVector[sourceIndex];
                        sourceIndex = (sourceIndex + 1) % statusVector.length;
                    }
                    logger.warn("Status vector长度不匹配：期望长度={}, 实际长度={}, 已通过循环重复调整。表={}, 约束链={}",
                               range, statusVector.length, tableName, chainIndex);
                } else {
                    // 如果返回的数组更大，只取前range个元素
                    System.arraycopy(statusVector, 0, adjustedVector, 0, range);
                    logger.warn("Status vector长度不匹配：期望长度={}, 实际长度={}, 已截断。表={}, 约束链={}",
                               range, statusVector.length, tableName, chainIndex);
                }
                return adjustedVector;
            }
            return statusVector;
        } else {
            boolean[] result = new boolean[range];
            Arrays.fill(result, true);
            return result;
        }
    }

    public boolean hasFkNode() {
        return nodes.stream().anyMatch(node -> node.getConstraintChainNodeType() == ConstraintChainNodeType.FK_JOIN);
    }

    public boolean hasJoinKeyNode() {
        return hasFkNode();
    }

    public boolean hasPhysicalFkNode() {
        return nodes.stream()
                .filter(ConstraintChainFkJoinNode.class::isInstance)
                .map(ConstraintChainFkJoinNode.class::cast)
                .anyMatch(ConstraintChainFkJoinNode::requiresPhysicalForeignKeyGeneration);
    }

    public boolean hasAggNode(){
        return nodes.stream().anyMatch(node -> node.getConstraintChainNodeType() == ConstraintChainNodeType.AGGREGATE);
    }

    @JsonIgnore
    public boolean canContinueJoinAfterAggregateOnLocalKey(String localKey) {
        if (!hasAggNode()) {
            return true;
        }
        if (localKey == null || localKey.isBlank()) {
            return false;
        }
        for (ConstraintChainNode node : nodes) {
            if (!(node instanceof ConstraintChainAggregateNode aggNode)) {
                continue;
            }
            if (aggNode.allowsPostAggregateJoins()) {
                continue;
            }
            if (!aggNode.isSingleGroupKeyDistinctConstraint()) {
                return false;
            }
            if (!localKey.equals(aggNode.getGroupKey().get(0))) {
                return false;
            }
        }
        return true;
    }

    @JsonIgnore
    public List<ConstraintChainFkJoinNode> getFkNodes() {
        return nodes.stream().filter(constraintChainNode ->
                        constraintChainNode.getConstraintChainNodeType() == ConstraintChainNodeType.FK_JOIN)
                .map(ConstraintChainFkJoinNode.class::cast).toList();
    }

    @JsonIgnore
    public List<ConstraintChainFkJoinNode> getPhysicalFkNodes() {
        return getFkNodes().stream()
                .filter(ConstraintChainFkJoinNode::requiresPhysicalForeignKeyGeneration)
                .toList();
    }

    public StringBuilder presentConstraintChains(Map<String, SubGraph> subGraphHashMap, String color) {
        String lastNodeInfo = "";
        double lastProbability = 0;
        String conditionColor = String.format("[style=filled, color=\"%s\"];%n", color);
        String tableColor = String.format("[shape=box,style=filled, color=\"%s\"];%n", color);
        StringBuilder graph = new StringBuilder();
        for (ConstraintChainNode node : nodes) {
            String currentNodeInfo;
            double currentProbability = 0;
            switch (node.constraintChainNodeType) {
                case FILTER -> {
                    currentNodeInfo = String.format("\"%s\"", node);
                    currentProbability = ((ConstraintChainFilterNode) node).getProbability().doubleValue();
                    graph.append("\t").append(currentNodeInfo).append(conditionColor);
                }
                case FK_JOIN -> {
                    ConstraintChainFkJoinNode fkJoinNode = ((ConstraintChainFkJoinNode) node);
                    String pkCols = fkJoinNode.getRefCols().split("\\.")[2];
                    currentNodeInfo = String.format("\"Fk%s%d\"", pkCols, fkJoinNode.getPkTag());
                    String subGraphTag = String.format("cluster%s%d", pkCols, fkJoinNode.getPkTag());
                    currentProbability = fkJoinNode.getProbability().doubleValue();
                    subGraphHashMap.putIfAbsent(subGraphTag, new SubGraph(subGraphTag));
                    subGraphHashMap.get(subGraphTag).fkInfo = currentNodeInfo + conditionColor;
                    subGraphHashMap.get(subGraphTag).joinLabel = switch (fkJoinNode.getType()) {
                        case INNER_JOIN -> "eq join";
                        case SEMI_JOIN -> "semi join: " + fkJoinNode.getPkDistinctProbability();
                        case OUTER_JOIN -> "outer join: " + fkJoinNode.getPkDistinctProbability();
                        case ANTI_SEMI_JOIN -> "anti semi join";
                        case ANTI_JOIN -> "anti join";
                    };
                    if (fkJoinNode.getProbabilityWithFailFilter() != null) {
                        subGraphHashMap.get(subGraphTag).joinLabel = String.format("%s filterWithCannotJoin: %2$,.4f",
                                subGraphHashMap.get(subGraphTag).joinLabel,
                                fkJoinNode.getProbabilityWithFailFilter());
                    }
                }
                case PK_JOIN -> {
                    ConstraintChainPkJoinNode pkJoinNode = ((ConstraintChainPkJoinNode) node);
                    String locPks = pkJoinNode.getPkColumns()[0];
                    currentNodeInfo = String.format("\"Pk%s%d\"", locPks, pkJoinNode.getPkTag());
                    String localSubGraph = String.format("cluster%s%d", locPks, pkJoinNode.getPkTag());
                    subGraphHashMap.putIfAbsent(localSubGraph, new SubGraph(localSubGraph));
                    subGraphHashMap.get(localSubGraph).pkInfo = currentNodeInfo + conditionColor;
                }
                case AGGREGATE -> {
                    ConstraintChainAggregateNode aggregateNode = ((ConstraintChainAggregateNode) node);
                    List<String> keys = aggregateNode.getGroupKey();
                    currentProbability = aggregateNode.getAggProbability().doubleValue();
                    currentNodeInfo = String.format("\"GroupKey:%s\"", keys == null ? "" : String.join(",", keys));
                    graph.append("\t").append(currentNodeInfo).append(conditionColor);
                    if (aggregateNode.getAggFilter() != null) {
                        if (!lastNodeInfo.isBlank()) {
                            graph.append(String.format("\t%s->%s[label=\"%3$,.4f\"];%n", lastNodeInfo, currentNodeInfo, lastProbability));
                        } else {
                            graph.append(String.format("\t\"%s\"%s", tableName, tableColor));
                            graph.append(String.format("\t\"%s\"->%s[label=\"1.0\"]%n", tableName, currentNodeInfo));
                        }
                        lastNodeInfo = currentNodeInfo;
                        lastProbability = currentProbability;
                        ConstraintChainFilterNode aggFilter = aggregateNode.getAggFilter();
                        currentNodeInfo = String.format("\"%s\"", aggFilter);
                        graph.append("\t").append(currentNodeInfo).append(conditionColor);
                        currentProbability = aggFilter.getProbability().doubleValue();
                    }
                }
                default -> throw new UnsupportedOperationException();
            }
            if (!lastNodeInfo.isBlank()) {
                graph.append(String.format("\t%s->%s[label=\"%3$,.4f\"];%n", lastNodeInfo, currentNodeInfo, lastProbability));
            } else {
                graph.append(String.format("\t\"%s\"%s", tableName, tableColor));
                graph.append(String.format("\t\"%s\"->%s[label=\"1.0\"]%n", tableName, currentNodeInfo));
            }
            lastNodeInfo = currentNodeInfo;
            lastProbability = currentProbability;
        }
        if (!lastNodeInfo.startsWith("\"Pk")) {
            graph.append("\t").append("RESULT").append(conditionColor);
            graph.append(String.format("\t%s->RESULT[label=\"%2$,.4f\"]%n", lastNodeInfo, lastProbability));
        }
        return graph;
    }


    public void setChainIndex(int chainIndex) {
        this.chainIndex = chainIndex;
    }

    public int getChainIndex() {
        return chainIndex;
    }

    static class SubGraph {
        private final String joinTag;
        String pkInfo;
        String fkInfo;
        String joinLabel;

        public SubGraph(String joinTag) {
            this.joinTag = joinTag;
        }

        @Override
        public String toString() {
            return String.format("""
                    subgraph "%s" {
                            %s
                            %slabel="%s";labelloc=b;
                    }""".indent(4), joinTag, pkInfo, fkInfo, joinLabel);
        }
    }
}
