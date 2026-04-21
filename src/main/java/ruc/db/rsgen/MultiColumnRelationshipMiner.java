package ruc.db.rsgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiColumnRelationshipMiner {
    private static final Logger logger = LoggerFactory.getLogger(MultiColumnRelationshipMiner.class);
    
    // 统计结构：表 -> (列组合 -> (query名 -> 次数))
    private final Map<String, Map<Set<String>, Map<String, Integer>>> tableColumnCooccurrence = new HashMap<>();
    
    // 表名到列名的映射：表名 -> 列名列表
    private final Map<String, List<String>> tableColumns = new HashMap<>();
    
    // 列名到表名的映射：列名 -> 表名（用于快速查找列属于哪个表）
    private final Map<String, String> columnToTable = new HashMap<>();
    
    // 存储表的完整信息：表名 -> 表信息
    private final Map<String, Object> tableInfo = new HashMap<>();

    // 递归遍历目录下所有.sql文件
    public void analyzeQueryDirectory(String dirPath) throws IOException {
        Files.walk(Paths.get(dirPath))
                .filter(p -> p.toString().endsWith(".sql"))
                .forEach(p -> {
                    try {
                        // 提取文件名作为query标识
                        String fileName = p.getFileName().toString();
                        String queryName = fileName.substring(0, fileName.lastIndexOf('.'));
                        analyzeSingleQuery(new String(Files.readAllBytes(p)), queryName);
                    } catch (IOException e) {
                        logger.warn("读取SQL文件失败: {}", p);
                    }
                });
    }

    // 加载schema.json文件
    public void loadSchemaInfo(String schemaPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> schemaData = mapper.readValue(new File(schemaPath), 
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        
        for (Map.Entry<String, Object> entry : schemaData.entrySet()) {
            String tableName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> tableData = (Map<String, Object>) entry.getValue();
            
            // 提取表名（去掉schema前缀）
            String shortTableName = tableName.contains(".") ? tableName.split("\\.")[1] : tableName;
            
            // 提取列名列表
            @SuppressWarnings("unchecked")
            List<String> canonicalColumns = (List<String>) tableData.get("canonicalColumnNames");
            if (canonicalColumns != null) {
                List<String> shortColumnNames = new ArrayList<>();
                for (String canonicalCol : canonicalColumns) {
                    String shortColName = canonicalCol.contains(".") ? 
                        canonicalCol.split("\\.")[2] : canonicalCol;
                    shortColumnNames.add(shortColName);
                    
                    // 建立列名到表名的映射
                    columnToTable.put(shortColName, shortTableName);
                }
                tableColumns.put(shortTableName, shortColumnNames);
                tableInfo.put(shortTableName, tableData);
                logger.debug("加载表 {} 的列信息: {}", shortTableName, shortColumnNames);
            }
        }
        logger.info("成功加载 {} 个表的schema信息，共 {} 个列", tableColumns.size(), columnToTable.size());
    }

    // 解析单个SQL，统计filter列共现
    public void analyzeSingleQuery(String sql, String queryName) {
        String sqlPreview = sql.substring(0, Math.min(100, sql.length())) + "...";
        logger.info("=== 开始分析SQL: {} ===", sqlPreview);
        
        // 1. 提取所有WHERE条件（包括子查询）
        List<String> allWhereConditions = extractAllWhereConditions(sql);
        
        // 2. 分析每个WHERE条件
        for (int i = 0; i < allWhereConditions.size(); i++) {
            String whereCondition = allWhereConditions.get(i);
            logger.info("--- 分析WHERE条件 {}: {} ---", i + 1, whereCondition);
            analyzeWhereCondition(whereCondition, sqlPreview, queryName);
        }
    }
    
    // 提取所有WHERE条件（包括子查询）
    private List<String> extractAllWhereConditions(String sql) {
        List<String> whereConditions = new ArrayList<>();
        
        // 主查询的WHERE条件
        Pattern mainWherePattern = Pattern.compile("where\\s+(.+?)(group by|order by|limit|$)", 
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher mainWhereMatcher = mainWherePattern.matcher(sql);
        if (mainWhereMatcher.find()) {
            whereConditions.add(mainWhereMatcher.group(1));
        }
        
        // 子查询的WHERE条件
        Pattern subqueryPattern = Pattern.compile("\\(\\s*select\\s+.*?where\\s+(.+?)(group by|order by|limit|\\s*\\)|$)", 
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher subqueryMatcher = subqueryPattern.matcher(sql);
        while (subqueryMatcher.find()) {
            whereConditions.add(subqueryMatcher.group(1));
        }
        
        return whereConditions;
    }
    
    // 分析单个WHERE条件
    private void analyzeWhereCondition(String whereCondition, String sqlPreview, String queryName) {
        logger.info("分析WHERE条件: {}", whereCondition);
        
        // 1. 按AND/OR分割条件
        String[] conditions = whereCondition.split("\\s+and\\s+|\\s+AND\\s+|\\s+or\\s+|\\s+OR\\s+");
        
        // 2. 统计每个表的列出现次数
        Map<String, Map<String, Integer>> tableColumnCounts = new HashMap<>();
        
        for (String condition : conditions) {
            condition = condition.trim();
            if (condition.isEmpty()) continue;
            
            logger.info("处理条件: {}", condition);
            
            // 跳过子查询条件
            if (condition.contains("select")) {
                logger.info("跳过子查询条件: {}", condition);
                continue;
            }
            
            // 跳过join条件
            if (isJoinCondition(condition)) {
                logger.info("跳过join条件: {}", condition);
                continue;
            }
            
            // 提取所有列名
            Set<String> columnsInCondition = extractColumnsFromCondition(condition);
            logger.info("提取的列: {}", columnsInCondition);
            
            // 统计每个表的列出现次数
            for (String column : columnsInCondition) {
                String tableName = columnToTable.get(column);
                if (tableName != null) {
                    tableColumnCounts.computeIfAbsent(tableName, k -> new HashMap<>());
                    Map<String, Integer> columnCounts = tableColumnCounts.get(tableName);
                    columnCounts.put(column, columnCounts.getOrDefault(column, 0) + 1);
                }
            }
        }
        
        logger.info("表列统计: {}", tableColumnCounts);
        
        // 3. 计算共现关系
        calculateCooccurrence(tableColumnCounts, sqlPreview, queryName);
    }
    
    // 从条件中提取列名
    private Set<String> extractColumnsFromCondition(String condition) {
        Set<String> columns = new HashSet<>();
        
        // 匹配列名模式：列名 op 值
        // 支持的操作符：=, <>, !=, <, >, <=, >=, like, in, between
        Pattern columnPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=<>!]+|\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s+(like|in|between)\\b", 
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = columnPattern.matcher(condition);
        
        while (matcher.find()) {
            String column1 = matcher.group(1);
            String column2 = matcher.group(2);
            
            if (column1 != null && !column1.isEmpty()) {
                columns.add(column1);
            }
            if (column2 != null && !column2.isEmpty()) {
                columns.add(column2);
            }
        }
        
        return columns;
    }
    
    // 计算共现关系
    private void calculateCooccurrence(Map<String, Map<String, Integer>> tableColumnCounts, String sqlPreview, String queryName) {
        for (Map.Entry<String, Map<String, Integer>> tableEntry : tableColumnCounts.entrySet()) {
            String tableName = tableEntry.getKey();
            Map<String, Integer> columnCounts = tableEntry.getValue();
            
            // 只统计有2个或以上列的表
            if (columnCounts.size() < 2) continue;
            
            logger.info("发现表 {} 有 {} 个filter列: {}", tableName, columnCounts.size(), columnCounts);
            
            // 计算所有列组合的共现次数（取最小值）
            List<String> columns = new ArrayList<>(columnCounts.keySet());
            
            // 生成所有可能的列组合（2列到所有列）
            for (int size = 2; size <= columns.size(); size++) {
                generateCombinations(columns, size, tableName, columnCounts, sqlPreview, queryName);
            }
        }
    }
    
    // 生成列组合
    private void generateCombinations(List<String> columns, int size, String tableName, Map<String, Integer> columnCounts, String sqlPreview, String queryName) {
        generateCombinationsHelper(columns, size, 0, new ArrayList<>(), tableName, columnCounts, sqlPreview, queryName);
    }
    
    private void generateCombinationsHelper(List<String> columns, int size, int start, List<String> current, 
                                          String tableName, Map<String, Integer> columnCounts, String sqlPreview, String queryName) {
        if (current.size() == size) {
            // 计算这个组合的最小出现次数
            int minCount = Integer.MAX_VALUE;
            for (String col : current) {
                minCount = Math.min(minCount, columnCounts.get(col));
            }
            
            // 添加到统计结果
            Set<String> columnSet = new TreeSet<>(current);
            tableColumnCooccurrence.computeIfAbsent(tableName, k -> new HashMap<>());
            Map<Set<String>, Map<String, Integer>> pairMap = tableColumnCooccurrence.get(tableName);
            pairMap.computeIfAbsent(columnSet, k -> new HashMap<>());
            Map<String, Integer> queryCounts = pairMap.get(columnSet);
            queryCounts.put(queryName, queryCounts.getOrDefault(queryName, 0) + minCount);
            
            // 记录发现的共现关系
            logger.info("在query '{}' 中发现表 {} 的列组合共现: {} (频数: {})", 
                       queryName, tableName, String.join(",", columnSet), minCount);
            
            return;
        }
        
        for (int i = start; i < columns.size(); i++) {
            current.add(columns.get(i));
            generateCombinationsHelper(columns, size, i + 1, current, tableName, columnCounts, sqlPreview, queryName);
            current.remove(current.size() - 1);
        }
    }
    
    // 判断是否为join条件
    private boolean isJoinCondition(String condition) {
        // 匹配形如 A.x = B.y 的条件
        Pattern joinPattern = Pattern.compile("([a-zA-Z0-9_]+)\\s*=\\s*([a-zA-Z0-9_]+)");
        Matcher matcher = joinPattern.matcher(condition);
        if (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(2);
            
            // 如果两边都是列名，且不是常量比较，则认为是join条件
            if (!isConstantValue(left) && !isConstantValue(right)) {
                return true;
            }
        }
        return false;
    }
    
    // 判断是否为常量值
    private boolean isConstantValue(String value) {
        value = value.trim();
        // 数字
        if (value.matches("\\d+(\\.\\d+)?")) return true;
        // 字符串（带引号）
        if (value.matches("'.*'") || value.matches("\".*\"")) return true;
        // 日期
        if (value.matches("date\\s+'.*'")) return true;
        // LIKE模式
        if (value.matches("'.*%.*'")) return true;
        return false;
    }

    // 导出为json
    public void exportResultToJson(String outputPath) throws IOException {
        Map<String, Object> exportMap = new HashMap<>();
        
        for (Map.Entry<String, Map<Set<String>, Map<String, Integer>>> entry : tableColumnCooccurrence.entrySet()) {
            String tableName = entry.getKey();
            Map<String, Object> tableResult = new HashMap<>();
            
            // 添加表的基本信息
            if (tableInfo.containsKey(tableName)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tableData = (Map<String, Object>) tableInfo.get(tableName);
                tableResult.put("tableSize", tableData.get("tableSize"));
                tableResult.put("primaryKeys", tableData.get("primaryKeys"));
                tableResult.put("foreignKeys", tableData.get("foreignKeys"));
                tableResult.put("canonicalColumnNames", tableData.get("canonicalColumnNames"));
            }
            
            // 添加列共现统计
            Map<String, Object> cooccurrenceStats = new HashMap<>();
            for (Map.Entry<Set<String>, Map<String, Integer>> pairEntry : entry.getValue().entrySet()) {
                Set<String> columns = pairEntry.getKey();
                Map<String, Integer> queryCounts = pairEntry.getValue();
                
                // 将Set转换为排序的List，确保输出格式一致
                List<String> sortedColumns = new ArrayList<>(columns);
                Collections.sort(sortedColumns);
                String columnPair = String.join(",", sortedColumns);
                
                cooccurrenceStats.put(columnPair, queryCounts);
            }
            tableResult.put("columnCooccurrence", cooccurrenceStats);
            
            exportMap.put(tableName, tableResult);
        }
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputPath), exportMap);
        logger.info("多列共现统计结果已导出到: {}", outputPath);
    }
} 