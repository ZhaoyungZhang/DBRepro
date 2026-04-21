package ruc.db.generator;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import ruc.db.LanguageManager;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.ConstraintChainManager;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.filter.BoolExprNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.LogicNode;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.filter.operation.MultiVarFilterOperation;
import ruc.db.generator.constraintchain.filter.operation.UniVarFilterOperation;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintChainPkJoinNode;
import ruc.db.generator.joininfo.JoinStatus;
import ruc.db.generator.joininfo.RuleTable;
import ruc.db.generator.joininfo.RuleTableManager;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.TableManager;
import ruc.db.utils.CommonUtils;
import ruc.db.utils.DataExportConstants;
import ruc.db.utils.exception.schema.CannotFindSchemaException;


@CommandLine.Command(name = "generate", description = "generate database according to gathered information",
        mixinStandardHelpOptions = true, sortOptions = false)
public class DataGenerator implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(DataGenerator.class);
    @CommandLine.Option(names = {"-c", "--config_path"}, required = true, description = "the config path for data generation")
    private String configPath;
    @CommandLine.Option(names = {"-o", "--output_path"}, description = "output path for data and join info")
    private String outputPath;
    @CommandLine.Option(names = {"-i", "--generator_id"}, description = "the id of current generator")
    private int generatorId;
    @CommandLine.Option(names = {"-n", "--num"}, description = "size of generators")
    private int generatorNum;
    @CommandLine.Option(names = {"-l", "--step_size"}, description = "the size of each batch", defaultValue = "7000000")
    private int stepSize;
    @CommandLine.Option(names = {"--close-topological"}, description = "close topological optimization", defaultValue = "false")
    private boolean closeTopologicalReduce;
    @CommandLine.Option(names = {"--expand-rule"}, description = "expand the status vector histogram", defaultValue = "false")
    private boolean expandRules;
    @CommandLine.Option(names = {"-sf", "--scale-factor"}, description = "the size of each batch", defaultValue = "1")
    private int scaleFactor;
    @CommandLine.Option(names = {"--statistics"}, description = "path to enhanced_column_statistics.json for statistics-based data generation")
    private String statisticsPath;
    @CommandLine.Option(names = {"--distribution-model"}, description = "distribution model for data generation: UNIFORM, NORMAL, EXPONENTIAL, GOLDEN_RATIO", defaultValue = "GOLDEN_RATIO")
    private String distributionModel;


    private Map<String, List<ConstraintChain>> schema2chains;

    private DataWriter dataWriter;


    // batch生成的起始位置
    private long batchStart;

    // batch生成的大小
    private long batchSize;

    // 下一次batch需要推进的range
    private long stepRange;

    /** GENERIC join 本地列 -> 键频次，跨 batch 累加；每张表开始生成时 {@link #clearGenericJoinHistogramAccumulators()} */
    private final Map<String, Map<Long, Long>> genericJoinHistogramAccumulators = new HashMap<>();

    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    private static void clearGenericJoinHistogramAccumulators(Map<String, Map<Long, Long>> acc) {
        acc.clear();
    }

    private static void refreshGenericJoinWeightsAfterBatch(
            Map<String, Map<Long, Long>> acc,
            List<ConstraintChain> chainsForTable,
            Map<String, long[]> fkCol2Values) {
        boolean enabled = !"false".equalsIgnoreCase(System.getProperty("mirage.genericJoin.refreshHistogram", "true"));
        int maxBuckets = 32;
        try {
            maxBuckets = Integer.parseInt(System.getProperty("mirage.genericJoin.maxHistogramBuckets", "32"));
        } catch (NumberFormatException ignored) {
            maxBuckets = 32;
        }
        if (maxBuckets < 2) {
            return;
        }
        GenericJoinWeightRefresher.mergeBatchAndUpdateNodes(acc, chainsForTable, fkCol2Values, maxBuckets, enabled);
    }

    private static Map<String, List<ConstraintChain>> getSchema2Chains(Map<String, List<ConstraintChain>> query2chains) {
        Map<String, List<ConstraintChain>> schema2chains = new HashMap<>();
        for (List<ConstraintChain> chains : query2chains.values()) {
            for (ConstraintChain chain : chains) {
                if (!schema2chains.containsKey(chain.getTableName())) {
                    schema2chains.put(chain.getTableName(), new ArrayList<>());
                }
                schema2chains.get(chain.getTableName()).add(chain);
            }
        }
        return schema2chains;
    }

    /**
     * 从constraint chains中提取所有查询涉及的列
     */
    private static Set<String> extractQueryInvolvedColumns(Map<String, List<ConstraintChain>> query2chains) {
        Set<String> involvedColumns = new HashSet<>();

        for (List<ConstraintChain> chains : query2chains.values()) {
            for (ConstraintChain chain : chains) {
                // 添加表名到列名的前缀
                String tablePrefix = chain.getTableName() + ".";

                // 从filter nodes中提取列
                for (ConstraintChainNode node : chain.getNodes()) {
                    if (node instanceof ConstraintChainFilterNode filterNode) {
                        for (String column : filterNode.getColumns()) {
                            if (!column.contains(".")) {
                                // 如果列名不包含表前缀，添加上
                                involvedColumns.add(tablePrefix + column);
                            } else {
                                involvedColumns.add(column);
                            }
                        }
                    }
                }

                // 从join nodes中提取列
                for (ConstraintChainNode node : chain.getNodes()) {
                    if (node instanceof ConstraintChainFkJoinNode joinNode) {
                        // 添加本地列（外键列）
                        involvedColumns.add(tablePrefix + joinNode.getLocalCols());
                        // 添加引用列（主键列）
                        if (joinNode.getRefCols() != null) {
                            involvedColumns.add(joinNode.getRefCols());
                        }
                    }
                }
            }
        }

        return involvedColumns;
    }

    /**
     * 当前表在约束链过滤条件中出现的规范列名（仅 Filter 节点），用于 prepareTupleData 与主键段写出。
     */
    private static Set<String> collectFilterCanonicalColumnsForTable(String schemaName, List<ConstraintChain> chains) {
        Set<String> out = new HashSet<>();
        if (chains == null) {
            return out;
        }
        String prefix = schemaName + ".";
        for (ConstraintChain chain : chains) {
            if (!schemaName.equals(chain.getTableName())) {
                continue;
            }
            String tablePrefix = chain.getTableName() + ".";
            for (ConstraintChainNode node : chain.getNodes()) {
                if (node instanceof ConstraintChainFilterNode filterNode) {
                    for (String column : filterNode.getColumns()) {
                        String canonical = column.contains(".") ? column : tablePrefix + column;
                        if (canonical.startsWith(prefix)) {
                            out.add(canonical);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String safeColumnOutput(String canonicalName, int rowId) {
        Column c = ColumnManager.getInstance().getColumn(canonicalName);
        if (c == null) {
            return "\\N";
        }
        return c.output(rowId);
    }

    private void init() throws IOException {
        // 设置分布模型系统属性
        System.setProperty("mirage.distribution.model", distributionModel);
        logger.info("设置数据生成分布模型: {}", distributionModel);
        logger.info("数据导出字段分隔符: '{}'（varchar 列会去除该字符）", DataExportConstants.FIELD_DELIMITER);

        //载入schema配置文件
        TableManager.getInstance().setResultDir(configPath);
        TableManager.getInstance().loadSchemaInfo();
        //载入分布配置文件
        ColumnManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnMetaData();
        ColumnManager.getInstance().loadColumnDistribution();
        //载入约束链，并进行transform（需要先加载来确定查询涉及的列）
        ConstraintChainManager.getInstance().setResultDir(configPath);
        Map<String, List<ConstraintChain>> query2chains = ConstraintChainManager.loadConstrainChainResult(configPath);
        ConstraintChainManager.getInstance().cleanConstrainChains(query2chains);

        // 如果提供了统计信息路径，加载统计信息并构建 CDF
        if (statisticsPath != null && !statisticsPath.isEmpty()) {
            logger.info("使用统计信息文件: {}", statisticsPath);

            // 提取查询涉及的所有列（仅用于诊断日志）
            Set<String> involvedColumns = extractQueryInvolvedColumns(query2chains);
            logger.info("查询涉及的列数量: {}", involvedColumns.size());
            
            // ★★★ 诊断：打印partsupp表相关的约束链信息 ★★★
            for (List<ConstraintChain> chains : query2chains.values()) {
                for (ConstraintChain chain : chains) {
                    if (chain.getTableName().equals("public.partsupp")) {
                        logger.info("🔍 partsupp约束链诊断 - Chain[{}]: 节点数={}, 节点类型: {}", 
                            chain.getChainIndex(), chain.getNodes().size(),
                            chain.getNodes().stream()
                                .map(node -> node.getClass().getSimpleName())
                                .collect(java.util.stream.Collectors.joining(", ")));
                        // 检查filter节点
                        List<ConstraintChainFilterNode> filterNodes = chain.getNodes().stream()
                            .filter(node -> node instanceof ConstraintChainFilterNode)
                            .map(node -> (ConstraintChainFilterNode) node)
                            .toList();
                        if (filterNodes.isEmpty()) {
                            logger.warn("⚠️ partsupp Chain[{}] 没有Filter节点！", chain.getChainIndex());
                        } else {
                            for (ConstraintChainFilterNode filterNode : filterNodes) {
                                List<String> columns = filterNode.getColumns();
                                logger.info("  Filter节点列: {}", columns);
                                for (String col : columns) {
                                    String fullColName = col.contains(".") ? col : chain.getTableName() + "." + col;
                                    boolean inInvolved = involvedColumns.contains(fullColName);
                                    logger.info("    列 {} 是否在involvedColumns中: {}", fullColName, inInvolved);
                                }
                            }
                        }
                    }
                }
            }

            try {
                ColumnManager.getInstance().loadStatisticsAndBuildCDF(statisticsPath, true);
            } catch (IOException e) {
                logger.error("加载统计信息失败: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to load statistics from: " + statisticsPath, e);
            }
            // 注意：dataIndex2ActualValue映射已经从 cdfMapping.json 在 loadColumnDistribution() 中加载
            // amendParameters() 会跳过CDF列，不会修改dataIndex，所以映射可以正常使用
        }

        // ★★★ 修复：重新调用amendParameters来设置dataValue ★★★
        // 因为约束链是从JSON加载的，dataValue没有被序列化，需要重新计算
        logger.info("重新计算参数的实际值...");
        for (List<ConstraintChain> chains : query2chains.values()) {
            for (ConstraintChain chain : chains) {
                for (ConstraintChainNode node : chain.getNodes()) {
                    if (node instanceof ConstraintChainFilterNode filterNode) {
                        // 递归查找所有的UniVarFilterOperation
                        findAndAmendUniVarFilters(filterNode.getRoot());
                    }
                }
            }
        }

        schema2chains = getSchema2Chains(query2chains);
        // 删除上次生成的数据
        File dataDir = new File(outputPath);
        if (dataDir.isDirectory() && dataDir.listFiles() != null) {
            Arrays.stream(Objects.requireNonNull(dataDir.listFiles()))
                    .filter(File::delete)
                    .forEach(file -> logger.info(rb.getString("deleteOldData"), file.getName()));
        }
        // 初始化数据生成器
        dataWriter = new DataWriter(outputPath, generatorId);

        stepRange = (long) stepSize * (generatorNum - 1);
    }

    private List<List<String>> classifyFkDependency(List<ConstraintChain> haveFkConstrainChains) {
        Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        HashSet<String> allFkCols = new HashSet<>();
        for (ConstraintChain haveFkConstrainChain : haveFkConstrainChains) {
            for (ConstraintChainFkJoinNode fkJoinNode : haveFkConstrainChain.getFkNodes()) {
                allFkCols.add(fkJoinNode.getLocalCols());
            }
        }
        if (closeTopologicalReduce) {
            return Collections.singletonList(new ArrayList<>(allFkCols));
        }
        for (String fkCol : allFkCols) {
            graph.addVertex(fkCol);
        }
        for (ConstraintChain haveFkConstrainChain : haveFkConstrainChains) {
            List<ConstraintChainFkJoinNode> fkJoinNodes = haveFkConstrainChain.getFkNodes();
            String lastColName = fkJoinNodes.getFirst().getLocalCols();
            for (int i = 1; i < fkJoinNodes.size(); i++) {
                String currentColName = fkJoinNodes.get(i).getLocalCols();
                graph.addEdge(lastColName, currentColName);
                lastColName = currentColName;
            }
        }
        List<Set<String>> fkSets = new KosarajuStrongConnectivityInspector<>(graph).stronglyConnectedSets();
        return fkSets.stream().map(fkSet -> fkSet.stream().toList()).toList();
    }

    private void computeStepRange(long tableSize) {
        if ((long) stepSize * generatorNum > tableSize) {
            batchSize = tableSize / generatorNum;
        } else {
            batchSize = stepSize;
        }
        batchStart = batchSize * generatorId;
    }


    private boolean[][] generateStatusViewOfEachRow(List<ConstraintChain> constraintChains, int range) {
        // 计算外键的filter status
        boolean[][] statusVectorOfEachRow = new boolean[range][constraintChains.size()];
        constraintChains.stream().parallel().forEach(chain -> {
            boolean[] statusVector = chain.evaluateFilterStatus(range);
            int chainIndex = chain.getChainIndex();
            // 确保使用range而不是statusVector.length，因为evaluateFilterStatus已经调整了数组长度
            int actualLength = Math.min(statusVector.length, range);
            for (int rowId = 0; rowId < actualLength; rowId++) {
                statusVectorOfEachRow[rowId][chainIndex] = statusVector[rowId];
            }
            // 如果statusVector.length < range，剩余位置保持false（默认值）
            // 如果statusVector.length > range，evaluateFilterStatus应该已经截断了
        });
        return statusVectorOfEachRow;
    }

    private StringBuilder[] generatePks(boolean[][] statusVectorOfEachRow, int[] pkStatusChainIndexes, String pkName,
            String schemaName, Set<String> filterCanonicalColumns) {
        // partsupp 兼容标准 TPCH schema：
        // Mirage 原始实现对“复合主键表”仍会额外输出一列单列PK（通常是 0..N-1 的行号），导致输出多一列。
        // 标准 TPCH 的 partsupp 只有 5 列 (ps_partkey, ps_suppkey, ps_availqty, ps_supplycost, ps_comment)，
        // 且 partsupp 的两列主键本身就是外键列，真正的 join 约束也只围绕这两列展开。
        // 因此这里对 public.partsupp 特判：不输出额外 row-id 列，让输出严格对齐标准 schema。
        logger.info("pkname is: {}", pkName);
        if (pkName != null && pkName.contains(",") && pkName.contains(".partsupp.")) {
            int range = statusVectorOfEachRow.length;
            StringBuilder[] rowData = new StringBuilder[range];
            IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder());
            logger.info("检测到 partsupp 复合主键，跳过额外row-id列输出以对齐标准TPCH schema: pkName={}", pkName);
            return rowData;
        }

        int range = statusVectorOfEachRow.length;
        Set<String> pkInFilter = new HashSet<>();
        if (filterCanonicalColumns != null) {
            for (String c : filterCanonicalColumns) {
                if (TableManager.getInstance().isPrimaryKey(c)) {
                    pkInFilter.add(c);
                }
            }
        }

        List<String> pkList = new ArrayList<>();
        try {
            pkList = new ArrayList<>(TableManager.getInstance().getCompletePrimaryKeysList(schemaName));
        } catch (CannotFindSchemaException e) {
            logger.debug("getCompletePrimaryKeysList({}): {}", schemaName, e.getMessage());
        }
        if (pkList.isEmpty() && pkName != null && !pkName.isEmpty()) {
            pkList = Arrays.stream(pkName.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }

        // 业务表：过滤条件命中主键列时，主键段与 prepareTupleData 对齐（无 PK join RuleTable 时）
        if (!pkInFilter.isEmpty() && pkStatusChainIndexes.length == 0 && !pkList.isEmpty()) {
            if (pkList.size() == 1 && pkInFilter.contains(pkList.get(0))) {
                final String colName = pkList.get(0);
                StringBuilder[] rowData = new StringBuilder[range];
                IntStream.range(0, range).parallel().forEach(rowId ->
                        rowData[rowId] = new StringBuilder(safeColumnOutput(colName, rowId)).append(DataExportConstants.FIELD_DELIMITER_CHAR));
                logger.info("表 {} 单列主键且出现在过滤中，主键段使用列生成值: {}", schemaName, colName);
                return rowData;
            }
            StringBuilder[] rowData = new StringBuilder[range];
            List<String> ordered = new ArrayList<>(pkList);
            IntStream.range(0, range).parallel().forEach(rowId -> {
                StringBuilder sb = new StringBuilder();
                for (String pkCol : ordered) {
                    sb.append(safeColumnOutput(pkCol, rowId)).append(DataExportConstants.FIELD_DELIMITER_CHAR);
                }
                rowData[rowId] = sb;
            });
            logger.info("表 {} 复合主键且部分主键列出现在过滤中，主键段按 schema 顺序输出 {} 列", schemaName, ordered.size());
            return rowData;
        }
        if (!pkInFilter.isEmpty() && pkStatusChainIndexes.length > 0) {
            logger.warn("表 {} 同时存在 PK join RuleTable 与过滤中的主键列，主键段沿用 RuleTable（可能与过滤用统计值不完全一致）", schemaName);
        }

        StringBuilder[] rowData = new StringBuilder[range];
        if (pkStatusChainIndexes.length > 0) {
            //创建主键状态矩阵
            JoinStatus[] allStatuses = new JoinStatus[range];
            Map<JoinStatus, Long> pkHistogram = new HashMap<>();
            FkGenerator.staticsStatusHistogram(statusVectorOfEachRow, allStatuses, pkStatusChainIndexes, pkHistogram);
            String showStatusVectorTable = rb.getString("showStatusVectorTable");
            logger.info(showStatusVectorTable, pkName);
            for (Map.Entry<JoinStatus, Long> joinStatusLongEntry : pkHistogram.entrySet()) {
                logger.info("size:{}, status:{}", joinStatusLongEntry.getValue(), joinStatusLongEntry.getKey().status());
            }
            var pkStatus2Location = RuleTableManager.getInstance().addRuleTable(pkName, pkHistogram, batchStart);
            IntStream.range(0, range).parallel().forEach(rowId ->
                    rowData[rowId] = new StringBuilder().append(pkStatus2Location.get(allStatuses[rowId]).getAndIncrement()).append(DataExportConstants.FIELD_DELIMITER_CHAR));
        }
        //处理不需要外键填充的主键状态
        else if (!pkName.isEmpty()) {
            IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder().append(batchStart + i).append(DataExportConstants.FIELD_DELIMITER_CHAR));
        } else {
            IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder());
        }
        return rowData;
    }

    private Map<String, long[]> generateFks(boolean[][] statusVectorOfEachRow, FkGenerator[] fkGenerators,
                                            List<List<String>> fkGroups) {
        Map<String, long[]> fkCol2Values = new TreeMap<>();
        for (int groupIndex = 0; groupIndex < fkGenerators.length; groupIndex++) {
            long[][] fkValues = fkGenerators[groupIndex].generateFK(statusVectorOfEachRow);
            List<String> fkGroup = fkGroups.get(groupIndex);
            for (int fkColIndex = 0; fkColIndex < fkGroup.size(); fkColIndex++) {
                fkCol2Values.put(fkGroup.get(fkColIndex), fkValues[fkColIndex]);
            }
        }
        return fkCol2Values;
    }

    private void generateFksNoConstraints(Map<String, long[]> fkCol2Values, SortedMap<String, Long> allFk2TableSize, int range) {
        for (Map.Entry<String, Long> fk2TableSize : allFk2TableSize.entrySet()) {
            if (!fkCol2Values.containsKey(fk2TableSize.getKey())) {
                long[] fks = ThreadLocalRandom.current().longs(range, 1, fk2TableSize.getValue() + 1).toArray();
                fkCol2Values.put(fk2TableSize.getKey(), fks);
            }
        }
    }

    private int[] getPkStatusChainIndexes(List<ConstraintChain> allChains) {
        TreeMap<Integer, Integer> pkJoinTag2ChainIndex = new TreeMap<>();
        for (ConstraintChain constraintChain : allChains) {
            for (ConstraintChainNode node : constraintChain.getNodes()) {
                if (node instanceof ConstraintChainPkJoinNode pkJoinNode) {
                    pkJoinTag2ChainIndex.put(pkJoinNode.getPkTag(), constraintChain.getChainIndex());
                }
            }
        }
        return pkJoinTag2ChainIndex.values().stream().mapToInt(Integer::intValue).toArray();
    }

    private void generateTableWithoutChains(String pkName, long tableSize, String schemaName) {
        long pkStart = ColumnManager.getInstance().getMin(pkName);
        while (batchStart < tableSize) {
            int range = (int) (Math.min(batchStart + batchSize, tableSize) - batchStart);
            //生成属性列数据
            long tPrepare = System.currentTimeMillis();
            ColumnManager.getInstance().prepareGeneration(range);
            logger.info("表 {} 本批 [{}-{}) prepareGeneration 结束，耗时 {} ms（各列并行 prepareTupleData）",
                    schemaName, batchStart, batchStart + range, System.currentTimeMillis() - tPrepare);
            long tAtt = System.currentTimeMillis();
            String[] attRows = ColumnManager.getInstance().generateAttRows(range);
            logger.info("表 {} 本批 [{}-{}) generateAttRows 结束，耗时 {} ms（按行拼接各列 output，大表此处常数分钟且无中间日志）",
                    schemaName, batchStart, batchStart + range, System.currentTimeMillis() - tAtt);
            StringBuilder[] rowData = new StringBuilder[range];
            long tRow = System.currentTimeMillis();
            if (pkName.isEmpty()) {
                IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder());
            } else {
                IntStream.range(0, range).parallel().forEach(i -> rowData[i] = new StringBuilder().append(batchStart + i + pkStart).append(DataExportConstants.FIELD_DELIMITER_CHAR));
            }
            dataWriter.addWriteTask(schemaName, rowData, attRows);
            logger.info("表 {} 本批 [{}-{}) 主键列组装与 addWriteTask 提交，耗时 {} ms",
                    schemaName, batchStart, batchStart + range, System.currentTimeMillis() - tRow);
            batchStart += range + stepRange;
        }
    }

    @Override
    public Integer call() throws Exception {
        if (expandRules) {
            RuleTable.openExpandRuleMap();
        }
        // 最后的生成过程
        init();
        long generateNonKeyTime = 0;
        long solveCPTime = 0;
        long computeStatusVectorTime = 0;
        long populateKeyTime = 0;
        long freeMemory = Long.MAX_VALUE;
        Runtime runtime = Runtime.getRuntime();

        long start = System.currentTimeMillis();
        for (String schemaName : TableManager.getInstance().createTopologicalOrder()) {
            clearGenericJoinHistogramAccumulators(genericJoinHistogramAccumulators);
            long tableSize = TableManager.getInstance().getTableSize(schemaName) * scaleFactor;
            String pkName = TableManager.getInstance().getPrimaryKeys(schemaName);
            computeStepRange(tableSize);
            String startDataOutPut = rb.getString("startDataOutPut");
            logger.info(startDataOutPut, schemaName, tableSize);
            // ★★★ 修复：确保表的所有列都被加载（stats模式下需要） ★★★
            List<String> attColumnNames = TableManager.getInstance().getAttributeColumnNames(schemaName);
            // 准备生成的属性列生成器
            ColumnManager.getInstance().cacheAttributeColumn(attColumnNames);
            // 获得所有约束链
            List<ConstraintChain> allChains = schema2chains.get(schemaName);
            logger.info("used memory before GN(MB): {}", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
            if (allChains == null) {
                // todo 当前假设主键是连续的
                long start1 = System.currentTimeMillis();
                logger.info("generateTableWithoutChains, schemaName: {}, pkName: {}, tableSize: {}", schemaName, pkName, tableSize);
                generateTableWithoutChains(pkName, tableSize, schemaName);
                generateNonKeyTime = (System.currentTimeMillis() - start1);
                logger.info("used memory after GN(MB): {}", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
                continue;
            }
            // 设置chain的索引
            for (int i = 0; i < allChains.size(); i++) {
                allChains.get(i).setChainIndex(i);
            }
            // logger.info("表 {} 的约束链总数: {}", schemaName, allChains.size());
            // for (int i = 0; i < allChains.size(); i++) {
            //     ConstraintChain chain = allChains.get(i);
            //     String filterCondition = chain.getNodes().stream()
            //             .filter(node -> node instanceof ConstraintChainFilterNode)
            //             .map(node -> ((ConstraintChainFilterNode) node).getRoot().toString())
            //             .findFirst().orElse("无过滤条件");
            //     logger.info("  约束链[{}]: 表={}, 节点数={}, 过滤条件: {}", 
            //         i, chain.getTableName(), chain.getNodes().size(), filterCondition);
            // }
            
            // 日志：输出加载的ACC参数值 
            // logLoadedAccParameters(allChains, schemaName);

            Set<String> filterColsForTable = collectFilterCanonicalColumnsForTable(schemaName, allChains);
            // if (!filterColsForTable.isEmpty()) {
            //     logger.info("表 {} 约束链过滤涉及列（含主键等）: {}", schemaName, filterColsForTable);
            // }
            
            // 获取外键约束链
            List<ConstraintChain> haveFkConstrainChains = allChains.stream().filter(ConstraintChain::hasFkNode).toList();
            // 根据外键列的连接依赖性划外键列生成组
            List<List<String>> fkGroups = classifyFkDependency(haveFkConstrainChains);
            SortedMap<String, Long> allFk2TableSize = TableManager.getInstance().getFk2PkTableSize(schemaName);
            FkGenerator[] fkGenerators = new FkGenerator[fkGroups.size()];
            for (int i = 0; i < fkGenerators.length; i++) {
                fkGenerators[i] = new FkGenerator(allChains, fkGroups.get(i), tableSize);
            }
            int[] pkStatusChainIndexes = getPkStatusChainIndexes(allChains);
            // 开始生成
            while (batchStart < tableSize) {
                int range = (int) (Math.min(batchStart + batchSize, tableSize) - batchStart);
                String generateFromTo = rb.getString("generateFromTo");
                logger.info(generateFromTo, batchStart, batchStart + range);
                // 属性列 + 过滤涉及列（及任一带过滤主键时整表主键列）prepare，供 evaluate 与主键段写出
                long start1 = System.currentTimeMillis();
                Set<String> prepareExtra = new HashSet<>(filterColsForTable);
                boolean anyPkInFilter = filterColsForTable.stream().anyMatch(TableManager.getInstance()::isPrimaryKey);
                if (anyPkInFilter) {
                    try {
                        prepareExtra.addAll(TableManager.getInstance().getCompletePrimaryKeysList(schemaName));
                    } catch (CannotFindSchemaException e) {
                        logger.warn("表 {} 扩展主键列 prepare 失败: {}", schemaName, e.getMessage());
                    }
                }
                ColumnManager.getInstance().prepareGeneration(range, prepareExtra);
                generateNonKeyTime += (System.currentTimeMillis() - start1);

                // // ★★★ 测试：基于实际数据重新估计ACC参数（硬编码三个约束）★★★
                // if (schemaName.equals("public.lineitem")) {
                //     testReestimateAccParametersFromActualData(range);
                // }

                // 计算每一行的状态向量 哪些行满足哪些约束
                long startComputeStatusVector = System.currentTimeMillis();
                boolean[][] statusVectorOfEachRow = generateStatusViewOfEachRow(allChains, range);
                computeStatusVectorTime += System.currentTimeMillis() - startComputeStatusVector;

                // 记录 status vector 统计（已注释，需排查时再取消块注释）
                /*
                if (logger.isInfoEnabled()) {
                    logger.info("=== Status Vector详细分析 (表: {}, 范围: 0-{}) ===", schemaName, batchStart + range);

                    // 打印每个Chain对应的查询信息（每一位的含义）
                    logger.info("Status Vector每一位含义:");
                    for (int chainIndex = 0; chainIndex < allChains.size(); chainIndex++) {
                        ConstraintChain chain = allChains.get(chainIndex);
                        String filterCondition = chain.getNodes().stream()
                                .filter(node -> node instanceof ConstraintChainFilterNode)
                                .map(node -> ((ConstraintChainFilterNode) node).getRoot().toString())
                                .findFirst().orElse("无过滤条件");
                        logger.info("  位[{}]: Chain[{}] - 表={}, 节点数={}, 过滤条件: {}",
                            chainIndex, chainIndex, chain.getTableName(), chain.getNodes().size(), filterCondition);
                    }

                    // 打印详细的status vector分布（每一位的占比）
                    logger.info("Status Vector每一位占比:");
                    for (int chainIndex = 0; chainIndex < allChains.size(); chainIndex++) {
                        int trueCount = 0;
                        for (int rowId = 0; rowId < range; rowId++) {
                            if (statusVectorOfEachRow[rowId][chainIndex]) {
                                trueCount++;
                            }
                        }
                        double ratio = range > 0 ? (double) trueCount / range : 0.0;
                        double percentage = ratio * 100.0;
                        ConstraintChain chain = allChains.get(chainIndex);
                        String filterCondition = chain.getNodes().stream()
                                .filter(node -> node instanceof ConstraintChainFilterNode)
                                .map(node -> ((ConstraintChainFilterNode) node).getRoot().toString())
                                .findFirst().orElse("无过滤条件");
                        logger.info("  位[{}]: true={}, false={}, ratio={} ({}%), 约束: {}",
                            chainIndex, trueCount, range - trueCount, String.format("%.4f", ratio), String.format("%.2f", percentage), filterCondition);
                    }

                    // 统计所有可能的status vector组合及其占比
                    Map<String, Integer> statusVectorCombinations = new HashMap<>();
                    for (int rowId = 0; rowId < range; rowId++) {
                        StringBuilder sb = new StringBuilder();
                        for (int chainIndex = 0; chainIndex < allChains.size(); chainIndex++) {
                            sb.append(statusVectorOfEachRow[rowId][chainIndex] ? "1" : "0");
                        }
                        String statusStr = sb.toString();
                        statusVectorCombinations.put(statusStr, statusVectorCombinations.getOrDefault(statusStr, 0) + 1);
                    }

                    // 不打印输出
                    logger.info("Status Vector组合分布 (共{}种组合):", statusVectorCombinations.size());
                    statusVectorCombinations.entrySet().stream()
                            .sorted((a, b) -> b.getValue().compareTo(a.getValue())) // 按数量降序
                            .forEach(entry -> {
                                double comboRatio = range > 0 ? (double) entry.getValue() / range : 0.0;
                                double comboPercentage = comboRatio * 100.0;
                                // 构建组合的详细说明
                                StringBuilder comboDesc = new StringBuilder();
                                for (int i = 0; i < entry.getKey().length(); i++) {
                                    if (entry.getKey().charAt(i) == '1') {
                                        ConstraintChain chain = allChains.get(i);
                                        String filterCondition = chain.getNodes().stream()
                                                .filter(node -> node instanceof ConstraintChainFilterNode)
                                                .map(node -> ((ConstraintChainFilterNode) node).getRoot().toString())
                                                .findFirst().orElse("无过滤条件");
                                        if (comboDesc.length() > 0) comboDesc.append(" AND ");
                                        comboDesc.append("位[").append(i).append("]:").append(filterCondition);
                                    }
                                }
                                if (comboDesc.length() == 0) {
                                    comboDesc.append("所有约束都不满足");
                                }
                                logger.info("  组合[{}]: 行数={}, 占比={} ({}%), 含义: {}",
                                    entry.getKey(), entry.getValue(), String.format("%.4f", comboRatio), String.format("%.2f", comboPercentage), comboDesc.toString());
                            });

                    // 打印前10行的status vector示例
                    logger.info("Status Vector前10行示例:");
                    for (int rowId = 0; rowId < Math.min(10, range); rowId++) {
                        StringBuilder sb = new StringBuilder();
                        for (int chainIndex = 0; chainIndex < allChains.size(); chainIndex++) {
                            sb.append(statusVectorOfEachRow[rowId][chainIndex] ? "1" : "0");
                        }
                        logger.info("  行{}: [{}]", rowId, sb.toString());
                    }

                    logger.info("=== Status Vector分析结束 ===\n");
                }
                */
                
                // 生成外键列数据
                Map<String, long[]> fkCol2Values = generateFks(statusVectorOfEachRow, fkGenerators, fkGroups);
                generateFksNoConstraints(fkCol2Values, allFk2TableSize, range);
                refreshGenericJoinWeightsAfterBatch(genericJoinHistogramAccumulators, allChains, fkCol2Values);

                // 生成主键列数据
                long startPopulatePK = System.currentTimeMillis();
                StringBuilder[] keyData = generatePks(statusVectorOfEachRow, pkStatusChainIndexes, pkName, schemaName, filterColsForTable);
                populateKeyTime += System.currentTimeMillis() - startPopulatePK;

                // 组合外键列数据和主键列数据
                IntStream.range(0, keyData.length).parallel().forEach(index -> {
                    StringBuilder row = keyData[index];
                    for (long[] fks : fkCol2Values.values()) {
                        long fk = fks[index];
                        if (fk == Long.MIN_VALUE) {
                            row.append("\\N").append(DataExportConstants.FIELD_DELIMITER_CHAR);
                        } else {
                            row.append(fk).append(DataExportConstants.FIELD_DELIMITER_CHAR);
                        }
                    }
                });
                //转换为字符串准备输出
                String[] data = ColumnManager.getInstance().generateAttRows(range);
                dataWriter.addWriteTask(schemaName, keyData, data);
                batchStart += range + stepRange;
            }
            freeMemory = Math.min(freeMemory, runtime.freeMemory());
            computeStatusVectorTime += Arrays.stream(fkGenerators).mapToLong(FkGenerator::getConstructHistogram).sum();
            populateKeyTime += Arrays.stream(fkGenerators).mapToLong(FkGenerator::getPopulateFKTime).sum();
            solveCPTime += Arrays.stream(fkGenerators).mapToLong(FkGenerator::getSolveCPTime).sum();
        }
        logger.info("GN:{}", generateNonKeyTime);
        logger.info("CS:{}", computeStatusVectorTime);
        logger.info("CP:{}", solveCPTime);
        logger.info("PK:{}", populateKeyTime);
        logger.info("total time: {}", System.currentTimeMillis() - start);
        logger.info("used memory (MB): {}", (runtime.totalMemory() - freeMemory) / 1024 / 1024);
        if (dataWriter.waitWriteFinish()) {
            logger.info("Output table data completed");
        }
        return 0;
    }

    /**
     * 递归查找并修正所有UniVarFilterOperation的参数
     */
    private void findAndAmendUniVarFilters(ruc.db.generator.constraintchain.filter.BoolExprNode node) {
        if (node instanceof UniVarFilterOperation uniFilter) {
            uniFilter.amendParameters();
        } else if (node instanceof ruc.db.generator.constraintchain.filter.operation.MultiVarFilterOperation) {
            // MultiVarFilterOperation也可能需要amend，但这里先只处理UniVar
        } else if (node instanceof ruc.db.generator.constraintchain.filter.LogicNode logicNode) {
            // 递归处理逻辑节点的所有子节点
            for (ruc.db.generator.constraintchain.filter.BoolExprNode child : logicNode.getChildren()) {
                findAndAmendUniVarFilters(child);
            }
        }
        // 其他类型的节点不需要处理
    }

    /**
     * 测试方法：基于实际数据重新估计ACC参数（硬编码四个约束）
     * 输出参数值供手动替换到JSON文件中
     * 
     * @param range 数据量
     */
    private void testReestimateAccParametersFromActualData(int range) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("开始基于实际数据重新估计ACC参数（测试模式）");
        logger.info("数据量: {}", range);
        
        try {
            // 获取列的实际数据（sampleSize = -1 表示使用实际数据）
            double[] receiptdateData = ColumnManager.getInstance().calculate("public.lineitem.l_receiptdate", -1);
            double[] commitdateData = ColumnManager.getInstance().calculate("public.lineitem.l_commitdate", -1);
            double[] shipdateData = ColumnManager.getInstance().calculate("public.lineitem.l_shipdate", -1);
            
            // 确保数据长度匹配
            int actualLength = Math.min(Math.min(receiptdateData.length, commitdateData.length), shipdateData.length);
            actualLength = Math.min(actualLength, range);
            
            logger.info("获取的实际数据长度: l_receiptdate={}, l_commitdate={}, l_shipdate={}, 使用长度={}", 
                       receiptdateData.length, commitdateData.length, shipdateData.length, actualLength);
            
            // 计算差值向量
            double[] diff1 = new double[actualLength]; // l_receiptdate - l_commitdate
            double[] diff2 = new double[actualLength]; // l_commitdate - l_receiptdate
            double[] diff3 = new double[actualLength]; // l_shipdate - l_commitdate
            
            int nanCount1 = 0, nanCount2 = 0, nanCount3 = 0;
            for (int i = 0; i < actualLength; i++) {
                diff1[i] = receiptdateData[i] - commitdateData[i];
                diff2[i] = commitdateData[i] - receiptdateData[i];
                diff3[i] = shipdateData[i] - commitdateData[i];
                
                if (Double.isNaN(diff1[i])) nanCount1++;
                if (Double.isNaN(diff2[i])) nanCount2++;
                if (Double.isNaN(diff3[i])) nanCount3++;
            }
            
            logger.info("差值向量统计:");
            logger.info("  diff1 (l_receiptdate - l_commitdate): NaN={}/{}", nanCount1, actualLength);
            logger.info("  diff2 (l_commitdate - l_receiptdate): NaN={}/{}", nanCount2, actualLength);
            logger.info("  diff3 (l_shipdate - l_commitdate): NaN={}/{}", nanCount3, actualLength);
            
            // 过滤NaN值
            double[] diff1Valid = Arrays.stream(diff1).filter(d -> !Double.isNaN(d) && !Double.isInfinite(d)).toArray();
            double[] diff2Valid = Arrays.stream(diff2).filter(d -> !Double.isNaN(d) && !Double.isInfinite(d)).toArray();
            double[] diff3Valid = Arrays.stream(diff3).filter(d -> !Double.isNaN(d) && !Double.isInfinite(d)).toArray();
            
            logger.info("过滤NaN后有效数据: diff1={}, diff2={}, diff3={}", 
                       diff1Valid.length, diff2Valid.length, diff3Valid.length);
            
            if (diff1Valid.length == 0 || diff2Valid.length == 0 || diff3Valid.length == 0) {
                logger.error("有效数据不足，无法重新估计参数");
                return;
            }
            
            // 约束1: l_receiptdate - l_commitdate > param
            // ConstraintChainNode的目标概率: 0.6320880021 (63.21%)
            // 对于GT操作符：P(x > param) = 0.6320880021
            // 所以应该选择(1 - 0.6320880021)分位数，即36.79%分位数
            Arrays.sort(diff1Valid);
            BigDecimal prob1 = new BigDecimal("0.6320880021");
            BigDecimal adjustedProb1 = BigDecimal.ONE.subtract(prob1); // 0.3679119979
            int pos1 = adjustedProb1.multiply(BigDecimal.valueOf(diff1Valid.length))
                .setScale(0, RoundingMode.HALF_UP).intValue();
            pos1 = Math.min(pos1, diff1Valid.length - 1);
            double param1 = diff1Valid[pos1];
            
            // ★★★ 验证：检查有多少数据大于param1 ★★★
            int countGreater = 0;
            for (double v : diff1Valid) {
                if (v > param1) countGreater++;
            }
            double actualRatio = (double) countGreater / diff1Valid.length;
            logger.info("约束1参数估计验证: 参数值={} (位置={}), 大于参数的数据占比={}, 期望占比={}", 
                       param1, pos1, actualRatio, prob1.doubleValue());
            
            // ★★★ 如果实际占比远小于期望，说明参数值太大，需要选择更小的值 ★★★
            if (actualRatio < prob1.doubleValue() * 0.9) {
                // 选择更小的参数值，从大到小的角度就是选择更小位置
                int newPos = adjustedProb1.multiply(BigDecimal.valueOf(diff1Valid.length))
                    .setScale(0, RoundingMode.HALF_UP).intValue();
                newPos = Math.min(newPos, diff1Valid.length - 1);
                param1 = diff1Valid[newPos];
                logger.warn("约束1参数调整: 原参数值太大（实际占比={}% < 期望{}%），调整为={} (位置={})", 
                           actualRatio * 100, prob1.doubleValue() * 100, param1, newPos);
            }
            long param1Internal = (long) (param1 * CommonUtils.SAMPLE_DOUBLE_PRECISION);
            long param1Days = param1Internal / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            String param1Value = "interval '" + param1Days + "' day";
            
            // 约束2: l_commitdate - l_receiptdate < param, 概率 0.6320880021
            Arrays.sort(diff2Valid);
            BigDecimal prob2 = new BigDecimal("0.6320880021");
            int pos2 = prob2.multiply(BigDecimal.valueOf(diff2Valid.length))
                .setScale(0, RoundingMode.HALF_UP).intValue();
            pos2 = Math.min(pos2, diff2Valid.length - 1);
            double param2 = diff2Valid[pos2];
            long param2Internal = (long) (param2 * CommonUtils.SAMPLE_DOUBLE_PRECISION);
            long param2Days = param2Internal / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            String param2Value = "interval '" + param2Days + "' day";
            
            // 约束3: l_commitdate - l_receiptdate < param, 概率 1.0
            // 注意：约束2和约束3使用相同的差值向量diff2，但概率不同
            int pos3 = diff2Valid.length - 1; // 概率1.0选择最大值（最后一个位置）
            double param3 = diff2Valid[pos3];
            long param3Internal = (long) (param3 * CommonUtils.SAMPLE_DOUBLE_PRECISION);
            long param3Days = param3Internal / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            String param3Value = "interval '" + param3Days + "' day";
            
            // 约束4: l_shipdate - l_commitdate < param, 概率 1.0
            Arrays.sort(diff3Valid);
            int pos4 = diff3Valid.length - 1; // 概率1.0选择最大值（最后一个位置）
            double param4 = diff3Valid[pos4];
            long param4Internal = (long) (param4 * CommonUtils.SAMPLE_DOUBLE_PRECISION);
            long param4Days = param4Internal / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            String param4Value = "interval '" + param4Days + "' day";
            
            // 输出结果
            logger.info("");
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("ACC 参数重新估计结果（基于实际数据，手动替换使用）");
            logger.info("═══════════════════════════════════════════════════════════════");
            logger.info("约束1: public.lineitem.l_receiptdate - public.lineitem.l_commitdate > param");
            logger.info("  目标概率(ConstraintChainNode): 0.6320880021 (63.21%)");
            logger.info("  估计参数: {} (internalValue: {}, days: {})", param1Value, param1Internal, param1Days);
            logger.info("  用于JSON: \"data\": {}, \"dataValue\": \"{}\"", param1Internal, param1Value);
            logger.info("  参数ID: 68 (文件: 21_1.sql.json)");
            logger.info("");
            logger.info("约束2: public.lineitem.l_commitdate - public.lineitem.l_receiptdate < param");
            logger.info("  概率: 0.6320880021");
            logger.info("  估计参数: {} (internalValue: {}, days: {})", param2Value, param2Internal, param2Days);
            logger.info("  用于JSON: \"data\": {}, \"dataValue\": \"{}\"", param2Internal, param2Value);
            logger.info("  参数ID: 90");
            logger.info("");
            logger.info("约束3: public.lineitem.l_commitdate - public.lineitem.l_receiptdate < param");
            logger.info("  概率: 1.0");
            logger.info("  估计参数: {} (internalValue: {}, days: {})", param3Value, param3Internal, param3Days);
            logger.info("  用于JSON: \"data\": {}, \"dataValue\": \"{}\"", param3Internal, param3Value);
            logger.info("  参数ID: 10");
            logger.info("");
            logger.info("约束4: public.lineitem.l_shipdate - public.lineitem.l_commitdate < param");
            logger.info("  概率: 1.0");
            logger.info("  估计参数: {} (internalValue: {}, days: {})", param4Value, param4Internal, param4Days);
            logger.info("  用于JSON: \"data\": {}, \"dataValue\": \"{}\"", param4Internal, param4Value);
            logger.info("  参数ID: 11");
            logger.info("═══════════════════════════════════════════════════════════════");
            
            // ★★★ 输出数据文件到logs目录 ★★★
            // outputAccConstraintDataFiles(configPath, receiptdateData, commitdateData, shipdateData, 
            //                             diff1, diff2, diff3, diff1Valid, diff2Valid, diff3Valid, actualLength);
            
        } catch (Exception e) {
            logger.error("ACC参数重新估计失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 输出ACC约束的详细数据文件
     */
    private void outputAccConstraintDataFiles(String configPath, double[] receiptdateData, double[] commitdateData, 
                                             double[] shipdateData, double[] diff1, double[] diff2, double[] diff3,
                                             double[] diff1Valid, double[] diff2Valid, double[] diff3Valid, int actualLength) {
        try {
            // 创建logs目录
            String logsDir = configPath + "/logs";
            File logsDirFile = new File(logsDir);
            if (!logsDirFile.exists()) {
                logsDirFile.mkdirs();
            }
            
            // 获取原始数据（通过反射访问columnActualData）
            Object[] receiptdateRaw = getColumnActualData("public.lineitem.l_receiptdate");
            Object[] commitdateRaw = getColumnActualData("public.lineitem.l_commitdate");
            Object[] shipdateRaw = getColumnActualData("public.lineitem.l_shipdate");
            
            // 输出约束1的数据文件: l_receiptdate - l_commitdate > param
            outputConstraintDataFile(logsDir + "/acc_constraint_1_data.csv",
                "约束1: l_receiptdate - l_commitdate > param",
                receiptdateRaw, commitdateRaw, null, diff1, diff1Valid, actualLength, "l_receiptdate", "l_commitdate", null);
            
            // 输出约束2的数据文件: l_commitdate - l_receiptdate < param
            outputConstraintDataFile(logsDir + "/acc_constraint_2_data.csv",
                "约束2: l_commitdate - l_receiptdate < param",
                commitdateRaw, receiptdateRaw, null, diff2, diff2Valid, actualLength, "l_commitdate", "l_receiptdate", null);
            
            // 输出约束3的数据文件: l_commitdate - l_receiptdate < param (与约束2相同，但概率不同)
            outputConstraintDataFile(logsDir + "/acc_constraint_3_data.csv",
                "约束3: l_commitdate - l_receiptdate < param (概率1.0)",
                commitdateRaw, receiptdateRaw, null, diff2, diff2Valid, actualLength, "l_commitdate", "l_receiptdate", null);
            
            // 输出约束4的数据文件: l_shipdate - l_commitdate < param
            outputConstraintDataFile(logsDir + "/acc_constraint_4_data.csv",
                "约束4: l_shipdate - l_commitdate < param",
                shipdateRaw, commitdateRaw, null, diff3, diff3Valid, actualLength, "l_shipdate", "l_commitdate", null);
            
            logger.info("ACC约束数据文件已输出到: {}", logsDir);
            
        } catch (Exception e) {
            logger.error("输出ACC约束数据文件失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 通过反射获取列的columnActualData
     */
    private Object[] getColumnActualData(String columnName) {
        try {
            ruc.db.schema.Column column = ColumnManager.getInstance().getColumn(columnName);
            if (column == null) {
                logger.warn("列 {} 不存在", columnName);
                return null;
            }
            
            Field field = ruc.db.schema.Column.class.getDeclaredField("columnActualData");
            field.setAccessible(true);
            return (Object[]) field.get(column);
        } catch (Exception e) {
            logger.warn("获取列 {} 的columnActualData失败: {}", columnName, e.getMessage());
            return null;
        }
    }
    
    /**
     * 输出单个约束的数据文件
     */
    private void outputConstraintDataFile(String filePath, String header, Object[] col1Raw, Object[] col2Raw, Object[] col3Raw,
                                         double[] diff, double[] diffValid, int actualLength,
                                         String col1Name, String col2Name, String col3Name) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            // 写入表头
            writer.println("# " + header);
            if (col3Name != null) {
                writer.println("# 列: " + col1Name + ", " + col2Name + ", " + col3Name + ", diff, diff_sorted");
                writer.println(col1Name + "," + col2Name + "," + col3Name + ",diff,diff_sorted");
            } else {
                writer.println("# 列: " + col1Name + ", " + col2Name + ", diff, diff_sorted");
                writer.println(col1Name + "," + col2Name + ",diff,diff_sorted");
            }
            
            // 将diffValid排序（从小到大）
            double[] diffSorted = Arrays.copyOf(diffValid, diffValid.length);
            Arrays.sort(diffSorted);
            
            // 写入原始数据（只写入有效数据，排除NaN）
            int validIndex = 0; // 有效数据的索引（用于访问排序后的数组）
            for (int i = 0; i < actualLength; i++) {
                if (Double.isNaN(diff[i]) || Double.isInfinite(diff[i])) {
                    continue; // 跳过NaN和Infinity
                }
                
                // 格式化原始数据
                String col1Str = formatColumnValue(col1Raw != null && i < col1Raw.length ? col1Raw[i] : null);
                String col2Str = formatColumnValue(col2Raw != null && i < col2Raw.length ? col2Raw[i] : null);
                String col3Str = col3Name != null ? formatColumnValue(col3Raw != null && i < col3Raw.length ? col3Raw[i] : null) : "";
                
                // 写入数据行：原始数据列、计算差值、排序后的差值（对应有效数据索引位置）
                if (col3Name != null) {
                    writer.printf("%s,%s,%s,%.6f,%.6f%n", col1Str, col2Str, col3Str, diff[i], 
                                validIndex < diffSorted.length ? diffSorted[validIndex] : diff[i]);
                } else {
                    writer.printf("%s,%s,%.6f,%.6f%n", col1Str, col2Str, diff[i], 
                                validIndex < diffSorted.length ? diffSorted[validIndex] : diff[i]);
                }
                validIndex++;
            }
            
            logger.info("已输出约束数据文件: {} ({} 行有效数据)", filePath, diffValid.length);
            
        } catch (Exception e) {
            logger.error("输出约束数据文件 {} 失败: {}", filePath, e.getMessage(), e);
        }
    }
    
    /**
     * 格式化列值为字符串
     */
    private String formatColumnValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        
        if (value instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) value).toString();
        } else if (value instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) value).toString();
        } else if (value instanceof String) {
            return (String) value;
        } else {
            return value.toString();
        }
    }

    /**
     * 递归查找并输出所有ACC约束的参数值（用于诊断）
     */
    private void logLoadedAccParameters(List<ConstraintChain> chains, String schemaName) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("加载的ACC参数值（表: {}）", schemaName);
        logger.info("═══════════════════════════════════════════════════════════════");
        
        int accCount = 0;
        for (ConstraintChain chain : chains) {
            for (ConstraintChainNode node : chain.getNodes()) {
                if (node instanceof ConstraintChainFilterNode filterNode) {
                    accCount = collectAndLogAccParameters(filterNode.getRoot(), accCount);
                }
            }
        }
        
        if (accCount == 0) {
            logger.info("未找到ACC约束");
        }
        logger.info("═══════════════════════════════════════════════════════════════");
    }

    /**
     * 递归收集并输出ACC参数
     */
    private int collectAndLogAccParameters(BoolExprNode node, int accIndex) {
        if (node instanceof MultiVarFilterOperation accOp) {
            Parameter param = accOp.getParameters().getFirst();
            String arithmeticTreeStr = accOp.getArithmeticTree().toString();
            long paramData = param.getData();
            String paramDataValue = param.getDataValue();
            long paramDays = paramData / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            double paramValue = (double) paramData / CommonUtils.SAMPLE_DOUBLE_PRECISION;
            
            logger.info("ACC[{}]: 表达式: {}, 操作符: {}, 参数ID: {}, data: {}, dataValue: \"{}\", 天数: {}, 数值: {}", 
                       accIndex, arithmeticTreeStr, accOp.getOperator().toString(), param.getId(), 
                       paramData, paramDataValue, paramDays, paramValue);
            return accIndex + 1;
        } else if (node instanceof LogicNode logicNode) {
            for (BoolExprNode child : logicNode.getChildren()) {
                accIndex = collectAndLogAccParameters(child, accIndex);
            }
        }
        return accIndex;
    }
}
