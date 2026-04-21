package ruc.db.utils;

/**
 * TaskConfigurator 相关配置（POJO），本身不包含日志输出；双语日志在 {@code TaskConfigurator} 命令与
 * {@link ruc.db.analyzer.SchemaStatsExtractor} 等入口中通过 {@link ruc.db.LanguageManager} 处理。
 *
 * @author qingshuai.wang
 */
public class TaskConfiguratorConfig {
    private DatabaseConnectorConfig databaseConnectorConfig;
    private String resultDirectory;
    private String queriesDirectory;
    private Double skipNodeThreshold = 0.01;
    private String defaultSchemaName;
    /** 单个 EXPLAIN JSON 绝对路径（与 --local-plan-json 一致） */
    private String localPlanJson;
    /** 目录：每个查询 &lt;stem&gt;_plan.json */
    private String localPlanJsonDir;

    public String getLocalPlanJson() {
        return localPlanJson;
    }

    public void setLocalPlanJson(String localPlanJson) {
        this.localPlanJson = localPlanJson;
    }

    public String getLocalPlanJsonDir() {
        return localPlanJsonDir;
    }

    public void setLocalPlanJsonDir(String localPlanJsonDir) {
        this.localPlanJsonDir = localPlanJsonDir;
    }

    public String getDefaultSchemaName() {
        return defaultSchemaName;
    }

    public void setDefaultSchemaName(String defaultSchemaName) {
        this.defaultSchemaName = defaultSchemaName;
    }

    public DatabaseConnectorConfig getDatabaseConnectorConfig() {
        return databaseConnectorConfig;
    }

    public void setDatabaseConnectorConfig(DatabaseConnectorConfig databaseConnectorConfig) {
        this.databaseConnectorConfig = databaseConnectorConfig;
    }

    public String getResultDirectory() {
        return resultDirectory;
    }

    public void setResultDirectory(String resultDirectory) {
        this.resultDirectory = resultDirectory;
    }

    public String getQueriesDirectory() {
        return queriesDirectory;
    }

    public void setQueriesDirectory(String queriesDirectory) {
        this.queriesDirectory = queriesDirectory;
    }

    public Double getSkipNodeThreshold() {
        return skipNodeThreshold;
    }

    public void setSkipNodeThreshold(Double skipNodeThreshold) {
        this.skipNodeThreshold = skipNodeThreshold;
    }
}
