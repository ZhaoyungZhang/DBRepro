package ruc.db.rsgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class TpchCommentGenerationTest {
    public static void main(String[] args) {
        System.out.println("=== TPC-H Comment列策略2.3测试 ===\n");
        
        // 模拟真实的TPC-H lineitem.l_comment直方图边界
        List<String> tpchCommentBounds = Arrays.asList(
            "A quick brown fox jumps over the lazy dog near the river",
            "About the business requirements and technical specifications", 
            "Carefully planned project execution requires dedicated resources",
            "Database performance optimization techniques improve query speed",
            "Efficient algorithms process large datasets with minimal overhead",
            "Financial reports must include accurate calculations and summaries",
            "Good customer service builds long-term business relationships",
            "High-quality products meet stringent industry safety standards",
            "Important decisions require thorough analysis and careful consideration",
            "Just-in-time delivery reduces inventory costs significantly"
        );
        
        // 测试策略2.3的完整流程
        testStrategy23(tpchCommentBounds, 67); // TPC-H l_comment平均长度约67
        
        // 测试不同长度的生成效果
        System.out.println("\n=== 不同目标长度测试 ===");
        testDifferentLengths(tpchCommentBounds);
        
        // 测试空边界和异常情况
        System.out.println("\n=== 边界情况测试 ===");
        testEdgeCases();
    }
    
    private static void testStrategy23(List<String> bounds, int avgLength) {
        System.out.println("直方图边界样本:");
        bounds.stream().limit(3).forEach(bound -> 
            System.out.println("  - " + bound.substring(0, Math.min(40, bound.length())) + "..."));
        
        // 词频分析
        Map<String, Integer> wordFreq = analyzeWordFrequency(bounds);
        System.out.println("\n提取的高频词汇 (前10个):");
        wordFreq.entrySet().stream()
            .limit(10)
            .forEach(entry -> System.out.printf("  %-15s: %d%n", entry.getKey(), entry.getValue()));
        
        // 生成comment样本
        System.out.println("\n生成的comment样本 (目标长度: " + avgLength + "):");
        for (int i = 0; i < 8; i++) {
            String comment = generateCommentFromWordFrequency(wordFreq, avgLength);
            System.out.printf("  %d. [%d chars] %s%n", i+1, comment.length(), comment);
        }
        
        // 统计生成结果
        analyzeGenerationQuality(wordFreq, avgLength, 100);
    }
    
    private static void testDifferentLengths(List<String> bounds) {
        Map<String, Integer> wordFreq = analyzeWordFrequency(bounds);
        int[] lengths = {20, 50, 100, 150};
        
        for (int length : lengths) {
            System.out.printf("\n目标长度 %d 的生成效果:%n", length);
            for (int i = 0; i < 3; i++) {
                String comment = generateCommentFromWordFrequency(wordFreq, length);
                System.out.printf("  [%d] %s%n", comment.length(), comment);
            }
        }
    }
    
    private static void testEdgeCases() {
        // 测试空边界
        System.out.println("空边界测试:");
        Map<String, Integer> emptyFreq = analyzeWordFrequency(Arrays.asList());
        String emptyResult = generateCommentFromWordFrequency(emptyFreq, 50);
        System.out.println("  空边界结果: " + emptyResult);
        
        // 测试单一边界
        System.out.println("\n单一边界测试:");
        Map<String, Integer> singleFreq = analyzeWordFrequency(Arrays.asList("test data processing"));
        String singleResult = generateCommentFromWordFrequency(singleFreq, 50);
        System.out.println("  单一边界结果: " + singleResult);
    }
    
    private static void analyzeGenerationQuality(Map<String, Integer> wordFreq, int targetLength, int sampleSize) {
        System.out.println("\n=== 生成质量分析 ===");
        
        List<Integer> actualLengths = new ArrayList<>();
        Set<String> uniqueWords = new HashSet<>();
        
        for (int i = 0; i < sampleSize; i++) {
            String comment = generateCommentFromWordFrequency(wordFreq, targetLength);
            actualLengths.add(comment.length());
            
            // 统计使用的词汇
            Arrays.stream(comment.split("\\s+"))
                .forEach(word -> uniqueWords.add(word.toLowerCase()));
        }
        
        // 计算统计信息
        double avgActualLength = actualLengths.stream().mapToInt(Integer::intValue).average().orElse(0);
        int minLength = actualLengths.stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxLength = actualLengths.stream().mapToInt(Integer::intValue).max().orElse(0);
        
        System.out.printf("样本数量: %d%n", sampleSize);
        System.out.printf("目标长度: %d%n", targetLength);
        System.out.printf("实际平均长度: %.1f%n", avgActualLength);
        System.out.printf("长度范围: %d - %d%n", minLength, maxLength);
        System.out.printf("长度偏差: %.1f%%\n", Math.abs(avgActualLength - targetLength) / targetLength * 100);
        System.out.printf("生成词汇多样性: %d 个不重复词汇%n", uniqueWords.size());
        
        // 检查高频词使用情况
        long highFreqWordUsage = uniqueWords.stream()
            .mapToLong(word -> wordFreq.entrySet().stream()
                .filter(entry -> entry.getKey().equals(word))
                .mapToLong(Map.Entry::getValue)
                .sum())
            .sum();
        
        System.out.printf("高频词覆盖率: %.1f%%\n", 
            (double) highFreqWordUsage / uniqueWords.size() * 100);
    }
    
    // 复制的核心方法...
    private static Map<String, Integer> analyzeWordFrequency(List<String> bounds) {
        Map<String, Integer> wordFreq = new HashMap<>();
        Set<String> stopWords = getStopWords();
        
        for (String bound : bounds) {
            if (bound == null || bound.trim().isEmpty()) continue;
            
            String[] words = bound.toLowerCase()
                    .replaceAll("[^a-zA-Z0-9\\s]", " ")
                    .split("\\s+");
            
            for (String word : words) {
                word = word.trim();
                if (word.length() >= 2 && !stopWords.contains(word)) {
                    wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
                }
            }
        }
        
        return wordFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(50)
                .collect(LinkedHashMap::new, 
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
    }
    
    private static Set<String> getStopWords() {
        return Set.of(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
            "from", "up", "about", "into", "through", "during", "before", "after", "above", "below",
            "between", "among", "within", "without", "under", "over", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "must", "can", "shall", "this", "that", "these",
            "those", "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them"
        );
    }
    
    private static String generateCommentFromWordFrequency(Map<String, Integer> wordFreq, int targetLength) {
        if (wordFreq.isEmpty()) {
            return "default comment text for testing purposes";
        }
        
        List<String> words = new ArrayList<>(wordFreq.keySet());
        List<Integer> frequencies = new ArrayList<>(wordFreq.values());
        
        double[] cumulativeProb = new double[frequencies.size()];
        int totalFreq = frequencies.stream().mapToInt(Integer::intValue).sum();
        double cumulative = 0.0;
        for (int i = 0; i < frequencies.size(); i++) {
            cumulative += (double) frequencies.get(i) / totalFreq;
            cumulativeProb[i] = cumulative;
        }
        
        StringBuilder comment = new StringBuilder();
        Random rand = new Random();
        
        while (comment.length() < targetLength - 10) {
            double randomValue = rand.nextDouble();
            String selectedWord = words.get(words.size() - 1);
            
            for (int i = 0; i < cumulativeProb.length; i++) {
                if (randomValue <= cumulativeProb[i]) {
                    selectedWord = words.get(i);
                    break;
                }
            }
            
            if (comment.length() > 0) {
                comment.append(" ");
            }
            comment.append(selectedWord);
            
            // 随机添加标点符号增加真实感
            if (rand.nextDouble() < 0.15 && comment.length() > 20) {
                char[] punctuation = {'.', ',', ';'};
                comment.append(punctuation[rand.nextInt(punctuation.length)]);
                if (comment.length() < targetLength - 20) {
                    comment.append(" ");
                }
            }
        }
        
        String result = comment.toString();
        if (result.length() > targetLength) {
            result = result.substring(0, targetLength).trim();
        }
        
        return result;
    }
}
