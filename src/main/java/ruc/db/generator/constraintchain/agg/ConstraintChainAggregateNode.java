package ruc.db.generator.constraintchain.agg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import ruc.db.LanguageManager;
import ruc.db.generator.ConstructCpModel;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.ConstraintChainNodeType;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.schema.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class ConstraintChainAggregateNode extends ConstraintChainNode {
    private final Logger logger = LoggerFactory.getLogger(ConstraintChainAggregateNode.class);
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    @JsonIgnore
    public int joinStatusIndex = -1;
    private List<String> groupKey;
    private BigDecimal aggProbability;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long inputRows;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long outputRows;
    ConstraintChainFilterNode aggFilter;
    @JsonIgnore
    private boolean allowsPostAggregateJoins;


    public ConstraintChainAggregateNode(List<String> groupKeys, BigDecimal aggProbability) {
        super(ConstraintChainNodeType.AGGREGATE);
        this.groupKey = groupKeys;
        this.aggProbability = aggProbability.stripTrailingZeros();
    }

    public ConstraintChainAggregateNode(List<String> groupKeys, BigDecimal aggProbability, Long inputRows, Long outputRows) {
        this(groupKeys, aggProbability);
        this.inputRows = inputRows;
        this.outputRows = outputRows;
    }

    public ConstraintChainAggregateNode() {
        super(ConstraintChainNodeType.AGGREGATE);
    }

    public BigDecimal getAggProbability() {
        return aggProbability;
    }

    public void setAggProbability(BigDecimal aggProbability) {
        this.aggProbability = aggProbability.stripTrailingZeros();
    }

    public Long getInputRows() {
        return inputRows;
    }

    public void setInputRows(Long inputRows) {
        this.inputRows = inputRows;
    }

    public Long getOutputRows() {
        return outputRows;
    }

    public void setOutputRows(Long outputRows) {
        this.outputRows = outputRows;
    }

    @JsonIgnore
    public boolean isSingleGroupKeyDistinctConstraint() {
        return groupKey != null && groupKey.size() == 1 && inputRows != null && inputRows > 0 && outputRows != null;
    }

    public long computeDistinctTargetForCp(long filterSize) {
        long target;
        if (inputRows != null && inputRows > 0 && outputRows != null) {
            BigDecimal scaled = BigDecimal.valueOf(Math.max(0L, filterSize))
                    .multiply(BigDecimal.valueOf(outputRows))
                    .divide(BigDecimal.valueOf(inputRows), 0, RoundingMode.HALF_UP);
            target = scaled.longValue();
        } else if (aggProbability != null) {
            target = BigDecimal.valueOf(Math.max(0L, filterSize))
                    .multiply(aggProbability)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } else {
            target = 0L;
        }
        return Math.max(0L, Math.min(target, Math.max(0L, filterSize)));
    }

    public BigDecimal computeAverageCountPerGroup(int scale) {
        if (inputRows == null || outputRows == null) {
            return BigDecimal.ZERO.setScale(Math.max(0, scale), RoundingMode.UNNECESSARY);
        }
        return AggregateValueModel.averageCountPerGroup(inputRows, outputRows, scale);
    }

    public boolean removeAgg() {
        // 如果filter含有虚参，则不能被约减。其需要参与计算。
        if (aggFilter != null && aggFilter.getParameters().stream().anyMatch(parameter -> parameter.getType() == Parameter.ParameterType.VIRTUAL)) {
            return false;
        }
        // filter不再需要被计算，只需要考虑group key的情况
        // 如果没有group key 则不需要进行分布控制 无需考虑
        if (groupKey == null) {
            return true;
        }
        if (groupKey.size() == 1) {
            return false;
        }
        // 清理group key， 如果含有参照表的外键，则clean被参照表的group key
        cleanGroupKeys();
        // 如果group key中全部是外键 则需要控制外键分布 不能删减
        if (groupKey.stream().allMatch(key -> TableManager.getInstance().isForeignKey(key))) {
            return false;
        }
        // 如果group key中包含主键 且无法支持 提示报错
        if (groupKey.stream().anyMatch(key -> TableManager.getInstance().isPrimaryKey(key))) {
            logger.error(rb.getString("AggOperatorCannotBeSupportedInQuery"), this);
        }
        return true;
    }

    public void addJoinDistinctConstraint(ConstructCpModel cpModel, long filterSize, boolean[][] canBeInput) {
        if (joinStatusIndex < 0) {
            return;
        }
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (canBeInput[filterIndex][pkStatusIndex]) {
                    cpModel.addJoinDistinctValidVar(joinStatusIndex, filterIndex, pkStatusIndex);
                }
            }
        }
        long pkSize = computeDistinctTargetForCp(filterSize);
        // GROUP BY 的输出基数是 distinct 目标，不应复用 JOIN 基数的容差。
        cpModel.addJoinCardinalityConstraint(pkSize, pkSize);
    }

    private void cleanGroupKeys() {
        TreeMap<String, List<String>> table2keys = new TreeMap<>();
        for (String key : groupKey) {
            String[] array = key.split("\\.");
            String tableName = array[0] + "." + array[1];
            table2keys.computeIfAbsent(tableName, v -> new ArrayList<>());
            table2keys.get(tableName).add(key);
        }
        // todo filter the attributes of the same table
        // todo mutiple key columns
        if (table2keys.size() == 1) {
            return;
        }
        List<String> topologicalOrder = TableManager.getInstance().createTopologicalOrder();
        Collections.reverse(topologicalOrder);
        // 从参照表到被参照表进行访问
        for (String tableName : topologicalOrder) {
            List<String> keys = table2keys.get(tableName);
            // if the first group attribute is fk, remove all its referenced table
            // todo 参照关系和groupkey可能不一致
            if (keys != null && keys.stream().anyMatch(key -> TableManager.getInstance().isForeignKey(key))) {
                var tableNames = table2keys.keySet().stream()
                        .filter(currentTable -> TableManager.getInstance().isRefTable(tableName, currentTable)).toList();
                for (String currentTable : tableNames) {
                    List<String> groupKeys = table2keys.get(currentTable);
                    logger.debug("remove invalid group key {} from node {}", groupKeys, this);
                    groupKey.removeAll(groupKeys);
                }
            }
        }
    }

    public ConstraintChainFilterNode getAggFilter() {
        return aggFilter;
    }

    public void setAggFilter(ConstraintChainFilterNode aggFilter) {
        this.aggFilter = aggFilter;
    }

    public boolean allowsPostAggregateJoins() {
        return allowsPostAggregateJoins;
    }

    public void setAllowsPostAggregateJoins(boolean allowsPostAggregateJoins) {
        this.allowsPostAggregateJoins = allowsPostAggregateJoins;
    }

    @Override
    public String toString() {
        return String.format("{GroupKey:%s, aggProbability:%s, inputRows:%s, outputRows:%s}",
                groupKey, aggProbability, inputRows, outputRows);
    }

    public List<String> getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(List<String> groupKey) {
        this.groupKey = groupKey;
    }
}
