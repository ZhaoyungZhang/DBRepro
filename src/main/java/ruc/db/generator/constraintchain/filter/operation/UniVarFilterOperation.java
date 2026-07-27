package ruc.db.generator.constraintchain.filter.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ruc.db.generator.constraintchain.filter.BoolExprType;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnCDF;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.TableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ruc.db.generator.constraintchain.filter.operation.CompareOperator.*;


/**
 * @author wangqingshuai
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UniVarFilterOperation extends AbstractFilterOperation {
    private static final Logger logger = LoggerFactory.getLogger(UniVarFilterOperation.class);
    
    protected String canonicalColumnName;

    public UniVarFilterOperation() {
        super(null);
    }

    public UniVarFilterOperation(String canonicalColumnName, CompareOperator operator, List<Parameter> parameters) {
        super(operator);
        this.canonicalColumnName = canonicalColumnName;
        this.parameters = parameters;
    }

    public void amendParameters() {
        Column column = ColumnManager.getInstance().getColumn(canonicalColumnName);
        if (column == null) {
            logger.warn("列 {} 不存在，无法修正参数", canonicalColumnName);
            return;
        }
        
        // ★★★ 关键修复：对于使用CDF的列，完全跳过（值已经是实际值，不需要虚拟索引调整）★★★
        // amendParameters() 的设计初衷是为原始Mirage的虚拟索引系统服务的（左开右闭区间调整）
        // 对于CDF列，值已经是数据库的真实值，不需要这种调整
        if (column.hasCDF()) {
            ColumnCDF.ParameterConstraint pc = column.getColumnCDF() != null
                    ? column.getColumnCDF().getParameterConstraint() : null;
            for (Parameter parameter : parameters) {
                boolean needRecover = parameter.getDataValue() == null || parameter.getDataValue().isEmpty();
                // CDF列：dataIndex→实际值 映射来自 bin-packing/其它实例化路径，可能恢复成错误 MCV（如 514030701）；
                // 应优先使用 Stage2/UPDATE_MCV 写入 ParameterConstraint 的 cdf 键/字面量，与目标值（如 51401）一致。
                if (pc != null && pc.valueToOperator != null) {
                    String cdfKey = findConstraintValueForOperator(pc, operator);
                    // CDF/IPF 路径可能把无通配符的 LIKE 记为 EQ 桶频率，此时 valueToOperator 为 EQ。
                    if (cdfKey == null && operator == LIKE) {
                        cdfKey = findConstraintValueForOperator(pc, EQ);
                    } else if (cdfKey == null && (operator == NE || operator == NOT_IN)) {
                        cdfKey = findConstraintValueForOperator(pc, EQ);
                    } else if (cdfKey == null && operator == NOT_LIKE) {
                        cdfKey = findConstraintValueForOperator(pc, LIKE);
                    }
                    if (cdfKey != null) {
                        String literal = (operator == LIKE || operator == NOT_LIKE)
                                ? likePatternLiteralForEvaluate(cdfKey) : cdfKey;
                        parameter.setDataValue(literal);
                        logger.info("CDF列 {} amendParameters: {} 使用 ParameterConstraint 键 [{}] → 评估字面量 [{}]（不使用 dataIndex 映射）",
                                canonicalColumnName, operator, cdfKey, parameter.getDataValue());
                        continue;
                    }
                }
                if (needRecover && column.getDataIndex2ActualValue().containsKey(parameter.getData())) {
                    String actualValue = column.getDataIndex2ActualValue().get(parameter.getData());
                    if (actualValue != null) {
                        logger.debug("参数 ID {} (列 {}): 从映射恢复 dataValue={} (dataIndex={})",
                                parameter.getId(), canonicalColumnName, actualValue, parameter.getData());
                        parameter.setDataValue(actualValue);
                    }
                }
            }
            return;  // CDF列完全跳过，不进行边界调整
        }
        
        // 只对使用原始方法的列进行边界调整
        if (operator == GE || operator == LT) {
            for (Parameter parameter : parameters) {
                    parameter.setData(parameter.getData() + 1);
                    parameter.setDataValue(column.transferDataToValue(parameter.getData()));
            }
        }

        for (Parameter parameter : parameters) {
            if (parameter.getDataValue() == null || parameter.getDataValue().isEmpty()) {
                parameter.setDataValue(column.transferDataToValue(parameter.getData()));
            }
        }
    }

    @Override
    public boolean hasKeyColumn() {
        return TableManager.getInstance().isPrimaryKey(canonicalColumnName) || TableManager.getInstance().isForeignKey(canonicalColumnName);
    }

    @Override
    public void getColumn2ParameterBucket(Map<String, Map<String, List<Integer>>> column2Value2ParameterList) {
        if (operator != IN && operator != EQ) {
            return;
        }
        for (Parameter parameter : parameters) {
            String dataValue = parameter.getDataValue();
            if (column2Value2ParameterList.containsKey(canonicalColumnName)) {
                Map<String, List<Integer>> dataValue2ID = column2Value2ParameterList.get(canonicalColumnName);
                if (dataValue2ID.containsKey(dataValue)) {
                    List<Integer> idList = dataValue2ID.get(dataValue);
                    idList.add(parameter.getId());
                } else {
                    List<Integer> idList = new ArrayList<>();
                    idList.add(parameter.getId());
                    dataValue2ID.put(dataValue, idList);
                }
            } else {
                Map<String, List<Integer>> dataValue2ID = new HashMap<>();
                List<Integer> idList = new ArrayList<>();
                idList.add(parameter.getId());
                dataValue2ID.put(dataValue, idList);
                column2Value2ParameterList.put(canonicalColumnName, dataValue2ID);
            }
        }
    }

    @Override
    public BoolExprType getType() {
        return BoolExprType.UNI_FILTER_OPERATION;
    }

    public String getCanonicalColumnName() {
        return canonicalColumnName;
    }

    public void setCanonicalColumnName(String canonicalColumnName) {
        this.canonicalColumnName = canonicalColumnName;
    }

    private static String findConstraintValueForOperator(ColumnCDF.ParameterConstraint pc, CompareOperator op) {
        if (pc.valueToOperator != null) {
            for (Map.Entry<String, CompareOperator> e : pc.valueToOperator.entrySet()) {
                if (e.getValue() == op) {
                    return e.getKey();
                }
            }
        }
        if (pc.selectedValues != null) {
            for (String v : pc.selectedValues) {
                if (pc.getOperatorForValue(v) == op) {
                    return v;
                }
            }
        }
        return null;
    }

    /**
     * cdf 键无通配符时补成 {@code %key}，与常见 SQL {@code col LIKE '%514013202'} 一致；键已含 %/_ 则原样用于评估。
     */
    private static String likePatternLiteralForEvaluate(String cdfKey) {
        if (cdfKey == null) {
            return null;
        }
        if (cdfKey.indexOf('%') >= 0 || cdfKey.indexOf('_') >= 0) {
            return cdfKey;
        }
        return "%" + cdfKey;
    }

    @Override
    public String toString() {
        String parametersSQL;
        if (parameters.size() == 1) {
            parametersSQL = "'" + parameters.get(0).getDataValue() + "'";
        } else {
            parametersSQL = "('" + parameters.stream().map(Parameter::getDataValue).collect(Collectors.joining("','")) + "')";
        }
        return canonicalColumnName.split("\\.")[2] + CompareOperator.toSQL(operator) + parametersSQL;
    }

    /**
     * 初始化等值filter的参数
     */
    public void applyConstraint() {
        ColumnManager.getInstance().applyUniVarConstraint(canonicalColumnName, probability, operator, parameters);
    }

    @Override
    public boolean[] evaluate() {
        return ColumnManager.getInstance().evaluate(canonicalColumnName, operator, parameters);
    }

    @Override
    public List<String> getColumns() {
        return new ArrayList<>(List.of(canonicalColumnName));
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return !canonicalColumnName.contains(tableName);
    }

    @Override
    public BigDecimal getNullProbability() {
        return ColumnManager.getInstance().getNullPercentage(canonicalColumnName);
    }
}
