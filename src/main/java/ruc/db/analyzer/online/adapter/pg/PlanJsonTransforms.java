package ruc.db.analyzer.online.adapter.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 对 EXPLAIN (FORMAT JSON) 文本做变换：将「仅含同一父表各分区上的 Seq Scan」的 Append
 * 折叠为单个虚拟 Seq Scan，汇总 Actual Rows / Actual Total Time，减轻 PgAnalyzer 对深树的负担。
 */
public final class PlanJsonTransforms {

    private static final Logger LOG = LoggerFactory.getLogger(PlanJsonTransforms.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 匹配常见分区表物理名：{@code base_p数字}、{@code base_pmin/pmax}、{@code base_ppmax}、{@code base_default...} 等。
     * 返回逻辑父表的 Relation Name（不含 schema）。
     */
    private static final Pattern PARTITION_UNDER_P = Pattern.compile("(?i)^(.+)_p(.+)$");
    private static final Pattern PARTITION_DEFAULT = Pattern.compile("(?i)^(.+)_(default(_sys)?)$");

    private PlanJsonTransforms() {
    }

    /**
     * 对完整 EXPLAIN JSON 字符串做折叠；解析失败或非数组根时原样返回。
     */
    public static String foldAppendPartitionSeqScans(String explainJson) {
        if (explainJson == null || explainJson.isBlank()) {
            return explainJson;
        }
        try {
            JsonNode root = MAPPER.readTree(explainJson.trim());
            if (!root.isArray()) {
                return explainJson;
            }
            for (JsonNode elt : root) {
                if (elt.isObject() && elt.has("Plan") && elt.get("Plan").isObject()) {
                    collapseInPlanTree((ObjectNode) elt.get("Plan"));
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("foldAppendPartitionSeqScans: parse/transform failed, using raw JSON: {}", e.getMessage());
            return explainJson;
        }
    }

    private static void collapseInPlanTree(ObjectNode plan) {
        JsonNode plans = plan.get("Plans");
        if (plans != null && plans.isArray()) {
            ArrayNode arr = (ArrayNode) plans;
            for (JsonNode ch : arr) {
                if (ch.isObject()) {
                    collapseInPlanTree((ObjectNode) ch);
                }
            }
        }
        tryCollapseAppendNode(plan);
    }

    private static void tryCollapseAppendNode(ObjectNode node) {
        if (!"Append".equals(node.path("Node Type").asText())) {
            return;
        }
        JsonNode plansNode = node.get("Plans");
        if (plansNode == null || !plansNode.isArray() || plansNode.size() < 2) {
            return;
        }
        List<ObjectNode> seqChildren = new ArrayList<>();
        String commonParent = null;
        String commonSchema = null;
        for (JsonNode ch : plansNode) {
            if (!ch.isObject()) {
                return;
            }
            ObjectNode on = (ObjectNode) ch;
            String nt = on.path("Node Type").asText("");
            if (!"Seq Scan".equals(nt) && !"Parallel Seq Scan".equals(nt)) {
                return;
            }
            if (on.has("Plans") && on.get("Plans").isArray() && on.get("Plans").size() > 0) {
                return;
            }
            String rel = text(on, "Relation Name");
            Optional<String> parentRel = resolvePartitionParentRelationName(rel);
            if (parentRel.isEmpty()) {
                return;
            }
            String schema = text(on, "Schema");
            if (commonParent == null) {
                commonParent = parentRel.get();
                commonSchema = schema;
            } else if (!commonParent.equals(parentRel.get()) || !nullSafeEq(commonSchema, schema)) {
                return;
            }
            seqChildren.add(on);
        }
        if (seqChildren.size() != plansNode.size()) {
            return;
        }
        double sumRowsLoops = 0.0;
        double sumTotalTime = 0.0;
        ObjectNode first = seqChildren.get(0);
        for (ObjectNode scan : seqChildren) {
            sumRowsLoops += actualRowsTimesLoops(scan);
            sumTotalTime += actualTotalTime(scan);
        }
        rewriteAppendAsMergedSeqScan(node, commonSchema, commonParent, first, sumRowsLoops, sumTotalTime);
        LOG.info("Folded Append with {} partition Seq Scans into single Seq Scan on {}.{} (rows≈{}, time≈{})",
                seqChildren.size(), commonSchema, commonParent, Math.round(sumRowsLoops), sumTotalTime);
    }

    private static void rewriteAppendAsMergedSeqScan(
            ObjectNode append,
            String schema,
            String parentRelationName,
            ObjectNode firstChild,
            double sumRowsLoops,
            double sumTotalTime) {
        List<String> keysToRemove = new ArrayList<>();
        append.fieldNames().forEachRemaining(keysToRemove::add);
        for (String k : keysToRemove) {
            append.remove(k);
        }
        append.put("Node Type", "Seq Scan");
        if (schema != null && !schema.isEmpty()) {
            append.put("Schema", schema);
        }
        append.put("Relation Name", parentRelationName);
        if (firstChild.has("Alias") && !firstChild.get("Alias").isNull()) {
            append.set("Alias", firstChild.get("Alias"));
        }
        append.put("Actual Rows", sumRowsLoops);
        append.put("Actual Loops", 1);
        if (sumTotalTime > 0 || firstChild.has("Actual Total Time")) {
            append.put("Actual Total Time", sumTotalTime);
        }
        if (firstChild.has("Output")) {
            append.set("Output", firstChild.get("Output"));
        }
        if (firstChild.has("Filter")) {
            append.set("Filter", firstChild.get("Filter"));
        }
    }

    static Optional<String> resolvePartitionParentRelationName(String relationName) {
        if (relationName == null || relationName.isEmpty()) {
            return Optional.empty();
        }
        java.util.regex.Matcher m = PARTITION_UNDER_P.matcher(relationName);
        if (m.matches()) {
            return Optional.of(m.group(1));
        }
        java.util.regex.Matcher d = PARTITION_DEFAULT.matcher(relationName);
        if (d.matches()) {
            return Optional.of(d.group(1));
        }
        return Optional.empty();
    }

    private static boolean nullSafeEq(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static String text(ObjectNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static double actualRowsTimesLoops(JsonNode node) {
        double rows = node.path("Actual Rows").asDouble(0.0);
        int loops = node.path("Actual Loops").asInt(1);
        return rows * Math.max(1, loops);
    }

    private static double actualTotalTime(JsonNode node) {
        JsonNode totalNode = node.get("Actual Total Time");
        if (totalNode != null && totalNode.isNumber()) {
            return totalNode.asDouble();
        }
        return node.path("Actual Startup Time").asDouble(0.0);
    }
}
