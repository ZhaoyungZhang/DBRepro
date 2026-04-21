package ruc.db.generator.constraintchain.filter.arithmetic;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class MathNode extends ArithmeticNode {

    public MathNode(ArithmeticNodeType type) {
        super(type);
    }

    public MathNode() {
        super(ArithmeticNodeType.MINUS);
    }

    @Override
    public double[] calculate() {
        double[] leftValue = leftNode.calculate();
        double[] rightValue = rightNode.calculate();
        
        // ★★★ 诊断：检查输入数据是否有 NaN ★★★
        long leftNaNCount = 0, rightNaNCount = 0;
        for (double v : leftValue) {
            if (Double.isNaN(v)) leftNaNCount++;
        }
        for (double v : rightValue) {
            if (Double.isNaN(v)) rightNaNCount++;
        }
        if (leftNaNCount > 0 || rightNaNCount > 0) {
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MathNode.class);
            logger.warn("MathNode.calculate() - 输入数据包含 NaN: leftNode={}, leftNaN={}/{}, rightNode={}, rightNaN={}/{}", 
                       leftNode.toString(), leftNaNCount, leftValue.length,
                       rightNode.toString(), rightNaNCount, rightValue.length);
        }
        
        // ★★★ 诊断：对于MINUS操作，检查输入数据的范围（用于日期差值计算） ★★★
        if (type == ArithmeticNodeType.MINUS && leftValue.length > 0 && rightValue.length > 0) {
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MathNode.class);
            double leftMin = Double.POSITIVE_INFINITY, leftMax = Double.NEGATIVE_INFINITY;
            double rightMin = Double.POSITIVE_INFINITY, rightMax = Double.NEGATIVE_INFINITY;
            for (double v : leftValue) {
                if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                    if (v < leftMin) leftMin = v;
                    if (v > leftMax) leftMax = v;
                }
            }
            for (double v : rightValue) {
                if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                    if (v < rightMin) rightMin = v;
                    if (v > rightMax) rightMax = v;
                }
            }
            if (leftMin != Double.POSITIVE_INFINITY && rightMin != Double.POSITIVE_INFINITY) {
                logger.debug("MathNode.MINUS - 输入数据范围: leftNode={}, leftRange=[{}, {}], rightNode={}, rightRange=[{}, {}]", 
                           leftNode.toString(), leftMin, leftMax,
                           rightNode.toString(), rightMin, rightMax);
            }
        }
        
        // ★★★ 修复：创建新数组而不是修改原有数组，避免数据污染 ★★★
        double[] result = new double[leftValue.length];
        
        switch (type) {
            case MUL -> {
                for (int i = 0; i < leftValue.length; i++) {
                    result[i] = leftValue[i] * rightValue[i];
                }
            }
            case DIV -> {
                for (int i = 0; i < leftValue.length; i++) {
                    result[i] = leftValue[i] / (rightValue[i] == 0 ? Double.MIN_NORMAL : rightValue[i]);
                }
            }
            case PLUS -> {
                for (int i = 0; i < leftValue.length; i++) {
                    result[i] = leftValue[i] + rightValue[i];
                }
            }
            case MINUS -> {
                for (int i = 0; i < leftValue.length; i++) {
                    result[i] = leftValue[i] - rightValue[i];
                }
                // ★★★ 诊断：检查MINUS操作的结果范围 ★★★
                if (result.length > 0) {
                    org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MathNode.class);
                    double resultMin = Double.POSITIVE_INFINITY, resultMax = Double.NEGATIVE_INFINITY;
                    for (double v : result) {
                        if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                            if (v < resultMin) resultMin = v;
                            if (v > resultMax) resultMax = v;
                        }
                    }
                    if (resultMin != Double.POSITIVE_INFINITY) {
                        logger.debug("MathNode.MINUS - 计算结果范围: [{}, {}]", 
                                   String.format("%.2f", resultMin), String.format("%.2f", resultMax));
                    }
                }
            }
            default -> throw new UnsupportedOperationException();
        }
        
        // ★★★ 诊断：检查计算结果是否有新的 NaN ★★★
        long resultNaNCount = 0;
        for (double v : result) {
            if (Double.isNaN(v)) resultNaNCount++;
        }
        if (resultNaNCount > leftNaNCount + rightNaNCount) {
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MathNode.class);
            logger.error("⚠️ MathNode.calculate() - 算术运算产生了新的 NaN: 操作={}, 输入NaN={}+{}, 输出NaN={}", 
                        type, leftNaNCount, rightNaNCount, resultNaNCount);
        }
        
        return result;
    }

    @JsonIgnore
    @Override
    public boolean isDifferentTable(String tableName) {
        return leftNode.isDifferentTable(tableName) || rightNode.isDifferentTable(tableName);
    }

    @Override
    public List<String> getColumns() {
        List<String> columnNames = leftNode.getColumns();
        if (rightNode != null) {
            columnNames.addAll(rightNode.getColumns());
        }
        return columnNames;
    }

    @Override
    public String toString() {
        String mathType = switch (type) {
            case MINUS -> "-";
            case DIV -> "/";
            case MUL -> "*";
            case PLUS -> "+";
            case MAX -> "max";
            case MIN -> "min";
            case AVG -> "avg";
            case SUM -> "sum";
            default -> throw new UnsupportedOperationException();
        };
        if (type.isUniComparator()) {
            return String.format("%s(%s)", mathType, leftNode.toString());
        } else {
            return String.format("%s %s %s", leftNode.toString(), mathType, rightNode.toString());
        }
    }
}
