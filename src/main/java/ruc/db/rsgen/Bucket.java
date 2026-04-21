package ruc.db.rsgen;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * RSGen论文中的Bucket概念实现
 * 每个Bucket代表一个值区间及其统计属性
 * 用于将统计信息转换为可生成的数据结构
 * 
 * @author RSGen Implementation
 */
public class Bucket {
    private Datum low;        // 区间下界
    private Datum high;       // 区间上界  
    private long count;       // 此区间内的行数
    private long nDistinct;   // 此区间内的唯一值数量
    private BucketType type;  // Bucket类型：NULL, MCV, HISTOGRAM
    
    public enum BucketType {
        NULL,       // NULL值专用bucket
        MCV,        // 最常见值bucket
        HISTOGRAM   // 直方图bucket
    }
    
    /**
     * 构造函数
     */
    public Bucket(Datum low, Datum high, long count, long nDistinct, BucketType type) {
        this.low = low;
        this.high = high;
        this.count = count;
        this.nDistinct = nDistinct;
        this.type = type;
    }
    
    /**
     * NULL值专用构造函数
     */
    public static Bucket createNullBucket(long count) {
        return new Bucket(null, null, count, 1, BucketType.NULL);
    }
    
    /**
     * MCV（最常见值）专用构造函数
     */
    public static Bucket createMcvBucket(Datum value, long count) {
        return new Bucket(value, value, count, 1, BucketType.MCV);
    }
    
    /**
     * 直方图区间构造函数
     */
    public static Bucket createHistogramBucket(Datum low, Datum high, long count, long nDistinct) {
        return new Bucket(low, high, count, nDistinct, BucketType.HISTOGRAM);
    }
    
    /**
     * 检查这个bucket是否是单值bucket（MCV或NULL）
     */
    public boolean isSingleValue() {
        return type == BucketType.NULL || type == BucketType.MCV || 
               (low != null && high != null && low.equals(high));
    }
    
    /**
     * 检查给定值是否在此bucket范围内
     */
    public boolean contains(Datum value) {
        if (type == BucketType.NULL) {
            return value == null;
        }
        if (value == null) {
            return false;
        }
        if (low == null || high == null) {
            return false;
        }
        return value.compareTo(low) >= 0 && value.compareTo(high) <= 0;
    }
    
    /**
     * 计算此bucket的平均频率
     */
    public double getAverageFrequency(long totalRows) {
        return totalRows > 0 ? (double) count / totalRows : 0.0;
    }
    
    /**
     * 按照uniform assumption拆分bucket
     * 用于bucket alignment时的均匀分配
     */
    public Bucket[] split(int parts) {
        if (parts <= 1) {
            return new Bucket[]{this};
        }
        
        Bucket[] result = new Bucket[parts];
        long splitCount = count / parts;
        long splitNDistinct = Math.max(1, nDistinct / parts);
        
        if (type == BucketType.HISTOGRAM && low != null && high != null) {
            // 对于直方图bucket，需要按范围拆分
            for (int i = 0; i < parts; i++) {
                // 简化实现：每个子bucket都使用原始范围
                // 实际实现中需要根据数据类型进行范围划分
                result[i] = new Bucket(low, high, splitCount, splitNDistinct, type);
            }
        } else {
            // 对于NULL和MCV bucket，保持原样拆分count
            for (int i = 0; i < parts; i++) {
                result[i] = new Bucket(low, high, splitCount, splitNDistinct, type);
            }
        }
        
        return result;
    }
    
    // Getters and Setters
    public Datum getLow() { return low; }
    public void setLow(Datum low) { this.low = low; }
    
    public Datum getHigh() { return high; }
    public void setHigh(Datum high) { this.high = high; }
    
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
    
    public long getNDistinct() { return nDistinct; }
    public void setNDistinct(long nDistinct) { this.nDistinct = nDistinct; }
    
    // 兼容方法
    public long getDistinct() { return nDistinct; }
    public void setDistinct(long nDistinct) { this.nDistinct = nDistinct; }
    
    public BucketType getType() { return type; }
    public void setType(BucketType type) { this.type = type; }
    
    @Override
    public String toString() {
        return String.format("Bucket{type=%s, range=[%s, %s], count=%d, nDistinct=%d}", 
                           type, low, high, count, nDistinct);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Bucket bucket = (Bucket) obj;
        return count == bucket.count &&
               nDistinct == bucket.nDistinct &&
               Objects.equals(low, bucket.low) &&
               Objects.equals(high, bucket.high) &&
               type == bucket.type;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(low, high, count, nDistinct, type);
    }
}
