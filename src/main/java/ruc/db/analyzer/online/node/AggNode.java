package ruc.db.analyzer.online.node;

public class AggNode extends ExecutionNode {
    /**
     * 如果aggregate中含有filter，则记录经过filter之后的行数
     */
    private FilterNode aggFilter;
    /**
     * 对 HAVING COUNT(*) > n 这类当前不走普通 select parser 的聚合谓词，记录语义类型。
     */
    private AggregateFilterKind aggregateFilterKind = AggregateFilterKind.NONE;
    /**
     * 当最终 Aggregate 输出里含有 count(distinct col) 但计划里没有显式 group aggregate 节点时，
     * 记录该节点需要在 QueryAnalyzer 侧恢复为 single-key distinct aggregate。
     */
    private boolean syntheticDistinctAggregate;

    public AggNode(String id, int outputRows, String info) {
        super(id, ExecutionNodeType.AGGREGATE, outputRows, info);
    }

    public FilterNode getAggFilter() {
        return aggFilter;
    }

    public void setAggFilter(FilterNode aggFilter) {
        this.aggFilter = aggFilter;
    }

    public AggregateFilterKind getAggregateFilterKind() {
        return aggregateFilterKind;
    }

    public void setAggregateFilterKind(AggregateFilterKind aggregateFilterKind) {
        this.aggregateFilterKind = aggregateFilterKind == null ? AggregateFilterKind.NONE : aggregateFilterKind;
    }

    public enum AggregateFilterKind {
        NONE,
        COUNT_GT_LITERAL
    }

    public boolean isSyntheticDistinctAggregate() {
        return syntheticDistinctAggregate;
    }

    public void setSyntheticDistinctAggregate(boolean syntheticDistinctAggregate) {
        this.syntheticDistinctAggregate = syntheticDistinctAggregate;
    }
}
