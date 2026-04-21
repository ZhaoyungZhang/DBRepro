package ruc.db.generator.constraintchain.filter.arithmetic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ruc.db.schema.ColumnManager;
import ruc.db.utils.CommonUtils;
import ruc.db.utils.exception.analyze.IllegalQueryColumnNameException;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wangqingshuai
 */
public class ColumnNode extends ArithmeticNode {
    private String canonicalColumnName;

    public ColumnNode() {
        super(ArithmeticNodeType.COLUMN);
    }

    public String getCanonicalColumnName() {
        return canonicalColumnName;
    }

    public void setCanonicalColumnName(String canonicalColumnName) throws IllegalQueryColumnNameException {
        if (CommonUtils.isNotCanonicalColumnName(canonicalColumnName)) {
            throw new IllegalQueryColumnNameException();
        }
        this.canonicalColumnName = canonicalColumnName;
    }

    @Override
    public double[] calculate() {
        // ★★★ 修改：只在有统计信息参数时才使用新的采样方法，否则使用原始逻辑 ★★★
        int sampleSize = ArithmeticNode.size > 0 ? ArithmeticNode.size : -1;
        if (sampleSize > 0) {
            // 检查是否有统计信息参数（通过检查列是否有统计信息）
            return ColumnManager.getInstance().calculate(canonicalColumnName, sampleSize);
        }
        // 没有采样大小或没有统计信息，使用原始方法
        return ColumnManager.getInstance().calculate(canonicalColumnName);
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return !canonicalColumnName.contains(tableName);
    }

    @Override
    @JsonIgnore
    public List<String> getColumns() {
        return new ArrayList<>(List.of(canonicalColumnName));
    }

    @Override
    public String toString() {
        return canonicalColumnName;
    }
}
