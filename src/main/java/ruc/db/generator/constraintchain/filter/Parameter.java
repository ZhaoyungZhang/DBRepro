package ruc.db.generator.constraintchain.filter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import ruc.db.generator.constraintchain.ConstraintChainManager;
import ruc.db.schema.ColumnManager;

/**
 * @author alan
 * 代表需要实例化的参数
 */
public class Parameter {

    private static final Pattern CanonicalColumnName = Pattern.compile("[a-zA-Z][a-zA-Z0-9$_]*\\.[a-zA-Z0-9_]+\\.[a-zA-Z0-9_]+");

    /**
     * parameter的id，用于后续实例化
     */
    private int id;
    /**
     * parameter的内部data，用于快速计算
     */
    private long data = -1;

    private ParameterType type = ParameterType.ACTUAL;

    /**
     * SUBSTRING 参数的起始位置（1-based），例如 substring(x from 1 for 2) 中的 1
     */
    @JsonProperty
    private int substringStart = -1;

    /**
     * SUBSTRING 参数的长度，例如 substring(x from 1 for 2) 中的 2
     */
    @JsonProperty
    private int substringLength = -1;

    @JsonIgnore
    public boolean isEqualPredicate() {
        return isEqualPredicate;
    }

    public void setEqualPredicate(boolean equalPredicate) {
        isEqualPredicate = equalPredicate;
    }

    @JsonIgnore
    private boolean isEqualPredicate = false;

    /**
     * 操作数
     */
    @JsonIgnore
    private String operand;
    /**
     * String化的值
     */
    @JsonIgnore
    private String dataValue;

    @JsonIgnore
    private boolean isSubPlan = false;

    @JsonIgnore
    private boolean canMerge = true;

    @JsonIgnore
    public boolean isCanMerge() {
        return canMerge;
    }

    public void setCanMerge(boolean canMerge) {
        this.canMerge = canMerge;
    }

    public Parameter() {
    }

    public Parameter(Integer id, String operand, String dataValue) {
        this.id = id;
        this.operand = operand;
        if (operand != null) {
            Matcher matcher = CanonicalColumnName.matcher(operand);
            List<String> cols = new ArrayList<>();
            if (matcher.find()) {
                cols.add(matcher.group());
            }
            if (cols.size() == 1 && ColumnManager.getInstance().isDateColumn((cols.get(0)))) {
                dataValue = dataValue.split(" ")[0];
            }
        }
        if (dataValue!=null && dataValue.endsWith("00:00:00")) {
            dataValue = dataValue.substring(0, dataValue.length() - 9);
        }
        this.dataValue = dataValue;
    }

    public ParameterType getType() {
        return type;
    }

    public void setType(ParameterType type) {
        this.type = type;
    }

    public List<String> hasOnlyOneColumn() {
        if (operand == null) {
            return new LinkedList<>();
        }
        Matcher matcher = CanonicalColumnName.matcher(operand);
        List<String> cols = new ArrayList<>();
        while (matcher.find()) {
            cols.add(matcher.group());
        }
        return cols;
    }

    public Integer getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    @JsonIgnore
    public String getRealDataValue() {
        if (type == ParameterType.SUBSTRING) {
            return dataValue.replace("%", "");
        } else {
            return dataValue;
        }
    }

    public String getDataValue() {
        if (ConstraintChainManager.getInstance().isDraw())
            return "#" + id;
        return dataValue;
    }

    public void setDataValue(String dataValue) {
        // ★★★ 修复 Q22：对于 SUBSTRING 类型参数，提取指定范围的字符 ★★★
        if (type == ParameterType.SUBSTRING && substringStart > 0 && substringLength > 0 && dataValue != null) {
            // 先从 dataValue 中去掉通配符 '%'（Parser 已经添加了）
            String cleanValue = dataValue.replace("%", "");
            // substring 在 SQL 中是 1-based 的索引
            int endIndex = Math.min(substringStart - 1 + substringLength, cleanValue.length());
            if (substringStart - 1 < cleanValue.length()) {
                dataValue = cleanValue.substring(substringStart - 1, endIndex);
            } else {
                dataValue = cleanValue;
            }
            // SUBSTRING 类型会在后面被当作 LIKE 处理，所以这里不需要再添加 '%'
        }
        
        // LIKE 参数：历史逻辑为“内部存裸串、这里补一个前缀 %”。若调用方已带 %（如 CDF amend 传入 %514013202），不可再拼一层 %%。
        if (type == ParameterType.LIKE && dataValue != null && !dataValue.isEmpty() && dataValue.charAt(0) != '%') {
            dataValue = '%' + dataValue;
        }
        if (dataValue != null && dataValue.endsWith("00:00:00")) {
            dataValue = dataValue.substring(0, dataValue.length() - 9);
        }
        this.dataValue = dataValue;
    }

    public long getData() {
        return data;
    }


    public void setData(long data) {
        this.data = data;
    }

    @JsonIgnore
    public String getOperand() {
        return operand;
    }

    public void setOperand(String operand) {
        this.operand = operand;
    }

    @Override
    public String toString() {
        return "{id:" + id + ", data:" + dataValue + "}";
    }

    @JsonIgnore
    public boolean isSubPlan() {
        return isSubPlan;
    }

    @JsonIgnore
    public boolean isSubString() {
        return type == ParameterType.SUBSTRING;
    }

    @JsonIgnore
    public void setSubPlan(boolean subPlan) {
        isSubPlan = subPlan;
    }

    @JsonIgnore
    public int getSubstringStart() {
        return substringStart;
    }

    @JsonIgnore
    public void setSubstringStart(int substringStart) {
        this.substringStart = substringStart;
    }

    @JsonIgnore
    public int getSubstringLength() {
        return substringLength;
    }

    @JsonIgnore
    public void setSubstringLength(int substringLength) {
        this.substringLength = substringLength;
    }

    public enum ParameterType {
        ACTUAL,
        VIRTUAL,
        LIKE,
        SUBSTRING
    }
}
