package ruc.db.generator.constraintchain.filter.operation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import ruc.db.generator.constraintchain.filter.BoolExprType;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.filter.arithmetic.ArithmeticNode;
import ruc.db.generator.constraintchain.filter.arithmetic.ArithmeticNodeType;
import ruc.db.generator.constraintchain.filter.arithmetic.ColumnNode;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.TableManager;
import ruc.db.utils.CommonUtils;

/**
 * @author wangqingshuai
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MultiVarFilterOperation extends AbstractFilterOperation {
    private static final Logger logger = LoggerFactory.getLogger(MultiVarFilterOperation.class);
    private ArithmeticNode arithmeticTree;

    public MultiVarFilterOperation() {
        super(null);
    }

    public MultiVarFilterOperation(CompareOperator operator, ArithmeticNode arithmeticTree, List<Parameter> parameters) {
        super(operator);
        this.arithmeticTree = arithmeticTree;
        this.parameters = parameters;
    }


    public Set<String> getAllCanonicalColumnNames() {
        HashSet<String> allTables = new HashSet<>();
        getCanonicalColumnNamesColNames(arithmeticTree, allTables);
        return allTables;
    }

    private void getCanonicalColumnNamesColNames(ArithmeticNode node, HashSet<String> colNames) {
        if (node == null) {
            return;
        }
        if (node.getType() == ArithmeticNodeType.COLUMN) {
            colNames.add(((ColumnNode) node).getCanonicalColumnName());
        }
        getCanonicalColumnNamesColNames(node.getLeftNode(), colNames);
        getCanonicalColumnNamesColNames(node.getRightNode(), colNames);
    }

    @Override
    public boolean hasKeyColumn() {
        return hasKeyColumn(arithmeticTree);
    }

    @Override
    public void getColumn2ParameterBucket(Map<String, Map<String, List<Integer>>> column2Value2ParameterList) {
        throw new UnsupportedOperationException();
    }

    private boolean hasKeyColumn(ArithmeticNode node) {
        boolean hasKeyColumn = false;
        if (node != null) {
            hasKeyColumn = hasKeyColumn(node.getLeftNode()) || hasKeyColumn(node.getRightNode());
            if (node.getType() == ArithmeticNodeType.COLUMN) {
                ColumnNode columnNode = (ColumnNode) node;
                hasKeyColumn = hasKeyColumn ||
                        TableManager.getInstance().isPrimaryKey(columnNode.getCanonicalColumnName()) ||
                        TableManager.getInstance().isForeignKey(columnNode.getCanonicalColumnName());
            }
        }
        return hasKeyColumn;
    }

    @Override
    public BoolExprType getType() {
        return BoolExprType.MULTI_FILTER_OPERATION;
    }

    /**
     * todo 暂时不考虑NULL
     *
     * @return 多值表达式的计算结果
     */
    @Override
    public boolean[] evaluate() {
        double[] data = arithmeticTree.calculate();
        boolean[] ret = new boolean[data.length];
        long parameterData = parameters.getFirst().getData();
        double parameterValue = (double) parameterData / CommonUtils.SAMPLE_DOUBLE_PRECISION;
        
        // ★★★ 日志：输出实际使用的参数值 ★★★
        String arithmeticTreeStr = arithmeticTree.toString();
        String paramDataValue = parameters.getFirst().getDataValue();
        long paramDays = parameterData / CommonUtils.SAMPLE_DOUBLE_PRECISION;
        logger.info("ACC 约束评估 - 表达式: {}, 操作符: {}, 参数ID: {}, data: {}, dataValue: \"{}\", 天数: {}", 
                   arithmeticTreeStr, operator, parameters.getFirst().getId(), parameterData, paramDataValue, paramDays);
        
        // ★★★ 诊断：检查评估时使用的数据分布 ★★★
        if (data.length > 0) {
            long nanCount = 0, infCount = 0;
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double v : data) {
                if (Double.isNaN(v)) nanCount++;
                else if (Double.isInfinite(v)) infCount++;
                else {
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            // 统计满足条件的数据数量
            int satisfyCount = 0;
            switch (operator) {
                case LT -> satisfyCount = (int) Arrays.stream(data).filter(d -> !Double.isNaN(d) && !Double.isInfinite(d) && d < parameterValue).count();
                case LE -> satisfyCount = (int) Arrays.stream(data).filter(d -> !Double.isNaN(d) && !Double.isInfinite(d) && d <= parameterValue).count();
                case GT -> satisfyCount = (int) Arrays.stream(data).filter(d -> !Double.isNaN(d) && !Double.isInfinite(d) && d > parameterValue).count();
                case GE -> satisfyCount = (int) Arrays.stream(data).filter(d -> !Double.isNaN(d) && !Double.isInfinite(d) && d >= parameterValue).count();
            }
            logger.info("ACC 约束评估 - 数据诊断: 总数={}, NaN={}, Inf={}, 最小值={}, 最大值={}, 满足条件数量={}, 占比={}%", 
                       data.length, nanCount, infCount, 
                       min == Double.POSITIVE_INFINITY ? "N/A" : String.format("%.2f", min),
                       max == Double.NEGATIVE_INFINITY ? "N/A" : String.format("%.2f", max),
                       satisfyCount, data.length > 0 ? (double)satisfyCount / data.length * 100 : 0);
        }
        
        switch (operator) {
            case LT -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] < parameterValue;
                }
            }
            case LE -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] <= parameterValue;
                }
            }
            case GT -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] > parameterValue;
                }
            }
            case GE -> {
                for (int i = 0; i < ret.length; i++) {
                    ret[i] = data[i] >= parameterValue;
                }
            }
            default -> throw new UnsupportedOperationException();
        }
        return ret;
    }

    @Override
    public List<String> getColumns() {
        return arithmeticTree.getColumns();
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return arithmeticTree.isDifferentTable(tableName);
    }

    @Override
    public String toString() {
        Parameter param = parameters.get(0);
        String paramValueStr = param.getDataValue();
        // ★★★ 修复：如果dataValue为null，从data字段计算可读字符串 ★★★
        if (paramValueStr == null || paramValueStr.equals("null")) {
            long paramData = param.getData();
            long paramDays = paramData / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            paramValueStr = "interval '" + paramDays + "' day";
        }
        return arithmeticTree.toString() + CompareOperator.toSQL(operator) + paramValueStr;
    }

    public ArithmeticNode getArithmeticTree() {
        return arithmeticTree;
    }

    public void setArithmeticTree(ArithmeticNode arithmeticTree) {
        this.arithmeticTree = arithmeticTree;
    }

    /**
     * todo 暂时不考虑null
     */
    public void instantiateMultiVarParameter() {
        // 调整概率：对于GT/GE操作符，需要转换为LT/LE的概率
        switch (operator) {
            case GE, GT:
                probability = BigDecimal.ONE.subtract(probability);
                break;
            case LE, LT:
                break;
            default:
                throw new UnsupportedOperationException("多变量计算节点仅接受非等值约束");
        }
        
        // 计算算术表达式结果
        double[] vector = arithmeticTree.calculate();
        Arrays.sort(vector);
        
        // 基于概率选择分位数位置
        int pos;
        if (probability.equals(BigDecimal.ONE)) {
            pos = vector.length - 1;
        } else {
            pos = probability.multiply(BigDecimal.valueOf(vector.length))
                    .setScale(0, RoundingMode.HALF_UP).intValue();
            pos = Math.min(pos, vector.length - 1);
        }
        
        // 如果选中的是NaN，查找最近的有效值
        if (Double.isNaN(vector[pos]) || Double.isInfinite(vector[pos])) {
            // 向前查找
            for (int i = pos - 1; i >= 0; i--) {
                if (!Double.isNaN(vector[i]) && !Double.isInfinite(vector[i])) {
                    pos = i;
                    break;
                }
            }
            // 如果向前没找到，向后查找
            if (Double.isNaN(vector[pos]) || Double.isInfinite(vector[pos])) {
                for (int i = pos + 1; i < vector.length; i++) {
                    if (!Double.isNaN(vector[i]) && !Double.isInfinite(vector[i])) {
                        pos = i;
                        break;
                    }
                }
            }
        }
        
        double paramValue = vector[pos];
        long internalValue = (long) (paramValue * CommonUtils.SAMPLE_DOUBLE_PRECISION);
        parameters.forEach(param -> param.setData(internalValue));
        
        // 格式化参数值字符串
        var columns = arithmeticTree.getColumns();
        boolean isDate = !columns.isEmpty() && ColumnManager.getInstance().isDateColumn(columns.getFirst());
        
        if (isDate) {
            long days = internalValue / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            String paramValueStr = "interval '" + days + "' day";
            parameters.forEach(param -> param.setDataValue(paramValueStr));
            logger.info("ACC 参数估计 - 日期差值: {} 天", days);
        } else {
            parameters.forEach(param -> param.setDataValue(" '" + (double) internalValue / CommonUtils.SAMPLE_DOUBLE_PRECISION + "' "));
        }
    }

    @Override
    public BigDecimal getNullProbability() {
        //todo deal with null
        return BigDecimal.ZERO;
    }
}
