package ruc.db.rsgen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * RSGen中的通用数据值类型
 * 支持不同数据类型的统一处理和比较
 * 
 * @author RSGen Implementation
 */
public class Datum implements Comparable<Datum> {
    private Object value;
    private DatumType type;
    
    public enum DatumType {
        NULL,
        INTEGER,
        DECIMAL,
        VARCHAR,
        DATE,
        DATETIME,
        BOOLEAN
    }
    
    /**
     * 私有构造函数
     */
    private Datum(Object value, DatumType type) {
        this.value = value;
        this.type = type;
    }
    
    /**
     * 创建NULL值
     */
    public static Datum createNull() {
        return new Datum(null, DatumType.NULL);
    }
    
    /**
     * 创建整数值
     */
    public static Datum createInteger(long value) {
        return new Datum(value, DatumType.INTEGER);
    }
    
    /**
     * 创建小数值
     */
    public static Datum createDecimal(BigDecimal value) {
        return new Datum(value, DatumType.DECIMAL);
    }
    
    /**
     * 创建字符串值
     */
    public static Datum createVarchar(String value) {
        return new Datum(value, DatumType.VARCHAR);
    }
    
    /**
     * 创建日期值
     */
    public static Datum createDate(LocalDate value) {
        return new Datum(value, DatumType.DATE);
    }
    
    /**
     * 创建日期时间值
     */
    public static Datum createDateTime(LocalDateTime value) {
        return new Datum(value, DatumType.DATETIME);
    }
    
    /**
     * 创建布尔值
     */
    public static Datum createBoolean(boolean value) {
        return new Datum(value, DatumType.BOOLEAN);
    }
    
    /**
     * 从字符串解析创建Datum（用于从pg_stats解析）
     */
    public static Datum parseFromString(String str, DatumType targetType) {
        if (str == null || str.trim().isEmpty() || "null".equalsIgnoreCase(str.trim())) {
            return createNull();
        }
        
        try {
            switch (targetType) {
                case INTEGER:
                    return createInteger(Long.parseLong(str.trim()));
                case DECIMAL:
                    return createDecimal(new BigDecimal(str.trim()));
                case VARCHAR:
                    return createVarchar(str.trim());
                case DATE:
                    return createDate(LocalDate.parse(str.trim()));
                case DATETIME:
                    return createDateTime(LocalDateTime.parse(str.trim()));
                case BOOLEAN:
                    return createBoolean(Boolean.parseBoolean(str.trim()));
                default:
                    return createVarchar(str.trim());
            }
        } catch (Exception e) {
            // 解析失败时返回字符串类型
            return createVarchar(str.trim());
        }
    }
    
    /**
     * 转换为字符串表示（用于数据文件输出）
     */
    public String toOutputString() {
        if (type == DatumType.NULL || value == null) {
            return ""; // 或者 "NULL"，根据输出格式需求
        }
        
        switch (type) {
            case DATE:
                return ((LocalDate) value).toString();
            case DATETIME:
                return ((LocalDateTime) value).toString();
            default:
                return value.toString();
        }
    }
    
    /**
     * 检查是否为NULL
     */
    public boolean isNull() {
        return type == DatumType.NULL || value == null;
    }
    
    /**
     * 获取数值（用于数值计算）
     */
    public double getNumericValue() {
        switch (type) {
            case INTEGER:
                return ((Long) value).doubleValue();
            case DECIMAL:
                return ((BigDecimal) value).doubleValue();
            case BOOLEAN:
                return ((Boolean) value) ? 1.0 : 0.0;
            default:
                throw new UnsupportedOperationException("Cannot get numeric value for type: " + type);
        }
    }
    
    /**
     * 计算两个Datum之间的"距离"（用于bucket splitting）
     */
    public double distanceTo(Datum other) {
        if (this.isNull() || other.isNull()) {
            return Double.MAX_VALUE;
        }
        
        if (this.type != other.type) {
            return Double.MAX_VALUE;
        }
        
        switch (type) {
            case INTEGER:
            case DECIMAL:
                return Math.abs(this.getNumericValue() - other.getNumericValue());
            case VARCHAR:
                return Math.abs(this.value.toString().length() - other.value.toString().length());
            default:
                return this.equals(other) ? 0.0 : 1.0;
        }
    }
    
    @Override
    public int compareTo(Datum other) {
        if (this.isNull() && other.isNull()) return 0;
        if (this.isNull()) return -1;
        if (other.isNull()) return 1;
        
        if (this.type != other.type) {
            return this.type.ordinal() - other.type.ordinal();
        }
        
        switch (type) {
            case INTEGER:
                return Long.compare((Long) this.value, (Long) other.value);
            case DECIMAL:
                return ((BigDecimal) this.value).compareTo((BigDecimal) other.value);
            case VARCHAR:
                return this.value.toString().compareTo(other.value.toString());
            case DATE:
                return ((LocalDate) this.value).compareTo((LocalDate) other.value);
            case DATETIME:
                return ((LocalDateTime) this.value).compareTo((LocalDateTime) other.value);
            case BOOLEAN:
                return Boolean.compare((Boolean) this.value, (Boolean) other.value);
            default:
                return 0;
        }
    }
    
    /**
     * 返回两个Datum中的较大值
     */
    public static Datum max(Datum a, Datum b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }
    
    /**
     * 返回两个Datum中的较小值
     */
    public static Datum min(Datum a, Datum b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    /**
     * 将当前Datum作为下界，将区间分成total个子区间，返回第index个子区间的中点
     * 这是RSGen bucket对齐算法的核心方法
     */
    public Datum split(int index, int total, Datum high) {
        if (high == null || this.isNull() || high.isNull()) {
            return this;
        }
        
        if (total <= 0 || index < 0 || index >= total) {
            return this;
        }
        
        switch (this.type) {
            case INTEGER:
                long lowLong = (Long) this.value;
                long highLong = (Long) high.value;
                if (highLong <= lowLong) return this;
                
                double range = (double) (highLong - lowLong);
                double segmentSize = range / total;
                long splitValue = lowLong + (long) (segmentSize * (index + 0.5));
                return createInteger(splitValue);
                
            case DECIMAL:
                double lowDouble = ((Number) this.value).doubleValue();
                double highDouble = ((Number) high.value).doubleValue();
                if (highDouble <= lowDouble) return this;
                
                double decimalRange = highDouble - lowDouble;
                double decimalSegmentSize = decimalRange / total;
                double decimalSplitValue = lowDouble + (decimalSegmentSize * (index + 0.5));
                return createDecimal(BigDecimal.valueOf(decimalSplitValue));
                
            case VARCHAR:
                // 对于字符串，简单返回低值（可以改进为字典序分割）
                return this;
                
            default:
                return this;
        }
    }
    
    /**
     * 重载方法：不需要高值的split（向后兼容）
     */
    public Datum split(int index, int total) {
        // 创建一个默认的高值
        switch (this.type) {
            case INTEGER:
                return split(index, total, createInteger((Long) this.value + 1000));
            case DECIMAL:
                return split(index, total, createDecimal(BigDecimal.valueOf(((Number) this.value).doubleValue() + 1000)));
            default:
                return this;
        }
    }

    // Getters
    public Object getValue() { return value; }
    public DatumType getType() { return type; }
    
    @Override
    public String toString() {
        if (isNull()) {
            return "NULL";
        }
        return String.format("%s(%s)", type, value);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Datum datum = (Datum) obj;
        return Objects.equals(value, datum.value) && type == datum.type;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(value, type);
    }
}
