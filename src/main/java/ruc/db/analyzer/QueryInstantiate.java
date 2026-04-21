package ruc.db.analyzer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.LanguageManager;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.ConstraintChainManager;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.filter.arithmetic.ArithmeticNode;
import ruc.db.generator.constraintchain.filter.operation.AbstractFilterOperation;
import ruc.db.generator.constraintchain.filter.operation.MultiVarFilterOperation;
import ruc.db.generator.constraintchain.filter.operation.UniVarFilterOperation;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.Distribution;
import ruc.db.utils.CommonUtils;
import static ruc.db.utils.CommonUtils.matchPattern;
import picocli.CommandLine;

@CommandLine.Command(name = "instantiate", description = "instantiate the query", mixinStandardHelpOptions = true)
public class QueryInstantiate implements Callable<Integer> {

    private static final Pattern PATTERN = Pattern.compile("'Mirage#(\\d+)'");
    private static final String WORKLOAD_DIR = "/workload";
    private static final String QUERIES = "/queries";
    private final Logger logger = LoggerFactory.getLogger(QueryInstantiate.class);
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    @CommandLine.Option(names = {"-c", "--config_path"}, required = true, description = "the config path for instantiating query ")
    private String configPath;
    @CommandLine.Option(names = {"-s", "--sampling_size"}, defaultValue = "4000000", description = "samplingSize")
    private String samplingSize;
    @CommandLine.Option(names = {"--statistics"}, description = "path to enhanced_column_statistics.json for CDF-based instantiation")
    private String statisticsPath;
    private Map<String, List<ConstraintChain>> query2constraintChains;

    @Override
    public Integer call() throws IOException {
        init();
        
        // ★★★ 在开始实例化之前，删除旧的 cdfConstraints.json 文件，确保从干净状态开始 ★★★
        try {
            File distributionDir = new File(configPath + "/distribution");
            File cdfConstraintsFile = new File(distributionDir, "cdfConstraints.json");
            if (cdfConstraintsFile.exists()) {
                if (cdfConstraintsFile.delete()) {
                    logger.info("已删除旧的 cdfConstraints.json 文件: {}", cdfConstraintsFile.getAbsolutePath());
                } else {
                    logger.warn("无法删除旧的 cdfConstraints.json 文件: {}", cdfConstraintsFile.getAbsolutePath());
                }
            } else {
                logger.debug("cdfConstraints.json 文件不存在，无需删除");
            }
        } catch (Exception e) {
            logger.warn("删除 cdfConstraints.json 文件时出错: {}", e.getMessage());
        }
        
        Map<String, String> queryName2QueryTemplates = getQueryName2QueryTemplates();
        logger.info(rb.getString("StartInstantiatingTheQueryPlan"));
        
        // 如果提供了统计信息路径，使用基于 CDF 的实例化方法
        if (statisticsPath != null && !statisticsPath.isEmpty()) {
            logger.info("使用统计信息文件: {}", statisticsPath);
        }
        
        List<ConstraintChain> allConstraintChains = query2constraintChains.values().stream().flatMap(Collection::stream).toList();
        Map<Integer, Parameter> id2Parameter = queryInstantiation(allConstraintChains, Integer.parseInt(samplingSize), statisticsPath);
        logger.info(rb.getString("TheInstantiatedQueryPlanSucceed"), id2Parameter.values());
        logger.info(rb.getString("StartPersistentQueryPlanWithNewDataDistribution"));
        ConstraintChainManager.getInstance().storeConstraintChain(query2constraintChains);
        ColumnManager.getInstance().storeColumnDistribution();

        // 保存CDF约束信息，用于数据生成阶段调整频率分布
        try {
            ColumnManager.getInstance().saveCdfConstraints();
        } catch (IOException e) {
            logger.error("保存CDF约束信息失败: {}", e.getMessage(), e);
        }

        logger.info(rb.getString("PersistentQueryPlanCompleted"));
        logger.info(rb.getString("StartPopulatingTheQueryTemplate"));
        writeQuery(queryName2QueryTemplates, id2Parameter);
        logger.info(rb.getString("FillInTheQueryTemplateComplete"));
        if (!id2Parameter.isEmpty()) {
            logger.info(rb.getString("TheParametersThatWereNotSuccessfullyReplaced"), id2Parameter.values());
        }
        return null;
    }

    private static List<List<AbstractFilterOperation>> pushDownProbability(List<ConstraintChain> constraintChains) {
        return constraintChains.stream()
                .map(ConstraintChain::getNodes)
                .flatMap(Collection::stream)
                .filter(ConstraintChainFilterNode.class::isInstance)
                .map(ConstraintChainFilterNode.class::cast)
                .map(ConstraintChainFilterNode::pushDownProbability).toList();
    }

    private static void applyUniVarConstraints(List<AbstractFilterOperation> filterOperations, String statisticsPath) {
        // 【新增】加载统计信息并构建 CDF（查询实例化阶段，不应用约束）
        if (statisticsPath != null && !statisticsPath.isEmpty()) {
            try {
                ColumnManager.getInstance().loadStatisticsAndBuildCDF(statisticsPath, false);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load statistics from: " + statisticsPath, e);
            }
        }
        
        // 原有逻辑：排序 + 应用约束
        List<UniVarFilterOperation> uniFilters = filterOperations.stream()
                .filter(UniVarFilterOperation.class::isInstance)
                .sorted(Comparator.comparing(AbstractFilterOperation::getProbability))
                .map(UniVarFilterOperation.class::cast).toList();
        uniFilters.stream()
                .filter(uniFilter -> !uniFilter.getOperator().isEqual())
                .forEach(UniVarFilterOperation::applyConstraint);
        uniFilters.stream()
                .filter(uniFilter -> uniFilter.getOperator().isEqual())
                .filter(uniFilter -> !uniFilter.getOperator().isMultiEqual())
                .forEach(UniVarFilterOperation::applyConstraint);
        uniFilters.stream()
                .filter(uniFilter -> uniFilter.getOperator().isEqual())
                .filter(uniFilter -> uniFilter.getOperator().isMultiEqual())
                .forEach(UniVarFilterOperation::applyConstraint);
        
        // 【修改】对于使用 CDF 的列，参数已经被直接设置为实际值
        // initAllParameters() 只需要处理未使用 CDF 的列（回退到原始 bin-packing 的列）
        // 这些列仍然使用虚拟 dataIndex，需要转换为实际值
        ColumnManager.getInstance().initAllParametersWithCDFSupport();

        // 修正>=和<的参数边界
        // 注意：使用 CDF 的参数已经是实际值，不需要边界调整
        // 只有使用原始方法（dataIndex）的参数才需要 +1 调整
        uniFilters.forEach(UniVarFilterOperation::amendParameters);
    }

    private static List<List<AbstractFilterOperation>> getBoundOperations(List<List<AbstractFilterOperation>> allFilterOperations) {
        List<List<AbstractFilterOperation>> boundOperations = new ArrayList<>();
        for (List<AbstractFilterOperation> filterOperation : allFilterOperations) {
            var validOperations = new ArrayList<>(filterOperation.stream()
                    .filter(node -> node.getProbability().compareTo(BigDecimal.ONE) != 0)
                    .filter(node -> node.getProbability().compareTo(BigDecimal.ZERO) != 0).toList());
            if (validOperations.size() > 1) {
                // 禁止bound operation的in算子内的参数重用
                for (AbstractFilterOperation validOperation : validOperations) {
                    if (validOperation.getOperator().isMultiEqual()) {
                        for (Parameter parameter : validOperation.getParameters()) {
                            parameter.setCanMerge(false);
                        }
                    }
                }
                boundOperations.add(validOperations);
            }
        }
        return boundOperations;
    }

    private static void applyMultiVarConstraints(List<AbstractFilterOperation> filterOperations, int samplingSize) {
        // multi-var non-eq sampling
        // 1. 收集需要采样的列（ACC 涉及的列）
        Set<String> prepareSamplingColumnName = filterOperations.parallelStream()
                .filter(MultiVarFilterOperation.class::isInstance)
                .map(MultiVarFilterOperation.class::cast)
                .map(MultiVarFilterOperation::getAllCanonicalColumnNames)
                .flatMap(Collection::stream).collect(Collectors.toSet());

        
        ColumnManager.getInstance().cacheAttributeColumn(prepareSamplingColumnName);
        
        // ★★★ 关键修复：设置 ArithmeticNode.size，用于采样数据生成 ★★★
        ArithmeticNode.setSize(samplingSize);
        
        // 3. 生成列的采样数据
        // ★★★ 注意：对于有统计信息的情况，prepareGeneration 会生成全表数据
        // 但在 calculate() 时，如果有统计信息且 sampleSize > 0，会使用新的采样方法
        ColumnManager.getInstance().prepareGeneration(samplingSize);
        
        // 4. 基于采样数据求解 ACC 参数
        filterOperations.parallelStream()
                .filter(MultiVarFilterOperation.class::isInstance)
                .map(MultiVarFilterOperation.class::cast)
                .forEach(MultiVarFilterOperation::instantiateMultiVarParameter);
    }

    // 绑定参数
    private static void boundParas(List<List<AbstractFilterOperation>> boundFilterOperations) {
        Logger logger = LoggerFactory.getLogger(QueryInstantiate.class);
        Set<TreeMap<String, Long>> allColumn2Bounds = new HashSet<>();
        // 收集需要绑定的列和对应的 dataIndex
        for (List<AbstractFilterOperation> boundFilterOperation : boundFilterOperations) {
            TreeMap<String, Long> column2Bound = new TreeMap<>();
            for (AbstractFilterOperation validOperation : boundFilterOperation) {
                String columnName = ((UniVarFilterOperation) validOperation).getCanonicalColumnName();
                var dataIndexes = validOperation.getParameters().stream()
                        .map(Parameter::getData).collect(Collectors.toSet());
                if (dataIndexes.size() > 1) {
                    throw new UnsupportedOperationException("暂时不支持多参数的bound");
                }
                long dataIndex = new ArrayList<>(dataIndexes).get(0);
                column2Bound.put(columnName, dataIndex);
                logger.info("🔗 BOUND DEBUG: 识别bound列 columnName={}, dataIndex={}", columnName, dataIndex);
            }
            allColumn2Bounds.add(column2Bound);
        }

        logger.info("🔗 BOUND DEBUG: 开始处理 {} 组bound操作", allColumn2Bounds.size());
        int boundGroupIndex = 0;
        for (TreeMap<String, Long> allColumn2Bound : allColumn2Bounds) {
            logger.info("🔗 BOUND DEBUG: ===== Bound Group {} =====", boundGroupIndex);
            BigDecimal offset = BigDecimal.ZERO;
            for (Map.Entry<String, Long> column2Bound : allColumn2Bound.entrySet()) {
                Distribution distribution = ColumnManager.getInstance().getColumn(column2Bound.getKey()).getDistribution();
                BigDecimal colOffset = distribution.getOffset(column2Bound.getValue());
                logger.info("🔗 BOUND DEBUG: 列 {} 的dataIndex {} 对应offset {}", 
                    column2Bound.getKey(), column2Bound.getValue(), colOffset);
                offset = offset.max(colOffset);
            }
            logger.info("🔗 BOUND DEBUG: Bound Group {} 选中最大offset: {}", boundGroupIndex, offset);
            
            // ★★★ 关键修复：为每个BoundGroup创建全局TableBoundInfo，确保多列对齐 ★★★
            // 创建全局的TableBoundInfo，由同一个BoundGroup中的所有列共享
            ruc.db.schema.TableBoundInfo globalTableBoundInfo = new ruc.db.schema.TableBoundInfo();
            ColumnManager.getInstance().setBoundGroupTableBoundInfo(boundGroupIndex, globalTableBoundInfo);
            logger.info("🔗 BOUND DEBUG: 为 Bound Group {} 创建全局 TableBoundInfo", boundGroupIndex);
            
            // 为每个bound列设置offset2Pv和引用全局TableBoundInfo
            for (Map.Entry<String, Long> column2Bound : allColumn2Bound.entrySet()) {
                Distribution distribution = ColumnManager.getInstance().getColumn(column2Bound.getKey()).getDistribution();
                distribution.getOffset2Pv().put(offset, column2Bound.getValue());
                logger.info("🔗 BOUND DEBUG: 为列 {} 设置 offset2Pv: {} → {}", 
                    column2Bound.getKey(), offset, column2Bound.getValue());
                
                // 所有列引用同一个全局TableBoundInfo，确保bound行对齐
                distribution.setTableBoundInfo(globalTableBoundInfo);
                distribution.setBoundGroupId(boundGroupIndex);
                logger.info("🔗 BOUND DEBUG: 列 {} 绑定到 Bound Group {}", column2Bound.getKey(), boundGroupIndex);
            }
            boundGroupIndex++;
        }
    }

    /**
     * 1. 对于数值型的filter, 首先计算单元的filter, 然后计算多值的filter，对于bet操作，先记录阈值，然后选择合适的区间插入，
     * 等值约束也需选择合适的区间每个filter operation内部保存自己实例化后的结果
     * 2. 对于字符型的filter, 只有like和eq的运算，直接计算即可
     *
     * @param constraintChains 待计算的约束链
     * @param samplingSize     采样大小
     * @param statisticsPath   统计信息 JSON 文件路径（可选，如果为 null 则使用原始方法）
     */
    public static Map<Integer, Parameter> queryInstantiation(List<ConstraintChain> constraintChains, 
                                                              int samplingSize, 
                                                              String statisticsPath) {
        // 1. 解耦 LCCs
        var allFilterOperations = pushDownProbability(constraintChains);
        var boundFilterOperations = getBoundOperations(allFilterOperations);

        List<AbstractFilterOperation> filterOperations = allFilterOperations.stream().flatMap(Collection::stream).toList();

        // 2. 求解 UCCs
        applyUniVarConstraints(filterOperations, statisticsPath);
        
        // 3. 
        boundParas(boundFilterOperations);

        // 处理多值绑定的情况 + 处理 ACCs
        applyMultiVarConstraints(filterOperations, samplingSize);
        
        // ★★★ 新增：清理 ACC 采样数据缓存，为下一次查询做准备 ★★★
        ColumnManager.getInstance().clearAccSampleDataCache();

        Map<Integer, Parameter> id2Parameter = new HashMap<>();
        filterOperations.stream().map(AbstractFilterOperation::getParameters).flatMap(Collection::stream)
                .forEach(parameter -> id2Parameter.put(parameter.getId(), parameter));
        return id2Parameter;
    }
    
    /**
     * 向后兼容的方法（不使用统计信息）
     */
    public static Map<Integer, Parameter> queryInstantiation(List<ConstraintChain> constraintChains, int samplingSize) {
        return queryInstantiation(constraintChains, samplingSize, null);
    }

    private void init() throws IOException {
        ColumnManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnMetaData();
        //载入约束链，并进行transform
        ConstraintChainManager.getInstance().setResultDir(configPath);
        ColumnManager.getInstance().loadColumnName2IdList();
        query2constraintChains = ConstraintChainManager.loadConstrainChainResult(configPath);
    }

    public void writeQuery(Map<String, String> queryName2QueryTemplates, Map<Integer, Parameter> id2Parameter) throws IOException {
        for (Map.Entry<String, String> queryName2QueryTemplate : queryName2QueryTemplates.entrySet()) {
            String query = queryName2QueryTemplate.getValue();
            File queryPath = new File(configPath + QUERIES);
            if (!queryPath.exists()) {
                queryPath.mkdir();
            }
            String path = configPath + QUERIES + '/' + queryName2QueryTemplate.getKey();
            List<List<String>> matches = matchPattern(PATTERN, query);
            if (matches.isEmpty()) {
                CommonUtils.writeFile(path, query);
            } else {
                for (List<String> group : matches) {
                    int parameterId = Integer.parseInt(group.get(1));
                    Parameter parameter = id2Parameter.remove(parameterId);
                    if (parameter != null) {
                        String parameterData = parameter.getDataValue();
                        try {
                            if (parameterData.contains("interval")) {
                                query = query.replaceAll(group.get(0), String.format("%s", parameterData));
                            } else {
                                query = query.replaceAll(group.get(0), String.format("'%s'", parameterData));
                            }
                        } catch (IllegalArgumentException e) {
                            logger.error("query is " + query + "; group is " + group + "; parameter data is " + parameterData, e);
                        }
                    }
                    CommonUtils.writeFile(path, query);
                }
            }
        }
    }

    private Map<String, String> getQueryName2QueryTemplates() throws IOException {
        String path = configPath + WORKLOAD_DIR;
        File sqlDic = new File(path);
        File[] sqlArray = sqlDic.listFiles();
        assert sqlArray != null;
        Map<String, String> queryName2QueryTemplates = new HashMap<>();
        for (File file : sqlArray) {
            if (!file.isDirectory()) {
                continue;
            }
            File[] eachFile = file.listFiles();
            assert eachFile != null;
            for (File sqlTemplate : eachFile) {
                if (sqlTemplate.getName().contains("Template")) {
                    String key = sqlTemplate.getName().replace("Template", "");
                    StringBuilder buffer = new StringBuilder();
                    try (BufferedReader bf = new BufferedReader(new FileReader(sqlTemplate.getPath()))) {
                        String s;
                        while ((s = bf.readLine()) != null) {//使用readLine方法，一次读一行
                            buffer.append(s.trim()).append("\n");
                        }
                    }
                    String value = buffer.toString();
                    queryName2QueryTemplates.put(key, value);
                }
            }
        }
        return queryName2QueryTemplates;
    }
}
