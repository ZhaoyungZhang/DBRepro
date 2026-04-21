package ruc.db.generator.constraintchain.filter.arithmetic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author wangqingshuai
 */
public class NumericNode extends ArithmeticNode {
    private Float constant;
    private String strVal;

    public NumericNode() {
        super(ArithmeticNodeType.CONSTANT);
    }

    public Float getConstant() {
        return constant;
    }

    public void setConstant(float constant) {
        this.strVal = Float.toString(constant);
        this.constant = constant;
    }

    public void setConstant(int constant) {
        this.strVal = Integer.toString(constant);
        this.constant = (float) constant;
    }

    /**
     * 词法整型字面量（可能超过 {@link Integer#MAX_VALUE}），用于 EXPLAIN 中 {@code bigint} 比较等。
     */
    public void setConstantLexeme(String digits) {
        this.strVal = digits;
        try {
            this.constant = (float) Long.parseLong(digits);
        } catch (NumberFormatException e) {
            this.constant = (float) Double.parseDouble(digits);
        }
    }

    @JsonSetter
    public void setConstant(String constant) {
        this.strVal = constant;
        this.constant = Float.parseFloat(constant);
    }

    @Override
    public double[] calculate() {
        double[] value = new double[size];
        Arrays.fill(value, constant);
        return value;
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return false;
    }

    @Override
    public List<String> getColumns() {
        return new ArrayList<>();
    }

    @Override
    public String toString() {
        return strVal;
    }
}
