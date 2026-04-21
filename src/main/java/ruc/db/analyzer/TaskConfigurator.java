package ruc.db.analyzer;

import com.alibaba.druid.DbType;
import ruc.db.LanguageManager;
import ruc.db.analyzer.online.AbstractAnalyzer;
import ruc.db.analyzer.online.QueryAnalyzer;
import ruc.db.analyzer.online.adapter.pg.PgAnalyzer;
import ruc.db.analyzer.online.adapter.pg.PgJsonReader;
import ruc.db.analyzer.online.adapter.tidb.TidbAnalyzer;
import ruc.db.analyzer.statical.QueryReader;
import ruc.db.analyzer.statical.QueryWriter;
import ruc.db.dbconnector.DbConnector;
import ruc.db.dbconnector.adapter.GaussConnector;
import ruc.db.dbconnector.adapter.PgConnector;
import ruc.db.dbconnector.adapter.Tidb3Connector;
import ruc.db.dbconnector.adapter.Tidb4Connector;
import ruc.db.dbconnector.adapter.KingBaseConnector;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.ConstraintChainManager;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.utils.CommonUtils;
import ruc.db.utils.DatabaseConnectorConfig;
import ruc.db.utils.exception.TouchstoneException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static ruc.db.utils.CommonUtils.MAPPER;

/**
 * @author alan
 */

@CommandLine.Command(name = "prepare", description = "extract database information for data generation",
        mixinStandardHelpOptions = true, usageHelpAutoWidth = true)
public class TaskConfigurator implements Callable<Integer> {
    public static final String SQL_FILE_POSTFIX = ".sql";
    private final Logger logger = LoggerFactory.getLogger(TaskConfigurator.class);
    @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
    private TaskConfiguratorConfig taskConfiguratorConfig;
    private static final String WORKLOAD_DIR = "/workload";
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    private static final HashMap<String, Map<String, List<Integer>>> columnName2ParameterID = new HashMap<>();

    /** 合并自配置文件与 CLI 的本地计划路径（CLI 优先） */
    private String effectiveLocalPlanJsonPath;
    private String effectiveLocalPlanJsonDir;

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
        effectiveLocalPlanJsonPath = config.getLocalPlanJson();
        effectiveLocalPlanJsonDir = config.getLocalPlanJsonDir();
        if (taskConfiguratorConfig.getLocalPlanJsonFromCommandLine() != null
                && !taskConfiguratorConfig.getLocalPlanJsonFromCommandLine().isBlank()) {
            effectiveLocalPlanJsonPath = taskConfiguratorConfig.getLocalPlanJsonFromCommandLine();
        }
        if (taskConfiguratorConfig.getLocalPlanJsonDirFromCommandLine() != null
                && !taskConfiguratorConfig.getLocalPlanJsonDirFromCommandLine().isBlank()) {
            effectiveLocalPlanJsonDir = taskConfiguratorConfig.getLocalPlanJsonDirFromCommandLine();
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
        AbstractAnalyzer abstractAnalyzer;
        switch (taskConfiguratorConfig.dbType) {
            case TIDB3 -> {
                dbConnector = new Tidb3Connector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new TidbAnalyzer();
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
            }
            case TIDB4 -> {
                dbConnector = new Tidb4Connector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new TidbAnalyzer();
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
            }
            case GAUSS -> {
                dbConnector = new GaussConnector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new PgAnalyzer();
                queryWriter.setDbType(DbType.mysql);
                queryReader.setDbType(DbType.mysql);
                PgJsonReader.setIsGauss();
            }
            case POSTGRESQL -> {
                dbConnector = new PgConnector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new PgAnalyzer();
                queryWriter.setDbType(DbType.postgresql);
                queryReader.setDbType(DbType.postgresql);
            }
            case KINGBASE -> {
                dbConnector = new KingBaseConnector(config.getDatabaseConnectorConfig());
                abstractAnalyzer = new PgAnalyzer();
                queryWriter.setDbType(DbType.postgresql);
                queryReader.setDbType(DbType.postgresql);
            }
            default -> throw new TouchstoneException(rb.getString("UnsupportedDatabaseType"));
        }
        QueryAnalyzer analyzer = new QueryAnalyzer(abstractAnalyzer, dbConnector);
        extract(dbConnector, analyzer, queryReader, queryWriter, config.getResultDirectory());
        return 0;
    }

    /**
     * 解析当前 SQL 文件对应的本地 EXPLAIN JSON。优先 {@code localPlanJsonDir} 下 {@code <stem>_plan.json}，
     * 否则使用全局 {@code localPlanJsonPath}。
     */
    private String resolveLocalPlanJson(File queryFile) throws IOException {
        if (effectiveLocalPlanJsonDir != null && !effectiveLocalPlanJsonDir.isBlank()) {
            String name = queryFile.getName();
            if (!name.endsWith(SQL_FILE_POSTFIX)) {
                return null;
            }
            String stem = name.substring(0, name.length() - SQL_FILE_POSTFIX.length());
            Path dir = Paths.get(effectiveLocalPlanJsonDir);
            Path p1 = dir.resolve(stem + "_plan.json");
            if (Files.isRegularFile(p1)) {
                return CommonUtils.readFile(p1.toString());
            }
            Path p2 = dir.resolve(stem + ".plan.json");
            if (Files.isRegularFile(p2)) {
                return CommonUtils.readFile(p2.toString());
            }
            logger.info("未在 {} 找到 {} 对应的本地计划（尝试 {} / {}），将使用数据库 EXPLAIN",
                    effectiveLocalPlanJsonDir, name, p1.getFileName(), p2.getFileName());
            return null;
        }
        if (effectiveLocalPlanJsonPath != null && !effectiveLocalPlanJsonPath.isBlank()) {
            return CommonUtils.readFile(effectiveLocalPlanJsonPath);
        }
        return null;
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
        // KingBase 等数据库内部存储的标识符是小写，但 SQL 文本可能是大写
        // 统一将表名和列名转为小写以匹配数据库 metadata
        tableNames = tableNames.stream()
                .map(t -> t.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
        Map<String, Set<String>> rawTable2Columns = queryReader.fetchQueryColumnNames(queryFiles);
        Map<String, Set<String>> tableName2Columns = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : rawTable2Columns.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            Set<String> lowerCols = entry.getValue().stream()
                    .map(c -> c.toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.toCollection(HashSet::new));
            tableName2Columns.merge(lowerKey, lowerCols, (a, b) -> { a.addAll(b); return a; });
        }
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
                logger.info(rb.getString("StartGettingTheDataDistributionOfTable"), canonicalTableName);
                // fetchTableNames 含 FROM/JOIN 中所有表；fetchQueryColumnNames 仅来自 WHERE/JOIN ON 等 condition，
                // 若某表只出现在 SELECT/GROUP BY 等或解析未归入该表键，此处 get 会为 null，需回退为全表列。
                Set<String> involvedCols = tableName2Columns.get(canonicalTableName);
                List<String> allColumns;
                if (involvedCols == null || involvedCols.isEmpty()) {
                    logger.warn("表 {} 未出现在查询条件解析的列映射中，使用全表列估算数据分布", canonicalTableName);
                    allColumns = new ArrayList<>(table.getCanonicalColumnNames());
                } else {
                    allColumns = involvedCols.stream().toList();
                }
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

    public Map<String, List<ConstraintChain>> checkQueryConstraintChains(Map<String, List<ConstraintChain>> query2constraintChains) {
        logger.info(rb.getString("StartCleaningUpTheQueryPlan"));
        for (Map.Entry<String, List<ConstraintChain>> query2constrainChain : query2constraintChains.entrySet()) {
            List<ConstraintChain> constraintChains = query2constrainChain.getValue();
            // 如果constraintChains是不可变集合，创建可变副本
            if (!(constraintChains instanceof java.util.ArrayList || constraintChains instanceof java.util.LinkedList)) {
                constraintChains = new LinkedList<>(constraintChains);
                query2constraintChains.put(query2constrainChain.getKey(), constraintChains);
            }
            List<ConstraintChain> reduceConstraintChains = new LinkedList<>();
            for (ConstraintChain constraintChain : constraintChains) {
                boolean hasKey = constraintChain.getNodes().stream()
                        .filter(ConstraintChainFilterNode.class::isInstance)
                        .map(ConstraintChainFilterNode.class::cast)
                        .anyMatch(ConstraintChainFilterNode::hasKeyColumn);
                if (hasKey) {
                    logger.warn("Chain in {} has filter on key columns, keeping with warning: {}",
                            query2constrainChain.getKey(), constraintChain);
                }
                List<ConstraintChainAggregateNode> aggregateNodes = constraintChain.getNodes().stream()
                        .filter(ConstraintChainAggregateNode.class::isInstance)
                        .map(ConstraintChainAggregateNode.class::cast)
                        .filter(ConstraintChainAggregateNode::removeAgg).toList();
                if (!aggregateNodes.isEmpty()) {
                    logger.info(rb.getString("RemoveSomeAggregationNodeOnAttributes"), query2constrainChain.getKey(), aggregateNodes);
                    constraintChain.getNodes().removeAll(aggregateNodes);
                }
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
        long sqlFileCount = queryFiles.size();
        if (effectiveLocalPlanJsonPath != null && !effectiveLocalPlanJsonPath.isBlank()
                && (effectiveLocalPlanJsonDir == null || effectiveLocalPlanJsonDir.isBlank())
                && sqlFileCount > 1) {
            logger.warn("--local-plan-json 指向单个 JSON 文件，但查询目录中有 {} 个 .sql；将对每个查询复用同一份计划。", sqlFileCount);
        }
        for (File queryFile : queryFiles) {
            List<String> queries = queryReader.getQueriesFromFile(queryFile.getPath());
            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                String queryCanonicalName = queryFile.getName().replace(SQL_FILE_POSTFIX, "_" + (i + 1) + SQL_FILE_POSTFIX);
                logger.info(rb.getString("StartGetting"), queryCanonicalName);
                queryAnalyzer.setAliasDic(queryReader.getTableAlias(query));
                List<Parameter> parameters = new ArrayList<>();
                String localPlanJson = resolveLocalPlanJson(queryFile);
                if (localPlanJson != null) {
                    logger.info("使用本地 EXPLAIN JSON（非数据库 EXPLAIN）解析计划: {}", queryCanonicalName);
                }
                List<List<ConstraintChain>> constraintChainsOfMultiplePlans =
                        localPlanJson != null ? queryAnalyzer.extractQuery(query, localPlanJson) : queryAnalyzer.extractQuery(query);
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
        @CommandLine.Option(names = {"--local-plan-json"}, description = "Absolute path to one EXPLAIN (FORMAT JSON) file; use with single-SQL dir or same plan for all")
        private String localPlanJson;
        @CommandLine.Option(names = {"--local-plan-json-dir"}, description = "Directory containing <queryStem>_plan.json per .sql (takes precedence over --local-plan-json)")
        private String localPlanJsonDir;

        String getLocalPlanJsonFromCommandLine() {
            return localPlanJson;
        }

        String getLocalPlanJsonDirFromCommandLine() {
            return localPlanJsonDir;
        }
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
