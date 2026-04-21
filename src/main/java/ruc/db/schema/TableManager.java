package ruc.db.schema;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.SortedMap;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import ruc.db.LanguageManager;
import ruc.db.utils.CommonUtils;
import static ruc.db.utils.CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL;
import static ruc.db.utils.CommonUtils.CANONICAL_NAME_SPLIT_REGEX;
import ruc.db.utils.exception.TouchstoneException;
import ruc.db.utils.exception.schema.CannotFindSchemaException;

public class TableManager {
    public static final String SCHEMA_MANAGE_INFO = "/schema.json";
    protected static final Logger logger = LoggerFactory.getLogger(TableManager.class);
    private static final TableManager INSTANCE = new TableManager();
    private LinkedHashMap<String, Table> schemas = new LinkedHashMap<>();
    private final Map<String, String> partitionChildCache = new HashMap<>();
    private File schemaInfoPath;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public TableManager() {
        // only for json reader
    }

    public static TableManager getInstance() {
        return INSTANCE;
    }

    public SortedMap<String, Long> getFk2PkTableSize(String schemaName) {
        return schemas.get(schemaName).getFk2PkTableSize();
    }

    public Map<String, Table> getSchemas() {
        return schemas;
    }

    public void setResultDir(String resultDir) {
        this.schemaInfoPath = new File(resultDir + SCHEMA_MANAGE_INFO);
    }

    public void storeSchemaInfo() throws IOException {
        // 假设主键为单列，复合主键中其他列的值作为辅助列
        // schemas.values().forEach(Table::cleanPrimaryKey);
        String content = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schemas);
        CommonUtils.writeFile(schemaInfoPath.getPath(), content);
    }

    public void loadSchemaInfo() throws IOException {
        schemas = CommonUtils.MAPPER.readValue(CommonUtils.readFile(schemaInfoPath.getPath()), new TypeReference<>() {
        });
    }

    public void addSchema(String tableName, Table schema) {
        schemas.put(tableName, schema);
    }

    public String getPrimaryKeys(String tableName) throws CannotFindSchemaException {
        return getSchema(tableName).getPrimaryKeysAsString();
    }

    /**
     * 复合主键完整列表（规范名列名 schema.table.column），顺序与 schema 中一致。
     */
    public List<String> getCompletePrimaryKeysList(String tableName) throws CannotFindSchemaException {
        return getSchema(tableName).getCompletePrimaryKeysList();
    }

    public String getRefKey(String localCol) {
        String[] nameArray = localCol.split("\\.");
        String tableName = nameArray[0] + "." + nameArray[1];
        Table table = schemas.get(tableName);
        if (table == null) {
            return null;
        }
        return table.getForeignKeys().get(localCol);
    }

    public boolean isPrimaryKey(String canonicalColumnName) {
        String[] nameArray = canonicalColumnName.split("\\.");
        String tableName = nameArray[0] + "." + nameArray[1];
        Table table = schemas.get(tableName);
        if (table == null) {
            return false;
        }
        return table.isPrimaryKeyColumn(canonicalColumnName);
        //return table.getPrimaryKeysList().contains(canonicalColumnName);
    }

    public boolean isForeignKey(String canonicalColumnName) {
        String[] nameArray = canonicalColumnName.split("\\.");
        String tableName = nameArray[0] + "." + nameArray[1];
        Table table = schemas.get(tableName);
        if (table == null) {
            return false;
        }
        return table.getForeignKeys().containsKey(canonicalColumnName);
    }

    public boolean containSchema(String tableName) {
        return schemas.containsKey(tableName);
    }

    public long getTableSize(String tableName) throws CannotFindSchemaException {
        return getSchema(tableName).getTableSize();
    }

    public long getTableSizeWithCol(String columnName) {
        String[] cols = columnName.split("\\.");
        String tableName = cols[0] + "." + cols[1];
        return schemas.get(tableName).getTableSize();
    }

    public int getJoinTag(String tableName) throws CannotFindSchemaException {
        return getSchema(tableName).getJoinTag();
    }

    public void setPrimaryKeys(String tableName, String primaryKeys) throws TouchstoneException {
        getSchema(tableName).setPrimaryKeys(tableName + "." + primaryKeys);
    }


    public void setForeignKeys(String localTable, String localColumns, String refTable, String refColumns) throws TouchstoneException {
        String addReferenceDependencies = rb.getString("AddReferenceDependencies");
        logger.debug(addReferenceDependencies, localTable, localColumns, refTable, refColumns);
        getSchema(localTable).addForeignKey(localTable, localColumns, refTable, refColumns);
    }

    public void setTmpForeignKeys(String localTable, String localColumns, String refTable, String refColumns) throws TouchstoneException {
        getSchema(localTable).addTmpForeignKey(localTable, localColumns, refTable, refColumns);
    }

    public boolean isRefTable(String locTable, String locColumn, String remoteColumn) throws CannotFindSchemaException {
        return getSchema(locTable).isRefTable(locTable + "." + locColumn, remoteColumn);
    }

    public boolean isRefTable(String locTable, String remoteTable) {
        return schemas.get(locTable).isRefTable(remoteTable);
    }


    /**
     * 根据join的连接顺序，排列表名。顺序从被参照表到参照表。
     *
     * @return 从被参照表到参照表排序的表名。
     */
    public List<String> createTopologicalOrder() {
        Graph<String, DefaultEdge> schemaGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        schemas.keySet().forEach(schemaGraph::addVertex);
        for (Map.Entry<String, Table> schemaName2Schema : schemas.entrySet()) {
            for (String refColumn : schemaName2Schema.getValue().getForeignKeys().values()) {
                String[] refInfo = refColumn.split(CANONICAL_NAME_SPLIT_REGEX);
                schemaGraph.addEdge(refInfo[0] + CANONICAL_NAME_CONTACT_SYMBOL + refInfo[1], schemaName2Schema.getKey());
            }
        }
        TopologicalOrderIterator<String, DefaultEdge> topologicalOrderIterator = new TopologicalOrderIterator<>(schemaGraph);
        List<String> orderedSchemas = new LinkedList<>();
        while (topologicalOrderIterator.hasNext()) {
            orderedSchemas.add(topologicalOrderIterator.next());
        }
        return orderedSchemas;
    }

    public List<String> getAttributeColumnNames(String schemaName) throws CannotFindSchemaException {
        return getSchema(schemaName).getAttributeColumnNames();
    }

    public Table getSchema(String tableName) throws CannotFindSchemaException {
        Table schema = schemas.get(tableName);
        if (schema == null) {
            String resolved = resolvePartitionParentName(tableName);
            if (resolved != null) {
                schema = schemas.get(resolved);
            }
        }
        if (schema == null) {
            throw new CannotFindSchemaException(tableName);
        }
        return schema;
    }

    /**
     * 尝试将分区子表名映射回已注册的父表名。
     * 例如 sgami_stat.a_lm_ui_out_det_hour_sys_p13391 -> sgami_stat.a_lm_ui_out_det_hour
     *
     * @return 父表名，未找到则返回 null
     */
    public String resolvePartitionParentName(String childTableName) {
        if (childTableName == null) {
            return null;
        }
        String cached = partitionChildCache.get(childTableName);
        if (cached != null) {
            return cached;
        }
        String[] parts = childTableName.split("\\.", 2);
        if (parts.length != 2) {
            return null;
        }
        String childSchema = parts[0].toLowerCase(Locale.ROOT);
        String childRelation = parts[1].toLowerCase(Locale.ROOT);

        String bestMatch = null;
        int bestLen = 0;
        for (String registered : schemas.keySet()) {
            String[] rParts = registered.split("\\.", 2);
            if (rParts.length != 2) continue;
            if (!rParts[0].toLowerCase(Locale.ROOT).equals(childSchema)) continue;
            String parentRelation = rParts[1].toLowerCase(Locale.ROOT);
            if (childRelation.startsWith(parentRelation + "_") && parentRelation.length() > bestLen) {
                bestMatch = registered;
                bestLen = parentRelation.length();
            }
        }
        if (bestMatch != null) {
            partitionChildCache.put(childTableName, bestMatch);
            logger.info("分区子表映射: {} -> {}", childTableName, bestMatch);
        }
        return bestMatch;
    }

    public void adjustFks(){
        schemas.values().forEach(Table::adjustFks);
    }
}
