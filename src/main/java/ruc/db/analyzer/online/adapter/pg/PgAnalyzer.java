package ruc.db.analyzer.online.adapter.pg;

import ruc.db.LanguageManager;
import ruc.db.analyzer.online.AbstractAnalyzer;
import ruc.db.analyzer.online.adapter.pg.parser.PgSelectOperatorInfoLexer;
import ruc.db.analyzer.online.adapter.pg.parser.PgSelectOperatorInfoParser;
import ruc.db.analyzer.online.node.*;
import ruc.db.generator.constraintchain.filter.LogicNode;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.TableManager;
import ruc.db.utils.CommonUtils;
import ruc.db.utils.exception.TouchstoneException;
import ruc.db.utils.exception.schema.CannotFindSchemaException;
import java_cup.runtime.ComplexSymbolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ruc.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;
import static ruc.db.utils.CommonUtils.matchPattern;

public class PgAnalyzer extends AbstractAnalyzer {

    protected static final Logger logger = LoggerFactory.getLogger(PgAnalyzer.class);
    private static final String NUMERIC = "'[0-9]+'::numeric";
    private static final String INTEGER = "'(0|[1-9][0-9]*|-[1-9][0-9]*)'::integer";
    private static final String DATE1 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}";
    private static final String DATE2 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}";
    private static final String DATE3 = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final String DATE = String.format("'(%s|%s|%s)'::date", DATE1, DATE2, DATE3);
    private static final String TIMESTAMP1 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}";
    private static final String TIMESTAMP2 = "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}";
    private static final String TIMESTAMP3 = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    public static final String TIME_OR_DATE = String.format("(%s|%s|%s|%s|%s|%s)", DATE1, DATE2, DATE3, TIMESTAMP1, TIMESTAMP2, TIMESTAMP3);
    private static final String TIMESTAMP = String.format("'(%s|%s|%s)'::timestamp(\\([0-9]+\\))? without time zone", TIMESTAMP1, TIMESTAMP2, TIMESTAMP3);
    private static final Pattern REDUNDANCY = Pattern.compile(INTEGER + "|" + NUMERIC + "|" + DATE + "|" + TIMESTAMP);
    private static final Pattern CanonicalColumnName = Pattern.compile("(([a-zA-Z][a-zA-Z0-9$_]*)|(\"[a-zA-Z][a-zA-Z0-9$_]*\"))\\.((\\w+)|(\"\\w+\"))");
    private static final Pattern FullCanonicalColumnName = Pattern.compile("([a-zA-Z][a-zA-Z0-9$_]*)\\.(([a-zA-Z][a-zA-Z0-9$_]*)|(\"[a-zA-Z][a-zA-Z0-9$_]*\"))\\.\\w+");
    private static final Pattern JOIN_EQ_OPERATOR = Pattern.compile("Cond: \\(.*\\)");
    private static final Pattern EQ_OPERATOR = Pattern.compile("\\(([a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+) = ([a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+\\.[a-zA-Z0-9_$]+)\\)");
    private static final Pattern HASH_SUB_PLAN = Pattern.compile("\\(NOT \\(hashed SubPlan \\d+\\)\\)");
    private static final Pattern SUBPLAN_ALTERNATIVE = Pattern.compile(
            "\\(?\\s*alternatives:\\s*SubPlan\\s+\\d+\\s+or\\s+hashed\\s+SubPlan\\s+\\d+\\s*\\)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_EQ_PREDICATE = Pattern.compile(
            "\\(?\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){0,2})\\s*\\)?\\s*=\\s*\\(?\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){0,2}|'[^']*'|\\d+)\\s*\\)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_GT_LITERAL = Pattern.compile(
            "^\\(*\\s*count\\s*\\(\\s*\\*\\s*\\)\\s*>\\s*\\d+\\s*\\)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_DISTINCT_OUTPUT = Pattern.compile(
            "count\\s*\\(\\s*distinct\\s+([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BOOL_LITERAL = Pattern.compile("\\b(true|false)\\b", Pattern.CASE_INSENSITIVE);
    /**
     * {@code (ref)::type}，ref 为 {@code alias.col} 或 {@code schema.table.col}（EXPLAIN Hash Cond 常见）。
     */
    private static final Pattern TWO_OR_THREE_PART_PAREN_CAST = Pattern.compile(
            "\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})\\s*\\)\\s*::\\s*[a-zA-Z0-9_.\\[\\]]+");
    private static final Pattern REDUNDANT_TWO_OR_THREE_PART_PARENS = Pattern.compile(
            "\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})\\s*\\)");
    private static final Pattern SINGLE_IDENT_PAREN_CAST = Pattern.compile(
            "\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*)\\s*\\)\\s*::\\s*[a-zA-Z0-9_.\\[\\]]+");
    private static final Pattern REDUNDANT_SINGLE_IDENT_PARENS = Pattern.compile(
            "\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*)\\s*\\)");
    /** 计划里大整数比较常无引号，词法器按 int 解析会 NumberFormatException，先加上引号。 */
    private static final Pattern UNQUOTED_LARGE_INT_COMPARE = Pattern.compile(
            "(<>|!=|>=|<=|>|<|=)\\s+(\\d{8,})\\b");
    /**
     * {@code (col)::text = ANY ((ARRAY[$n])::text[])}（InitPlan 参数数组）。
     * 这类谓词的真实值域在主计划里不可见，CUP 也无法解析 {@code ARRAY[$n]}；
     * 普通过滤链先剔除它，具体基数需由拆出的子查询/改写 SQL 约束补上。
     */
    private static final Pattern PG_TEXT_EQ_ANY_TEXTARRAY = Pattern.compile(
            "\\(\\s*\\(?\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})\\s*\\)?\\s*::\\s*text\\s*=\\s*ANY\\s*\\(\\s*\\(?\\s*ARRAY\\s*\\[\\s*\\$\\d+\\s*\\]\\s*\\)?\\s*::\\s*text\\[\\]\\s*\\)\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    /** 等号两侧为两段或三段限定名（在剥 cast / 前缀之后）。 */
    private static final Pattern LOOSE_TWO_OR_THREE_PART_EQ = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})\\s*=\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})");
    private static final Pattern TWO_OR_THREE_PART_REF = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})");
    /**
     * {@code (((schema.table.col)} 多一层括号常见于 {@code ~~} 前；收束为 {@code ((col)} 以匹配 {@code like_compare_expr}。
     */
    private static final Pattern QUAD_LP_BEFORE_THREE_PART = Pattern.compile(
            "\\(\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){2})\\s*\\)");
    private static final Pattern TRIPLE_LP_BEFORE_THREE_PART = Pattern.compile(
            "\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){2})\\s*\\)");
    /** 列侧 {@code )::timestamp ...}，与字面量侧 {@code '...'::timestamp ...}；词法器会吞 :: 导致括号与语法错位，解析前先去掉。 */
    private static final Pattern PG_PAREN_TIMESTAMP_CAST = Pattern.compile(
            "\\)\\s*::\\s*timestamp(?:\\s*\\(\\d+\\))?\\s+without\\s+time\\s+zone", Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_PAREN_TIMESTAMPTZ_CAST = Pattern.compile(
            "\\)\\s*::\\s*timestamptz(?:\\s*\\(\\d+\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_PAREN_TIMESTAMP_TZ_WORDS = Pattern.compile(
            "\\)\\s*::\\s*timestamp(?:\\s*\\(\\d+\\))?\\s+with\\s+time\\s+zone", Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_LIT_TIMESTAMP_CAST = Pattern.compile(
            "'([^']*)'\\s*::\\s*timestamp(?:\\s*\\(\\d+\\))?\\s+without\\s+time\\s+zone", Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_LIT_TIMESTAMPTZ_CAST = Pattern.compile(
            "'([^']*)'\\s*::\\s*timestamptz(?:\\s*\\(\\d+\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_LIT_TIMESTAMP_TZ_WORDS = Pattern.compile(
            "'([^']*)'\\s*::\\s*timestamp(?:\\s*\\(\\d+\\))?\\s+with\\s+time\\s+zone", Pattern.CASE_INSENSITIVE);
    private static final Pattern PG_LIT_DATE_CAST = Pattern.compile(
            "'([^']*)'\\s*::\\s*date", Pattern.CASE_INSENSITIVE);
    /**
     * {@code ((((schema.table.col) >= '...')}：四层 {@code (}、列后仅一层 {@code )}（Kingbase 合并/计划常见）。
     */
    private static final Pattern PG_QUAD_PAREN_THREE_PART_COMPARE = Pattern.compile(
            "\\(\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*\\)\\s*(>=|<=|<>|!=|>|<|=)");
    /**
     * {@code (((schema.table.col)) >= '...')}：三层 {@code (}、列两侧两层 {@code )}，再比较符。
     * CUP 的 {@code uni_compare_expr} 只接受 {@code ((col) op const)}。
     */
    private static final Pattern PG_TRIPLE_PAREN_THREE_PART_COMPARE = Pattern.compile(
            "\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*\\)\\)\\s*(>=|<=|<>|!=|>|<|=)");
    /** 形如 {@code (((col) op}：列前三个 {@code (}、列后仅一个 {@code )}（常见于第二段日期比较）。 */
    private static final Pattern PG_TRIPLE_LP_SINGLE_RP_THREE_PART_COMPARE = Pattern.compile(
            "\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*\\)\\s*(>=|<=|<>|!=|>|<|=)");
    /**
     * 整段 {@code ((((col) >= 'a') AND ((col) < 'b')))}：若只对前缀 {@code ((((col) >=} 做 quad 替换，会少消掉末尾与之配对的两层
     * {@code )}，整串括号失衡并在解析时出现 rparen（约 160 字符处）。
     */
    private static final Pattern PG_QUAD_WRAPPED_DATE_RANGE_AND = Pattern.compile(
            "\\(\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*\\)\\s*>="
                    + "\\s*('[^']*')\\s*\\)\\s*AND\\s*\\(\\(\\s*\\1\\s*\\)\\s*<\\s*('[^']*')\\s*\\)\\s*\\)\\s*\\)");
    /** 同上，第二段为 {@code (((col) < 'b')))}。 */
    private static final Pattern PG_QUAD_WRAPPED_DATE_RANGE_AND_TRIPLE_RP_SECOND = Pattern.compile(
            "\\(\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*\\)\\s*>="
                    + "\\s*('[^']*')\\s*\\)\\s*AND\\s*\\(\\(\\(\\s*\\1\\s*\\)\\s*<\\s*('[^']*')\\s*\\)\\s*\\)\\s*\\)");
    /**
     * {@code (((a.x = n) AND (a.y = m) AND (a.z = k)))}：首段三个 {@code (} 但每段比较只有一层 {@code )}，CUP 按
     * {@code (bool_expr AND …)} 解析时多一层外层括号会触发 rparen；收束为 {@code ((…) AND …)}。
     */
    private static final Pattern PG_TRIPLE_WRAPPED_THREE_INT_EQ_AND = Pattern.compile(
            "\\(\\(\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*=\\s*(\\d+)\\)\\s*AND\\s*\\("
                    + "([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*=\\s*(\\d+)\\)\\s*AND\\s*\\("
                    + "([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z0-9_$]+){2})\\s*=\\s*(\\d+)\\)\\)\\)");
    private static final Pattern SUB_QUERY = Pattern.compile("(\\()(\\s)*(SELECT)(.+)(FROM)(.+)(\\))");
    private final PgSelectOperatorInfoParser parser = new PgSelectOperatorInfoParser(new PgSelectOperatorInfoLexer(new StringReader("")), new ComplexSymbolFactory());
    public StringBuilder pathForSplit = null;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    private final Map<String, String[]> derivedColumnLineage = new HashMap<>();

    public PgAnalyzer() {
        super();
        this.nodeTypeRef = new PgNodeTypeInfo();
    }

    /**
     * 将执行计划中的表名（可能是分区子表）解析为已注册的父表名。
     * 若已注册则原样返回，否则尝试 fallback 到父表。
     */
    private String resolveTableName(String rawTableName) {
        if (rawTableName == null) {
            return null;
        }
        if (TableManager.getInstance().containSchema(rawTableName)) {
            return rawTableName;
        }
        String resolved = TableManager.getInstance().resolvePartitionParentName(rawTableName);
        if (resolved != null) {
            return resolved;
        }
        return rawTableName;
    }

    private void collectPlanAliasesAndDerivedLineage(StringBuilder path) {
        String nodeType = PgJsonReader.readNodeType(path);
        if (nodeType == null) {
            return;
        }
        int plansCount = PgJsonReader.readPlansCount(path);
        for (int i = 0; i < plansCount; i++) {
            collectPlanAliasesAndDerivedLineage(new StringBuilder(path).append("['Plans'][").append(i).append("]"));
        }
        registerBaseTableAlias(path);
        if ("Subquery Scan".equals(nodeType)) {
            registerSubqueryScanLineage(path);
        }
    }

    private void registerBaseTableAlias(StringBuilder path) {
        String alias = PgJsonReader.readAlias(path.toString());
        String schema = PgJsonReader.readSchema(path.toString());
        String relation = PgJsonReader.readRelationName(path.toString());
        if (alias == null || schema == null || relation == null) {
            return;
        }
        aliasDic.put(alias.replace("\"", ""), resolveTableName(schema + "." + relation));
    }

    private void registerSubqueryScanLineage(StringBuilder path) {
        String alias = PgJsonReader.readAlias(path.toString());
        List<String> outputs = PgJsonReader.readOutput(path);
        if (alias == null || outputs == null || outputs.isEmpty() || PgJsonReader.readPlansCount(path) == 0) {
            return;
        }
        List<String[]> childRefs = collectResolvableColumnRefs(PgJsonReader.move2LeftChild(path));
        for (String output : outputs) {
            String outputCol = lastColumnName(output);
            if (outputCol == null) {
                continue;
            }
            String[] source = findUniqueSourceForOutputColumn(outputCol, childRefs);
            if (source != null) {
                derivedColumnLineage.put(lineageKey(alias, outputCol), source);
                logger.debug("Derived column lineage: {}.{} -> {}", alias, outputCol, String.join(".", source));
            }
        }
    }

    private List<String[]> collectResolvableColumnRefs(StringBuilder path) {
        List<String[]> refs = new ArrayList<>();
        addResolvableRefs(refs, PgJsonReader.readGroupKey(path));
        addResolvableRefs(refs, PgJsonReader.readOutput(path));
        int plansCount = PgJsonReader.readPlansCount(path);
        for (int i = 0; i < plansCount; i++) {
            refs.addAll(collectResolvableColumnRefs(new StringBuilder(path).append("['Plans'][").append(i).append("]")));
        }
        return refs;
    }

    private void addResolvableRefs(List<String[]> refs, List<String> expressions) {
        if (expressions == null) {
            return;
        }
        for (String expression : expressions) {
            Matcher matcher = TWO_OR_THREE_PART_REF.matcher(stripTwoOrThreePartParenCastsLoop(expression));
            while (matcher.find()) {
                String[] resolved = ensureThreePartBaseRef(matcher.group(1));
                if (resolved != null) {
                    refs.add(resolved);
                }
            }
        }
    }

    private String[] findUniqueSourceForOutputColumn(String outputCol, List<String[]> childRefs) {
        Map<String, String[]> candidates = new LinkedHashMap<>();
        for (String[] ref : childRefs) {
            if (ref.length == 3 && ref[2].equalsIgnoreCase(outputCol)) {
                candidates.put(String.join(".", ref).toLowerCase(Locale.ROOT), ref);
            }
        }
        return candidates.size() == 1 ? candidates.values().iterator().next() : null;
    }

    private static String lastColumnName(String expression) {
        if (expression == null) {
            return null;
        }
        Matcher matcher = TWO_OR_THREE_PART_REF.matcher(stripTwoOrThreePartParenCastsLoop(expression.replace("\"", "")));
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        if (last == null) {
            return null;
        }
        String[] parts = last.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 1] : null;
    }

    private static String lineageKey(String alias, String col) {
        return (alias + "." + col).replace("\"", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public ExecutionNode getExecutionTree(List<String[]> queryPlans) throws TouchstoneException, IOException, SQLException {
        String queryPlan = queryPlans.stream().map(queryPlanLine -> queryPlanLine[0]).collect(Collectors.joining());
        queryPlan = PlanJsonTransforms.foldAppendPartitionSeqScans(queryPlan);
        PgJsonReader.setReadContext(queryPlan);
        if (queryPlan.contains("= subquery")) {
            transformHashJoin2AggForOpenGauss(queryPlan);
        }
        derivedColumnLineage.clear();
        collectPlanAliasesAndDerivedLineage(PgJsonReader.getRootPath());
        return getExecutionTreeRes(PgJsonReader.skipNodes(PgJsonReader.getRootPath()));
    }

    public void transformHashJoin2AggForOpenGauss(String queryPlan) {
        StringBuilder subQueryJoinNodePath = getJoinNodeWithSubQuery(PgJsonReader.skipNodes(PgJsonReader.getRootPath()));
        StringBuilder leftChildNode = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(subQueryJoinNodePath));
        PgJsonReader.deleteOutPut();
        String leftPlan = PgJsonReader.readPlan(leftChildNode, 0) + "," + PgJsonReader.readPlan(leftChildNode, 1);
        String rightPlan = PgJsonReader.readPlan(subQueryJoinNodePath, 1);
        PgJsonReader.setReadContext(queryPlan);
        if (rightPlan.contains(leftPlan)) {
            PgJsonReader.deleteTree(leftChildNode);
        }
    }

    public StringBuilder getJoinNodeWithSubQuery(StringBuilder currentNode) {
        if (PgJsonReader.readNodeType(currentNode).equals("Hash Join") &&
                PgJsonReader.readJoinCond(currentNode).contains("= subquery")) {
            return currentNode;
        }
        int plansCount = PgJsonReader.readPlansCount(currentNode);
        if (plansCount == 0) {
            return null;
        } else {
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(currentNode));
            StringBuilder leftNodePath = getJoinNodeWithSubQuery(leftChildPath);
            if (leftNodePath == null && plansCount > 1) {
                StringBuilder rightChildPath = PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNode));
                leftNodePath = getJoinNodeWithSubQuery(rightChildPath);
            }
            return leftNodePath;
        }
    }

    public ExecutionNode getExecutionTreeRes(StringBuilder currentNodePath) throws TouchstoneException, IOException, SQLException {
        ExecutionNode recoveredSubPlanJoin = tryRecoverSubPlanSemiJoin(currentNodePath);
        if (recoveredSubPlanJoin != null) {
            return recoveredSubPlanJoin;
        }
        ExecutionNode leftNode = null;
        ExecutionNode rightNode = null;
        int plansCount = PgJsonReader.readPlansCount(currentNodePath);
        if (plansCount >= 2) {
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(currentNodePath));
            leftNode = getExecutionTreeRes(leftChildPath);
            StringBuilder rightChildPath = PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNodePath));
            rightNode = getExecutionTreeRes(rightChildPath);
        } else if (plansCount == 1) {
            //todo fix only for query 20
            if (canNotDeal(currentNodePath)) {
                pathForSplit = currentNodePath;
                String tableName = resolveTableName(PgJsonReader.readTableName(currentNodePath.toString()));
                long tableSize = TableManager.getInstance().getTableSize(tableName);
                aliasDic.put(PgJsonReader.readAlias(currentNodePath.toString()), tableName);
                ExecutionNode subNode = new FilterNode(currentNodePath.toString(), tableSize, null);
                subNode.setTableName(tableName);
                return subNode;
            }
            StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(currentNodePath));
            leftNode = getExecutionTreeRes(leftChildPath);
            rightNode = transferSubPlan2AntiJoin(currentNodePath);
        }
        ExecutionNode node = getExecutionNode(currentNodePath);
        if (node == null) {
            return null;
        }
        node.setLeftNode(leftNode);
        node.setRightNode(rightNode);
        if (node.getType() == ExecutionNodeType.JOIN) {
            if (rightNode == null) {
                // fix for opengauss
                logger.info("generate agg from hash join for opengauss");
                node = leftNode;
            } else if (rightNode.getType() == ExecutionNodeType.FILTER && rightNode.getInfo() != null && ((FilterNode) rightNode).isIndexScan()) {
                long rowsRemoveByFilterAfterJoin = PgJsonReader.readRowsRemoved(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNodePath)));
                ((JoinNode) node).setRowsRemoveByFilterAfterJoin(rowsRemoveByFilterAfterJoin);
                String indexJoinFilter = PgJsonReader.readFilterInfo(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(currentNodePath)));
                if (indexJoinFilter != null) {
                    ((JoinNode) node).setIndexJoinFilter(removeRedundancy(indexJoinFilter, true));
                }
            }
        }
        //create agg node
        if (plansCount == 3) {
            StringBuilder thirdChildPath = PgJsonReader.skipNodes(PgJsonReader.move3ThirdChild(currentNodePath));
            ExecutionNode parentAggNode = createParentAggNode(currentNodePath, thirdChildPath);
            int rowCount = PgJsonReader.readRowCount(currentNodePath) + PgJsonReader.readRowsRemovedByJoinFilter(currentNodePath);
            if (node != null) {
                node.setOutputRows(rowCount);
            }
            parentAggNode.setLeftNode(node);
            node = parentAggNode;
        }
        //todo fix only for query 20
        if (pathForSplit != null) {
            if (PgJsonReader.move2LeftChild(currentNodePath).toString().contentEquals(pathForSplit) ||
                    PgJsonReader.move2RightChild(currentNodePath).toString().contentEquals(pathForSplit)) {
                node.setOutputRows(PgJsonReader.readActualLoops(PgJsonReader.move2LeftChild(pathForSplit)));
            }
        }
        return node;
    }

    private ExecutionNode tryRecoverSubPlanSemiJoin(StringBuilder currentNodePath) throws TouchstoneException, IOException, SQLException {
        String nodeType = PgJsonReader.readNodeType(currentNodePath);
        if (nodeType == null || !nodeTypeRef.isFilterNode(nodeType)) {
            return null;
        }
        String filterInfo = PgJsonReader.readFilterInfo(currentNodePath);
        if (filterInfo == null || !SUBPLAN_ALTERNATIVE.matcher(filterInfo.trim()).matches()) {
            return null;
        }
        int childCount = PgJsonReader.readPlansCount(currentNodePath);
        if (childCount <= 0) {
            return null;
        }
        StringBuilder correlatedChildPath = chooseCorrelatedSubPlanChild(currentNodePath);
        if (correlatedChildPath == null) {
            return null;
        }
        String rawPredicate = combineScanFilterPredicates(correlatedChildPath, PgJsonReader.readFilterInfo(correlatedChildPath));
        String joinInfo = extractCorrelatedJoinInfo(
                rawPredicate,
                PgJsonReader.readAlias(currentNodePath.toString()),
                PgJsonReader.readAlias(correlatedChildPath.toString()));
        if (joinInfo == null) {
            return null;
        }

        String tableName = resolveTableName(PgJsonReader.readTableName(currentNodePath.toString()));
        aliasDic.put(PgJsonReader.readAlias(currentNodePath.toString()), tableName);

        long outputRows = PgJsonReader.readRowCount(currentNodePath);
        long baseRows = outputRows + PgJsonReader.readRowsRemoved(currentNodePath);
        FilterNode leftNode = new FilterNode(currentNodePath + "#base", baseRows, null);
        leftNode.setTableName(tableName);

        ExecutionNode rightNode = getExecutionTreeRes(PgJsonReader.skipNodes(correlatedChildPath));
        JoinNode joinNode = new JoinNode(currentNodePath + "#semi", outputRows, joinInfo, false, true, BigDecimal.ZERO);
        joinNode.setLeftNode(leftNode);
        joinNode.setRightNode(rightNode);
        joinNode.setLeftInputRows(baseRows);
        joinNode.setPreferredConstraintChainTable(tableName);
        joinNode.setRightInputRows(resolveCorrelatedSubPlanInputRows(currentNodePath, correlatedChildPath, rightNode));
        return joinNode;
    }

    private StringBuilder chooseCorrelatedSubPlanChild(StringBuilder parentPath) {
        String outerAlias = PgJsonReader.readAlias(parentPath.toString());
        StringBuilder best = null;
        long bestRows = Long.MIN_VALUE;
        int childCount = PgJsonReader.readPlansCount(parentPath);
        for (int i = 0; i < childCount; i++) {
            StringBuilder child = new StringBuilder(parentPath).append("['Plans'][").append(i).append("]");
            String merged = combineScanFilterPredicates(child, PgJsonReader.readFilterInfo(child));
            if (merged == null || outerAlias == null || !merged.contains(outerAlias + ".")) {
                continue;
            }
            long rows = scoreCorrelatedSubPlanChild(child);
            if (rows > bestRows) {
                best = child;
                bestRows = rows;
            }
        }
        if (best != null) {
            return best;
        }
        if (childCount == 0) {
            return null;
        }
        return new StringBuilder(parentPath).append("['Plans'][").append(childCount - 1).append("]");
    }

    private long scoreCorrelatedSubPlanChild(StringBuilder childPath) {
        long preferred = readJoinInputRowCount(new StringBuilder(childPath));
        if (preferred > 0) {
            return preferred;
        }
        return PgJsonReader.readRowCount(childPath);
    }

    private long resolveCorrelatedSubPlanInputRows(StringBuilder parentPath,
                                                   StringBuilder correlatedChildPath,
                                                   ExecutionNode rightNode) {
        long direct = scoreCorrelatedSubPlanChild(correlatedChildPath);
        if (direct > 0) {
            return direct;
        }
        int childCount = PgJsonReader.readPlansCount(parentPath);
        for (int i = 0; i < childCount; i++) {
            StringBuilder sibling = new StringBuilder(parentPath).append("['Plans'][").append(i).append("]");
            if (sibling.toString().equals(correlatedChildPath.toString())) {
                continue;
            }
            long siblingRows = scoreCorrelatedSubPlanChild(sibling);
            if (siblingRows > 0) {
                return siblingRows;
            }
        }
        return rightNode != null ? rightNode.getOutputRows() : 0L;
    }

    private String extractCorrelatedJoinInfo(String predicate, String outerAlias, String innerAlias) {
        if (predicate == null || outerAlias == null || innerAlias == null) {
            return null;
        }
        String normalized = stripSimpleTypeCasts(stripTwoOrThreePartParenCastsLoop(predicate));
        Matcher matcher = SIMPLE_EQ_PREDICATE.matcher(normalized);
        while (matcher.find()) {
            String left = qualifyCorrelatedRef(matcher.group(1), innerAlias);
            String right = qualifyCorrelatedRef(matcher.group(2), innerAlias);
            boolean leftOuter = left.startsWith(outerAlias + ".");
            boolean rightOuter = right.startsWith(outerAlias + ".");
            if ((leftOuter ^ rightOuter) && !isLiteralPredicateOperand(left) && !isLiteralPredicateOperand(right)) {
                return "Index Cond: (" + left + " = " + right + ")";
            }
        }
        return null;
    }

    private static String qualifyCorrelatedRef(String ref, String innerAlias) {
        if (ref == null || ref.indexOf('.') >= 0 || isLiteralPredicateOperand(ref)) {
            return ref;
        }
        return innerAlias + "." + ref;
    }

    private static boolean isLiteralPredicateOperand(String ref) {
        return ref != null && (ref.startsWith("'") || ref.chars().allMatch(Character::isDigit));
    }

    private static String stripSimpleTypeCasts(String expr) {
        if (expr == null) {
            return null;
        }
        return expr.replaceAll("::\\s*[a-zA-Z0-9_]+(?:\\s+[a-zA-Z0-9_]+)*(?:\\[\\])?", "");
    }


    private ExecutionNode transferSubPlan2AntiJoin(StringBuilder path) {
        //todo multiple subPlans
        String filterInfo = PgJsonReader.readFilterInfo(path);
        if (nodeTypeRef.isFilterNode(PgJsonReader.readNodeType(path)) && filterInfo != null) {
            Matcher notHashSubPlan = HASH_SUB_PLAN.matcher(filterInfo);
            if (notHashSubPlan.find()) {
                int count = 1;
                while (notHashSubPlan.find()) {
                    count++;
                }
                if (count == 1) {
                    String tableName = resolveTableName(PgJsonReader.readTableName(path.toString()));
                    aliasDic.put(PgJsonReader.readAlias(path.toString()), tableName);
                    int outPutCount = PgJsonReader.readRowCount(path);
                    int removedCount = PgJsonReader.readRowsRemoved(path);
                    int rowCount = outPutCount + removedCount;
                    StringBuilder rightPath = PgJsonReader.move2RightChild(path);
                    FilterNode currentRightNode = new FilterNode(rightPath.toString(), rowCount, null);
                    currentRightNode.setTableName(tableName);
                    currentRightNode.setAdd();
                    return currentRightNode;
                } else {
                    throw new UnsupportedOperationException();
                }
            } else {
                return null;
            }
        }
        return null;
    }


    /**
     * 对 Index Scan / Index Only Scan / Bitmap Heap Scan：把 {@code Index Cond} 与 {@code Filter}（或 {@code Recheck Cond}）合并，
     * 否则约束链只含残留 Filter，{@code getRowsAfterFilter} 会得到错误的选择率。
     * <p>
     * 注意：Index Cond / Filter 自身往往已带外层括号，若再写成 {@code (a) AND (b)} 会变成 {@code ((a))) AND (((b)))}
     * 这类畸形嵌套，解析器在约 160 字符处报 rparen。只加<strong>一层</strong>括号包住整段 {@code a AND b} 即可。
     */
    private String combineScanFilterPredicates(StringBuilder path, String filterFromPlan) {
        String nodeType = PgJsonReader.readNodeType(path);
        if (nodeType == null || !nodeTypeRef.isIndexScanNode(nodeType)) {
            return filterFromPlan;
        }
        LinkedHashSet<String> predicates = new LinkedHashSet<>();
        String indexCond = PgJsonReader.readIndexCond(path);
        if (indexCond != null && !indexCond.isEmpty()) {
            predicates.add(indexCond);
        }
        if ("Bitmap Heap Scan".equals(nodeType)) {
            collectBitmapIndexPredicates(path, predicates);
        }
        if (predicates.isEmpty()) {
            return filterFromPlan;
        }
        if (filterFromPlan != null && !filterFromPlan.isEmpty()) {
            predicates.add(filterFromPlan);
        }
        if (predicates.size() == 1) {
            return predicates.iterator().next();
        }
        return "(" + String.join(" AND ", predicates) + ")";
    }

    private void collectBitmapIndexPredicates(StringBuilder path, Set<String> predicates) {
        int childCount = PgJsonReader.readPlansCount(path);
        for (int i = 0; i < childCount; i++) {
            StringBuilder childPath = new StringBuilder(path).append("['Plans'][").append(i).append("]");
            String childType = PgJsonReader.readNodeType(childPath);
            if (childType == null) {
                continue;
            }
            String indexCond = PgJsonReader.readIndexCond(childPath);
            if (indexCond != null && !indexCond.isEmpty()) {
                predicates.add(indexCond);
            }
            if ("Bitmap Index Scan".equals(childType) || !nodeTypeRef.isPassNode(childType)) {
                continue;
            }
            collectBitmapIndexPredicates(childPath, predicates);
        }
    }

    /**
     * Kingbase/PostgreSQL 计划在 {@code Index Cond}、{@code Filter} 中常带 {@code ::timestamp without time zone}。
     * {@link PgSelectOperatorInfoLexer} 对 {@code ::timestamp...} 使用空规则吞掉，会破坏与 CUP 语法的括号对应并触发 rparen 类错误。
     * 在 {@link #transColumnName} / 解析前删除这些 cast，语义不变。
     */
    private String normalizePlanCasts(String expr) {
        if (expr == null) {
            return null;
        }
        String s = expr;
        String prev;
        do {
            prev = s;
            s = PG_PAREN_TIMESTAMP_CAST.matcher(s).replaceAll(")");
            s = PG_PAREN_TIMESTAMPTZ_CAST.matcher(s).replaceAll(")");
            s = PG_PAREN_TIMESTAMP_TZ_WORDS.matcher(s).replaceAll(")");
        } while (!s.equals(prev));
        s = PG_LIT_TIMESTAMP_CAST.matcher(s).replaceAll("'$1'");
        s = PG_LIT_TIMESTAMPTZ_CAST.matcher(s).replaceAll("'$1'");
        s = PG_LIT_TIMESTAMP_TZ_WORDS.matcher(s).replaceAll("'$1'");
        s = PG_LIT_DATE_CAST.matcher(s).replaceAll("'$1'");
        return s;
    }

    /**
     * 将计划里多包的比较式收束为 {@code ((三段列) op …)}：先整段收束 {@link #PG_QUAD_WRAPPED_DATE_RANGE_AND} 等日期范围块，
     * 再对单层 quad/triple 比较式做替换，循环直到稳定。
     */
    String normalizeTripleParenCompare(String expr) {
        if (expr == null) {
            return null;
        }
        String s = expr;
        String prev;
        do {
            prev = s;
            s = replaceTripleWrappedThreeIntEqAndBlock(s);
            s = applyWrappedQuadDateRangeNormalizations(s);
            s = replaceThreePartCompareParenLevel(s, PG_QUAD_PAREN_THREE_PART_COMPARE);
            s = replaceThreePartCompareParenLevel(s, PG_TRIPLE_PAREN_THREE_PART_COMPARE);
            s = replaceThreePartCompareParenLevel(s, PG_TRIPLE_LP_SINGLE_RP_THREE_PART_COMPARE);
            s = collapseTripleLeadingParenBeforeThreePartColumn(s);
        } while (!s.equals(prev));
        return s;
    }

    private static String collapseTripleLeadingParenBeforeThreePartColumn(String s) {
        if (s == null) {
            return null;
        }
        String t = s;
        String prev;
        do {
            prev = t;
            t = QUAD_LP_BEFORE_THREE_PART.matcher(t).replaceAll("((($1)");
            t = TRIPLE_LP_BEFORE_THREE_PART.matcher(t).replaceAll("((($1)");
        } while (!t.equals(prev));
        return t;
    }

    private static String replaceTripleWrappedThreeIntEqAndBlock(String input) {
        Matcher m = PG_TRIPLE_WRAPPED_THREE_INT_EQ_AND.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String repl =
                    "(("
                            + m.group(1)
                            + " = "
                            + m.group(2)
                            + ") AND ("
                            + m.group(3)
                            + " = "
                            + m.group(4)
                            + ") AND ("
                            + m.group(5)
                            + " = "
                            + m.group(6)
                            + "))";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String applyWrappedQuadDateRangeNormalizations(String input) {
        String s = input;
        String prev;
        do {
            prev = s;
            s = replaceWrappedQuadDateRangeBlock(s, PG_QUAD_WRAPPED_DATE_RANGE_AND);
            s = replaceWrappedQuadDateRangeBlock(s, PG_QUAD_WRAPPED_DATE_RANGE_AND_TRIPLE_RP_SECOND);
        } while (!s.equals(prev));
        return s;
    }

    private static String replaceWrappedQuadDateRangeBlock(String input, Pattern pattern) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String col = m.group(1);
            String lit1 = m.group(2);
            String lit2 = m.group(3);
            String repl = "((" + col + ") >= " + lit1 + ") AND ((" + col + ") < " + lit2 + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceThreePartCompareParenLevel(String input, Pattern pattern) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("((" + m.group(1) + ") " + m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private ExecutionNode getFilterNode(StringBuilder path, long rowCount) throws CannotFindSchemaException {
        String planId = path.toString();
        String filterFromPlan = PgJsonReader.readFilterInfo(path);
        if (filterFromPlan != null && filterFromPlan.contains("(NOT (hashed SubPlan")) {
            Matcher hashSubPlan = HASH_SUB_PLAN.matcher(filterFromPlan);
            int count = 0;
            while (hashSubPlan.find()) {
                count++;
            }
            if (count == 1) {
                return transferFilter2AntiJoin(path, rowCount);
            } else {
                throw new UnsupportedOperationException();
            }
        } else {
            String filterInfo = combineScanFilterPredicates(path, filterFromPlan);
            if (filterInfo != null) {
                filterInfo = normalizePlanCasts(filterInfo);
                filterInfo = normalizeTripleParenCompare(filterInfo);
                filterInfo = normalizeUnsupportedSelectPredicates(filterInfo);
            }
            String tableName = resolveTableName(PgJsonReader.readTableName(path.toString()));
            aliasDic.put(PgJsonReader.readAlias(path.toString()), tableName);
            FilterNode node = new FilterNode(planId, rowCount, transColumnName(filterInfo));
            node.setTableName(tableName);
            if (nodeTypeRef.isIndexScanNode(PgJsonReader.readNodeType(path))) {
                if (filterInfo == null) {
                    node.setOutputRows(TableManager.getInstance().getTableSize(tableName));
                } else {
                    node.setIndexScan(true);
                    node.setFilterInfoWithQuote(transColumnName(removeRedundancy(filterInfo, true)));
                }
            }
            return node;
        }
    }

    private ExecutionNode transferFilter2AntiJoin(StringBuilder path, long rowCount) {
        StringBuilder leftNodePath = PgJsonReader.move2LeftChild(path);
        List<String> leftNodeResult = PgJsonReader.readOutput(leftNodePath);
        List<String> outPut = PgJsonReader.readOutput(path);
        String joinInfo = "";
        for (String s : leftNodeResult) {
            String antiJoinTable1 = s.split("\\.")[0];
            String antiJoinKey1 = s.split("\\.")[1];
            String joinColumn1 = antiJoinKey1.split("_")[1];
            for (String value : outPut) {
                String antiJoinKey2 = value.split("\\.")[1];
                String antiJoinTable2 = value.split("\\.")[0];
                String joinColumn2 = antiJoinKey2.split("_")[1];
                if (joinColumn1.equals(joinColumn2)) {
                    joinInfo = antiJoinTable2 + "." + antiJoinKey2 + " = " + antiJoinTable1 + "." + antiJoinKey1;
                }
            }
        }
        joinInfo = "Hash Cond: " + "(" + joinInfo + ")";
        return new JoinNode(path.toString(), rowCount, joinInfo, true, false, BigDecimal.ZERO);
    }

    private ExecutionNode getJoinNode(StringBuilder path, int rowCount) {
        String joinType = PgJsonReader.readNodeType(path);
        String joinInfo = switch (joinType) {
            case "Hash Join" -> PgJsonReader.readHashJoin(path);
            case "Nested Loop" -> PgJsonReader.readIndexJoin(path);
            case "Merge Join" -> PgJsonReader.readMergeJoin(path);
            default -> throw new UnsupportedOperationException();
        };
        if (joinInfo.equals("needReadDeep")) {
            joinInfo = readDeep(path);
            System.out.println(joinInfo);
        }
        
        // ★★★ 添加日志：检查 JOIN 类型 ★★★
        boolean isOuterJoin = PgJsonReader.isOutJoin(path);
        boolean isAntiJoinCheck = PgJsonReader.isAntiJoin(path);
        boolean isSemiJoinCheck = PgJsonReader.isSemiJoin(path);
        boolean isInnerJoin = !isOuterJoin && !isAntiJoinCheck && !isSemiJoinCheck;
        
        System.out.println(String.format("[NJDC_DEBUG] JOIN 类型: %s, OUTER=%s, ANTI=%s, SEMI=%s, INNER=%s, rowCount=%d, joinInfo=%s",
            joinType, isOuterJoin, isAntiJoinCheck, isSemiJoinCheck, isInnerJoin, rowCount, joinInfo));
        
        StringBuilder leftChildPath = PgJsonReader.move2LeftChild(path);
        StringBuilder rightChildPath = PgJsonReader.move2RightChild(path);
        Long leftInputRows = null;
        Long rightInputRows = null;
        if (PgJsonReader.readPlansCount(path) >= 1) {
            leftInputRows = (long) readJoinInputRowCount(leftChildPath);
        }
        if (PgJsonReader.readPlansCount(path) >= 2) {
            rightInputRows = (long) readJoinInputRowCount(rightChildPath);
        }

        BigDecimal pkDistinctProbability = BigDecimal.ZERO;
        if (PgJsonReader.isOutJoin(path)) {
            int pkRowCount, fkRowCount;
            if (PgJsonReader.isRightOuterJoin(path)) {
                pkRowCount = Math.toIntExact(rightInputRows == null ? 0L : rightInputRows);
                fkRowCount = Math.toIntExact(leftInputRows == null ? 0L : leftInputRows);
            } else if (PgJsonReader.isLeftOuterJoin(path)) {
                fkRowCount = Math.toIntExact(rightInputRows == null ? 0L : rightInputRows);
                pkRowCount = Math.toIntExact(leftInputRows == null ? 0L : leftInputRows);
            } else {
                throw new UnsupportedOperationException();
            }
            pkDistinctProbability = computeOuterJoinPkDistinctProbability(path, pkRowCount, fkRowCount, rowCount);
            System.out.println(String.format("[NJDC_DEBUG] OUTER JOIN: pkRowCount=%d, fkRowCount=%d, NJDC_prob=%.4f",
                pkRowCount, fkRowCount, pkDistinctProbability.doubleValue()));
        } else if (PgJsonReader.isAntiJoin(path)) {
            StringBuilder antiJoinLeftPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(path));
            rowCount = PgJsonReader.readRowCount(antiJoinLeftPath) - rowCount;
            System.out.println(String.format("[NJDC_DEBUG] ANTI JOIN: NJDC_prob=%.4f", pkDistinctProbability.doubleValue()));
        } else {
            // INNER JOIN 或其他类型
            System.out.println(String.format("[NJDC_DEBUG] INNER JOIN 或其他: pkDistinctProbability=%.4f (保持为 0)", pkDistinctProbability.doubleValue()));
        }
        boolean isSemiJoin = PgJsonReader.isAntiJoin(path) || PgJsonReader.isSemiJoin(path);
        System.out.println(String.format("[NJDC_DEBUG] 最终 NJDC_prob=%.4f\n", pkDistinctProbability.doubleValue()));
        JoinNode joinNode = new JoinNode(path.toString(), rowCount, joinInfo, PgJsonReader.isAntiJoin(path), isSemiJoin, pkDistinctProbability);
        joinNode.setLeftInputRows(leftInputRows);
        joinNode.setRightInputRows(rightInputRows);
        return joinNode;
    }

    private int readJoinInputRowCount(StringBuilder childPath) {
        StringBuilder skippedChildPath = PgJsonReader.skipNodes(new StringBuilder(childPath));
        int skippedRows = PgJsonReader.readRowCount(skippedChildPath);
        if (skippedRows > 0) {
            return skippedRows;
        }
        int passNodeRows = PgJsonReader.readRowCount(childPath);
        if (passNodeRows > 0 && !Objects.equals(childPath.toString(), skippedChildPath.toString())) {
            logger.warn("JOIN input row count from skipped child is 0; use pass node {} rows={} instead, childPath={}, skippedPath={}",
                    PgJsonReader.readNodeType(childPath), passNodeRows, childPath, skippedChildPath);
            return passNodeRows;
        }
        return skippedRows;
    }

    private BigDecimal computeOuterJoinPkDistinctProbability(StringBuilder path, int pkRowCount, int fkRowCount, int outputRowCount) {
        if (fkRowCount <= 0) {
            logger.warn("OUTER JOIN distinct probability denominator is 0; use 0 instead. nodeType={}, joinType outer=true, pkRows={}, fkRows={}, outputRows={}, path={}",
                    PgJsonReader.readNodeType(path), pkRowCount, fkRowCount, outputRowCount, path);
            return BigDecimal.ZERO;
        }
        long matchedRows = Math.max(0L, (long) pkRowCount + fkRowCount - outputRowCount);
        matchedRows = Math.min(matchedRows, fkRowCount);
        return BigDecimal.valueOf(matchedRows)
                .divide(BigDecimal.valueOf(fkRowCount), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
    }

    String readDeep(StringBuilder path) {
        String currentJoinCond = null;
        StringBuilder leftChildPath = PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(path));
        StringBuilder rightChildPath = PgJsonReader.skipNodes(PgJsonReader.move2RightChild(path));
        String leftType = PgJsonReader.readNodeType(leftChildPath);
        String rightType = PgJsonReader.readNodeType(rightChildPath);
        if (rightType.equals("Nested Loop")) {
            String joinCond = PgJsonReader.readIndexJoin(rightChildPath);
            if (joinCond.equals("needReadDeep")) {
                currentJoinCond = readDeep(rightChildPath);
            } else {
                joinCond = joinCond.replace("Index Cond: (", "");
                joinCond = joinCond.substring(0, (joinCond.length() - 1));
                String table1 = resolveTableName(PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(rightChildPath)).toString())).split("\\.")[1];
                String table2 = resolveTableName(PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(rightChildPath)).toString())).split("\\.")[1];
                List<String> joinCondList = List.of(joinCond.split("AND"));
                for (String eachCond : joinCondList) {
                    if (!eachCond.contains(table1) || !eachCond.contains(table2)) {
                        currentJoinCond = eachCond;
                    }
                }
            }
        } else if (leftType.equals("Nested Loop")) {
            String joinCond = PgJsonReader.readIndexJoin(leftChildPath);
            if (joinCond.equals("needReadDeep")) {
                currentJoinCond = readDeep(leftChildPath);
            } else {
                String table1 = resolveTableName(PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2LeftChild(leftChildPath)).toString())).split("\\.")[1];
                String table2 = resolveTableName(PgJsonReader.readTableName(PgJsonReader.skipNodes(PgJsonReader.move2RightChild(leftChildPath)).toString())).split("\\.")[1];
                List<String> joinCondList = List.of(joinCond.split("AND"));
                for (String eachCond : joinCondList) {
                    if (!eachCond.contains(table1) || !eachCond.contains(table2)) {
                        currentJoinCond = eachCond;
                    }
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }
        assert currentJoinCond != null;
        if (!currentJoinCond.contains("Index Cond:")) {
            currentJoinCond = "Index Cond:" + currentJoinCond;
        }
        return currentJoinCond;
    }

    private ExecutionNode getAggregationNode(StringBuilder path, int rowCount) {
        List<String> groupKey = PgJsonReader.readGroupKey(path);
        String groupKeyInfo = null;
        String tableName = null;
        String aggFilterInfo = PgJsonReader.readFilterInfo(path);
        FilterNode aggFilter = null;
        if (groupKey != null) {
            //todo multiple table name
            List<String> normalizedGroupKeys = groupKey.stream()
                    .map(this::normalizeAggregateGroupKey)
                    .toList();
            groupKeyInfo = String.join(";", normalizedGroupKeys);
            tableName = resolveTableNameFromGroupKey(normalizedGroupKeys.get(0));
            if (aggFilterInfo != null) {
                aggFilter = new FilterNode(path.toString(), rowCount, transColumnName(aggFilterInfo));
                rowCount += PgJsonReader.readRowsRemoved(path);
            }
        } else {
            String subPlanIndex = PgJsonReader.readSubPlanIndex(path);
            if (aggFilterInfo == null && subPlanIndex != null) {
                aggFilterInfo = "(" + removeRedundancy(PgJsonReader.readOutput(path).get(0), false) + "=" + subPlanIndex + ")";
                aggFilter = new FilterNode(path.toString(), 1, transColumnName(aggFilterInfo));
                tableName = getTableNameFromOutput(path);
            } else {
                String distinctGroupKey = extractDistinctGroupKeyFromAggregateOutput(path);
                if (distinctGroupKey != null) {
                    groupKeyInfo = normalizeAggregateGroupKey(distinctGroupKey);
                    tableName = resolveTableNameFromGroupKey(groupKeyInfo);
                }
            }
        }
        AggNode node = new AggNode(path.toString(), rowCount, groupKeyInfo);
        node.setTableName(tableName);
        node.setAggFilter(aggFilter);
        node.setSyntheticDistinctAggregate(groupKey == null && groupKeyInfo != null && aggFilter == null);
        if (aggFilter != null && aggFilter.getInfo() != null && COUNT_GT_LITERAL.matcher(aggFilter.getInfo().trim()).matches()) {
            node.setAggregateFilterKind(AggNode.AggregateFilterKind.COUNT_GT_LITERAL);
        }
        return node;
    }

    private String extractDistinctGroupKeyFromAggregateOutput(StringBuilder path) {
        List<String> outputs = PgJsonReader.readOutput(path);
        if (outputs == null) {
            return null;
        }
        for (String output : outputs) {
            if (output == null) {
                continue;
            }
            String normalized = stripTwoOrThreePartParenCastsLoop(transColumnName(output));
            Matcher matcher = COUNT_DISTINCT_OUTPUT.matcher(normalized);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private ExecutionNode createParentAggNode(StringBuilder parentPath, StringBuilder aggPath) throws TouchstoneException, IOException, SQLException {
        int rowCount;
        int rowsAfterFilter;
        String joinCond = PgJsonReader.readJoinCond(parentPath);
        String leftJoinCond = joinCond.split("=")[0];
        List<String> groupKey = new ArrayList<>();
        groupKey.add(leftJoinCond.substring(1));
        String[] outPut = PgJsonReader.readOutput(aggPath).get(0).split("\\.");
        String tableName = outPut[outPut.length - 2];
        tableName = tableName.replaceAll(".*\\(", "");
        tableName = tableName.split("_")[0];
        tableName = aliasDic.get(tableName);
        getExecutionTreeRes(aggPath);
        String aggFilterInfo = PgJsonReader.readJoinFilter(parentPath);
        if (aggFilterInfo != null) {
            String aggOutPut = PgJsonReader.readOutput(aggPath).get(0);
            aggFilterInfo = aggFilterInfo.replace("(SubPlan 1)", aggOutPut);
            rowsAfterFilter = PgJsonReader.readRowCount(parentPath);
            if (!aggOutPut.toLowerCase(Locale.ROOT).contains("min") && !aggOutPut.toLowerCase(Locale.ROOT).contains("max")) {
                rowCount = PgJsonReader.readAggGroup(aggPath);
            } else {
                rowCount = rowsAfterFilter;
            }
        } else {
            throw new UnsupportedOperationException();
        }
        groupKey = groupKey.stream().map(this::normalizeAggregateGroupKey).toList();
        AggNode node = new AggNode(aggPath.toString(), rowCount, String.join(";", groupKey));
        node.setAggFilter(new FilterNode(aggPath.toString(), rowsAfterFilter, transColumnName(aggFilterInfo)));
        node.setTableName(tableName);
        return node;
    }

    private String normalizeAggregateGroupKey(String groupKey) {
        return stripTwoOrThreePartParenCastsLoop(transColumnName(groupKey));
    }

    private String resolveTableNameFromGroupKey(String groupKey) {
        if (groupKey == null) {
            return null;
        }
        Matcher matcher = TWO_OR_THREE_PART_REF.matcher(groupKey.replace("\"", ""));
        String lastColumnRef = null;
        while (matcher.find()) {
            lastColumnRef = matcher.group(1);
        }
        if (lastColumnRef == null) {
            return null;
        }
        String[] splitColumns = lastColumnRef.split("\\.");
        if (splitColumns.length == 2) {
            return aliasDic.get(splitColumns[0]);
        }
        if (splitColumns.length >= 3) {
            return splitColumns[0] + "." + splitColumns[1];
        }
        return null;
    }

    private ExecutionNode getExecutionNode(StringBuilder path) throws TouchstoneException {
        String nodeType = PgJsonReader.readNodeType(path);
        if (nodeType == null) {
            return null;
        }
        int rowCount = PgJsonReader.readRowCount(path);
        if (nodeTypeRef.isFilterNode(nodeType)) {
            return getFilterNode(path, rowCount);
        } else if (nodeTypeRef.isJoinNode(nodeType)) {
            return getJoinNode(path, rowCount);
        } else if (nodeTypeRef.isAggregateNode(nodeType)) {
            return getAggregationNode(path, rowCount);
        } else {
            throw new UnsupportedOperationException("Unsupported PG/KingBase plan node type: " + nodeType + ", path=" + path);
        }
    }

    public String transColumnName(String filterInfo) {
        if (filterInfo == null) {
            return null;
        }
        // todo: delete if open-gauss fix the output bug
        StringBuilder tmpStr = new StringBuilder();
        Matcher matcherOnlyForOpenGauss = FullCanonicalColumnName.matcher(filterInfo);
        while (matcherOnlyForOpenGauss.find()) {
            String[] tableNameAndColName = matcherOnlyForOpenGauss.group().split("\\.");
            aliasDic.put(tableNameAndColName[1], tableNameAndColName[0] + "." + tableNameAndColName[1]);
            matcherOnlyForOpenGauss.appendReplacement(tmpStr, tableNameAndColName[1] + "." + tableNameAndColName[2]);
        }
        matcherOnlyForOpenGauss.appendTail(tmpStr);

        String[] splitResults = tmpStr.toString().split("'");
        for (int i = 0; i < splitResults.length; i++) {
            if (i % 2 == 0) {
                StringBuilder filter = new StringBuilder();
                Matcher m = CanonicalColumnName.matcher(splitResults[i]);
                while (m.find()) {
                    String[] tableNameAndColName = m.group().split("\\.");
                    String replacement = expandTwoPartColumnRef(
                            tableNameAndColName[0].replace("\"", ""),
                            tableNameAndColName[1].replace("\"", ""));
                    if (replacement == null) {
                        replacement = m.group();
                    }
                    m.appendReplacement(filter, Matcher.quoteReplacement(replacement));
                }
                m.appendTail(filter);
                splitResults[i] = filter.toString();
            }
        }
        return normalizeBooleanLiterals(removeRedundancy(String.join("'", splitResults), false));
    }

    public String removeRedundancy(String filterInfo, boolean keepQuotes) {
        int filterLocation = keepQuotes ? 0 : 1;
        Matcher m = REDUNDANCY.matcher(filterInfo);
        StringBuilder filter = new StringBuilder();
        while (m.find()) {
            String date = m.group().split("::")[0];
            m.appendReplacement(filter, date.substring(filterLocation, date.length() - filterLocation));
        }
        m.appendTail(filter);
        return filter.toString();
    }

    /**
     * PostgreSQL/KingBase 的 boolean 列在 EXPLAIN 中以 true/false 字面量出现，
     * 但 Mirage 的 filter parser 只识别数值，此处统一转为 1/0。
     */
    private String normalizeBooleanLiterals(String filterInfo) {
        if (filterInfo == null) return null;
        Matcher m = BOOL_LITERAL.matcher(filterInfo);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.group().equalsIgnoreCase("true") ? "1" : "0");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 在 {@link #transColumnName} 之前调用：大整型加引号，并剔除主计划中不可解析的 InitPlan
     * {@code ANY((ARRAY[$n])::text[])} 过滤片段。该片段对应的真实值域不在当前 operator_info 内，
     * 保留会导致解析器失败；剔除后仍可保留同一 Filter 中其他可建模谓词。
     */
    private String normalizeUnsupportedSelectPredicates(String expr) {
        if (expr == null) {
            return null;
        }
        String normalized = elideUnmodeledTextEqAnyPredicates(expr);
        normalized = elideUnmodeledSubPlanAlternatives(normalized);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return quoteLargeUnquotedIntegerLiterals(normalized);
    }

    private String elideUnmodeledTextEqAnyPredicates(String expr) {
        String out = expr;
        Matcher m = PG_TEXT_EQ_ANY_TEXTARRAY.matcher(expr);
        while (m.find()) {
            logger.warn(
                    "Unmodeled filter elided: (= ANY on InitPlan ARRAY[$n]::text[]). "
                            + "Use a separate subquery/rewritten SQL constraint for this predicate. matched: {}",
                    m.group());
            out = removeConjunct(out, m.group());
        }
        return normalizeEmptyParens(out);
    }

    private String elideUnmodeledSubPlanAlternatives(String expr) {
        if (expr == null) {
            return null;
        }
        String out = expr;
        Matcher m = SUBPLAN_ALTERNATIVE.matcher(expr);
        while (m.find()) {
            logger.warn(
                    "Unmodeled filter elided: planner SubPlan alternative placeholder. "
                            + "Use the rewritten EXISTS/semi-join predicate for this filter when possible. matched: {}",
                    m.group());
            out = removeConjunct(out, m.group());
        }
        return normalizeEmptyParens(out);
    }

    private static String removeConjunct(String expr, String conjunct) {
        String quoted = Pattern.quote(conjunct);
        String out = expr.replaceFirst("(?i)\\s+AND\\s+" + quoted, "");
        if (!out.equals(expr)) {
            return out;
        }
        out = expr.replaceFirst("(?i)" + quoted + "\\s+AND\\s+", "");
        if (!out.equals(expr)) {
            return out;
        }
        return expr.replace(conjunct, "");
    }

    private static String normalizeEmptyParens(String expr) {
        if (expr == null) {
            return null;
        }
        String out = expr.trim();
        if (out.replace("(", "").replace(")", "").isBlank()) {
            return null;
        }
        while (hasRedundantOuterWrapper(out)) {
            out = out.substring(1, out.length() - 1).trim();
        }
        return out.isBlank() ? null : out;
    }

    private static boolean hasRedundantOuterWrapper(String s) {
        if (s == null || s.length() < 4 || s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')') {
            return false;
        }
        String inner = s.substring(1, s.length() - 1).trim();
        return inner.length() >= 2 && inner.charAt(0) == '(' && inner.charAt(inner.length() - 1) == ')'
                && outerParensWrapWholeExpression(inner);
    }

    private static boolean outerParensWrapWholeExpression(String s) {
        int depth = 0;
        boolean inSingleQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (inSingleQuote) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < s.length() - 1) {
                    return false;
                }
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static String quoteLargeUnquotedIntegerLiterals(String s) {
        Matcher m = UNQUOTED_LARGE_INT_COMPARE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + " '" + m.group(2) + "'"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String stripJoinCondPrefix(String joinInfo) {
        if (joinInfo == null) {
            return null;
        }
        return joinInfo.replaceFirst("(?i)^(Hash|Merge|Index)\\s+Cond:\\s*", "").trim();
    }

    private static String stripTwoOrThreePartParenCastsLoop(String s) {
        if (s == null) {
            return null;
        }
        String t = s;
        String prev;
        do {
            prev = t;
            t = TWO_OR_THREE_PART_PAREN_CAST.matcher(t).replaceAll("$1");
        } while (!t.equals(prev));
        return t;
    }

    private static String stripRedundantColumnParensLoop(String s) {
        if (s == null) {
            return null;
        }
        String t = s;
        String prev;
        do {
            prev = t;
            t = REDUNDANT_TWO_OR_THREE_PART_PARENS.matcher(t).replaceAll("$1");
            t = REDUNDANT_SINGLE_IDENT_PARENS.matcher(t).replaceAll("$1");
        } while (!t.equals(prev));
        return t;
    }

    private static String stripSingleIdentParenCastsLoop(String s) {
        if (s == null) {
            return null;
        }
        String t = s;
        String prev;
        do {
            prev = t;
            t = SINGLE_IDENT_PAREN_CAST.matcher(t).replaceAll("$1");
        } while (!t.equals(prev));
        return t;
    }

    /** 将 {@code alias.col} 按 {@link #aliasDic} 展开为 {@code [schema, table, col]}；已是三段则原样返回。 */
    private String[] ensureThreePartJoinRef(String ref) {
        if (ref == null) {
            return null;
        }
        String cleaned = ref.replace("\"", "");
        String[] parts = cleaned.split("\\.");
        if (parts.length == 3) {
            return parts;
        }
        if (parts.length == 2) {
            String[] derived = derivedColumnLineage.get(lineageKey(parts[0], parts[1]));
            if (derived != null) {
                return Arrays.copyOf(derived, derived.length);
            }
            return ensureThreePartBaseRef(cleaned);
        }
        return null;
    }

    private String[] ensureThreePartBaseRef(String ref) {
        if (ref == null) {
            return null;
        }
        String cleaned = ref.replace("\"", "");
        String[] parts = cleaned.split("\\.");
        if (parts.length == 3) {
            return parts;
        }
        if (parts.length == 2) {
            String mapped = aliasDic.get(parts[0]);
            if (mapped != null) {
                String[] st = mapped.replace("\"", "").split("\\.", 2);
                if (st.length == 2) {
                    return new String[]{st[0], st[1], parts[1]};
                }
            }
        }
        return null;
    }

    private String expandTwoPartColumnRef(String alias, String col) {
        String[] derived = derivedColumnLineage.get(lineageKey(alias, col));
        if (derived != null) {
            return String.join(".", derived);
        }
        String mapped = aliasDic.get(alias);
        return mapped == null ? null : mapped + "." + col;
    }

    private static int countTopLevelSingleEquals(String s) {
        int depth = 0;
        int eq = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '=' && depth == 0) {
                if (i > 0 && s.charAt(i - 1) == '!') {
                    continue;
                }
                if (i + 1 < s.length() && s.charAt(i + 1) == '=') {
                    continue;
                }
                if (i > 0 && s.charAt(i - 1) == '<') {
                    continue;
                }
                if (i > 0 && s.charAt(i - 1) == '>') {
                    continue;
                }
                eq++;
            }
        }
        return eq;
    }

    private static String stripRedundantOuterParensAroundSingleEq(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        while (t.length() >= 2 && t.charAt(0) == '(' && t.charAt(t.length() - 1) == ')') {
            String inner = t.substring(1, t.length() - 1).trim();
            if (countTopLevelSingleEquals(inner) != 1) {
                break;
            }
            t = inner;
        }
        return t;
    }

    @Override
    public double analyzeJoinInfo(String joinInfo, String[] result) {
        joinInfo = transColumnName(joinInfo);
        Matcher eqCondition = JOIN_EQ_OPERATOR.matcher(joinInfo);
        double filterProbability = 1;
        if (!eqCondition.find()) {
            return filterProbability;
        }
        if (eqCondition.groupCount() > 1) {
            throw new UnsupportedOperationException();
        }
        String normalizedEq = stripJoinCondPrefix(joinInfo);
        normalizedEq = stripRedundantOuterParensAroundSingleEq(normalizedEq);
        normalizedEq = stripTwoOrThreePartParenCastsLoop(normalizedEq);
        normalizedEq = stripRedundantOuterParensAroundSingleEq(normalizedEq);
        String forEqMatch = normalizedEq;
        if (!forEqMatch.startsWith("(") || !forEqMatch.endsWith(")")) {
            forEqMatch = "(" + forEqMatch + ")";
        }
        List<List<String>> matches = matchPattern(EQ_OPERATOR, forEqMatch);
        if (matches.isEmpty()) {
            Matcher loose = LOOSE_TWO_OR_THREE_PART_EQ.matcher(normalizedEq);
            if (loose.find()) {
                List<String> synthetic = new ArrayList<>(3);
                synthetic.add(loose.group(0));
                synthetic.add(loose.group(1));
                synthetic.add(loose.group(2));
                matches = Collections.singletonList(synthetic);
                logger.debug("analyzeJoinInfo: used loose two/three-part equality match");
            }
        }
        if (matches.isEmpty()) {
            logger.warn("analyzeJoinInfo: cannot parse join equality from: {}", joinInfo);
            return filterProbability;
        }
        String[] firstLeft = ensureThreePartJoinRef(matches.getFirst().get(1));
        String[] firstRight = ensureThreePartJoinRef(matches.getFirst().get(2));
        if (firstLeft == null || firstRight == null) {
            logger.warn("analyzeJoinInfo: cannot resolve join columns to schema.table.col: {} = {}",
                    matches.getFirst().get(1), matches.getFirst().get(2));
            return filterProbability;
        }
        String leftTable = firstLeft[0] + CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL + firstLeft[1];
        String rightTable = firstRight[0] + CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL + firstRight[1];
        List<String> leftCols = new ArrayList<>();
        List<String> rightCols = new ArrayList<>();
        for (List<String> match : matches) {
            String[] leftJoinInfos = ensureThreePartJoinRef(match.get(1));
            String[] rightJoinInfos = ensureThreePartJoinRef(match.get(2));
            if (leftJoinInfos == null || rightJoinInfos == null) {
                logger.warn("analyzeJoinInfo: skip match, unresolved ref: {} = {}", match.get(1), match.get(2));
                continue;
            }
            String currLeftTable = String.format("%s.%s", leftJoinInfos[0], leftJoinInfos[1]);
            String currLeftCol = leftJoinInfos[2];
            String currRightTable = String.format("%s.%s", rightJoinInfos[0], rightJoinInfos[1]);
            String currRightCol = rightJoinInfos[2];
            if (!leftTable.equals(currLeftTable) || !rightTable.equals(currRightTable)) {
                double tmpFilterProbability = computeMatchProbability(String.join(".", leftJoinInfos), String.join(".", rightJoinInfos));
                logger.info("deal with multiple fks {}", tmpFilterProbability);
                filterProbability *= tmpFilterProbability;
                break;
            }
            leftCols.add(currLeftCol);
            rightCols.add(currRightCol);
        }
        if (leftCols.isEmpty()) {
            logger.warn("analyzeJoinInfo: no usable equality pair after resolution: {}", joinInfo);
            return filterProbability;
        }
        String leftCol = String.join(",", leftCols);
        String rightCol = String.join(",", rightCols);
        result[0] = leftTable;
        result[1] = leftCol;
        result[2] = rightTable;
        result[3] = rightCol;
        return filterProbability;
    }


    public double computeMatchProbability(String leftColName, String rightColName) {
        boolean withTheSameColumnType = ColumnManager.getInstance().getColumnType(leftColName)
                .equals(ColumnManager.getInstance().getColumnType(rightColName));
        boolean withTheSameSpecialValue = ColumnManager.getInstance().getColumn(leftColName).getSpecialValue() ==
                ColumnManager.getInstance().getColumn(rightColName).getSpecialValue();
        boolean withTheSameStart = ColumnManager.getInstance().getMin(leftColName) ==
                ColumnManager.getInstance().getMin(rightColName);
        boolean withTheSameRange = ColumnManager.getInstance().getNdv(leftColName) ==
                ColumnManager.getInstance().getNdv(rightColName);
        if (withTheSameColumnType && withTheSameRange && withTheSameSpecialValue && withTheSameStart) {
            logger.warn("infer {} and {} reference the same primary key column", leftColName, rightColName);
            return 1.0 / ColumnManager.getInstance().getNdv(leftColName);
        } else {
            logger.error("infer {} and {} may not reference the same primary key column", leftColName, rightColName);
            return 1.0;
        }
    }

    @Override
    public List<List<String[]>> splitQueryPlan(List<String[]> queryPlan) {
        String queryPlanString = queryPlan.stream().map(queryPlanLine -> queryPlanLine[0]).collect(Collectors.joining());
        queryPlanString = PlanJsonTransforms.foldAppendPartitionSeqScans(queryPlanString);
        PgJsonReader.setReadContext(queryPlanString);
        String queryPlanMainTree = PgJsonReader.readTheWholePlan();
        StringBuilder path = PgJsonReader.getRootPath();
        List<List<String[]>> queryPlans = new LinkedList<>();
        if (PgJsonReader.hasInitPlan(path)) {
            for (int i = 0; i < PgJsonReader.readPlansCount(path); i++) {
                String subPlanName = PgJsonReader.readSubPlanIndex(path, i);
                if (subPlanName != null) {
                    String subQueryPlan = PgJsonReader.readPlan(path, i);
                    if (i == PgJsonReader.readPlansCount(path) - 1) {
                        queryPlanMainTree = queryPlanMainTree.replace(subQueryPlan, "");
                    } else {
                        queryPlanMainTree = queryPlanMainTree.replace(subQueryPlan + ",", "");
                    }
                    queryPlans.add(Collections.singletonList(new String[]{PgJsonReader.formatPlan(subQueryPlan)}));
                }
            }
        }
        queryPlans.addAll(splitSetOperationBranches(queryPlanMainTree));
        if (queryPlans.isEmpty()) {
            queryPlans.add(Collections.singletonList(new String[]{queryPlanMainTree}));
        }
        return queryPlans;
    }

    private List<List<String[]>> splitSetOperationBranches(String queryPlanMainTree) {
        PgJsonReader.setReadContext(queryPlanMainTree);
        StringBuilder setOpPath = findTopLevelSetOperationAppend(PgJsonReader.getRootPath());
        if (setOpPath == null) {
            return Collections.singletonList(Collections.singletonList(new String[]{queryPlanMainTree}));
        }
        List<List<String[]>> split = new ArrayList<>();
        int childCount = PgJsonReader.readPlansCount(setOpPath);
        for (int i = 0; i < childCount; i++) {
            String branchPlan = PgJsonReader.readPlan(setOpPath, i);
            split.add(Collections.singletonList(new String[]{PgJsonReader.formatPlan(branchPlan)}));
        }
        return split;
    }

    private StringBuilder findTopLevelSetOperationAppend(StringBuilder path) {
        StringBuilder current = new StringBuilder(path);
        while (true) {
            String nodeType = PgJsonReader.readNodeType(current);
            if (nodeType == null) {
                return null;
            }
            if ("Append".equals(nodeType) && !PgJsonReader.isPartitionLikeAppend(current)) {
                return current;
            }
            if (!isSetOperationWrapperNode(current, nodeType)) {
                return null;
            }
            current = PgJsonReader.move2LeftChild(current);
        }
    }

    private boolean isSetOperationWrapperNode(StringBuilder path, String nodeType) {
        if (PgJsonReader.readPlansCount(path) != 1) {
            return false;
        }
        if (PgJsonReader.isSkippablePassNode(path)) {
            return true;
        }
        return "Aggregate".equals(nodeType) || "Result".equals(nodeType) || "WindowAgg".equals(nodeType);
    }

    @Override
    public List<Map.Entry<String, String>> splitQueryPlanForMultipleAggregate() {
        if (pathForSplit == null) {
            return null;
        } else {
            List<Map.Entry<String, String>> tableNameAndFilterInfo = new LinkedList<>();
            StringBuilder path = PgJsonReader.move2LeftChild(PgJsonReader.move2LeftChild(pathForSplit));
            String tableName = resolveTableName(PgJsonReader.readTableName(path.toString())).split("\\.")[1];
            String merged = combineScanFilterPredicates(path, PgJsonReader.readFilterInfo(path));
            String normalized = merged == null ? null : normalizeTripleParenCompare(normalizePlanCasts(merged));
            normalized = normalized == null ? null : normalizeUnsupportedSelectPredicates(normalized);
            String filterInfo = normalized == null ? null : removeRedundancy(normalized, true);
            tableNameAndFilterInfo.add(new AbstractMap.SimpleEntry<>(tableName, filterInfo));
            pathForSplit = null;
            return tableNameAndFilterInfo;
        }
    }

    public boolean canNotDeal(StringBuilder path) throws SQLException, TouchstoneException, IOException {
        String nodeType = PgJsonReader.readNodeType(path);
        StringBuilder leftPath = PgJsonReader.move2LeftChild(path);
        String leftNodeType = PgJsonReader.readNodeType(leftPath);
        String tableName = resolveTableName(PgJsonReader.readTableName(path.toString())).split("\\.")[1];
        if (nodeTypeRef.isAggregateNode(leftNodeType) && nodeTypeRef.isIndexScanNode(nodeType)) {
            logger.error("cannot deal with {}", path);
            getExecutionTreeRes(PgJsonReader.move2LeftChild(leftPath));
            return !tableName.equals(getTableNameFromOutput(leftPath));
        } else {
            return false;
        }
    }

    private String getTableNameFromOutput(StringBuilder path) {
        String outPut = PgJsonReader.readOutput(path).getFirst();
        Set<String> tableNames = aliasDic.keySet().stream().filter(outPut::contains)
                .map(alias -> aliasDic.get(alias)).collect(Collectors.toSet());
        if (tableNames.size() > 1) {
            logger.error(rb.getString("CannotRecognizeMultipleTables"));
            return null;
        } else {
            return tableNames.iterator().next();
        }
    }

    @Override
    public LogicNode analyzeSelectOperator(String operatorInfo) throws Exception {
        String normalized = operatorInfo == null ? null : normalizeUnsupportedSelectPredicates(operatorInfo);
        String input = normalized != null ? normalized : operatorInfo;
        try {
            return parser.parseSelectOperatorInfo(input);
        } catch (Exception firstError) {
            String fallback = normalizeSingleTablePredicateForParser(input);
            if (fallback == null || fallback.equals(input)) {
                throw firstError;
            }
            return parser.parseSelectOperatorInfo(fallback);
        }
    }

    private String normalizeSingleTablePredicateForParser(String operatorInfo) {
        if (operatorInfo == null) {
            return null;
        }
        String normalized = normalizePlanCasts(operatorInfo);
        normalized = normalizeTripleParenCompare(normalized);
        normalized = stripSimpleTypeCasts(stripTwoOrThreePartParenCastsLoop(normalized));
        normalized = stripSingleIdentParenCastsLoop(normalized);
        normalized = stripRedundantColumnParensLoop(normalized);
        normalized = normalized.replaceAll("'([^']*)'\\s*::\\s*[a-zA-Z0-9_.]+(?:\\s+[a-zA-Z0-9_]+)*(?:\\[\\])?", "'$1'");
        normalized = normalized.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        if (countTopLevelSingleEquals(normalized) == 1 && !(normalized.startsWith("(") && normalized.endsWith(")"))) {
            normalized = "(" + normalized + ")";
        }
        return normalized;
    }
}
