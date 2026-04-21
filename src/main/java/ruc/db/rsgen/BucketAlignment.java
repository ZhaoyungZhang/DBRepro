package ruc.db.rsgen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RSGen论文中的Bucket对齐算法实现
 * 用于处理外键关系，确保外键列和被引用列的数据一致性
 * 
 * 核心思想：
 * 1. 合并外键列和被引用列的所有直方图边界
 * 2. 创建统一的区间划分
 * 3. 对齐nDistinct值，确保外键引用的有效性
 * 
 * @author RSGen Implementation
 */
public class BucketAlignment {
    private static final Logger logger = LoggerFactory.getLogger(BucketAlignment.class);
    
    /**
     * 对齐外键列和被引用列的bucket
     * 
     * @param foreignKeyBuckets 外键列的buckets
     * @param referencedBuckets 被引用列的buckets
     * @param foreignKeyColumn 外键列名
     * @param referencedColumn 被引用列名
     * @return 对齐后的bucket对
     */
    public AlignedBuckets alignBuckets(List<Bucket> foreignKeyBuckets, List<Bucket> referencedBuckets,
                                       String foreignKeyColumn, String referencedColumn) {
        logger.info("开始对齐外键列 {} 和被引用列 {}", foreignKeyColumn, referencedColumn);
        
        try {
            // 检查是否需要对齐：只有当两列都有直方图bucket时才进行对齐
            boolean fkHasHistogram = hasHistogramBuckets(foreignKeyBuckets);
            boolean refHasHistogram = hasHistogramBuckets(referencedBuckets);
            
            if (!fkHasHistogram || !refHasHistogram) {
                logger.info("跳过bucket对齐：外键列{}有直方图={}, 主键列{}有直方图={}", 
                           foreignKeyColumn, fkHasHistogram, referencedColumn, refHasHistogram);
                return new AlignedBuckets(foreignKeyBuckets, referencedBuckets);
            }
            
            // 步骤1: 提取所有边界点
            Set<Datum> allBoundaries = extractAllBoundaries(foreignKeyBuckets, referencedBuckets);
            
            // 步骤2: 创建统一的区间划分
            List<Datum> sortedBoundaries = allBoundaries.stream()
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());
            
            // 步骤3: 重新分配buckets到统一区间
            List<Bucket> alignedFkBuckets = reallocateBuckets(foreignKeyBuckets, sortedBoundaries);
            List<Bucket> alignedRefBuckets = reallocateBuckets(referencedBuckets, sortedBoundaries);
            
            // 步骤4: 对齐nDistinct值
            alignDistinctValues(alignedFkBuckets, alignedRefBuckets);
            
            logger.info("对齐完成，外键列: {} buckets, 被引用列: {} buckets", 
                       alignedFkBuckets.size(), alignedRefBuckets.size());
            
            return new AlignedBuckets(alignedFkBuckets, alignedRefBuckets);
            
        } catch (Exception e) {
            logger.error("对齐buckets时出错: {}", e.getMessage(), e);
            // 返回原始buckets作为fallback
            return new AlignedBuckets(foreignKeyBuckets, referencedBuckets);
        }
    }
    
    /**
     * 检查bucket列表是否包含直方图bucket
     */
    private boolean hasHistogramBuckets(List<Bucket> buckets) {
        return buckets.stream().anyMatch(bucket -> bucket.getType() == Bucket.BucketType.HISTOGRAM);
    }
    
    /**
     * 提取所有bucket的边界点
     */
    private Set<Datum> extractAllBoundaries(List<Bucket> fkBuckets, List<Bucket> refBuckets) {
        Set<Datum> boundaries = new HashSet<>();
        
        // 添加外键列的边界
        for (Bucket bucket : fkBuckets) {
            if (bucket.getType() == Bucket.BucketType.MCV) {
                // 对于MCV bucket，添加精确值作为边界点
                if (bucket.getLow() != null) {
                    boundaries.add(bucket.getLow());
                }
            } else {
                // 对于直方图bucket，添加low和high边界
                if (bucket.getLow() != null) boundaries.add(bucket.getLow());
                if (bucket.getHigh() != null) boundaries.add(bucket.getHigh());
            }
        }
        
        // 添加被引用列的边界
        for (Bucket bucket : refBuckets) {
            if (bucket.getType() == Bucket.BucketType.MCV) {
                // 对于MCV bucket，添加精确值作为边界点
                if (bucket.getLow() != null) {
                    boundaries.add(bucket.getLow());
                }
            } else {
                // 对于直方图bucket，添加low和high边界
                if (bucket.getLow() != null) boundaries.add(bucket.getLow());
                if (bucket.getHigh() != null) boundaries.add(bucket.getHigh());
            }
        }
        
        logger.debug("提取了 {} 个唯一边界点", boundaries.size());
        return boundaries;
    }
    
    /**
     * 将现有buckets重新分配到统一的区间划分中
     * 按照论文逻辑：按长度比例分配count和nDistinct
     */
    private List<Bucket> reallocateBuckets(List<Bucket> originalBuckets, List<Datum> sortedBoundaries) {
        List<Bucket> newBuckets = new ArrayList<>();
        
        // 首先处理MCV buckets，保持它们的精确性
        for (Bucket bucket : originalBuckets) {
            if (bucket.getType() == Bucket.BucketType.MCV) {
                // MCV bucket保持原样，不进行重新分配
                newBuckets.add(bucket);
                logger.debug("保持MCV bucket: 值={}, count={}", 
                           bucket.getLow() != null ? bucket.getLow().getValue() : "null", 
                           bucket.getCount());
            }
        }
        
        // 然后处理直方图buckets，重新分配到统一区间
        for (int i = 0; i < sortedBoundaries.size() - 1; i++) {
            Datum low = sortedBoundaries.get(i);
            Datum high = sortedBoundaries.get(i + 1);
            
            // 计算这个区间内的总count和nDistinct
            long totalCount = 0;
            long totalNDistinct = 0;
            
            // 按长度比例分配每个原始bucket的count和nDistinct
            for (Bucket original : originalBuckets) {
                if (original.getType() == Bucket.BucketType.HISTOGRAM && 
                    original.getLow() != null && original.getHigh() != null) {
                    
                    double overlap = calculateOverlap(original, low, high);
                    if (overlap > 0) {
                        // 按长度比例分配count和nDistinct
                        long bucketCount = Math.round(original.getCount() * overlap);
                        long bucketNDistinct = Math.round(original.getDistinct() * overlap);
                        
                        totalCount += bucketCount;
                        totalNDistinct += bucketNDistinct;
                    }
                }
            }
            
            if (totalCount > 0) {
                // 确保nDistinct不超过区间内的可能值数量
                long maxPossibleDistinct = calculateMaxPossibleDistinct(low, high);
                long finalNDistinct = Math.min(totalNDistinct, maxPossibleDistinct);
                
                // 确保nDistinct至少为1
                finalNDistinct = Math.max(1, finalNDistinct);
                
                newBuckets.add(Bucket.createHistogramBucket(low, high, totalCount, finalNDistinct));
                logger.debug("创建直方图bucket: [{}, {}], count={}, distinct={} (按长度比例分配)", 
                           low.getValue(), high.getValue(), totalCount, finalNDistinct);
            }
        }
        
        // 如果没有找到任何bucket，创建一个默认的bucket
        if (newBuckets.isEmpty() && !originalBuckets.isEmpty()) {
            logger.warn("重新分配后没有找到任何bucket，创建默认bucket");
            // 使用第一个原始bucket作为模板
            Bucket firstBucket = originalBuckets.get(0);
            if (firstBucket.getLow() != null && firstBucket.getHigh() != null) {
                newBuckets.add(Bucket.createHistogramBucket(firstBucket.getLow(), firstBucket.getHigh(), 
                                                          firstBucket.getCount(), firstBucket.getDistinct()));
            }
        }
        
        logger.debug("重新分配完成: 原始{}个buckets -> 新{}个buckets", originalBuckets.size(), newBuckets.size());
        return newBuckets;
    }
    
    /**
     * 计算区间内可能的最大distinct值数量
     */
    private long calculateMaxPossibleDistinct(Datum low, Datum high) {
        if (low == null || high == null) {
            return 1;
        }
        
        try {
            double lowVal = low.getNumericValue();
            double highVal = high.getNumericValue();
            
            if (highVal < lowVal) {
                return 1;
            }
            
            // 对于整数类型，最大distinct就是区间长度
            if (lowVal == Math.floor(lowVal) && highVal == Math.floor(highVal)) {
                // 注意：Bucket的contains方法使用的是闭区间 [low, high]，所以实际包含的整数是 [low, high]
                // 对于 [102, 112]，实际包含 102, 103, ..., 112，共11个值
                long distinctCount = (long) (highVal - lowVal + 1);
                return Math.max(1, distinctCount);
            }
            
            // 对于浮点数，使用一个合理的估计
            return Math.max(1, (long) (highVal - lowVal + 1));
            
        } catch (Exception e) {
            logger.debug("计算最大可能distinct时出错: {}, 使用默认值100", e.getMessage());
            return 100;
        }
    }
    
    /**
     * 计算原始bucket与新区间的重叠比例
     */
    private double calculateOverlap(Bucket original, Datum newLow, Datum newHigh) {
        if (original.getLow() == null || original.getHigh() == null) {
            return 0.0;
        }
        
        // 计算重叠区间
        Datum overlapLow = Datum.max(original.getLow(), newLow);
        Datum overlapHigh = Datum.min(original.getHigh(), newHigh);
        
        if (overlapLow.compareTo(overlapHigh) >= 0) {
            return 0.0;
        }
        
        try {
            // 计算重叠长度和原始区间长度
            double overlapLength = overlapHigh.getNumericValue() - overlapLow.getNumericValue();
            double originalLength = original.getHigh().getNumericValue() - original.getLow().getNumericValue();
            
            if (originalLength <= 0) {
                return 0.0;
            }
            
            // 返回重叠比例
            double ratio = overlapLength / originalLength;
            return Math.max(0.0, Math.min(1.0, ratio)); // 确保在[0,1]范围内
            
        } catch (Exception e) {
            logger.debug("计算重叠比例时出错: {}, 使用默认值0.5", e.getMessage());
            // 如果计算失败，返回一个合理的默认值
            return 0.5;
        }
    }
    
    /**
     * 对齐两个bucket列表的nDistinct值
     * 确保对应区间的distinct值一致，满足外键约束
     * 
     * 论文逻辑：
     * 1. 对齐区间边界
     * 2. 强制对齐相同区间的nDistinct值，确保两列在相同位置生成相同的值集合
     * 3. count按比例分配，保持各自的统计特性
     * 4. 外键列可能有很多count=0的bucket，这是正常的
     */
    private void alignDistinctValues(List<Bucket> fkBuckets, List<Bucket> refBuckets) {
        int minSize = Math.min(fkBuckets.size(), refBuckets.size());
        
        for (int i = 0; i < minSize; i++) {
            Bucket fkBucket = fkBuckets.get(i);
            Bucket refBucket = refBuckets.get(i);
            
            // 对于NULL buckets，跳过对齐
            if (isNullBucket(fkBucket) || isNullBucket(refBucket)) {
                continue;
            }
            
            // 对于MCV buckets，保持distinct=1，不进行对齐
            if (fkBucket.getType() == Bucket.BucketType.MCV || refBucket.getType() == Bucket.BucketType.MCV) {
                logger.debug("跳过MCV bucket的对齐: FK类型={}, PK类型={}", 
                           fkBucket.getType(), refBucket.getType());
                continue;
            }
            
            // 关键：强制对齐相同区间的nDistinct值
            // 选择较大的nDistinct值，确保两列在相同位置生成相同的值集合
            long alignedDistinct = Math.max(fkBucket.getDistinct(), refBucket.getDistinct());
            
            // 确保nDistinct不超过区间内的可能值数量
            long maxPossibleDistinct = calculateMaxPossibleDistinct(fkBucket.getLow(), fkBucket.getHigh());
            alignedDistinct = Math.min(alignedDistinct, maxPossibleDistinct);
            
            // 确保nDistinct至少为1
            alignedDistinct = Math.max(1, alignedDistinct);
            
            // 更新两个bucket的nDistinct值
            fkBucket.setDistinct(alignedDistinct);
            refBucket.setDistinct(alignedDistinct);
            
            logger.debug("对齐nDistinct: 区间[{}, {}], 外键count={}, distinct={}->{}, 主键count={}, distinct={}->{}", 
                        fkBucket.getLow().getValue(), fkBucket.getHigh().getValue(),
                        fkBucket.getCount(), fkBucket.getDistinct(), alignedDistinct,
                        refBucket.getCount(), refBucket.getDistinct(), alignedDistinct);
        }
    }
    
    /**
     * 检查是否为NULL bucket
     */
    private boolean isNullBucket(Bucket bucket) {
        return bucket.getLow() == null && bucket.getHigh() == null;
    }
    
    /**
     * 对齐结果的数据类
     */
    public static class AlignedBuckets {
        private final List<Bucket> foreignKeyBuckets;
        private final List<Bucket> referencedBuckets;
        
        public AlignedBuckets(List<Bucket> foreignKeyBuckets, List<Bucket> referencedBuckets) {
            this.foreignKeyBuckets = foreignKeyBuckets;
            this.referencedBuckets = referencedBuckets;
        }
        
        public List<Bucket> getForeignKeyBuckets() {
            return foreignKeyBuckets;
        }
        
        public List<Bucket> getReferencedBuckets() {
            return referencedBuckets;
        }
    }
}
