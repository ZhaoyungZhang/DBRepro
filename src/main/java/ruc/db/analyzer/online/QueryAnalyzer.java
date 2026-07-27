package ruc.db.analyzer.online;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ruc.db.LanguageManager;
import ruc.db.analyzer.online.node.AggNode;
import ruc.db.analyzer.online.node.ExecutionNode;
import ruc.db.analyzer.online.node.ExecutionNodeType;
import ruc.db.analyzer.online.node.FilterNode;
import ruc.db.analyzer.online.node.JoinNode;
import ruc.db.dbconnector.DbConnector;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.filter.LogicNode;
import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.filter.BoolExprType;
import ruc.db.generator.constraintchain.filter.operation.CompareOperator;
import ruc.db.generator.constraintchain.filter.operation.UniVarFilterOperation;
import ruc.db.analyzer.online.adapter.pg.PgJsonReader;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.analyzer.online.adapter.pg.PlanJsonTransforms;
import ruc.db.generator.constraintchain.join.ConstraintChainPkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintNodeJoinType;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.TableManager;
import static ruc.db.utils.CommonUtils.CANONICAL_NAME_CONTACT_SYMBOL;
import static ruc.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;
import ruc.db.generator.GenericJoinAntiDomain;
import ruc.db.generator.GenericJoinWeightEstimator;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.utils.exception.TouchstoneException;
import ruc.db.utils.exception.analyze.UnsupportedSelect;
import ruc.db.utils.exception.schema.CannotFindSchemaException;

public class QueryAnalyzer {

    protected static final Logger logger = LoggerFactory.getLogger(QueryAnalyzer.class);
    private static final int SKIP_JOIN_TAG = -1;
    private static final int STOP_CONSTRUCT = -2;
    private static final int SKIP_SELF_JOIN = -3;
    private static final Pattern SUBPLAN_ALTERNATIVE_PLACEHOLDER = Pattern.compile(
            "^\\(*\\s*alternatives:\\s*SubPlan\\s+\\d+\\s+or\\s+hashed\\s+SubPlan\\s+\\d+\\s*\\)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CANONICAL_COLUMN_REF = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_$]*\\.[a-zA-Z_][a-zA-Z0-9_$]*\\.[a-zA-Z_][a-zA-Z0-9_$]*)");
    private static final Pattern QUALIFIED_COLUMN_REF = Pattern.compile(
            "([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){1,2})");
    private static final Pattern COUNT_GT_LITERAL = Pattern.compile(
            "^\\(*\\s*count\\s*\\(\\s*\\*\\s*\\)\\s*>\\s*\\d+\\s*\\)*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_SINGLE_TABLE_EQ_LITERAL = Pattern.compile(
            "^\\(*\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){0,2})\\s*=\\s*'([^']*)'\\s*\\)*$",
            Pattern.CASE_INSENSITIVE);
    private final AbstractAnalyzer abstractAnalyzer;
    private final DbConnector dbConnector;
    protected double skipNodeThreshold = 0.01;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    private static final boolean OPEN_SKIP_JOIN_FEATURE = true;


    public QueryAnalyzer(AbstractAnalyzer abstractAnalyzer, DbConnector dbConnector) {
        this.abstractAnalyzer = abstractAnalyzer;
        this.dbConnector = dbConnector;
    }

    public void setAliasDic(Map<String, String> aliasDic) {
        abstractAnalyzer.setAliasDic(aliasDic);
    }


    /**
     * 根据输入的列名统计非重复值的个数，进而给出该列是否为主键
     *
     * @param pkTable 需要测试的主表
     * @param pkCol   主键
     * @param fkTable 外表
     * @param fkCol   外键
     * @return 该列是否为主键
     * @throws TouchstoneException 由于逻辑错误无法判断是否为主键的异常
     * @throws SQLException        无法通过数据库SQL查询获得多列属性的ndv
     */
    /**
     * 是否走主键侧 JOIN 约束链分支（PK_JOIN）。若 NDV 推断的「主键侧」在表行数上不满足 join 键唯一性，则降级为外键侧分支（FK_JOIN / GENERIC），不再抛错。
     * 与同包测试共用可见性。
     */
    boolean shouldUsePkJoinBranch(String pkTable, String pkCol, String fkTable, String fkCol) throws TouchstoneException, SQLException {
        if (TableManager.getInstance().isRefTable(fkTable, fkCol, pkTable + "." + pkCol)) {
            return true;
        }
        if (TableManager.getInstance().isRefTable(pkTable, pkCol, fkTable + "." + fkCol)) {
            return false;
        }
        int leftTableNdv = estimateJoinKeyNdv(pkTable, pkCol);
        int rightTableNdv = estimateJoinKeyNdv(fkTable, fkCol);
        long leftTableSize = TableManager.getInstance().getTableSize(pkTable);
        long rightTableSize = TableManager.getInstance().getTableSize(fkTable);
        boolean inferredPkOnLeft;
        if (leftTableNdv == rightTableNdv) {
            if (leftTableSize == rightTableSize) {
                logger.warn("Join direction ambiguous between {}.{} and {}.{} (same table size and NDV); use FK_JOIN/GENERIC branch instead",
                        pkTable, pkCol, fkTable, fkCol);
                return false;
            }
            inferredPkOnLeft = leftTableSize < rightTableSize;
        } else {
            inferredPkOnLeft = leftTableNdv > rightTableNdv;
        }
        if (inferredPkOnLeft && leftTableSize > leftTableNdv) {
            logger.warn("Join key not unique on inferred PK side {}.{}, using FK_JOIN branch instead", pkTable, pkCol);
            return false;
        }
        if (!inferredPkOnLeft && rightTableSize > rightTableNdv) {
            logger.warn("Join key not unique on inferred PK side {}.{}, using FK_JOIN branch instead", fkTable, fkCol);
            return false;
        }
        return inferredPkOnLeft;
    }

    private int estimateJoinKeyNdv(String tableName, String joinCols) throws SQLException {
        long tableSize;
        try {
            tableSize = Math.max(1L, TableManager.getInstance().getTableSize(tableName));
        } catch (CannotFindSchemaException e) {
            tableSize = 1L;
        }
        if (joinCols == null || joinCols.isBlank()) {
            return (int) Math.min(Integer.MAX_VALUE, tableSize);
        }
        if (!joinCols.contains(",")) {
            return Math.max(1, ColumnManager.getInstance().getNdv(tableName + CANONICAL_NAME_CONTACT_SYMBOL + joinCols.trim()));
        }
        if (dbConnector != null) {
            return Math.max(1, dbConnector.getMultiColNdv(tableName, joinCols));
        }
        long estimate = 1L;
        for (String col : joinCols.split(",")) {
            String trimmed = col.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String canonical = trimmed.contains(".") ? trimmed : tableName + CANONICAL_NAME_CONTACT_SYMBOL + trimmed;
            int ndv = Math.max(1, ColumnManager.getInstance().getNdv(canonical));
            if (estimate > tableSize / ndv) {
                estimate = tableSize;
                break;
            }
            estimate *= ndv;
        }
        estimate = Math.max(1L, Math.min(tableSize, estimate));
        return (int) Math.min(Integer.MAX_VALUE, estimate);
    }

    private static boolean isDeclaredRefJoin(String localTable, String localCol, String externalTable, String externalCol) {
        try {
            return TableManager.getInstance().isRefTable(localTable, localCol, externalTable + "." + externalCol)
                    || TableManager.getInstance().isRefTable(externalTable, externalCol, localTable + "." + localCol);
        } catch (TouchstoneException e) {
            return false;
        }
    }

    /**
     * FK_JOIN 节点上 {@link JoinConstraintJoinModel} 的判定（与 {@link #analyzeJoinNode} 中逻辑一致）：仅当参照键为参照表
     * <strong>完整主键</strong>时为 {@link JoinConstraintJoinModel#PK_FK}，否则为 {@link JoinConstraintJoinModel#GENERIC}
     * （计划基数、桶权重、反域等）。
     */
    public static JoinConstraintJoinModel resolveJoinModelForFkJoinNode(
            String localTable, String localCol, String externalTable, String externalCol) {
        boolean declaredRefJoin = isDeclaredRefJoin(localTable, localCol, externalTable, externalCol);
        boolean pkFkJoinModel = refJoinKeyIsExactlyTablePrimaryKey(externalTable, externalCol);
        if (!pkFkJoinModel) {
            logger.info(
                    "FK_JOIN 参照侧 {} 的 join 键 [{}] 非该表完整主键，使用 joinModel=GENERIC（计划基数 targetJoinRows 等）；不写入物理 schema foreignKeys。",
                    externalTable, externalCol);
        } else if (!declaredRefJoin) {
            logger.debug(
                    "FK_JOIN 参照侧 {} 的 join 键 [{}] 为完整主键，使用 joinModel=PK_FK 并登记为生成阶段物理依赖。",
                    externalTable, externalCol);
        }
        return pkFkJoinModel ? JoinConstraintJoinModel.PK_FK : JoinConstraintJoinModel.GENERIC;
    }

    static void registerJoinReferenceForGeneration(
            String localTable,
            String localCol,
            String externalTable,
            String externalCol,
            JoinConstraintJoinModel joinModel) throws TouchstoneException {
        if (joinModel == JoinConstraintJoinModel.PK_FK) {
            TableManager.getInstance().setTmpForeignKeys(localTable, localCol, externalTable, externalCol);
        } else {
            logger.debug(
                    "Skip physical FK registration for GENERIC join: {}.{} -> {}.{}",
                    localTable, localCol, externalTable, externalCol);
        }
    }

    /**
     * {@link JoinConstraintJoinModel#PK_FK} 仅当「参照侧等值键列集合 = 该表 schema 中声明的完整主键列集合」时为真（列名比较为短名，顺序无关）。
     * 仅「schema 里有 FK 边」但参照的是非主键列（如 trml_addr_code）时应为假，走 {@link JoinConstraintJoinModel#GENERIC}，
     * 以便使用计划基数 {@code targetJoinRows}、桶权重与反域等路径。
     */
    static boolean refJoinKeyIsExactlyTablePrimaryKey(String externalTable, String externalCol) {
        try {
            List<String> pkList = TableManager.getInstance().getCompletePrimaryKeysList(externalTable);
            if (pkList == null || pkList.isEmpty()) {
                return false;
            }
            Set<String> pkShort = new HashSet<>();
            for (String pk : pkList) {
                int dot = pk.lastIndexOf('.');
                pkShort.add(dot >= 0 ? pk.substring(dot + 1) : pk);
            }
            Set<String> refShort = new HashSet<>();
            for (String part : externalCol.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) {
                    continue;
                }
                int dot = t.lastIndexOf('.');
                refShort.add(dot >= 0 ? t.substring(dot + 1) : t);
            }
            return !pkShort.isEmpty() && pkShort.equals(refShort);
        } catch (CannotFindSchemaException e) {
            return false;
        }
    }

    /**
     * 提取约束链失败时，仅当节点有表名才可用行数比例决定是否截断路径；JOIN 等节点常无 tableName，避免 getTableSize(null)。
     */
    static boolean canUseTableSizeForSkipRatio(ExecutionNode executionNode) {
        String name = executionNode.getTableName();
        return name != null && !name.isBlank();
    }

    /**
     * 分析一个节点，提取约束链信息
     *
     * @param node            需要分析的节点
     * @param constraintChain 约束链
     * @return 节点行数，小于零代表停止继续向上分析
     * @throws TouchstoneException 节点分析出错
     * @throws SQLException        无法收集多列主键的ndv
     */
    private long analyzeNode(ExecutionNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException, SQLException {
        return switch (node.getType()) {
            case JOIN -> analyzeJoinNode((JoinNode) node, constraintChain, lastNodeLineCount);
            case FILTER -> analyzeSelectNode(node, constraintChain, lastNodeLineCount);
            case AGGREGATE -> analyzeAggregateNode((AggNode) node, constraintChain, lastNodeLineCount);
        };
    }

    private long analyzeSelectNode(ExecutionNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException {
        if (node.getInfo() == null || node.getInfo().isBlank()) {
            logger.warn("Skip filter node with empty/unmodeled predicate on table {}", node.getTableName());
            return node.getOutputRows();
        }
        if (isUnmodeledSubPlanAlternative(node.getInfo())) {
            logger.warn("Skip unmodeled SubPlan alternative filter on table {}: {}", node.getTableName(), node.getInfo());
            return node.getOutputRows();
        }
        String predicateForAnalysis = node.getInfo();
        if (node.getTableName() != null) {
            boolean crossTablePredicate = referencesOtherTablesWithAliases(node.getInfo(), node.getTableName());
            String localOnlyInfo = extractLocalOnlyFilterInfoWithAliases(node.getInfo(), node.getTableName());
            if (localOnlyInfo == null || localOnlyInfo.isBlank()) {
                if (crossTablePredicate) {
                    logger.info("Skip cross-table filter predicate on {}: {}", node.getTableName(), node.getInfo());
                    return node.getOutputRows();
                }
            } else {
                predicateForAnalysis = localOnlyInfo;
            }
        }
        LogicNode root = analyzeSelectInfo(predicateForAnalysis, node.getTableName());
        if (node.getTableName() != null && !root.retainOnlyTableOperations(node.getTableName())) {
            logger.info("Skip cross-table filter predicate on {}: {}", node.getTableName(), node.getInfo());
            return node.getOutputRows();
        }
        BigDecimal ratio = computeFilterProbability(node.getOutputRows(), lastNodeLineCount);
        ConstraintChainFilterNode filterNode = new ConstraintChainFilterNode(ratio, root);
        constraintChain.addNode(filterNode);
        return node.getOutputRows();
    }

    private long analyzeAggregateNode(AggNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException {
        // 无 GROUP BY 的外层 Aggregate（如 COUNT(*)）在 PgAnalyzer 中常未设置 tableName，应视为当前路径基表上的聚合，而非「另一张表」
        if (node.getTableName() != null && !constraintChain.getTableName().equals(node.getTableName())) {
            return STOP_CONSTRUCT;
        }

        List<String> groupKeys = null;
        if (node.getInfo() != null) {
            groupKeys = new ArrayList<>(Arrays.stream(node.getInfo().trim().split(";")).toList());
        }
        BigDecimal aggProbability = computeFilterProbability(node.getOutputRows(), lastNodeLineCount);
        long aggregateOutputRows = node.getOutputRows();
        boolean recoverHavingCountFilter = shouldRecoverCountGtLiteralAggregate(node);
        if (recoverHavingCountFilter && node.getAggFilter() != null) {
            aggregateOutputRows = node.getAggFilter().getOutputRows();
            aggProbability = computeFilterProbability(aggregateOutputRows, lastNodeLineCount);
        } else if (node.isSyntheticDistinctAggregate() && groupKeys != null && groupKeys.size() == 1) {
            aggregateOutputRows = estimateSyntheticDistinctOutputRows(groupKeys.get(0), lastNodeLineCount);
            aggProbability = computeFilterProbability(aggregateOutputRows, lastNodeLineCount);
        }
        ConstraintChainAggregateNode aggregateNode = new ConstraintChainAggregateNode(
                groupKeys,
                aggProbability,
                lastNodeLineCount,
                aggregateOutputRows);
        aggregateNode.setAllowsPostAggregateJoins(recoverHavingCountFilter);

        if (node.getAggFilter() != null && !recoverHavingCountFilter) {
            ExecutionNode aggNode = node.getAggFilter();
            LogicNode root = analyzeSelectInfo(aggNode.getInfo());
            BigDecimal filterProbability = computeFilterProbability(aggNode.getOutputRows(), lastNodeLineCount);
            aggregateNode.setAggFilter(new ConstraintChainFilterNode(filterProbability, root));
        }
        constraintChain.addNode(aggregateNode);
        return node.getOutputRows();
    }

    private BigDecimal computeFilterProbability(long outputRowCount, long inputRowCount) {
        if (inputRowCount == 0) {
            return BigDecimal.ZERO;
        } else {
            return BigDecimal.valueOf(outputRowCount).divide(BigDecimal.valueOf(inputRowCount), DECIMAL_DIVIDE_SCALE, RoundingMode.DOWN);
        }
    }

    private long estimateSyntheticDistinctOutputRows(String groupKey, long inputRows) {
        if (groupKey == null || inputRows <= 0) {
            return Math.max(0L, inputRows);
        }
        int ndv = ColumnManager.getInstance().getNdv(groupKey);
        if (ndv <= 0) {
            return inputRows;
        }
        return Math.max(1L, Math.min(inputRows, ndv));
    }

    /**
     * PK join 分支是否应调用 {@link TableManager#setPrimaryKeys}。
     * Schema 已为复合主键、但连接键仅覆盖其中部分列时，{@link ruc.db.schema.Table#setPrimaryKeys(String)} 会抛「多列主键的部分主键」。
     */
    static boolean shouldApplySetPrimaryKeysFromPkJoinKey(String localTable, String localCol) {
        try {
            List<String> declared = TableManager.getInstance().getCompletePrimaryKeysList(localTable);
            if (declared.isEmpty()) {
                return true;
            }
            Set<String> joinCols = canonicalJoinKeyColumnSet(localTable, localCol);
            Set<String> declaredSet = new HashSet<>(declared);
            if (joinCols.equals(declaredSet)) {
                return true;
            }
            if (declaredSet.containsAll(joinCols) && joinCols.size() < declaredSet.size()) {
                return false;
            }
            if (!declaredSet.containsAll(joinCols)) {
                return false;
            }
            return false;
        } catch (CannotFindSchemaException e) {
            return true;
        }
    }

    private static Set<String> canonicalJoinKeyColumnSet(String tableName, String commaSeparatedCols) {
        Set<String> out = new HashSet<>();
        for (String part : commaSeparatedCols.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t.contains(".") ? t : tableName + "." + t);
            }
        }
        return out;
    }

    private long analyzeJoinNode(JoinNode node, ConstraintChain constraintChain, long lastNodeLineCount) throws TouchstoneException, SQLException {
        String[] joinColumnInfos = new String[4];
        double fkJoinProbability = abstractAnalyzer.analyzeJoinInfo(node.getInfo(), joinColumnInfos);
        String localTable = joinColumnInfos[0];
        String localCol = joinColumnInfos[1];
        String externalTable = joinColumnInfos[2];
        String externalCol = joinColumnInfos[3];
        if (localTable == null || localCol == null || externalTable == null || externalCol == null) {
            logger.warn("Skip unresolved join node: info={}, resolved={}", node.getInfo(), Arrays.toString(joinColumnInfos));
            node.setJoinStatus(SKIP_JOIN_TAG);
            return node.getOutputRows();
        }
        if (localTable.equals(externalTable)) {
            if (isAggregateMembershipArtifactSelfJoin(node, localTable, localCol, externalCol)) {
                node.setJoinStatus(SKIP_JOIN_TAG);
                logger.info("Skip aggregate membership artifact self join on {}: {}", localTable, node.getInfo());
                return node.getOutputRows();
            }
            if (!shouldKeepSamePhysicalTableJoin(node, localTable)) {
                node.setJoinStatus(SKIP_SELF_JOIN);
                logger.error(rb.getString("SkipSelfJoinNode"), node.getInfo());
                return STOP_CONSTRUCT;
            }
        }
        // 如果当前的join节点，不属于之前遍历的节点
        if (!constraintChain.getTableName().equals(localTable) && !constraintChain.getTableName().equals(externalTable)) {
            if (node.getJoinStatus() == SKIP_JOIN_TAG)
                return node.getOutputRows();
            else
                return STOP_CONSTRUCT;
        }
        if (node.isSemiJoin()
                && node.getPreferredConstraintChainTable() != null
                && !node.getPreferredConstraintChainTable().equals(constraintChain.getTableName())) {
            return STOP_CONSTRUCT;
        }
        //将本表的信息放在前面，交换位置
        if (constraintChain.getTableName().equals(externalTable)) {
            localTable = joinColumnInfos[2];
            localCol = joinColumnInfos[3];
            externalTable = joinColumnInfos[0];
            externalCol = joinColumnInfos[1];
        }
        //根据主外键分别设置约束链输出信息
        if (shouldUsePkJoinBranch(localTable, localCol, externalTable, externalCol)) {
            //设置主键
            if (constraintChain.getJoinTables().contains(externalTable)) {
                logger.error(rb.getString("skipSelfJoin"), node.getInfo());
                node.setJoinStatus(SKIP_SELF_JOIN);
                return STOP_CONSTRUCT;
            } else {
                constraintChain.addJoinTable(externalTable);
            }
            boolean pkAllRowsInput = TableManager.getInstance().getTableSize(localTable) == lastNodeLineCount;
            boolean fkColIsNotNull = true;
            if (externalCol.contains(",")) {
                for (String col : externalCol.split(",")) {
                    fkColIsNotNull &= ColumnManager.getInstance().getNullPercentage(externalTable + "." + col)
                            .compareTo(BigDecimal.ZERO) == 0;
                }
            }
            boolean joinIsNotOuterJoin = node.getPkDistinctSize().compareTo(BigDecimal.ZERO) == 0;
            boolean skipTheJoinNode = false;
            if (pkAllRowsInput && fkColIsNotNull && joinIsNotOuterJoin && isSimpleLeafJoin(node)) {
                logger.debug(rb.getString("SkipNodeDueToFullTableScan"), node.getInfo());
                node.setJoinStatus(SKIP_JOIN_TAG);
                skipTheJoinNode = OPEN_SKIP_JOIN_FEATURE;
            }
            if (!skipTheJoinNode) {
                node.setJoinTag(TableManager.getInstance().getJoinTag(localTable));
                ConstraintChainPkJoinNode pkJoinNode = new ConstraintChainPkJoinNode(node.getJoinTag(), localCol.split(","));
                constraintChain.addNode(pkJoinNode);
                if (shouldApplySetPrimaryKeysFromPkJoinKey(localTable, localCol)) {
                    TableManager.getInstance().setPrimaryKeys(localTable, localCol);
                } else {
                    logger.warn(
                            "Skip setPrimaryKeys for table {}: join key ({}) is not the full declared primary key (e.g. composite PK partial join).",
                            localTable, localCol);
                }
            }
            return lastNodeLineCount;
        } else {
            constraintChain.addJoinTable(externalTable);
            logger.debug("{} wait join tag", node.getInfo());
            // JoinNode.getJoinStatus() 会先 waitSetJoinTag.await()；PK 分支里由 setJoinTag/setJoinStatus 释放。
            // GENERIC/FK 分支若首次进入且从未走过 PK 分支，必须先分配 joinTag，否则会永久阻塞（非聚合慢，是死等）。
            if (node.getJoinTag() == Integer.MIN_VALUE) {
                node.setJoinTag(TableManager.getInstance().getJoinTag(localTable));
            }
            int joinStatus = node.getJoinStatus();
            logger.debug("{} get join tag", node.getInfo());
            JoinConstraintJoinModel joinModel = resolveJoinModelForFkJoinNode(localTable, localCol, externalTable, externalCol);
            registerJoinReferenceForGeneration(localTable, localCol, externalTable, externalCol, joinModel);
            if (joinStatus == SKIP_JOIN_TAG && OPEN_SKIP_JOIN_FEATURE) {
                logger.debug(rb.getString("SkipNodeDueToFullPk"), node.getInfo());
                return node.getOutputRows();
            } else if (joinStatus == SKIP_SELF_JOIN) {
                logger.error(rb.getString("SkipSelfJoinNode"), node.getInfo());
                return STOP_CONSTRUCT;
            } else if (!canContinueJoinAfterAggregate(constraintChain, localTable, localCol)) {
                logger.error("cannot support join {} after aggregation currently", node.getInfo());
                return STOP_CONSTRUCT;
            }
            BigDecimal probability = computeFilterProbability(node.getOutputRows(), lastNodeLineCount);
            probability = probability.divide(BigDecimal.valueOf(fkJoinProbability), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
            if (probability.compareTo(BigDecimal.ONE) > 0 || joinStatus == SKIP_JOIN_TAG) {
                probability = BigDecimal.ONE;
            }
            ConstraintChainFkJoinNode fkJoinNode = new ConstraintChainFkJoinNode(localTable + "." + localCol, externalTable + "." + externalCol, node.getJoinTag(), probability);
            // deal with index join
            String leftTable = null;
            String rightTable = null;
            ExecutionNode childNode = null;
            if (node.getLeftNode().getType() == ExecutionNodeType.FILTER && node.getLeftNode().getInfo() != null &&
                    ((FilterNode) node.getLeftNode()).isIndexScan()) {
                leftTable = node.getLeftNode().getTableName();
                childNode = node.getLeftNode();
            }
            if (node.getRightNode().getType() == ExecutionNodeType.FILTER && node.getRightNode().getInfo() != null &&
                    ((FilterNode) node.getRightNode()).isIndexScan()) {
                rightTable = node.getRightNode().getTableName();
                childNode = node.getRightNode();
            }
            if ((leftTable != null && leftTable.equals(localTable)) || (rightTable != null && rightTable.equals(localTable))) {
                long tableSize = TableManager.getInstance().getTableSize(childNode.getTableName());
                long rowsRemovedByScanFilter = tableSize - childNode.getOutputRows();
                BigDecimal probabilityWithFailFilter = computeFilterProbability(node.getRowsRemoveByFilterAfterJoin(), rowsRemovedByScanFilter);
                fkJoinNode.setProbabilityWithFailFilter(probabilityWithFailFilter);
            }
            if (node.isSemiJoin()) {
                if (node.isAntiJoin()) {
                    fkJoinNode.setType(ConstraintNodeJoinType.ANTI_SEMI_JOIN);
                } else {
                    fkJoinNode.setType(ConstraintNodeJoinType.SEMI_JOIN);
                }
                fkJoinNode.setPkDistinctProbability(fkJoinNode.getProbability());
            } else {
                if (node.getPkDistinctSize() != null && node.getPkDistinctSize().compareTo(BigDecimal.ZERO) > 0) {
                    fkJoinNode.setType(ConstraintNodeJoinType.OUTER_JOIN);
                    fkJoinNode.setPkDistinctProbability(node.getPkDistinctSize());
                } else if (node.isAntiJoin()) {
                    fkJoinNode.setType(ConstraintNodeJoinType.ANTI_JOIN);
                }
            }
            fkJoinNode.setJoinModel(joinModel);
            fkJoinNode.setTargetJoinRows(node.getOutputRows());
            if (node.getLeftNode() != null) {
                fkJoinNode.setLeftInputRows(resolvePreferredInputRows(node.getLeftInputRows(), node.getLeftNode().getOutputRows()));
            }
            if (node.getRightNode() != null) {
                fkJoinNode.setRightInputRows(resolvePreferredInputRows(node.getRightInputRows(), node.getRightNode().getOutputRows()));
            }
            setRoleInputRows(fkJoinNode, node, localTable, externalTable);
            if (fkJoinNode.getJoinModel() == JoinConstraintJoinModel.GENERIC) {
                if (localCol.contains(",") || externalCol.contains(",")) {
                    logger.warn(
                            "GENERIC 复合 join key 当前版本未完全支持（localCol={}, externalCol={}），使用单桶占位权重；跨批直方图仅对单列键生效。",
                            localCol, externalCol);
                }
                if (!localCol.contains(",")) {
                    String localCanon = localTable + CANONICAL_NAME_CONTACT_SYMBOL + localCol;
                    // 默认单桶 PF，避免与主 JOIN 基数约束叠加后易 INFEASIBLE；多桶由 genericBucketWeights 显式写入或后续统计 pass
                    fkJoinNode.setGenericBucketWeights(GenericJoinWeightEstimator.estimateUniformBucketWeights(
                            localCanon, fkJoinNode.getLocalInputRows(), 1));
                }
                if (!externalCol.contains(",")) {
                    String refCanon = externalTable + CANONICAL_NAME_CONTACT_SYMBOL + externalCol;
                    long off = GenericJoinAntiDomain.estimateOffsetForRefColumn(refCanon);
                    if (off > 0) {
                        fkJoinNode.setGenericAntiDomainOffset(off);
                    }
                }
            }
            constraintChain.addNode(fkJoinNode);
            return node.getOutputRows();
        }
    }

    private static void setRoleInputRows(ConstraintChainFkJoinNode fkJoinNode, JoinNode node,
                                         String localTable, String externalTable) {
        ExecutionNode left = node.getLeftNode();
        ExecutionNode right = node.getRightNode();
        Long localRows = findInputRowsForTable(left, node.getLeftInputRows(), right, node.getRightInputRows(), localTable);
        Long refRows = findInputRowsForTable(left, node.getLeftInputRows(), right, node.getRightInputRows(), externalTable);
        if (localRows == null) {
            localRows = fkJoinNode.getLeftInputRows();
        }
        if (refRows == null) {
            refRows = fkJoinNode.getRightInputRows();
        }
        fkJoinNode.setLocalInputRows(localRows);
        fkJoinNode.setRefInputRows(refRows);
    }

    private static Long findInputRowsForTable(ExecutionNode left, Long leftInputRows,
                                              ExecutionNode right, Long rightInputRows,
                                              String tableName) {
        if (tableName == null) {
            return null;
        }
        if (containsTable(left, tableName)) {
            return resolvePreferredInputRows(leftInputRows, left != null ? left.getOutputRows() : null);
        }
        if (containsTable(right, tableName)) {
            return resolvePreferredInputRows(rightInputRows, right != null ? right.getOutputRows() : null);
        }
        return null;
    }

    private static Long resolvePreferredInputRows(Long preferredRows, Long fallbackRows) {
        if (preferredRows != null && preferredRows > 0) {
            return preferredRows;
        }
        if (fallbackRows != null && fallbackRows > 0) {
            return fallbackRows;
        }
        return preferredRows != null ? preferredRows : fallbackRows;
    }

    private static boolean containsTable(ExecutionNode node, String tableName) {
        if (node == null || tableName == null) {
            return false;
        }
        if (tableName.equals(node.getTableName())) {
            return true;
        }
        return containsTable(node.getLeftNode(), tableName) || containsTable(node.getRightNode(), tableName);
    }

    private static boolean shouldKeepSamePhysicalTableJoin(JoinNode node, String tableName) {
        if (node == null || tableName == null) {
            return false;
        }
        ExecutionNode left = node.getLeftNode();
        ExecutionNode right = node.getRightNode();
        if (left == null || right == null) {
            return false;
        }
        if (!containsTable(left, tableName) || !containsTable(right, tableName)) {
            return false;
        }
        return !isSimpleFilterLeaf(left) || !isSimpleFilterLeaf(right)
                || subtreeContainsAggregate(left) || subtreeContainsAggregate(right);
    }

    private static boolean isSimpleLeafJoin(JoinNode node) {
        return node != null && isSimpleFilterLeaf(node.getLeftNode()) && isSimpleFilterLeaf(node.getRightNode());
    }

    private static boolean isSimpleFilterLeaf(ExecutionNode node) {
        return node != null
                && node.getType() == ExecutionNodeType.FILTER
                && node.getLeftNode() == null
                && node.getRightNode() == null;
    }

    private static boolean subtreeContainsAggregate(ExecutionNode node) {
        if (node == null) {
            return false;
        }
        if (node.getType() == ExecutionNodeType.AGGREGATE) {
            return true;
        }
        return subtreeContainsAggregate(node.getLeftNode()) || subtreeContainsAggregate(node.getRightNode());
    }

    private static boolean canContinueJoinAfterAggregate(ConstraintChain constraintChain,
                                                         String localTable,
                                                         String localCol) {
        if (constraintChain == null || !constraintChain.hasAggNode()) {
            return true;
        }
        String localKey = localTable == null || localCol == null ? null : localTable + "." + localCol;
        if (constraintChain.canContinueJoinAfterAggregateOnLocalKey(localKey)) {
            return true;
        }
        if (localTable == null || constraintChain.getTableName() == null) {
            return false;
        }
        if (constraintChain.getTableName().equals(localTable)) {
            return false;
        }
        return constraintChain.getNodes().stream()
                .filter(ConstraintChainAggregateNode.class::isInstance)
                .map(ConstraintChainAggregateNode.class::cast)
                .allMatch(ConstraintChainAggregateNode::isSingleGroupKeyDistinctConstraint);
    }

    /**
     * 获取一条路径上的约束链
     *
     * @param path 需要处理的路径
     * @return 获取的约束链
     */
    private ConstraintChain extractConstraintChain(List<ExecutionNode> path, Set<ExecutionNode> inputNodes) throws TouchstoneException, SQLException {
        if (path == null || path.isEmpty()) {
            throw new TouchstoneException(String.format("invalid path input '%s'", path));
        }
        ExecutionNode headNode = path.getFirst();
        ConstraintChain constraintChain;
        long lastNodeLineCount;
        //分析约束链的第一个node
        if (headNode.getType() == ExecutionNodeType.FILTER) {
            constraintChain = new ConstraintChain(headNode.getTableName());
            FilterNode filterNode = (FilterNode) headNode;
            if (filterNode.getInfo() != null) {
                if (isUnmodeledSubPlanAlternative(filterNode.getInfo())) {
                    logger.warn("Skip unmodeled SubPlan alternative head filter on table {}: {}",
                            filterNode.getTableName(), filterNode.getInfo());
                } else {
                    boolean crossTablePredicate = referencesOtherTablesWithAliases(filterNode.getInfo(), filterNode.getTableName());
                    String localOnlyInfo = extractLocalOnlyFilterInfoWithAliases(filterNode.getInfo(), filterNode.getTableName());
                    if (localOnlyInfo == null || localOnlyInfo.isBlank()) {
                        if (crossTablePredicate) {
                            logger.info("Skip cross-table filter predicate on {}: {}",
                                    filterNode.getTableName(), filterNode.getInfo());
                        }
                        lastNodeLineCount = resolveEffectiveHeadRows(filterNode);
                        inputNodes.add(headNode);
                        for (ExecutionNode executionNode : path.subList(1, path.size())) {
                            try {
                                lastNodeLineCount = analyzeNode(executionNode, constraintChain, lastNodeLineCount);
                                inputNodes.add(executionNode);
                                if (lastNodeLineCount == STOP_CONSTRUCT) {
                                    if (constraintChain.getNodes().isEmpty()) {
                                        return null;
                                    } else {
                                        break;
                                    }
                                } else if (lastNodeLineCount < 0) {
                                    throw new UnsupportedOperationException();
                                }
                            } catch (TouchstoneException e) {
                                logger.error("extract constraint chain fail", e);
                                if (canUseTableSizeForSkipRatio(executionNode)
                                        && executionNode.getOutputRows() * 1.0 / TableManager.getInstance().getTableSize(executionNode.getTableName()) < skipNodeThreshold) {
                                    logger.error(rb.getString("FailToExtractConstraintChain"), e);
                                    logger.info(String.format(rb.getString("SkipNodeDueToRatio"), e.getMessage(), executionNode));
                                    return constraintChain;
                                }
                            }
                        }
                        return constraintChain.getNodes().isEmpty() ? null : constraintChain;
                    }
                    LogicNode result = analyzeSelectInfo(localOnlyInfo, filterNode.getTableName());
                    boolean hasSingleTableFilter = result.retainOnlyTableOperations(filterNode.getTableName());
                    if (filterNode.isIndexScan()) {
                        if (hasSingleTableFilter && dbConnector != null) {
                            int rowsAfterFilter = dbConnector.getRowsAfterFilter(filterNode.getTableName(), result.toString());
                            filterNode.setOutputRows(rowsAfterFilter);
                        } else {
                            logger.info("Skip standalone filter cardinality query for cross-table Index Cond on {}: {}",
                                    filterNode.getTableName(), filterNode.getInfo());
                        }
                    } else if (crossTablePredicate || result.isDifferentTable(filterNode.getTableName())) {
                        hasSingleTableFilter = false;
                        logger.info("Skip cross-table filter predicate on {}: {}",
                                filterNode.getTableName(), filterNode.getInfo());
                    }
                    if (hasSingleTableFilter) {
                        BigDecimal ratio = computeFilterProbability(filterNode.getOutputRows(), TableManager.getInstance().getTableSize(filterNode.getTableName()));
                        constraintChain.addNode(new ConstraintChainFilterNode(ratio, result));
                    } else {
                    }
                }
            }
            lastNodeLineCount = resolveEffectiveHeadRows(filterNode);
        } else {
            throw new TouchstoneException(String.format(rb.getString("InvalidUnderlyingNode"), headNode.getId()));
        }
        inputNodes.add(headNode);
        for (ExecutionNode executionNode : path.subList(1, path.size())) {
            try {
                lastNodeLineCount = analyzeNode(executionNode, constraintChain, lastNodeLineCount);
                inputNodes.add(executionNode);
                if (lastNodeLineCount == STOP_CONSTRUCT) {
                    if (constraintChain.getNodes().isEmpty()) {
                        return null;
                    } else {
                        break;
                    }
                } else if (lastNodeLineCount < 0) {
                    throw new UnsupportedOperationException();
                }
            } catch (TouchstoneException e) {
                logger.error("extract constraint chain fail", e);
                // 小于设置的阈值以后略去后续的节点（JOIN 等节点可能无 tableName，不可调用 getTableSize）
                if (canUseTableSizeForSkipRatio(executionNode)
                        && executionNode.getOutputRows() * 1.0 / TableManager.getInstance().getTableSize(executionNode.getTableName()) < skipNodeThreshold) {
                    logger.error(rb.getString("FailToExtractConstraintChain"), e);
                    logger.info(String.format(rb.getString("SkipNodeDueToRatio"), e.getMessage(), executionNode));
                    return constraintChain;
                }
            }
        }
        return constraintChain.getNodes().isEmpty() ? null : constraintChain;
    }


    /**
     * 将树结构根据叶子节点分割为不同的path
     *
     * @param currentNode 需要处理的查询树节点
     * @param paths       需要返回的路径
     */
    private void getPathsIterate(ExecutionNode currentNode, List<List<ExecutionNode>> paths, List<ExecutionNode> currentPath) {
        currentPath.addFirst(currentNode);
        if (currentNode.getLeftNode() == null && currentNode.getRightNode() == null) {
            paths.add(new ArrayList<>(currentPath));
        }
        if (currentNode.getLeftNode() != null) {
            getPathsIterate(currentNode.getLeftNode(), paths, currentPath);
        }
        if (currentNode.getRightNode() != null) {
            getPathsIterate(currentNode.getRightNode(), paths, currentPath);
        }
        currentPath.removeFirst();
    }

    /**
     * 获取查询树的约束链信息和表信息（对库执行 EXPLAIN）。
     */
    public List<List<ConstraintChain>> extractQuery(String query) throws SQLException {
        return extractQuery(query, null);
    }

    /**
     * 获取查询树的约束链信息和表信息。
     *
     * @param query            查询语句（用于模板与元数据；本地计划模式下不发往数据库做 EXPLAIN）
     * @param localPlanJson    非空时：使用该 EXPLAIN JSON 字符串（与 EXPLAIN FORMAT JSON 一致），不再对库执行 EXPLAIN
     */
    public List<List<ConstraintChain>> extractQuery(String query, String localPlanJson) throws SQLException {
        boolean localPlanMode = localPlanJson != null && !localPlanJson.isBlank();
        List<String[]> queryPlan;
        if (localPlanMode) {
            String folded = PlanJsonTransforms.foldAppendPartitionSeqScans(localPlanJson.trim());
            queryPlan = Collections.singletonList(new String[]{folded});
        } else {
            queryPlan = dbConnector.explainQuery(query);
        }
        List<List<String[]>> queryPlans = abstractAnalyzer.splitQueryPlan(queryPlan);
        List<ExecutionNode> executionTrees = new LinkedList<>();
        try {
            for (List<String[]> plan : queryPlans) {
                executionTrees.add(abstractAnalyzer.getExecutionTree(plan));
                List<Map.Entry<String, String>> tableNameAndFilterInfos = abstractAnalyzer.splitQueryPlanForMultipleAggregate();
                if (tableNameAndFilterInfos != null) {
                    for (Map.Entry<String, String> tableNameAndFilterInfo : tableNameAndFilterInfos) {
                        if (localPlanMode) {
                            logger.warn("Local plan mode: skip secondary EXPLAIN for aggregate split ({}); sub-plan not loaded from DB.",
                                    tableNameAndFilterInfo.getKey());
                            continue;
                        }
                        executionTrees.add(abstractAnalyzer.getExecutionTree(dbConnector.explainQuery(tableNameAndFilterInfo)));
                    }
                }
            }
        } catch (TouchstoneException | IOException e) {
            if (queryPlan != null && !queryPlan.isEmpty()) {
                String queryPlanContent = queryPlan.stream().map(plan -> String.join("\t", plan))
                        .collect(Collectors.joining(System.lineSeparator()));
                logger.error(rb.getString("FailToExtractQueryTree"));
                logger.error(queryPlanContent, e);
            }
        }
        List<List<ConstraintChain>> constraintChains = new ArrayList<>();
        for (ExecutionNode executionTree : executionTrees) {
            //获取查询树的所有路径
            List<List<ExecutionNode>> paths = new ArrayList<>();
            getPathsIterate(executionTree, paths, new LinkedList<>());
            // 并发处理约束链
            Set<ExecutionNode> allNodes = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
            paths.forEach(allNodes::addAll);
            Set<ExecutionNode> inputNodes = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
            try (ForkJoinPool forkJoinPool = new ForkJoinPool(paths.size())) {
                constraintChains.add(new ArrayList<>(forkJoinPool.submit(() -> paths.parallelStream().map(path -> {
                    try {
                        return extractConstraintChain(path, inputNodes);
                    } catch (Exception e) {
                        logger.error(path.toString(), e);
                        return null;
                    }
                }).filter(Objects::nonNull).toList()).get()));
            } catch (InterruptedException | ExecutionException e) {
                logger.error(rb.getString("FailToConstructConstraintChain"), e);
                Thread.currentThread().interrupt();
            }
            allNodes.removeAll(inputNodes);
            if (!allNodes.isEmpty()) {
                for (ExecutionNode node : allNodes) {
                    logger.error("can not input {}", node);
                }
            }
        }
        if (constraintChains.size() > 1) {
            for (ConstraintChain constraintChain : constraintChains.getFirst()) {
                for (Parameter parameter : constraintChain.getParameters()) {
                    parameter.setSubPlan(true);
                }
            }
        }
        logger.info(rb.getString("GetComplete"));
        return constraintChains;
    }

    /**
     * 分析select信息
     *
     * @param operatorInfo 需要分析的operator_info
     * @return 分析查询的逻辑树
     * @throws TouchstoneException 分析失败
     */
    private synchronized LogicNode analyzeSelectInfo(String operatorInfo) throws TouchstoneException {
        return analyzeSelectInfo(operatorInfo, null);
    }

    private synchronized LogicNode analyzeSelectInfo(String operatorInfo, String tableName) throws TouchstoneException {
        LogicNode recovered = tryRecoverSimpleSingleTableEqualityFilter(operatorInfo, tableName);
        if (recovered != null) {
            return recovered;
        }
        try {
            return abstractAnalyzer.analyzeSelectOperator(operatorInfo);
        } catch (Exception e) {
            recovered = tryRecoverSimpleSingleTableEqualityFilter(operatorInfo, tableName);
            if (recovered != null) {
                return recovered;
            }
            throw new UnsupportedSelect(operatorInfo, e);
        }
    }

    private long resolveEffectiveHeadRows(FilterNode filterNode) {
        long outputRows = filterNode.getOutputRows();
        if (outputRows > 0 || filterNode.getId() == null) {
            return outputRows;
        }
        long recoveredRows = PgJsonReader.readNearestSkippableAncestorRows(filterNode.getId());
        if (recoveredRows > outputRows) {
            logger.info("Recover head rows from skipped ancestor for {}: {} -> {}",
                    filterNode.getTableName(), outputRows, recoveredRows);
            filterNode.setOutputRows(recoveredRows);
            return recoveredRows;
        }
        return outputRows;
    }

    private LogicNode tryRecoverSimpleSingleTableEqualityFilter(String operatorInfo, String tableName) {
        if (operatorInfo == null || tableName == null) {
            return null;
        }
        String normalized = operatorInfo
                .replaceAll("::\\s*[a-zA-Z0-9_.]+(?:\\s+[a-zA-Z0-9_]+)*(?:\\[\\])?", "")
                .replaceAll("\\(\\s*([a-zA-Z_][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_][a-zA-Z0-9_$]*){0,2})\\s*\\)", "$1")
                .trim();
        normalized = trimSingleConjunct(normalized);
        if (normalized.contains(" AND ") || normalized.contains(" OR ")) {
            return null;
        }
        Matcher matcher = SIMPLE_SINGLE_TABLE_EQ_LITERAL.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        String colRef = matcher.group(1);
        String literal = matcher.group(2);
        String canonicalColumnName = toCanonicalColumnName(colRef, tableName);
        if (canonicalColumnName == null) {
            return null;
        }
        Parameter parameter = new Parameter(0, canonicalColumnName, literal);
        parameter.setEqualPredicate(true);
        UniVarFilterOperation operation = new UniVarFilterOperation(
                canonicalColumnName, CompareOperator.EQ, Collections.singletonList(parameter));
        LogicNode logicNode = new LogicNode();
        logicNode.setType(BoolExprType.AND);
        logicNode.setChildren(new ArrayList<>(List.of(operation)));
        return logicNode;
    }

    private String toCanonicalColumnName(String colRef, String tableName) {
        if (colRef == null || colRef.isBlank() || tableName == null || tableName.isBlank()) {
            return null;
        }
        String cleaned = colRef.replace("\"", "").trim();
        String[] parts = cleaned.split("\\.");
        if (parts.length == 3) {
            return cleaned;
        }
        if (parts.length == 2) {
            return tableName + "." + parts[1];
        }
        if (parts.length == 1) {
            return tableName + "." + parts[0];
        }
        return null;
    }

    static boolean isUnmodeledSubPlanAlternative(String operatorInfo) {
        return operatorInfo != null && SUBPLAN_ALTERNATIVE_PLACEHOLDER.matcher(operatorInfo.trim()).matches();
    }

    private static boolean shouldRecoverCountGtLiteralAggregate(AggNode node) {
        if (node == null || node.getAggregateFilterKind() != AggNode.AggregateFilterKind.COUNT_GT_LITERAL) {
            return false;
        }
        FilterNode aggFilter = node.getAggFilter();
        return aggFilter != null && aggFilter.getInfo() != null && COUNT_GT_LITERAL.matcher(aggFilter.getInfo().trim()).matches();
    }

    static boolean referencesOtherTables(String operatorInfo, String tableName) {
        if (operatorInfo == null || tableName == null) {
            return false;
        }
        Matcher matcher = CANONICAL_COLUMN_REF.matcher(operatorInfo);
        while (matcher.find()) {
            String ref = matcher.group(1);
            int split = ref.lastIndexOf('.');
            if (split <= 0) {
                continue;
            }
            String refTable = ref.substring(0, split);
            if (!tableName.equals(refTable)) {
                return true;
            }
        }
        return false;
    }

    private boolean referencesOtherTablesWithAliases(String operatorInfo, String tableName) {
        if (operatorInfo == null || tableName == null) {
            return false;
        }
        Matcher matcher = QUALIFIED_COLUMN_REF.matcher(operatorInfo.replace("\"", ""));
        while (matcher.find()) {
            String refTable = resolveReferenceTableName(matcher.group(1), tableName);
            if (refTable != null && !tableName.equals(refTable)) {
                return true;
            }
        }
        return false;
    }

    static String extractLocalOnlyFilterInfo(String operatorInfo, String tableName) {
        if (operatorInfo == null || tableName == null) {
            return operatorInfo;
        }
        String normalized = trimSingleConjunct(operatorInfo);
        List<String> kept = new ArrayList<>();
        for (String conjunct : splitTopLevelAndConjuncts(normalized)) {
            String trimmed = trimSingleConjunct(conjunct);
            if (trimmed.isBlank()) {
                continue;
            }
            if (referencesOtherTables(trimmed, tableName)) {
                continue;
            }
            if (containsCanonicalReference(trimmed) && !containsReferenceForTable(trimmed, tableName)) {
                continue;
            }
            kept.add(trimmed);
        }
        if (kept.isEmpty()) {
            return null;
        }
        return String.join(" AND ", kept);
    }

    private String extractLocalOnlyFilterInfoWithAliases(String operatorInfo, String tableName) {
        if (operatorInfo == null || tableName == null) {
            return operatorInfo;
        }
        String normalized = trimSingleConjunct(operatorInfo);
        List<String> kept = new ArrayList<>();
        for (String conjunct : splitTopLevelAndConjuncts(normalized)) {
            String trimmed = trimSingleConjunct(conjunct);
            if (trimmed.isBlank()) {
                continue;
            }
            if (!predicateReferencesOnlyCurrentTable(trimmed, tableName)) {
                continue;
            }
            kept.add(trimmed);
        }
        if (kept.isEmpty()) {
            return null;
        }
        return String.join(" AND ", kept);
    }

    private boolean predicateReferencesOnlyCurrentTable(String expr, String tableName) {
        Matcher matcher = QUALIFIED_COLUMN_REF.matcher(expr.replace("\"", ""));
        boolean sawQualifiedRef = false;
        while (matcher.find()) {
            sawQualifiedRef = true;
            String refTable = resolveReferenceTableName(matcher.group(1), tableName);
            if (refTable == null || !tableName.equals(refTable)) {
                return false;
            }
        }
        return !sawQualifiedRef || containsReferenceForResolvedTable(expr, tableName);
    }

    private boolean containsReferenceForResolvedTable(String expr, String tableName) {
        Matcher matcher = QUALIFIED_COLUMN_REF.matcher(expr.replace("\"", ""));
        while (matcher.find()) {
            String refTable = resolveReferenceTableName(matcher.group(1), tableName);
            if (tableName.equals(refTable)) {
                return true;
            }
        }
        return false;
    }

    private String resolveReferenceTableName(String ref, String currentTable) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String cleaned = ref.replace("\"", "").trim();
        String[] parts = cleaned.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1];
        }
        if (parts.length == 2) {
            String qualifier = parts[0];
            String aliasedTable = abstractAnalyzer.lookupAliasTable(qualifier);
            if (aliasedTable != null) {
                return aliasedTable;
            }
            if (currentTable != null) {
                int split = currentTable.lastIndexOf('.');
                String shortTable = split >= 0 ? currentTable.substring(split + 1) : currentTable;
                if (shortTable.equals(qualifier)) {
                    return currentTable;
                }
            }
        }
        return null;
    }

    private static List<String> splitTopLevelAndConjuncts(String expr) {
        List<String> out = new ArrayList<>();
        if (expr == null || expr.isBlank()) {
            return out;
        }
        expr = trimSingleConjunct(expr);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expr.length() - 2; i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && expr.regionMatches(true, i, "AND", 0, 3)) {
                out.add(expr.substring(start, i).trim());
                start = i + 3;
                i += 2;
            }
        }
        out.add(expr.substring(start).trim());
        return out;
    }

    private static String trimSingleConjunct(String expr) {
        String trimmed = expr == null ? "" : expr.trim();
        while (trimmed.startsWith("(") && trimmed.endsWith(")") && hasBalancedOuterParentheses(trimmed)) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static boolean containsCanonicalReference(String expr) {
        return expr != null && CANONICAL_COLUMN_REF.matcher(expr).find();
    }

    private static boolean containsReferenceForTable(String expr, String tableName) {
        Matcher matcher = CANONICAL_COLUMN_REF.matcher(expr);
        while (matcher.find()) {
            String ref = matcher.group(1);
            int split = ref.lastIndexOf('.');
            if (split > 0 && tableName.equals(ref.substring(0, split))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizedJoinColSet(String joinCols) {
        Set<String> out = new HashSet<>();
        if (joinCols == null) {
            return out;
        }
        for (String col : joinCols.split(",")) {
            String trimmed = col.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int split = trimmed.lastIndexOf('.');
            out.add(split >= 0 ? trimmed.substring(split + 1) : trimmed);
        }
        return out;
    }

    private static boolean isAggregateMembershipArtifactSelfJoin(JoinNode node,
                                                                 String tableName,
                                                                 String localCol,
                                                                 String externalCol) {
        if (node == null || tableName == null) {
            return false;
        }
        if (!normalizedJoinColSet(localCol).equals(normalizedJoinColSet(externalCol))) {
            return false;
        }
        ExecutionNode left = node.getLeftNode();
        ExecutionNode right = node.getRightNode();
        if (left == null || right == null) {
            return false;
        }
        boolean leftHasAggregate = subtreeContainsAggregate(left);
        boolean rightHasAggregate = subtreeContainsAggregate(right);
        if (!(leftHasAggregate ^ rightHasAggregate)) {
            return false;
        }
        return containsTable(left, tableName) && containsTable(right, tableName);
    }

    private static boolean hasBalancedOuterParentheses(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < expression.length() - 1) {
                    return false;
                }
            }
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

}
