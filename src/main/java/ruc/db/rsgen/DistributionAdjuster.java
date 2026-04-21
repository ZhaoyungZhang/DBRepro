package ruc.db.rsgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 使用迭代比例拟合(IPF)算法调整离散概率分布
 * 
 * IPF算法通过迭代调整频率来满足约束条件，同时最小化KL散度（保持原始分布的相对比例）
 */
public class DistributionAdjuster {
    private static final Logger logger = LoggerFactory.getLogger(DistributionAdjuster.class);
    
    private static final int MAX_ITERATIONS = 1000;
    private static final double CONVERGENCE_THRESHOLD = 1e-6;
    
    /**
     * 分布桶：包含值和频率
     */
    public static class DistributionBucket {
        public final String value;
        public double frequency;
        
        public DistributionBucket(String value, double frequency) {
            this.value = value;
            this.frequency = frequency;
        }
        
        @Override
        public String toString() {
            return String.format("%s: %.6f", value, frequency);
        }
    }
    
    /**
     * 约束：包含匹配条件和目标频率
     */
    public static class Constraint {
        public final Predicate<String> matcher;
        public final double targetFrequency;
        public final String description;
        
        public Constraint(Predicate<String> matcher, double targetFrequency, String description) {
            this.matcher = matcher;
            this.targetFrequency = targetFrequency;
            this.description = description;
        }
    }
    
    /**
     * 使用IPF算法调整分布以满足约束
     * 
     * @param buckets 初始分布桶列表
     * @param constraints 约束列表
     * @return 调整后的分布桶列表
     */
    public List<DistributionBucket> adjustDistribution(List<DistributionBucket> buckets, 
                                                        List<Constraint> constraints) {
        if (buckets == null || buckets.isEmpty()) {
            logger.warn("分布桶列表为空，无法调整");
            return buckets;
        }
        
        if (constraints == null || constraints.isEmpty()) {
            logger.debug("没有约束，保持原始分布");
            return buckets;
        }
        
        // 创建副本以避免修改原始数据
        List<DistributionBucket> adjusted = new ArrayList<>();
        for (DistributionBucket bucket : buckets) {
            adjusted.add(new DistributionBucket(bucket.value, bucket.frequency));
        }
        
        // 归一化初始频率
        normalize(adjusted);
        
        // IPF迭代
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double maxChange = 0.0;
            
            // 对每个约束应用缩放
            for (Constraint constraint : constraints) {
                double change = applyConstraint(adjusted, constraint);
                maxChange = Math.max(maxChange, Math.abs(change));
            }
            
            // 归一化
            normalize(adjusted);
            
            // 检查收敛
            if (maxChange < CONVERGENCE_THRESHOLD) {
                logger.debug("IPF算法在第 {} 次迭代后收敛", iteration + 1);
                break;
            }
            
            if (iteration == MAX_ITERATIONS - 1) {
                logger.warn("IPF算法达到最大迭代次数 {}，可能未完全收敛", MAX_ITERATIONS);
            }
        }
        
        return adjusted;
    }
    
    /**
     * 应用单个约束：缩放匹配和不匹配的值
     * 
     * @param buckets 分布桶列表
     * @param constraint 约束
     * @return 最大变化量
     */
    private double applyConstraint(List<DistributionBucket> buckets, Constraint constraint) {
        // 计算匹配集合S的当前频率总和
        double currentProb = 0.0;
        List<Integer> matchingIndices = new ArrayList<>();
        
        for (int i = 0; i < buckets.size(); i++) {
            DistributionBucket bucket = buckets.get(i);
            if (constraint.matcher.test(bucket.value)) {
                currentProb += bucket.frequency;
                matchingIndices.add(i);
            }
        }
        
        // 处理边界情况
        if (currentProb <= 0.0) {
            logger.warn("约束 '{}' 没有匹配的值，无法调整", constraint.description);
            return 0.0;
        }
        
        if (currentProb >= 1.0 && constraint.targetFrequency >= 1.0) {
            // 所有值都匹配，且目标也是1.0，无需调整
            return 0.0;
        }
        
        if (currentProb >= 1.0 && constraint.targetFrequency < 1.0) {
            // 所有值都匹配，但目标小于1.0，这是不可能的
            logger.warn("约束 '{}' 所有值都匹配，但目标频率 {} < 1.0，无法满足", 
                       constraint.description, constraint.targetFrequency);
            return 0.0;
        }
        
        // 计算缩放因子
        double alpha = constraint.targetFrequency / currentProb;
        double beta = (1.0 - constraint.targetFrequency) / (1.0 - currentProb);
        
        // 处理除零情况
        if (Double.isInfinite(alpha) || Double.isNaN(alpha)) {
            logger.warn("约束 '{}' 计算缩放因子alpha失败: currentProb={}, target={}", 
                       constraint.description, currentProb, constraint.targetFrequency);
            return 0.0;
        }
        
        if (Double.isInfinite(beta) || Double.isNaN(beta)) {
            logger.warn("约束 '{}' 计算缩放因子beta失败: currentProb={}, target={}", 
                       constraint.description, currentProb, constraint.targetFrequency);
            return 0.0;
        }
        
        // 应用缩放
        double maxChange = 0.0;
        for (int i = 0; i < buckets.size(); i++) {
            DistributionBucket bucket = buckets.get(i);
            double oldFreq = bucket.frequency;
            
            if (matchingIndices.contains(i)) {
                bucket.frequency *= alpha;
            } else {
                bucket.frequency *= beta;
            }
            
            maxChange = Math.max(maxChange, Math.abs(bucket.frequency - oldFreq));
        }
        
        return maxChange;
    }
    
    /**
     * 归一化频率，使总和为1.0
     */
    private void normalize(List<DistributionBucket> buckets) {
        double total = 0.0;
        for (DistributionBucket bucket : buckets) {
            total += bucket.frequency;
        }
        
        if (total > 0.0) {
            for (DistributionBucket bucket : buckets) {
                bucket.frequency /= total;
            }
        }
    }
    
    /**
     * 从操作符和值创建约束匹配器
     */
    public static Predicate<String> createMatcher(String operator, String value, String columnName) {
        // 先尝试 varcharpatterns（例如 MFGR#<num>）的特殊比较
        try {
            ruc.db.utils.VarcharPatternManager.PrefixIntSpec spec =
                    ruc.db.utils.VarcharPatternManager.getPrefixIntSpec(columnName);
            if (spec != null) {
                Integer rhs = ruc.db.utils.VarcharPatternManager.parsePrefixIntSuffix(value, spec);
                if (rhs != null) {
                    return v -> {
                        Integer lhs = ruc.db.utils.VarcharPatternManager.parsePrefixIntSuffix(v, spec);
                        if (lhs == null) return false;
                        return switch (operator) {
                            case "EQ" -> lhs.equals(rhs);
                            case "GT" -> lhs > rhs;
                            case "GE" -> lhs >= rhs;
                            case "LT" -> lhs < rhs;
                            case "LE" -> lhs <= rhs;
                            default -> false;
                        };
                    };
                }
            }
        } catch (Exception ignore) {
            // fallback 到原逻辑
        }

        switch (operator) {
            case "EQ":
                return v -> v.equals(value);
            case "IN":
                // IN操作符的值可能是逗号分隔的多个值，如 "A,B"
                String[] values = value.split(",");
                Set<String> valueSet = new HashSet<>();
                for (String v : values) {
                    valueSet.add(v.trim());
                }
                return v -> valueSet.contains(v);
            case "GT":
                return createNumericMatcher(v -> {
                    try {
                        double vNum = Double.parseDouble(v);
                        double threshold = Double.parseDouble(value);
                        return vNum > threshold;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
            case "GE":
                return createNumericMatcher(v -> {
                    try {
                        double vNum = Double.parseDouble(v);
                        double threshold = Double.parseDouble(value);
                        return vNum >= threshold;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
            case "LT":
                return createNumericMatcher(v -> {
                    try {
                        double vNum = Double.parseDouble(v);
                        double threshold = Double.parseDouble(value);
                        return vNum < threshold;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
            case "LE":
                return createNumericMatcher(v -> {
                    try {
                        double vNum = Double.parseDouble(v);
                        double threshold = Double.parseDouble(value);
                        return vNum <= threshold;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
            case "LIKE":
                // value 为 cdf 中的模式串（JSON 键）。不得使用带随机前缀的占位串，否则无法匹配任何真实 MCV，IPF 会报「没有匹配的值」。
                if (value == null) {
                    return v -> false;
                }
                if (!value.contains("%") && !value.contains("_")) {
                    String sub = value;
                    return v -> v != null && v.contains(sub);
                }
                try {
                    Pattern compiled = Pattern.compile(likePatternToRegexAnchored(value), Pattern.DOTALL);
                    return v -> v != null && compiled.matcher(v).matches();
                } catch (Exception e) {
                    String sub = value;
                    return v -> v != null && v.contains(sub);
                }
            default:
                logger.warn("不支持的操作符: {}，使用EQ匹配器", operator);
                return v -> v.equals(value);
        }
    }
    
    /**
     * 创建数值匹配器（处理日期等特殊类型）
     */
    /** SQL LIKE 转整行匹配正则（^...$），仅处理 % 与 _，其它字符按正则字面量转义 */
    private static String likePatternToRegexAnchored(String likePattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append('.');
                case '\\' -> regex.append("\\\\");
                default -> {
                    if ("[](){}.*+?$^|#\\".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        regex.append('$');
        return regex.toString();
    }

    private static Predicate<String> createNumericMatcher(Predicate<String> numericPredicate) {
        return v -> {
            // 先尝试数值比较
            if (numericPredicate.test(v)) {
                return true;
            }
            // 尝试日期比较
            try {
                java.time.LocalDate date = java.time.LocalDate.parse(v);
                return true; // 日期解析成功，但这里需要更复杂的逻辑
            } catch (Exception e) {
                return false;
            }
        };
    }
}




