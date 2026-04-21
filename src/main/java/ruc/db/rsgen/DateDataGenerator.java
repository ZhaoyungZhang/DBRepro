package ruc.db.rsgen;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日期数据生成器
 * 
 * 严格按照RSGen论文的bucket生成逻辑：
 * 1. 每个bucket只生成nDistinct个候选唯一值（子区间中点）
 * 2. 这些候选值用于后续的i%nDistinct循环选择
 * 3. 确保所有日期都是标准yyyy-MM-dd格式，无任何后缀
 * 
 * @author RSGen Implementation
 */
public class DateDataGenerator {
    private static final Logger logger = LoggerFactory.getLogger(DateDataGenerator.class);
    
    // 默认日期常量
    private static final String DEFAULT_DATE = "1992-01-01";
    
    public DateDataGenerator() {
        // logger.info("DateDataGenerator initialized");
    }

    /**
     * 为日期类型bucket生成distinct值（子区间中点）
     * 
     * 严格按照RSGen算法：
     * 1. 将日期区间分成nDistinct个子区间
     * 2. 每个子区间取中点作为候选唯一值
     * 3. 返回nDistinct个标准日期字符串
     * 
     * @param bucket 日期bucket
     * @param nDistinct 需要的唯一值数量
     * @return nDistinct个标准日期字符串列表
     */
    public List<Object> generateDateDistinctValues(Bucket bucket, int nDistinct) {
        List<Object> values = new ArrayList<>();
        
        try {
            if (bucket.getLow() != null && bucket.getHigh() != null) {
                // 获取bucket边界，确保是标准日期格式
                String lowDateStr = getStandardDateString(bucket.getLow());
                String highDateStr = getStandardDateString(bucket.getHigh());
                
                if (isValidDateFormat(lowDateStr) && isValidDateFormat(highDateStr)) {
                    LocalDate lowDate = LocalDate.parse(lowDateStr);
                    LocalDate highDate = LocalDate.parse(highDateStr);
                    
                    if (lowDate.equals(highDate)) {
                        // 如果范围相同，所有值都使用同一个日期
                        for (int i = 0; i < nDistinct; i++) {
                            values.add(lowDateStr);
                        }
                    } else {
                        // 严格按照RSGen算法：将区间分成nDistinct个子区间，每个子区间取中点
                        long daysBetween = ChronoUnit.DAYS.between(lowDate, highDate);
                        
                        if (daysBetween > 0 && nDistinct > 0) {
                            // 计算每个子区间的大小
                            double intervalSize = (double) daysBetween / nDistinct;
                            
                            for (int i = 0; i < nDistinct; i++) {
                                // 计算第i个子区间的中点
                                double intervalStart = i * intervalSize;
                                double intervalEnd = (i + 1) * intervalSize;
                                double midPoint = intervalStart + (intervalEnd - intervalStart) / 2.0;
                                
                                // 计算对应的日期
                                LocalDate midDate = lowDate.plusDays((long) midPoint);
                                values.add(midDate.toString());
                            }
                        } else {
                            // 如果区间无效，使用低值
                            for (int i = 0; i < nDistinct; i++) {
                                values.add(lowDateStr);
                            }
                        }
                    }
                } else {
                    // 如果日期格式不正确，使用默认日期
                    logger.warn("日期格式不正确: low={}, high={}，使用默认日期", lowDateStr, highDateStr);
                    for (int i = 0; i < nDistinct; i++) {
                        values.add(DEFAULT_DATE);
                    }
                }
            } else {
                // 如果bucket边界为空，使用默认日期
                for (int i = 0; i < nDistinct; i++) {
                    values.add(DEFAULT_DATE);
                }
            }
        } catch (Exception e) {
            logger.error("生成日期distinct值时出错: {}", e.getMessage());
            // 出错时使用默认日期
            for (int i = 0; i < nDistinct; i++) {
                values.add(DEFAULT_DATE);
            }
        }
        
        // logger.debug("为bucket生成{}个日期候选值: {}", nDistinct, values);
        return values;
    }

    /**
     * 获取标准日期字符串，确保无任何后缀
     * 
     * @param datum 日期Datum对象
     * @return 标准yyyy-MM-dd格式的日期字符串
     */
    private String getStandardDateString(Datum datum) {
        if (datum == null) {
            return DEFAULT_DATE;
        }
        
        try {
            // 使用toOutputString()获取标准格式
            String dateStr = datum.toOutputString();
            
            // 如果是DATE类型，直接返回（toOutputString已经确保格式正确）
            if (datum.getType() == Datum.DatumType.DATE) {
                return dateStr;
            }
            
            // 如果是字符串类型，尝试解析为标准日期
            if (isValidDateFormat(dateStr)) {
                return dateStr;
            }
            
            // 如果解析失败，返回默认日期
            logger.warn("无法解析日期字符串: {}，使用默认日期", dateStr);
            return DEFAULT_DATE;
            
        } catch (Exception e) {
            logger.warn("获取标准日期字符串失败: {}，使用默认日期", e.getMessage());
            return DEFAULT_DATE;
        }
    }

    /**
     * 验证日期格式是否正确
     * 
     * @param dateStr 日期字符串
     * @return 是否为有效的yyyy-MM-dd格式
     */
    private boolean isValidDateFormat(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return false;
        }
        
        try {
            LocalDate.parse(dateStr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取默认日期值
     */
    public String getDefaultDateValue() {
        return DEFAULT_DATE;
    }
}
