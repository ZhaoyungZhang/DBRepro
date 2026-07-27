package ruc.db.analyzer;

import ruc.db.LanguageManager;
import ruc.db.analyzer.online.AbstractAnalyzer;
import ruc.db.analyzer.online.QueryAnalyzer;
import ruc.db.analyzer.online.adapter.pg.PgAnalyzer;
import ruc.db.analyzer.online.adapter.pg.PgJsonReader;
import ruc.db.analyzer.online.adapter.tidb.TidbAnalyzer;
import ruc.db.analyzer.statical.QueryReader;
import ruc.db.analyzer.statical.QueryWriter;
import ruc.db.dbconnector.DbConnector;
import ruc.db.dbconnector.adapter.*;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.ConstraintChainManager;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.schema.ColumnStatistics;
import ruc.db.schema.ColumnStatisticsManager;
import ruc.db.utils.CommonUtils;
import ruc.db.utils.DatabaseConnectorConfig;
import ruc.db.utils.exception.TouchstoneException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.DbType;

import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static ruc.db.utils.CommonUtils.MAPPER;

@CommandLine.Command(name = "RSGEN-EXTRACT",
        description = "Extract full schema and pg_stats column-level statistics for all tables",
        mixinStandardHelpOptions = true, usageHelpAutoWidth = true)
public class SchemaStatsExtractor implements Callable<Integer> {
    public static final String SQL_FILE_POSTFIX = ".sql";
    private final Logger logger = LoggerFactory.getLogger(TaskConfigurator.class);
    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    private TaskConfiguratorConfig taskConfiguratorConfig;
    private static final String WORKLOAD_DIR = "/workload";
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    private static final HashMap<String, Map<String, List<Integer>>> columnName2ParameterID = new HashMap<>();

    @Override
    public Integer call() throws Exception {
        ruc.db.utils.TaskConfiguratorConfig config;
        if (taskConfiguratorConfig.othersConfig.fileConfigInfo != null) {
            config = MAPPER.readValue(CommonUtils.readFile(taskConfiguratorConfig.othersConfig.fileConfigInfo.configPath),
                    ruc.db.utils.TaskConfiguratorConfig.class);
        } else {
            CliConfigInfo cliConfigInfo = taskConfiguratorConfig.othersConfig.cliConfigInfo;
            config = new ruc.db.utils.TaskConfiguratorConfig();
            config.setDatabaseConnectorConfig(new DatabaseConnectorConfig(cliConfigInfo.databaseIp, cliConfigInfo.databasePort,
                    cliConfigInfo.databaseUser, cliConfigInfo.databasePwd, cliConfigInfo.databaseName));
        }
        QueryReader queryReader = new QueryReader(config.getDefaultSchemaName(), config.getQueriesDirectory());
        QueryWriter queryWriter = new QueryWriter();
        TableManager.getInstance().setResultDir(config.getResultDirectory());
        ColumnManager.getInstance().setResultDir(config.getResultDirectory());
        File resultDir = new File(config.getResultDirectory());
        if (!resultDir.exists() || !resultDir.isDirectory()) {
            if (resultDir.mkdirs()) {
                logger.info(rb.getString("createResultDir"), config.getResultDirectory());
            } else {
                logger.error(rb.getString("createResultDirFail"), config.getResultDirectory());
                System.exit(-1);
            }
        }
        ConstraintChainManager.getInstance().setResultDir(config.getResultDirectory());
        if (taskConfiguratorConfig.isLoad) {
            TableManager.getInstance().loadSchemaInfo();
            ColumnManager.getInstance().loadColumnMetaData();
        }
        DbConnector dbConnector;
        switch (taskConfiguratorConfig.dbType) {
            case TIDB3 -> {
                dbConnector = new Tidb3Connector(config.getDatabaseConnectorConfig());
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
            }
            case TIDB4 -> {
                dbConnector = new Tidb4Connector(config.getDatabaseConnectorConfig());
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
            }
            case GAUSS -> {
                dbConnector = new GaussConnector(config.getDatabaseConnectorConfig());
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
                PgJsonReader.setIsGauss();
            }
            case POSTGRESQL -> {
                dbConnector = new PgConnector(config.getDatabaseConnectorConfig());
                queryWriter.setDbType(DbType.postgresql);
                queryReader.setDbType(DbType.postgresql);
            }
            default -> throw new TouchstoneException(rb.getString("UnsupportedDatabaseType"));
        }
        // QueryAnalyzer analyzer = new QueryAnalyzer(abstractAnalyzer, dbConnector);
        //extract(dbConnector, analyzer, queryReader, queryWriter, config.getResultDirectory());
        // 直接调用这个就行了
        querySchemaMetadataAndColumnStatistics(dbConnector, config.getResultDirectory());
        return 0;
    }

    public void dealWithUnknownTable(Set<String> unKnownCols, Table table,
                                     String canonicalTableName,
                                     Map<String, Set<String>> tableName2Columns) {
        for (String unKnownCol : unKnownCols) {
            String completeCol = canonicalTableName + "." + unKnownCol.split("\\.")[2];
            if (table.containColumn(completeCol)) {
                tableName2Columns.putIfAbsent(canonicalTableName, new HashSet<>());
                tableName2Columns.get(canonicalTableName).add(completeCol);
            }
        }
    }


    private List<File> querySchemaMetadataAndColumnMetadata(QueryReader queryReader, DbConnector dbConnector)
            throws IOException, TouchstoneException, SQLException {
        List<File> queryFiles = queryReader.loadQueryFiles();
        List<String> tableNames = queryReader.fetchTableNames(queryFiles);
        Map<String, Set<String>> tableName2Columns = queryReader.fetchQueryColumnNames(queryFiles);
        Set<String> unKnownCols = new HashSet<>();
        for (String s : new HashSet<>(tableName2Columns.keySet())) {
            if (s.split("\\.")[1].equals("UNKNOWN")) {
                unKnownCols = tableName2Columns.remove(s);
            }
        }
        logger.info(rb.getString("GetTableNameSuccessfully"), tableNames);
        for (String canonicalTableName : tableNames) {
            logger.info(rb.getString("StartGettingColumnMetadata"), canonicalTableName);
            if (TableManager.getInstance().containSchema(canonicalTableName)) {
                logger.info(rb.getString("ColumnMetadataHasLoaded"), canonicalTableName);
            } else {
                Table table = new Table(dbConnector.getColumnMetadata(canonicalTableName),
                        dbConnector.getTableSize(canonicalTableName));
                table.setPrimaryKeys(dbConnector.getPrimaryKey(canonicalTableName));
                dealWithUnknownTable(unKnownCols, table, canonicalTableName, tableName2Columns);
                TableManager.getInstance().addSchema(canonicalTableName, table);
                logger.info(rb.getString("GetColumnMetadataSuccessfully"), canonicalTableName);
                Set<String> involvedCols = tableName2Columns.get(canonicalTableName);
                List<String> allColumns = TaskConfigurator.resolveDistributionColumns(
                        canonicalTableName, table, involvedCols, logger);
                ColumnManager.getInstance().setDataRangeBySqlResult(allColumns,
                        dbConnector.getDataRange(canonicalTableName, allColumns));
                logger.info(rb.getString("GetTheDataDistributionOfTableSuccessfully"), canonicalTableName);
            }

        }
        logger.info(rb.getString("ObtainTableStructureAndDataDistributionSuccessfully"));
        logger.info(rb.getString("StartPersistingTableStructureInformation"));
        TableManager.getInstance().storeSchemaInfo();
        logger.info(rb.getString("PersistenceOfTableStructureInformationSucceeded"));
        logger.info(rb.getString("StartPersistingDataDistributionInformation"));
        ColumnManager.getInstance().storeColumnMetaData();
        logger.info(rb.getString("PersistentDataDistributionInformationSucceeded"));
        return queryFiles;
    }

    private List<File> querySchemaMetadataAndColumnStatistics(DbConnector dbConnector, String resultDir)
            throws IOException, TouchstoneException, SQLException {
        // 不再依赖查询文件，直接获取数据库所有表
        logger.info("开始获取数据库所有表信息");
        List<String> allTableNames = dbConnector.getAllTableNames();
        logger.info("发现数据库表: {}", allTableNames);
        
        for (String canonicalTableName : allTableNames) {
            logger.info(rb.getString("StartGettingColumnMetadata"), canonicalTableName);
            if (TableManager.getInstance().containSchema(canonicalTableName)) {
                logger.info(rb.getString("ColumnMetadataHasLoaded"), canonicalTableName);
            } else {
                // 获取表的列元数据
                List<String> columnNames = dbConnector.getColumnMetadata(canonicalTableName);
                Table table = new Table(columnNames, dbConnector.getTableSize(canonicalTableName));
                
                // 获取主键信息
                List<String> primaryKeys = dbConnector.getPrimaryKey(canonicalTableName);
                table.setPrimaryKeys(primaryKeys);
                
                // 获取外键信息
                Map<String, String> foreignKeys = dbConnector.getForeignKeys(canonicalTableName);
                table.setForeignKeys(foreignKeys);
                
                TableManager.getInstance().addSchema(canonicalTableName, table);
                logger.info(rb.getString("GetColumnMetadataSuccessfully"), canonicalTableName);
                
                // 获取非主外键列的统计信息
                List<String> normalColumns = new ArrayList<>();
                for (String columnName : columnNames) {
                    // 跳过主键和外键列
                    if (!primaryKeys.contains(columnName) && !foreignKeys.containsKey(columnName)) {
                        normalColumns.add(columnName);
                    }
                }
                
                if (!normalColumns.isEmpty()) {
                    logger.info("开始获取表 {} 的 {} 个普通列的统计信息", canonicalTableName, normalColumns.size());
                    List<ColumnStatistics> columnStats = dbConnector.getColumnDataStatistics(canonicalTableName, normalColumns);
                    ColumnStatisticsManager.getInstance().addTableColumnStatistics(canonicalTableName, columnStats);
                    logger.info("表 {} 的统计信息获取完成，共 {} 列", canonicalTableName, columnStats.size());
                }
            }
        }
        
        // 存储统计信息到JSON文件
        storeColumnStatisticsToJson(resultDir);
        logger.info("存储统计信息完成");

        logger.info(rb.getString("ObtainTableStructureAndDataDistributionSuccessfully"));
        logger.info(rb.getString("StartPersistingTableStructureInformation"));
        TableManager.getInstance().storeSchemaInfo();
        logger.info(rb.getString("PersistenceOfTableStructureInformationSucceeded"));

        return new ArrayList<>(); // 返回空列表，因为不需要查询文件
    }

    /**
     * 将列统计信息存储为JSON文件
     */
    private void storeColumnStatisticsToJson(String resultDir) throws IOException {
        String statsFilePath = resultDir + "/column_statistics.json";
        
        // 从管理器获取所有统计信息
        Map<String, List<ColumnStatistics>> allTableStats = ColumnStatisticsManager.getInstance().getAllTableColumnStatistics();
        
        // 构造完整的JSON结构
        Map<String, Object> jsonData = new HashMap<>();
        jsonData.put("timestamp", new Date().toString());
        jsonData.put("description", "PostgreSQL column statistics from pg_stats for all tables");
        
        // 添加字段说明
        Map<String, String> fieldDescriptions = new HashMap<>();
        fieldDescriptions.put("null_fraction", "Fraction of entries that are NULL (0.0 to 1.0)");
        fieldDescriptions.put("n_distinct_values", "Number of distinct values (NDV). -1 means all values are distinct, positive numbers indicate estimated distinct values");
        fieldDescriptions.put("avg_width_bytes", "Average width of column values in bytes");
        fieldDescriptions.put("most_common_values", "Array of most common values (MCV) in the column");
        fieldDescriptions.put("most_common_frequencies", "Array of frequencies for the most common values (MCF)");
        fieldDescriptions.put("histogram_bounds", "Bounds of histogram buckets for equi-depth distribution analysis");
        
        jsonData.put("statistics_field_descriptions", fieldDescriptions);
        
        // 构造表和列的统计信息
        Map<String, Object> tablesData = new HashMap<>();
        
        for (Map.Entry<String, List<ColumnStatistics>> entry : allTableStats.entrySet()) {
            String tableName = entry.getKey();
            List<ColumnStatistics> columnStatsList = entry.getValue();
            
            Map<String, Object> tableInfo = new HashMap<>();
            tableInfo.put("table_name", tableName);
            tableInfo.put("column_count", columnStatsList.size());
            
            List<Map<String, Object>> columnsData = new ArrayList<>();
            
            for (ColumnStatistics stats : columnStatsList) {
                Map<String, Object> columnData = new HashMap<>();
                columnData.put("column_name", stats.getColumnName());
                
                // 构造统计信息对象
                Map<String, Object> statistics = new HashMap<>();
                statistics.put("null_fraction", stats.getNullFraction());
                statistics.put("n_distinct_values", stats.getNDistinct());
                statistics.put("avg_width_bytes", stats.getAvgWidth());
                statistics.put("most_common_values", stats.getMostCommonVals());
                statistics.put("most_common_frequencies", stats.getMostCommonFreqs());
                statistics.put("histogram_bounds", stats.getHistogramBounds());
                
                columnData.put("statistics", statistics);
                columnsData.add(columnData);
            }
            
            tableInfo.put("columns", columnsData);
            tablesData.put(tableName, tableInfo);
        }
        
        jsonData.put("tables", tablesData);
        
        // 添加统计摘要
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_tables", allTableStats.size());
        summary.put("total_columns", allTableStats.values().stream().mapToInt(List::size).sum());
        summary.put("extraction_summary", ColumnStatisticsManager.getInstance().getStatisticsSummary());
        
        jsonData.put("summary", summary);
        
        // 使用已有的MAPPER写入JSON
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(statsFilePath), jsonData);
        logger.info("列统计信息已保存到文件: {}", statsFilePath);
    }

    public Map<String, List<ConstraintChain>> checkQueryConstraintChains(Map<String, List<ConstraintChain>> query2constraintChains) {
        logger.info(rb.getString("StartCleaningUpTheQueryPlan"));
        for (Map.Entry<String, List<ConstraintChain>> query2constrainChain : query2constraintChains.entrySet()) {
            List<ConstraintChain> constraintChains = query2constrainChain.getValue();
            List<ConstraintChain> reduceConstraintChains = new LinkedList<>();
            for (ConstraintChain constraintChain : constraintChains) {
                // 移除带有键值的过滤条件
                if (constraintChain.getNodes().stream()
                        .filter(ConstraintChainFilterNode.class::isInstance)
                        .map(ConstraintChainFilterNode.class::cast)
                        .anyMatch(ConstraintChainFilterNode::hasKeyColumn)) {
                    reduceConstraintChains.add(constraintChain);
                } else {
                    List<ConstraintChainAggregateNode> aggregateNodes = constraintChain.getNodes().stream()
                            .filter(ConstraintChainAggregateNode.class::isInstance)
                            .map(ConstraintChainAggregateNode.class::cast)
                            .filter(ConstraintChainAggregateNode::removeAgg).toList();
                    if (!aggregateNodes.isEmpty()) {
                        logger.info(rb.getString("RemoveSomeAggregationNodeOnAttributes"), query2constrainChain.getKey(), aggregateNodes);
                        constraintChain.getNodes().removeAll(aggregateNodes);
                    }
                }
            }
            constraintChains.removeAll(reduceConstraintChains);
            if (!reduceConstraintChains.isEmpty()) {
                logger.error(rb.getString("RemoveSomeChainsWithFilterOnKeysFrom"), query2constrainChain.getKey());
                String reduceChains = reduceConstraintChains.stream()
                        .map(ConstraintChain::toString)
                        .collect(Collectors.joining(System.lineSeparator()));
                logger.error(reduceChains);
            }
            constraintChains.removeIf(constraintChain -> constraintChain.getNodes().isEmpty());
        }
        logger.info(rb.getString("CleanupQueryPlanCompleted"));
        return query2constraintChains;
    }

    public void extract(DbConnector dbConnector, QueryAnalyzer queryAnalyzer, QueryReader queryReader,
                        QueryWriter queryWriter, String resultDir) throws IOException, TouchstoneException, SQLException {
        List<File> queryFiles = querySchemaMetadataAndColumnMetadata(queryReader, dbConnector);
        Map<String, List<ConstraintChain>> query2constraintChains = new HashMap<>();
        Map<String, String> queryName2QueryTemplates = new HashMap<>();
        logger.info(rb.getString("StartGettingQueryPlans"));
        queryFiles = queryFiles.stream().filter(File::isFile)
                .filter(queryFile -> queryFile.getName().endsWith(SQL_FILE_POSTFIX)).toList();
        queryFiles = new LinkedList<>(queryFiles);
        queryFiles.sort(Comparator.comparing(File::getName));
        for (File queryFile : queryFiles) {
            List<String> queries = queryReader.getQueriesFromFile(queryFile.getPath());
            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                String queryCanonicalName = queryFile.getName().replace(SQL_FILE_POSTFIX, "_" + (i + 1) + SQL_FILE_POSTFIX);
                logger.info(rb.getString("StartGetting"), queryCanonicalName);
                queryAnalyzer.setAliasDic(queryReader.getTableAlias(query));
                List<Parameter> parameters = new ArrayList<>();
                List<List<ConstraintChain>> constraintChainsOfMultiplePlans = queryAnalyzer.extractQuery(query);
                int subPlanIndex = 0;
                for (List<ConstraintChain> constraintChains : constraintChainsOfMultiplePlans) {
                    if (subPlanIndex++ > 0) {
                        query2constraintChains.put(queryCanonicalName + "_" + subPlanIndex, constraintChains);
                    } else {
                        query2constraintChains.put(queryCanonicalName, constraintChains);
                    }
                    buildColumnName2ParameterID(constraintChains);
                    parameters.addAll(constraintChains.stream().flatMap((c -> c.getParameters().stream())).toList());
                }
                queryName2QueryTemplates.put(queryCanonicalName, queryWriter.templatizeSql(queryCanonicalName, query, parameters));
            }
        }
        writeWithoutParameterValue();
        logger.info(rb.getString("GetQueryPlanDone"));
        logger.info(rb.getString("StartPersistingTableReferenceInformation"));
        TableManager.getInstance().adjustFks();
        TableManager.getInstance().storeSchemaInfo();
        logger.info(rb.getString("PersistentTableReferenceInformationSucceeded"));
        query2constraintChains = checkQueryConstraintChains(query2constraintChains);
        logger.info(rb.getString("StartPersistentQueryPlan"));
        ConstraintChainManager.getInstance().storeConstraintChain(query2constraintChains);
        logger.info(rb.getString("PersistentQueryPlanCompleted"));
        logger.info(rb.getString("StartQueryTemplating"));
        writeTemplateQuery(queryName2QueryTemplates, resultDir);
        logger.info(rb.getString("FillInTheQueryTemplateComplete"));
    }

    private void writeWithoutParameterValue() throws IOException {
        Map<String, List<List<Integer>>> column2IdList = new HashMap<>();
        for (Map.Entry<String, Map<String, List<Integer>>> column2ID : columnName2ParameterID.entrySet()) {
            column2IdList.put(column2ID.getKey(), column2ID.getValue().values().stream().toList());
        }
        ColumnManager.getInstance().storeColumnName2IdList(column2IdList);
    }

    private void buildColumnName2ParameterID(List<ConstraintChain> constraintChains) {
        for (ConstraintChain constraintChain : constraintChains) {
            ConstraintChainNode node = constraintChain.getNodes().get(0);
            if (node instanceof ConstraintChainFilterNode filterNode) {
                filterNode.getRoot().getColumn2ParameterBucket(columnName2ParameterID);
            }
        }
    }

    public void writeTemplateQuery(Map<String, String> queryName2QueryTemplates, String resultDir) throws IOException {
        String path = resultDir + WORKLOAD_DIR;
        File file = new File(path);
        if (!file.exists()) {
            file.mkdir();
        }
        for (Map.Entry<String, String> queryName2QueryTemplate : queryName2QueryTemplates.entrySet()) {
            String currentPath = path + '/' + queryName2QueryTemplate.getKey().split("\\.")[0];
            File currentFile = new File(currentPath);
            if (!currentFile.exists()) {
                currentFile.mkdir();
            }
            String pathOfTemplate = currentPath + '/' + queryName2QueryTemplate.getKey().split("\\.")[0] + "Template.sql";
            CommonUtils.writeFile(pathOfTemplate, queryName2QueryTemplate.getValue());
        }
    }

    static class TaskConfiguratorConfig {
        @CommandLine.ArgGroup
        private OthersConfig othersConfig;
        @CommandLine.Option(names = {"-t", "--db_type"}, required = true, description = "database version: ${COMPLETION-CANDIDATES}")
        private TouchstoneDbType dbType;
        @CommandLine.Option(names = {"-l", "--load"}, description = "load the configuration from the previous result")
        private boolean isLoad;
    }

    static class OthersConfig {
        @CommandLine.ArgGroup(exclusive = false, heading = "Input configuration by file%n")
        FileConfigInfo fileConfigInfo;
        @CommandLine.ArgGroup(exclusive = false, heading = "Input configuration by cli%n")
        CliConfigInfo cliConfigInfo;
    }

    static class FileConfigInfo {
        @CommandLine.Option(names = {"-c", "--config_path"}, required = true, description = "file path to read query instantiation configuration, " +
                "other settings in command line will override the settings in the file")
        private String configPath;

    }

    static class CliConfigInfo {
        @CommandLine.Option(names = {"-H", "--host"}, required = true, defaultValue = "localhost", description = "database ip, default value: '${DEFAULT-VALUE}'")
        private String databaseIp;
        @CommandLine.Option(names = {"-P", "--port"}, required = true, defaultValue = "4000", description = "database port, default value: '${DEFAULT-VALUE}'")
        private String databasePort;
        @CommandLine.Option(names = {"-u", "--user"}, required = true, description = "database user name")
        private String databaseUser;
        @CommandLine.Option(names = {"-p", "--password"}, required = true, description = "database password", interactive = true)
        private String databasePwd;
        @CommandLine.Option(names = {"-D", "--database_name"}, description = "database name")
        private String databaseName;
        @CommandLine.Option(names = {"-q", "--query_input"}, required = true, description = "the dir path of queries")
        private String queriesDirectory;
        @CommandLine.Option(names = {"-o", "--output"}, required = true, description = "the dir path for output")
        private String resultDirectory;
        @CommandLine.Option(names = {"--skip_threshold"}, description = "skip threshold, if passsing this threshold, then we will skip the node")
        private Double skipNodeThreshold;
    }
}
