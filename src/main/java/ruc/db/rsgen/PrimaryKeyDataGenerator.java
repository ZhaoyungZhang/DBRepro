package ruc.db.rsgen;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.rsgen.EnhancedStatsExtractor.EnhancedColumnStatistics;

/**
 * 主键数据生成器
 * 
 * 从RSGenDataGeneratorRefactored中拆分出来的主键生成逻辑
 * 支持多种主键生成策略：
 * 1. 连续unique主键生成
 * 2. 基于MCV的主键生成
 * 3. 基于直方图的主键生成
 * 4. 基于bucket的标准生成
 * 
 * @author RSGen Team
 */
public class PrimaryKeyDataGenerator {
    private static final Logger logger = LoggerFactory.getLogger(PrimaryKeyDataGenerator.class);
    
    private final Random random;
    private final Map<String, List<Bucket>> globalBuckets; // 用于获取bucket信息
    
    public PrimaryKeyDataGenerator(Map<String, List<Bucket>> globalBuckets) {
        this.random = new Random(System.currentTimeMillis());
        this.globalBuckets = globalBuckets;
        logger.info("PrimaryKeyDataGenerator初始化完成");
    }
    
    /**
     * 主键列数据生成入口方法（调度各类情况）
     */
    public Object[] generatePrimaryKeyColumnData(EnhancedColumnStatistics colStats, long tableSize, List<Bucket> buckets) {
        List<String> mcvValues = colStats.getMostCommonValues();
        List<Double> mcvFreqs = colStats.getMostCommonFrequencies();
        List<String> histogramBounds = colStats.getHistogramBounds();
        double nDistinct = colStats.getNDistinct();
        String tableName = colStats.getTableName();
        String columnName = colStats.getColumnName();

        // ========== 1. 检查是否为真正的连续unique主键 ==========
        // 只有当nDistinct为-1.0且值域范围接近表大小时才认为是连续unique
        boolean isSequentialUnique = isSequentialUniquePrimaryKey(colStats, tableSize);
        if (isSequentialUnique) {
            logger.info("检测到连续unique主键列 {}.{} (nDistinct={}, tableSize={})，使用连续unique主键生成策略", 
                       tableName, columnName, nDistinct, tableSize);
            return generateUniquePrimaryKeyData(colStats, tableSize);
        } else if (nDistinct == -1.0) {
            // 稀疏unique主键，使用bucket生成
            logger.info("检测到稀疏unique主键列 {} (nDistinct={}, tableSize={})，使用bucket生成策略", 
                       columnName, nDistinct, tableSize);
        } else {
            logger.info("检测到非unique主键列 {} (nDistinct={}, tableSize={})，使用标准主键生成策略", 
                       columnName, nDistinct, tableSize);
        }

        // ========== 2. 优先处理 MCV ==========
        if (mcvValues != null && !mcvValues.isEmpty() && mcvFreqs != null && !mcvFreqs.isEmpty()) {
            double totalFreq = mcvFreqs.stream().mapToDouble(Double::doubleValue).sum();

            if (totalFreq >= 0.8) {
                logger.debug("主键列{}使用MCV频率生成（总频率={})", colStats.getColumnName(), totalFreq);
                return generatePrimaryKeyFromMCV(mcvValues, mcvFreqs, tableSize, colStats);
            } else {
                logger.debug("主键列{}使用MCV+Histogram混合生成（总频率={})", colStats.getColumnName(), totalFreq);
                return generatePrimaryKeyFromMCVAndHistogram(mcvValues, mcvFreqs, histogramBounds, tableSize, colStats);
            }
        }

        // ========== 3. 如果有Histogram ==========
        if (histogramBounds != null && !histogramBounds.isEmpty()) {
            int histSize = histogramBounds.size();

            // 3.1 如果边界数量刚好等于表大小，可直接使用
            if (histSize == tableSize) {
                logger.debug("主键列{}使用直方图边界值直接生成", colStats.getColumnName());
                return generatePrimaryKeyFromHistogramBounds(histogramBounds, tableSize, colStats);
            }

            // 3.2 如果边界数量远大于表大小，则当成稀疏分布处理
            if (histSize > tableSize) {
                logger.debug("主键列{}使用稀疏直方图生成", colStats.getColumnName());
                return generatePrimaryKeyFromSparseHistogram(histogramBounds, tableSize, colStats);
            }
        }

        // ========== 4. fallback 使用bucket生成 ==========
        logger.info("主键列{}无特殊策略，使用标准bucket生成", colStats.getColumnName());
        return generateColumnDataFromBuckets(buckets, tableSize, colStats);
    }

    /**
     * 基于MCV生成主键数据
     */
    private Object[] generatePrimaryKeyFromMCV(List<String> mcvValues, List<Double> mcvFreqs, long tableSize, EnhancedColumnStatistics colStats) {
        // 布尔主键：直接按频率生成 true/false，避免走后续unique/non-unique分支
        String dataType = colStats.getDataType().toLowerCase();
        if (dataType.contains("bool")) {
            return generateBooleanColumnData(tableSize, colStats);
        }

        Object[] data = new Object[(int) tableSize];
        
        // 检查是否为非unique主键列
        double nDistinct = colStats.getNDistinct();
        boolean isNonUniquePrimaryKey = nDistinct > 0 && nDistinct < tableSize * 0.95;
        
        if (isNonUniquePrimaryKey) {
            // 对于非unique主键列，按照MCV频率分布生成，不强制唯一性
            logger.debug("非unique主键列{}，按照MCV频率分布生成数据（允许重复值）", colStats.getColumnName());
            
            for (int i = 0; i < tableSize; i++) {
                Object value = selectWeightedMCVValue(mcvValues, mcvFreqs, colStats);
                data[i] = value;
            }
        } else {
            // 对于unique主键列，保持原有逻辑
            if (mcvValues.size() >= tableSize) {
                for (int i = 0; i < tableSize; i++) {
                    data[i] = convertStringToValue(mcvValues.get(i), colStats.getDataType().toLowerCase());
                }
            } else {
                // MCV值数量不足，需要生成唯一值
                Set<Object> usedValues = new HashSet<>();
                
                for (int i = 0; i < tableSize; i++) {
                    Object value = selectWeightedMCVValue(mcvValues, mcvFreqs, colStats);
                    
                    // 如果值已存在，尝试生成唯一值
                    if (usedValues.contains(value)) {
                        value = generateUniqueValue(value, usedValues, colStats);
                    }
                    
                    data[i] = value;
                    usedValues.add(value);
                }
            }
        }
        
        logger.debug("基于MCV生成了{}个主键值", tableSize);
        return data;
    }

    /**
     * 基于MCV和直方图生成主键数据
     */
    private Object[] generatePrimaryKeyFromMCVAndHistogram(List<String> mcvValues, List<Double> mcvFreqs, 
            List<String> histogramBounds, long tableSize, EnhancedColumnStatistics colStats) {
        // 简化逻辑：直接使用bucket生成，因为bucket已经包含了MCV和直方图的信息
        logger.info("主键列 {} 使用bucket生成策略（MCV+直方图信息已包含在bucket中）", colStats.getColumnName());
        
        // 获取该列的buckets
        String bucketKey = generateBucketKey(colStats.getTableName(), colStats.getColumnName());
        List<Bucket> buckets = globalBuckets.get(bucketKey);
        
        if (buckets == null || buckets.isEmpty()) {
            logger.warn("主键列 {} 没有找到buckets，使用默认生成策略", colStats.getColumnName());
            return generateSequentialPrimaryKey(tableSize, colStats);
        }
        
        // 直接使用bucket生成数据
        return generateColumnDataFromBuckets(buckets, tableSize, colStats);
    }

    /**
     * 基于直方图边界生成主键数据
     */
    private Object[] generatePrimaryKeyFromHistogramBounds(List<String> histogramBounds, long tableSize, EnhancedColumnStatistics colStats) {
        Object[] data = new Object[(int) tableSize];
        String dataType = colStats.getDataType().toLowerCase();
        
        for (int i = 0; i < tableSize && i < histogramBounds.size(); i++) {
            data[i] = convertStringToValue(histogramBounds.get(i), dataType);
        }
        
        // 填充剩余位置
        for (int i = histogramBounds.size(); i < tableSize; i++) {
            data[i] = getDefaultValue(colStats);
        }
        
        logger.debug("基于直方图边界生成了{}个主键值", tableSize);
        return data;
    }

    /**
     * 基于稀疏直方图生成主键数据
     */
    private Object[] generatePrimaryKeyFromSparseHistogram(List<String> histogramBounds, long tableSize, EnhancedColumnStatistics colStats) {
        Object[] data = new Object[(int) tableSize];
        String dataType = colStats.getDataType().toLowerCase();
        
        // 使用Set确保唯一性
        Set<Object> usedValues = new HashSet<>();
        
        for (int i = 0; i < tableSize; i++) {
            Object value;
            int attempts = 0;
            
            // 尝试从直方图边界中选择唯一值
            do {
                int randomIndex = random.nextInt(histogramBounds.size());
                value = convertStringToValue(histogramBounds.get(randomIndex), dataType);
                attempts++;
                
                // 如果尝试次数过多，生成顺序值
                if (attempts > 100) {
                    value = generateSequentialValue(i, colStats);
                    break;
                }
            } while (usedValues.contains(value));
            
            data[i] = value;
            usedValues.add(value);
        }
        
        logger.debug("基于稀疏直方图生成了{}个唯一主键值", tableSize);
        return data;
    }

    /**
     * 判断是否为连续unique主键
     * 条件：
     * 1. nDistinct为-1.0（表示unique）
     * 2. 值域范围接近表大小（稀疏度不高）
     */
    private boolean isSequentialUniquePrimaryKey(EnhancedColumnStatistics colStats, long tableSize) {
        double nDistinct = colStats.getNDistinct();
        
        // 首先检查nDistinct是否为-1.0
        if (nDistinct != -1.0) {
            return false;
        }
        
        // 检查值域范围
        String minValue = colStats.getMinValue();
        String maxValue = colStats.getMaxValue();
        
        if (minValue != null && maxValue != null) {
            try {
                long min = Long.parseLong(minValue);
                long max = Long.parseLong(maxValue);
                long range = max - min + 1;
                
                // 如果值域范围小于等于表大小的1.5倍，认为是连续的
                // 这样可以处理一些轻微的稀疏情况，但避免过度稀疏
                double sparsityRatio = (double) range / tableSize;
                boolean isSequential = sparsityRatio <= 1.5;
                
                logger.debug("主键列 {} 值域范围 [{}, {}] = {}, 表大小 = {}, 稀疏度 = {}, 是否连续 = {}", 
                           colStats.getColumnName(), min, max, range, tableSize, sparsityRatio, isSequential);
                
                return isSequential;
            } catch (NumberFormatException e) {
                logger.debug("无法解析主键列 {} 的值域范围: min={}, max={}", 
                           colStats.getColumnName(), minValue, maxValue);
            }
        }
        
        // 如果没有值域信息，使用保守策略：默认不是连续的
        logger.debug("主键列 {} 缺少值域信息，默认使用bucket生成", colStats.getColumnName());
        return false;
    }

    /**
     * 生成unique主键数据
     */
    private Object[] generateUniquePrimaryKeyData(EnhancedColumnStatistics colStats, long tableSize) {
        String dataType = colStats.getDataType().toLowerCase();
        
        // 获取统计信息中的值域范围
        long startValue = 1; // 默认从1开始
        String minValue = colStats.getMinValue();
        String maxValue = colStats.getMaxValue();
        
        if (minValue != null && maxValue != null) {
            try {
                long min = Long.parseLong(minValue);
                long max = Long.parseLong(maxValue);
                
                // 如果值域范围接近表大小，使用实际的值域范围
                long range = max - min + 1;
                if (range <= tableSize * 1.5) { // 允许一定的稀疏度
                    startValue = min;
                    logger.debug("主键列 {} 使用实际值域范围 [{}, {}] 生成", 
                               colStats.getColumnName(), min, max);
                } else {
                    logger.debug("主键列 {} 值域范围 [{}, {}] 过于稀疏，使用默认范围 [1, {}]", 
                               colStats.getColumnName(), min, max, tableSize);
                }
            } catch (NumberFormatException e) {
                logger.debug("无法解析主键列 {} 的值域范围: min={}, max={}，使用默认范围", 
                           colStats.getColumnName(), minValue, maxValue);
            }
        }
        
        // 生成unique主键数据
        Object[] data = new Object[(int) tableSize];
        
        for (int i = 0; i < tableSize; i++) {
            if (dataType.contains("int") || dataType.contains("serial")) {
                data[i] = (long) (startValue + i);
            } else if (dataType.contains("decimal") || dataType.contains("numeric") || dataType.contains("real") || dataType.contains("float") || dataType.contains("double")) {
                data[i] = (double) (startValue + i);
            } else {
                data[i] = String.valueOf(startValue + i);
            }
        }
        
        logger.debug("生成了{}个unique主键值，范围[{}-{}]", tableSize, startValue, startValue + tableSize - 1);
        return data;
    }

    /**
     * 生成唯一顺序数据（扩展范围）
     */
    private Object[] generateUniqueSequentialData(long tableSize, int minValue, long maxValue, EnhancedColumnStatistics colStats) {
        Object[] data = new Object[(int) tableSize];
        String dataType = colStats.getDataType().toLowerCase();
        
        for (int i = 0; i < tableSize; i++) {
            if (dataType.contains("int") || dataType.contains("serial")) {
                data[i] = (long) (minValue + i);
            } else if (dataType.contains("decimal") || dataType.contains("numeric") || dataType.contains("real") || dataType.contains("float") || dataType.contains("double")) {
                data[i] = (double) (minValue + i);
            } else {
                data[i] = String.valueOf(minValue + i);
            }
        }
        
        logger.debug("生成了{}个唯一顺序主键值，范围[{}-{}]", tableSize, minValue, minValue + tableSize - 1);
        return data;
    }

    /**
     * 生成顺序主键数据（从1开始）
     */
    private Object[] generateSequentialPrimaryKey(long tableSize, EnhancedColumnStatistics colStats) {
        return generateUniqueSequentialData(tableSize, 1, tableSize, colStats);
    }

    /**
     * 从bucket生成列数据（标准方法）
     */
    private Object[] generateColumnDataFromBuckets(List<Bucket> buckets, long tableSize, EnhancedColumnStatistics colStats) {
        // 检查buckets是否为空
        if (buckets == null || buckets.isEmpty()) {
            logger.warn("列 {} 的buckets为空，使用主键默认生成策略", colStats.getColumnName());
            return generatePrimaryKeyDefaultData(colStats, tableSize);
        }
        
        // 验证buckets总数
        long totalBucketCount = buckets.stream().mapToLong(Bucket::getCount).sum();
        if (totalBucketCount != tableSize) {
            logger.warn("列 {} 的bucket总数 {} 不等于表大小 {}，进行调整", 
                       colStats.getColumnName(), totalBucketCount, tableSize);
            
            // 调整最后一个bucket的count
            if (!buckets.isEmpty()) {
                Bucket lastBucket = buckets.get(buckets.size() - 1);
                long adjustment = tableSize - totalBucketCount;
                long newCount = Math.max(0, lastBucket.getCount() + adjustment);
                lastBucket.setCount(newCount);
                logger.info("调整最后一个bucket的count：{} -> {}", lastBucket.getCount(), newCount);
            }
        }

        // 使用改进的bucket数据生成逻辑
        Object[] data = new Object[(int) tableSize];
        int index = 0;
        
        for (Bucket bucket : buckets) {
            int count = (int) bucket.getCount();
            Object[] bucketData = generateBucketData(bucket, count, colStats);
            
            // 复制bucket数据到结果数组
            System.arraycopy(bucketData, 0, data, index, count);
            index += count;
        }
        
        return data;
    }

    /**
     * 生成单个bucket的数据
     * 根据RSGen论文的算法：将bucket区间划分为nDistinct个子区间，每个子区间取中点作为候选值
     */
    private Object[] generateBucketData(Bucket bucket, int count, EnhancedColumnStatistics colStats) {
        Object[] data = new Object[count];
        String dataType = colStats.getDataType().toLowerCase();
        
        if (bucket.getType() == Bucket.BucketType.NULL) {
            // ★★★ 防御性检查：如果nullFraction=0，不应该有NULL bucket ★★★
            if (colStats.getNullFraction() <= 0) {
                logger.warn("⚠️ 列 {} 的nullFraction=0，但遇到NULL bucket (count={})，跳过生成NULL值，使用默认值填充",
                           colStats.getColumnName(), count);
                // 使用默认值而不是null
                Object defaultValue = getDefaultValue(colStats);
                for (int i = 0; i < count; i++) {
                    data[i] = defaultValue;
                }
                return data;
            }
            // NULL bucket正常处理（nullFraction > 0）
            for (int i = 0; i < count; i++) {
                data[i] = null;
            }
        } else if (bucket.getType() == Bucket.BucketType.MCV) {
            // MCV bucket
            Object value = bucket.getLow().getValue();
            for (int i = 0; i < count; i++) {
                data[i] = value;
            }
        } else {
            // Histogram bucket - 使用RSGen核心算法
            if (bucket.getLow() != null && bucket.getHigh() != null) {
                long nDistinct = bucket.getDistinct();
                if (nDistinct <= 1) {
                    // 只有一个唯一值，直接使用low值
                    Object value = bucket.getLow().getValue();
                    for (int i = 0; i < count; i++) {
                        data[i] = value;
                    }
                } else {
                    if (isDateLikeHistogramDataType(dataType)) {
                        DateDataGenerator dateGenerator = new DateDataGenerator();
                        Bucket dateBucket = new Bucket(bucket.getLow(), bucket.getHigh(), 0, nDistinct, Bucket.BucketType.HISTOGRAM);
                        List<Object> distinctValues = dateGenerator.generateDateDistinctValues(dateBucket, (int) nDistinct);
                        int dvSize = distinctValues.isEmpty() ? 1 : distinctValues.size();
                        for (int i = 0; i < count; i++) {
                            long idx = i % nDistinct;
                            data[i] = distinctValues.get((int) (idx % dvSize));
                        }
                    } else {
                        // 核心算法：将区间划分为nDistinct个子区间，每个子区间取中点
                        for (int i = 0; i < count; i++) {
                            long index = i % nDistinct; // 确定性索引，保证主外键一致性
                            data[i] = generateValueFromSubInterval(bucket.getLow(), bucket.getHigh(), index, nDistinct, dataType);
                        }
                    }
                }
            } else {
                // 边界为null，使用默认值
                for (int i = 0; i < count; i++) {
                    data[i] = getDefaultValue(colStats);
                }
            }
        }
        
        return data;
    }

    private static boolean isDateLikeHistogramDataType(String dataType) {
        if (dataType == null) {
            return false;
        }
        String dt = dataType.toLowerCase();
        return dt.contains("timestamp") || dt.contains("datetime")
                || (dt.contains("date") && !dt.contains("json"));
    }

    /**
     * 根据子区间索引生成值
     * 将[low, high]区间划分为nDistinct个子区间，取第index个子区间的中点
     */
    private Object generateValueFromSubInterval(Datum low, Datum high, long index, long nDistinct, String dataType) {
        try {
            if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
                long lowVal = (Long) low.getValue();
                long highVal = (Long) high.getValue();
                
                // 特殊处理：当nDistinct等于区间范围时，直接生成区间内的所有整数值
                long range = highVal - lowVal + 1;
                if (nDistinct == range && range > 0) {
                    // 这种情况下，直接返回区间内的第index个整数值
                    long value = lowVal + index;
                    if (value <= highVal) {
                        return value;
                    } else {
                        // 如果超出范围，循环使用
                        return lowVal + (index % range);
                    }
                }
                
                // 修复：使用浮点数计算避免整数除法精度丢失
                double rangeDouble = (double) (highVal - lowVal);
                double intervalSize = rangeDouble / nDistinct;
                double subIntervalStart = lowVal + (index * intervalSize);
                double subIntervalEnd = lowVal + ((index + 1) * intervalSize);
                // 取子区间中点
                long value = Math.round(subIntervalStart + (subIntervalEnd - subIntervalStart) / 2.0);
                return value;
            } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
                double lowVal = convertToDouble(low.getValue());
                double highVal = convertToDouble(high.getValue());
                double intervalSize = (highVal - lowVal) / nDistinct;
                double subIntervalStart = lowVal + (index * intervalSize);
                double subIntervalEnd = lowVal + ((index + 1) * intervalSize);
                // 取子区间中点
                double value = subIntervalStart + (subIntervalEnd - subIntervalStart) / 2.0;
                return value;
            } else if (dataType.contains("date")) {
                // 日期类型：使用DateDataGenerator生成标准日期
                DateDataGenerator dateGenerator = new DateDataGenerator();
                Bucket dateBucket = new Bucket(low, high, 0, nDistinct, Bucket.BucketType.HISTOGRAM);
                List<Object> distinctValues = dateGenerator.generateDateDistinctValues(dateBucket, (int) nDistinct);
                
                if (!distinctValues.isEmpty()) {
                    int valueIndex = (int) (index % distinctValues.size());
                    return distinctValues.get(valueIndex);
                } else {
                    // 如果生成失败，返回标准日期格式
                    return "1992-01-01";
                }
            } else if (dataType.contains("bool")) {
                // 布尔类型：直接返回low的布尔值，不附加后缀
                Object base = low.getValue();
                if (base instanceof Boolean) return base;
                String s = String.valueOf(base);
                return Boolean.parseBoolean(s);
            } else {
                // 其他字符串类型：直接返回low值，避免附加后缀导致导入失败
                return low.getValue();
            }
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("子区间值生成出错：数据类型={}, low={}, high={}, index={}, nDistinct={}, 错误={}", 
                    dataType, low != null ? low.getValue() : "null", 
                    high != null ? high.getValue() : "null", index, nDistinct, e.getMessage());
            }
            return low != null ? low.getValue() : getDefaultValueForType(dataType);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据加权频率选择MCV值
     */
    private Object selectWeightedMCVValue(List<String> mcvValues, List<Double> mcvFreqs, EnhancedColumnStatistics colStats) {
        double randomValue = random.nextDouble();
        double cumulativeFreq = 0.0;
        
        for (int i = 0; i < mcvValues.size(); i++) {
            cumulativeFreq += mcvFreqs.get(i);
            if (randomValue <= cumulativeFreq) {
                return convertStringToValue(mcvValues.get(i), colStats.getDataType().toLowerCase());
            }
        }
        
        // 如果所有频率都不匹配，返回第一个值
        return convertStringToValue(mcvValues.get(0), colStats.getDataType().toLowerCase());
    }

    /**
     * 生成唯一值（当值重复时）
     */
    private Object generateUniqueValue(Object originalValue, Set<Object> usedValues, EnhancedColumnStatistics colStats) {
        String dataType = colStats.getDataType().toLowerCase();
        int attempt = 0;
        Object newValue = originalValue;
        
        while (usedValues.contains(newValue) && attempt < 1000) {
            attempt++;
            
            if (dataType.contains("int") || dataType.contains("serial")) {
                long baseValue = (Long) originalValue;
                newValue = baseValue + attempt;
            } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
                double baseValue = (Double) originalValue;
                newValue = baseValue + attempt;
            } else if (dataType.contains("bool")) {
                // 布尔保持原值（理论上主键不会为布尔）
                newValue = originalValue;
            } else {
                // 字符串类型：为避免导入失败，不拼接后缀，改为使用索引序列
                newValue = String.valueOf(attempt);
            }
        }
        
        return newValue;
    }

    /**
     * 生成顺序值
     */
    private Object generateSequentialValue(int index, EnhancedColumnStatistics colStats) {
        String dataType = colStats.getDataType().toLowerCase();
        
        if (dataType.contains("int") || dataType.contains("serial")) {
            return (long) (index + 1);
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            return (double) (index + 1);
        } else {
            return String.valueOf(index + 1);
        }
    }

    /**
     * 将字符串转换为对应类型的值
     */
    private Object convertStringToValue(String value, String dataType) {
        try {
            if (dataType.contains("int") || dataType.contains("serial")) {
                return Long.parseLong(value);
            } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
                return Double.parseDouble(value);
            } else {
                return value;
            }
        } catch (NumberFormatException e) {
            return value; // 返回原字符串
        }
    }

    /**
     * 在两个值之间插值
     */
    private Object interpolateValue(Object low, Object high, int index, long total, String dataType) {
            if (dataType.contains("int") || dataType.contains("serial")) {
            long lowVal = (Long) low;
            long highVal = (Long) high;
            return lowVal + (highVal - lowVal) * index / total;
        } else if (dataType.contains("decimal") || dataType.contains("numeric") || dataType.contains("real") || dataType.contains("float") || dataType.contains("double")) {
            double lowVal = (Double) low;
            double highVal = (Double) high;
            return lowVal + (highVal - lowVal) * index / total;
        } else {
            return low; // 字符串类型直接返回低值
        }
    }

    /**
     * 安全地将Object转换为double
     */
    private double convertToDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        } else {
            return 0.0;
        }
    }

    /**
     * 根据数据类型获取默认值
     */
    private Object getDefaultValueForType(String dataType) {
        if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
            return 0L;
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            return 0.0;
        } else if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            return "default";
        } else {
            return null;
        }
    }

    /**
     * 获取默认值
     */
    private Object getDefaultValue(EnhancedColumnStatistics colStats) {
        String dataType = colStats.getDataType().toLowerCase();
        
        if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
            return 0L;
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            return 0.0;
        } else if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            return "default";
        } else {
            return null;
        }
    }

    /**
     * 生成bucket key
     */
    private String generateBucketKey(String tableName, String columnName) {
        // 如果columnName已经包含了tableName前缀，直接返回columnName
        if (columnName.startsWith(tableName + ".")) {
            return columnName;
        }
        // 否则拼接tableName和columnName
        return tableName + "." + columnName;
    }

    /**
     * 主键默认数据生成（当buckets为空时使用）
     */
    private Object[] generatePrimaryKeyDefaultData(EnhancedColumnStatistics colStats, long tableSize) {
        String dataType = colStats.getDataType().toLowerCase();
        
        // 获取min/max值和nDistinct
        Object minValue = colStats.getMinValue();
        Object maxValue = colStats.getMaxValue();
        long nDistinct = (long) colStats.getNDistinct();
        
        if (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial")) {
            if (minValue != null && maxValue != null) {
                long min = convertToLong(minValue);
                long max = convertToLong(maxValue);
                if (nDistinct > 0 && nDistinct <= (max - min + 1)) {
                    // 在范围内均匀生成唯一值
                    Object[] data = new Object[(int) tableSize];
                    for (int i = 0; i < tableSize; i++) {
                        long valueIndex = (long) i % nDistinct;
                        long value = min + (valueIndex * (max - min + 1) / nDistinct);
                        data[i] = Math.min(value, max);
                    }
                    return data;
                } else {
                    // 简单递增
                    return generateUniqueSequentialData(tableSize, (int) Math.min(min, Integer.MAX_VALUE), max, colStats);
                }
            } else {
                // 没有统计信息，生成递增的唯一值
                return generateUniqueSequentialData(tableSize, 1, tableSize, colStats);
            }
        } else if (dataType.contains("decimal") || dataType.contains("numeric")) {
            if (minValue != null && maxValue != null) {
                double min = convertToDouble(minValue);
                double max = convertToDouble(maxValue);
                if (nDistinct > 0) {
                    // 在范围内均匀生成唯一值
                    Object[] data = new Object[(int) tableSize];
                    for (int i = 0; i < tableSize; i++) {
                        long valueIndex = (long) i % nDistinct;
                        double value = min + ((double) valueIndex * (max - min) / nDistinct);
                        data[i] = Math.min(value, max);
                    }
                    return data;
                } else {
                    // 简单递增
                    Object[] data = new Object[(int) tableSize];
                    for (int i = 0; i < tableSize; i++) {
                        data[i] = min + (double) i;
                    }
                    return data;
                }
            } else {
                // 没有统计信息，生成递增的唯一值
                Object[] data = new Object[(int) tableSize];
                for (int i = 0; i < tableSize; i++) {
                    data[i] = (double) i;
                }
                return data;
            }
        } else if (dataType.contains("bool")) {
            // 主键极少为布尔，但若出现则仍按频率安全生成（true/false）
            return generateBooleanColumnData(tableSize, colStats);
        } else if (dataType.contains("varchar") || dataType.contains("char") || dataType.contains("text")) {
            // 字符串类型生成唯一值
            Object[] data = new Object[(int) tableSize];
            for (int i = 0; i < tableSize; i++) {
                data[i] = "pk_" + i;
            }
            return data;
        } else if (dataType.contains("date")) {
            if (minValue != null && maxValue != null) {
                java.time.LocalDate minDate = parseToLocalDate(minValue);
                java.time.LocalDate maxDate = parseToLocalDate(maxValue);
                long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(minDate, maxDate);
                if (nDistinct > 0 && nDistinct <= daysBetween + 1) {
                    Object[] data = new Object[(int) tableSize];
                    for (int i = 0; i < tableSize; i++) {
                        long valueIndex = (long) i % nDistinct;
                        long daysToAdd = (valueIndex * daysBetween / nDistinct);
                        data[i] = minDate.plusDays(daysToAdd);
                    }
                    return data;
                } else {
                    Object[] data = new Object[(int) tableSize];
                    for (int i = 0; i < tableSize; i++) {
                        data[i] = minDate.plusDays(i);
                    }
                    return data;
                }
            } else {
                Object[] data = new Object[(int) tableSize];
                for (int i = 0; i < tableSize; i++) {
                    data[i] = java.time.LocalDate.now().plusDays(i);
                }
                return data;
            }
        } else {
            // 默认生成递增的唯一值
            Object[] data = new Object[(int) tableSize];
            for (int i = 0; i < tableSize; i++) {
                data[i] = i;
            }
            return data;
        }
    }

    // ===== 布尔列生成：期望累计法 =====
    private Object[] generateBooleanColumnData(long tableSize, EnhancedColumnStatistics colStats) {
        Object[] data = new Object[(int) tableSize];
        List<String> mcvValues = colStats.getMostCommonValues();
        List<Double> mcvFreqs = colStats.getMostCommonFrequencies();
        double pTrue = 0.5;
        if (mcvValues != null && mcvFreqs != null && !mcvValues.isEmpty() && mcvValues.size() == mcvFreqs.size()) {
            for (int i = 0; i < mcvValues.size(); i++) {
                String v = mcvValues.get(i);
                if (v != null && v.equalsIgnoreCase("true")) {
                    pTrue = Math.max(0.0, Math.min(1.0, mcvFreqs.get(i)));
                    break;
                }
            }
        }
        for (int i = 0; i < tableSize; i++) {
            double expected = (i + 1) * pTrue;
            double prevExpected = i * pTrue;
            boolean out = Math.floor(expected) > Math.floor(prevExpected);
            data[i] = out ? Boolean.TRUE : Boolean.FALSE;
        }
        return data;
    }

    private long convertToLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong(((String) value).trim()); } catch (Exception ignored) {}
        }
        return 0L;
    }
    private java.time.LocalDate parseToLocalDate(Object value) {
        if (value instanceof java.time.LocalDate d) return d;
        String s = String.valueOf(value).trim();
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception ignore) {}
        try {
            return java.time.LocalDateTime.parse(s, ruc.db.utils.CommonUtils.INPUT_FMT).toLocalDate();
        } catch (Exception e) {
            return java.time.LocalDate.now();
        }
    }
}
