package ruc.db.utils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ruc.db.LanguageManager;

/**
 * 配置文件管理器
 * 负责从 /home/Mirage/conf 目录加载和管理配置文件
 * 
 * @author RSGen Implementation
 */
public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_DIR = "/home/Mirage/conf";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 支持的数据库类型
     */
    public enum DatabaseType {
        POSTGRESQL("postgresql"),
        KINGBASE("kingbase"),
        TIDB3("tidb3"),
        TIDB4("tidb4"),
        GAUSS("gauss");
        
        private final String typeName;
        
        DatabaseType(String typeName) {
            this.typeName = typeName;
        }
        
        public String getTypeName() {
            return typeName;
        }
        
        public static DatabaseType fromString(String type) {
            for (DatabaseType dbType : DatabaseType.values()) {
                if (dbType.typeName.equalsIgnoreCase(type)) {
                    return dbType;
                }
            }
            throw new IllegalArgumentException("不支持的数据库类型: " + type);
        }
    }
    
    /**
     * 数据库配置类
     */
    public static class DatabaseConfig {
        private String host;
        private String port;
        private String database;
        private String username;
        private String password;
        private DatabaseType type;
        private String rangeMode;
        private String[] schemas = new String[]{"public"}; // 默认为 public schema
        private Map<String, String> additionalProperties;
        
        public DatabaseConfig() {
            this.additionalProperties = new HashMap<>();
        }
        
        // Getters and Setters
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        
        public String getPort() { return port; }
        public void setPort(String port) { this.port = port; }
        
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        
        public DatabaseType getType() { return type; }
        public void setType(DatabaseType type) { this.type = type; }
        
        public String getRangeMode() { return rangeMode; }
        public void setRangeMode(String rangeMode) { this.rangeMode = rangeMode; }
        
        public String[] getSchemas() { return schemas; }
        public void setSchemas(String[] schemas) { this.schemas = schemas; }
        
        public Map<String, String> getAdditionalProperties() { return additionalProperties; }
        public void setAdditionalProperties(Map<String, String> additionalProperties) { 
            this.additionalProperties = additionalProperties; 
        }
        
        public String getProperty(String key) {
            return additionalProperties.get(key);
        }
        
        public void setProperty(String key, String value) {
            additionalProperties.put(key, value);
        }
    }
    
    /**
     * RSGen 配置类
     */
    public static class RSGenConfig {
        private DatabaseConfig database;
        private String outputDirectory;
        private double scaleFactor;
        private int numWorkers;
        private String tables;
        private int phase;
        /** 可选：SQL 工作集目录，与 CLI --sql-dir 一致 */
        private String sqlWorkloadDirectory;
        private String sqlWorkloadDefaultSchema = "public";
        /** full | off，与 CLI --partition-mode 一致 */
        private String partitionMode = "full";
        
        public RSGenConfig() {
            this.scaleFactor = 1.0;
            this.numWorkers = 1;
            this.phase = 4;
        }
        
        // Getters and Setters
        public DatabaseConfig getDatabase() { return database; }
        public void setDatabase(DatabaseConfig database) { this.database = database; }
        
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        
        public double getScaleFactor() { return scaleFactor; }
        public void setScaleFactor(double scaleFactor) { this.scaleFactor = scaleFactor; }
        
        public int getNumWorkers() { return numWorkers; }
        public void setNumWorkers(int numWorkers) { this.numWorkers = numWorkers; }
        
        public String getTables() { return tables; }
        public void setTables(String tables) { this.tables = tables; }
        
        public int getPhase() { return phase; }
        public void setPhase(int phase) { this.phase = phase; }

        public String getSqlWorkloadDirectory() { return sqlWorkloadDirectory; }
        public void setSqlWorkloadDirectory(String sqlWorkloadDirectory) { this.sqlWorkloadDirectory = sqlWorkloadDirectory; }

        public String getSqlWorkloadDefaultSchema() { return sqlWorkloadDefaultSchema; }
        public void setSqlWorkloadDefaultSchema(String sqlWorkloadDefaultSchema) {
            this.sqlWorkloadDefaultSchema = sqlWorkloadDefaultSchema;
        }

        public String getPartitionMode() { return partitionMode; }
        public void setPartitionMode(String partitionMode) { this.partitionMode = partitionMode; }
    }
    
    /**
     * 从配置文件加载 RSGen 配置
     * 
     * @param configNameOrPath 配置文件名（不含 .json 扩展名，从默认目录加载）或完整路径（以 / 或 ./ 开头、或以 .json 结尾）
     * @return RSGen 配置对象
     * @throws IOException 如果配置文件读取失败
     */
    public static RSGenConfig loadRSGenConfig(String configNameOrPath) throws IOException {
        File configFile;
        if (configNameOrPath.endsWith(".json") || configNameOrPath.startsWith("/") || configNameOrPath.startsWith("./")) {
            configFile = new File(configNameOrPath);
        } else {
            configFile = new File(CONFIG_DIR, configNameOrPath + ".json");
        }
        if (!configFile.exists()) {
            throw new IOException("配置文件不存在: " + configFile.getAbsolutePath());
        }

        LanguageManager lm = LanguageManager.getInstance();
        logger.info(lm.formatBilingual("ConfigManagerLoadConfigFile", configFile.getAbsolutePath()));
        
        JsonNode rootNode = objectMapper.readTree(configFile);
        RSGenConfig config = new RSGenConfig();
        
        // 解析数据库配置
        if (rootNode.has("database")) {
            JsonNode dbNode = rootNode.get("database");
            DatabaseConfig dbConfig = new DatabaseConfig();
            
            if (dbNode.has("host")) dbConfig.setHost(dbNode.get("host").asText());
            if (dbNode.has("port")) dbConfig.setPort(dbNode.get("port").asText());
            if (dbNode.has("database")) dbConfig.setDatabase(dbNode.get("database").asText());
            if (dbNode.has("username")) dbConfig.setUsername(dbNode.get("username").asText());
            if (dbNode.has("password")) dbConfig.setPassword(dbNode.get("password").asText());
            if (dbNode.has("type")) dbConfig.setType(DatabaseType.fromString(dbNode.get("type").asText()));
            if (dbNode.has("rangeMode")) dbConfig.setRangeMode(dbNode.get("rangeMode").asText());
            
            // 解析 schemas 数组
            if (dbNode.has("schemas")) {
                JsonNode schemasNode = dbNode.get("schemas");
                if (schemasNode.isArray()) {
                    String[] schemas = new String[schemasNode.size()];
                    for (int i = 0; i < schemasNode.size(); i++) {
                        schemas[i] = schemasNode.get(i).asText();
                    }
                    dbConfig.setSchemas(schemas);
                    logger.info(lm.formatBilingual("ConfigManagerSchemasLog", Arrays.toString(schemas)));
                }
            }
            
            // 解析额外属性
            if (dbNode.has("properties")) {
                JsonNode propsNode = dbNode.get("properties");
                propsNode.fields().forEachRemaining(entry -> {
                    dbConfig.setProperty(entry.getKey(), entry.getValue().asText());
                });
            }
            
            config.setDatabase(dbConfig);
        } else if (rootNode.has("databaseConnectorConfig")) {
            // prepare / TaskConfigurator 风格：databaseConnectorConfig（与 database 块二选一）
            JsonNode dc = rootNode.get("databaseConnectorConfig");
            DatabaseConfig dbConfig = new DatabaseConfig();
            if (dc.has("databaseIp")) {
                dbConfig.setHost(dc.get("databaseIp").asText());
            }
            if (dc.has("databasePort")) {
                dbConfig.setPort(dc.get("databasePort").asText());
            }
            if (dc.has("databaseName")) {
                dbConfig.setDatabase(dc.get("databaseName").asText());
            }
            if (dc.has("databaseUser")) {
                dbConfig.setUsername(dc.get("databaseUser").asText());
            }
            if (dc.has("databasePwd")) {
                dbConfig.setPassword(dc.get("databasePwd").asText());
            }
            if (dc.has("type")) {
                dbConfig.setType(DatabaseType.fromString(dc.get("type").asText()));
            } else {
                dbConfig.setType(DatabaseType.POSTGRESQL);
            }
            config.setDatabase(dbConfig);
        }
        
        // 解析其他配置
        if (rootNode.has("outputDirectory")) {
            config.setOutputDirectory(rootNode.get("outputDirectory").asText());
        }
        if (rootNode.has("scaleFactor")) {
            config.setScaleFactor(rootNode.get("scaleFactor").asDouble());
        }
        if (rootNode.has("numWorkers")) {
            config.setNumWorkers(rootNode.get("numWorkers").asInt());
        }
        if (rootNode.has("tables")) {
            config.setTables(rootNode.get("tables").asText());
        }
        if (rootNode.has("phase")) {
            config.setPhase(rootNode.get("phase").asInt());
        }
        if (rootNode.has("sqlWorkloadDirectory")) {
            config.setSqlWorkloadDirectory(rootNode.get("sqlWorkloadDirectory").asText());
        }
        if (rootNode.has("sqlWorkloadDefaultSchema")) {
            config.setSqlWorkloadDefaultSchema(rootNode.get("sqlWorkloadDefaultSchema").asText());
        }
        if (rootNode.has("partitionMode")) {
            config.setPartitionMode(rootNode.get("partitionMode").asText());
        }
        
        return config;
    }
    
    /**
     * 创建 DatabaseConnectorConfig 对象
     * 
     * @param dbConfig 数据库配置
     * @return DatabaseConnectorConfig 对象
     */
    public static DatabaseConnectorConfig createDatabaseConnectorConfig(DatabaseConfig dbConfig) {
        DatabaseConnectorConfig config = new DatabaseConnectorConfig(
            dbConfig.getHost(),
            dbConfig.getPort(),
            dbConfig.getUsername(),
            dbConfig.getPassword(),
            dbConfig.getDatabase()
        );
        config.setSchemas(dbConfig.getSchemas());
        return config;
    }
    
    /**
     * 列出所有可用的配置文件
     * 
     * @return 配置文件名列表（不包含扩展名）
     */
    public static String[] listAvailableConfigs() {
        File configDir = new File(CONFIG_DIR);
        if (!configDir.exists() || !configDir.isDirectory()) {
            return new String[0];
        }
        
        return configDir.list((dir, name) -> name.endsWith(".json"))
                       .clone()
                       .toString()
                       .replaceAll("\\.json$", "")
                       .split(",");
    }
} 