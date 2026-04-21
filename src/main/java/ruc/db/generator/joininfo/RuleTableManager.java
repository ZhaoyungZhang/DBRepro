package ruc.db.generator.joininfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.schema.TableManager;
import ruc.db.utils.exception.schema.CannotFindSchemaException;

public class RuleTableManager {
    private static final Logger logger = LoggerFactory.getLogger(RuleTableManager.class);
    private static final RuleTableManager INSTANCE = new RuleTableManager();
    private final Map<String, RuleTable> ruleTableMap = new HashMap<>();

    private RuleTableManager() {
    }

    public static RuleTableManager getInstance() {
        return INSTANCE;
    }

    /**
     * FK 参照列 {@code colName} 无独立 RuleTable 时（常见于参照非主键列，如 trml_addr_code），
     * 使用同表已注册的复合主键 RuleTable（与主键列共用行槽位/批次索引语义）。
     */
    private RuleTable resolveRuleTableForReferencedColumn(String refCanonicalCol) {
        if (refCanonicalCol == null || refCanonicalCol.isEmpty()) {
            return null;
        }
        String[] parts = refCanonicalCol.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        String tableName = parts[0] + "." + parts[1];
        try {
            List<String> pkList = TableManager.getInstance().getCompletePrimaryKeysList(tableName);
            if (pkList == null || pkList.isEmpty()) {
                return null;
            }
            if (pkList.contains(refCanonicalCol)) {
                return null;
            }
            String compositeKey = String.join(",", pkList);
            RuleTable rt = ruleTableMap.get(compositeKey);
            if (rt != null) {
                logger.warn("FK 参照列 {} 非主键且无独立 RuleTable，使用同表复合主键键 [{}] 的 RuleTable",
                        refCanonicalCol, compositeKey);
                return rt;
            }
            for (String pkCol : pkList) {
                rt = ruleTableMap.get(pkCol);
                if (rt != null) {
                    logger.warn("FK 参照列 {} 非主键且无独立 RuleTable，使用同表主键列 {} 的 RuleTable",
                            refCanonicalCol, pkCol);
                    return rt;
                }
            }
        } catch (CannotFindSchemaException e) {
            logger.debug("resolveRuleTableForReferencedColumn: 表 {} 不可用: {}", tableName, e.getMessage());
        }
        return null;
    }

    public MergedRuleTable getRuleTable(String colName, int[] location) {
        RuleTable rt = ruleTableMap.get(colName);
        if (rt == null) {
            rt = resolveRuleTableForReferencedColumn(colName);
        }
        if (rt == null) {
            throw new IllegalStateException(
                    "RuleTable 未注册，无法生成 FK。列键=" + colName
                            + "。已注册键（与复合主键逗号串或单列主键有关）: " + ruleTableMap.keySet());
        }
        return rt.mergeRules(location);
    }

    public Map<JoinStatus, AtomicLong> addRuleTable(String tableName, Map<JoinStatus, Long> pkHistogram, long indexStart) {
        RuleTable rt = ruleTableMap.computeIfAbsent(tableName, v -> new RuleTable());
        // 复合主键时 tableName 为 "schema.t.col1,schema.t.col2"；FkGenerator 用 getRefKey 按单列查，需为每个主键列注册同一 RuleTable
        if (tableName != null && tableName.contains(",")) {
            for (String part : tableName.split(",")) {
                String k = part.trim();
                if (!k.isEmpty()) {
                    ruleTableMap.putIfAbsent(k, rt);
                }
            }
        }
        Map<JoinStatus, AtomicLong> pkStatus2Index = new HashMap<>();
        long accumulativeIndex = indexStart;
        for (Map.Entry<JoinStatus, Long> pk2Size : pkHistogram.entrySet()) {
            long size = pk2Size.getValue();
            rt.addRule(pk2Size.getKey(), accumulativeIndex, accumulativeIndex + size);
            pkStatus2Index.put(pk2Size.getKey(), new AtomicLong(accumulativeIndex));
            accumulativeIndex += size;
        }
        return pkStatus2Index;
    }

}
