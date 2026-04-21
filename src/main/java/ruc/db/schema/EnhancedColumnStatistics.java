package ruc.db.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 对应 enhanced_column_statistics.json 中的列统计信息
 * 从 PostgreSQL 的 pg_stats 提取的增强统计信息
 * 
 * @author wangqingshuai
 */
public class EnhancedColumnStatistics {
    
    @JsonProperty("columnName")
    private String columnName;
    
    @JsonProperty("tableName")
    private String tableName;
    
    @JsonProperty("shortColumnName")
    private String shortColumnName;
    
    @JsonProperty("dataType")
    private String dataType;
    
    @JsonProperty("nullFraction")
    private double nullFraction;
    
    @JsonProperty("avgWidth")
    private int avgWidth;
    
    @JsonProperty("mostCommonValues")
    private List<String> mostCommonValues;
    
    @JsonProperty("mostCommonFrequencies")
    private List<Double> mostCommonFrequencies;
    
    @JsonProperty("histogramBounds")
    private List<String> histogramBounds;
    
    @JsonProperty("minValue")
    private String minValue;
    
    @JsonProperty("maxValue")
    private String maxValue;
    
    @JsonProperty("ndistinct")
    private double ndistinct;
    
    @JsonProperty("mcvCount")
    private int mcvCount;
    
    @JsonProperty("histogramBoundsCount")
    private int histogramBoundsCount;
    
    @JsonProperty("primaryKey")
    private boolean primaryKey;
    
    @JsonProperty("foreignKey")
    private boolean foreignKey;
    
    @JsonProperty("dataPattern")
    private String dataPattern;
    
    // 表大小（从外层 JSON 获取）
    private long tableSize;
    
    public EnhancedColumnStatistics() {
    }
    
    // Getters and Setters
    public String getColumnName() {
        return columnName;
    }
    
    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
    
    public String getShortColumnName() {
        return shortColumnName;
    }
    
    public void setShortColumnName(String shortColumnName) {
        this.shortColumnName = shortColumnName;
    }
    
    public String getDataType() {
        return dataType;
    }
    
    public void setDataType(String dataType) {
        this.dataType = dataType;
    }
    
    public double getNullFraction() {
        return nullFraction;
    }
    
    public void setNullFraction(double nullFraction) {
        this.nullFraction = nullFraction;
    }
    
    public int getAvgWidth() {
        return avgWidth;
    }
    
    public void setAvgWidth(int avgWidth) {
        this.avgWidth = avgWidth;
    }
    
    public List<String> getMostCommonValues() {
        return mostCommonValues;
    }
    
    public void setMostCommonValues(List<String> mostCommonValues) {
        this.mostCommonValues = mostCommonValues;
    }
    
    public List<Double> getMostCommonFrequencies() {
        return mostCommonFrequencies;
    }
    
    public void setMostCommonFrequencies(List<Double> mostCommonFrequencies) {
        this.mostCommonFrequencies = mostCommonFrequencies;
    }
    
    public List<String> getHistogramBounds() {
        return histogramBounds;
    }
    
    public void setHistogramBounds(List<String> histogramBounds) {
        this.histogramBounds = histogramBounds;
    }
    
    public String getMinValue() {
        return minValue;
    }
    
    public void setMinValue(String minValue) {
        this.minValue = minValue;
    }
    
    public String getMaxValue() {
        return maxValue;
    }
    
    public void setMaxValue(String maxValue) {
        this.maxValue = maxValue;
    }
    
    public double getNdistinct() {
        return ndistinct;
    }
    
    public void setNdistinct(double ndistinct) {
        this.ndistinct = ndistinct;
    }
    
    public int getMcvCount() {
        return mcvCount;
    }
    
    public void setMcvCount(int mcvCount) {
        this.mcvCount = mcvCount;
    }
    
    public int getHistogramBoundsCount() {
        return histogramBoundsCount;
    }
    
    public void setHistogramBoundsCount(int histogramBoundsCount) {
        this.histogramBoundsCount = histogramBoundsCount;
    }
    
    public boolean isPrimaryKey() {
        return primaryKey;
    }
    
    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }
    
    public boolean isForeignKey() {
        return foreignKey;
    }
    
    public void setForeignKey(boolean foreignKey) {
        this.foreignKey = foreignKey;
    }
    
    public String getDataPattern() {
        return dataPattern;
    }
    
    public void setDataPattern(String dataPattern) {
        this.dataPattern = dataPattern;
    }
    
    public long getTableSize() {
        return tableSize;
    }
    
    public void setTableSize(long tableSize) {
        this.tableSize = tableSize;
    }
    
    @Override
    public String toString() {
        return "EnhancedColumnStatistics{" +
                "columnName='" + columnName + '\'' +
                ", dataType='" + dataType + '\'' +
                ", mcvCount=" + mcvCount +
                ", histogramBoundsCount=" + histogramBoundsCount +
                ", ndistinct=" + ndistinct +
                '}';
    }
}

