package ruc.db.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 列统计信息类
 * 用于存储数据库列的统计信息，包括NDV、MCV、直方图等
 * 
 * @author mirage
 */
public class ColumnStatistics {
    
    @JsonProperty("column_name")
    private String columnName;
    
    @JsonProperty("null_fraction")
    private String nullFraction;     // NULL值的比例
    
    @JsonProperty("n_distinct_values")
    private String nDistinct;        // 不同值的数量 (NDV - Number of Distinct Values)
    
    @JsonProperty("avg_width_bytes")
    private String avgWidth;         // 平均字节宽度
    
    @JsonProperty("most_common_values")
    private String mostCommonVals;   // 最常见值 (MCV - Most Common Values)
    
    @JsonProperty("most_common_frequencies")
    private String mostCommonFreqs;  // 最常见值频率 (MCF - Most Common Frequencies)
    
    @JsonProperty("histogram_bounds")
    private String histogramBounds;  // 直方图边界 (用于等深度分布)
    
    /**
     * 默认构造函数，用于JSON反序列化
     */
    public ColumnStatistics() {
    }
    
    /**
     * 完整构造函数
     */
    public ColumnStatistics(String columnName, String nullFraction, String nDistinct, 
                           String avgWidth, String mostCommonVals, String mostCommonFreqs, 
                           String histogramBounds) {
        this.columnName = columnName;
        this.nullFraction = nullFraction;
        this.nDistinct = nDistinct;
        this.avgWidth = avgWidth;
        this.mostCommonVals = mostCommonVals;
        this.mostCommonFreqs = mostCommonFreqs;
        this.histogramBounds = histogramBounds;
    }
    
    // Getters
    public String getColumnName() { return columnName; }
    public String getNullFraction() { return nullFraction; }
    public String getNDistinct() { return nDistinct; }
    public String getAvgWidth() { return avgWidth; }
    public String getMostCommonVals() { return mostCommonVals; }
    public String getMostCommonFreqs() { return mostCommonFreqs; }
    public String getHistogramBounds() { return histogramBounds; }
    
    // Setters
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public void setNullFraction(String nullFraction) { this.nullFraction = nullFraction; }
    public void setNDistinct(String nDistinct) { this.nDistinct = nDistinct; }
    public void setAvgWidth(String avgWidth) { this.avgWidth = avgWidth; }
    public void setMostCommonVals(String mostCommonVals) { this.mostCommonVals = mostCommonVals; }
    public void setMostCommonFreqs(String mostCommonFreqs) { this.mostCommonFreqs = mostCommonFreqs; }
    public void setHistogramBounds(String histogramBounds) { this.histogramBounds = histogramBounds; }
    
    /**
     * 检查是否有有效的NDV信息
     */
    public boolean hasNDistinctInfo() {
        return nDistinct != null && !nDistinct.trim().isEmpty();
    }
    
    /**
     * 检查是否有MCV信息
     */
    public boolean hasMostCommonValues() {
        return mostCommonVals != null && !mostCommonVals.trim().isEmpty();
    }
    
    /**
     * 检查是否有直方图信息
     */
    public boolean hasHistogramInfo() {
        return histogramBounds != null && !histogramBounds.trim().isEmpty();
    }
    
    /**
     * 获取NDV的数值形式（如果可能）
     */
    public Double getNDistinctAsDouble() {
        if (nDistinct == null || nDistinct.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(nDistinct.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 获取NULL比例的数值形式（如果可能）
     */
    public Double getNullFractionAsDouble() {
        if (nullFraction == null || nullFraction.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(nullFraction.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    @Override
    public String toString() {
        return String.format("ColumnStatistics{columnName='%s', nullFraction='%s', nDistinct='%s', avgWidth='%s'}",
                columnName, nullFraction, nDistinct, avgWidth);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ColumnStatistics that = (ColumnStatistics) obj;
        return columnName != null && columnName.equals(that.columnName);
    }
    
    @Override
    public int hashCode() {
        return columnName != null ? columnName.hashCode() : 0;
    }
}