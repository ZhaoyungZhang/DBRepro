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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

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
        int leftTableNdv;
        int rightTableNdv;
        if (pkCol.contains(",")) {
            leftTableNdv = dbConnector.getMultiColNdv(pkTable, pkCol);
            rightTableNdv = dbConnector.getMultiColNdv(fkTable, fkCol);
        } else {
            leftTableNdv = ColumnManager.getInstance().getNdv(pkTable + CANONICAL_NAME_CONTACT_SYMBOL + pkCol);
            rightTableNdv = ColumnManager.getInstance().getNdv(fkTable + CANONICAL_NAME_CONTACT_SYMBOL + fkCol);
        }
        long leftTableSize = TableManager.getInstance().getTableSize(pkTable);
        long rightTableSize = TableManager.getInstance().getTableSize(fkTable);
        boolean inferredPkOnLeft;
        if (leftTableNdv == rightTableNdv) {
            if (leftTableSize == rightTableSize) {
                throw new TouchstoneException("两个表无法区分主外键");
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

    private static boolean isDeclaredRefJoin(String localTable, String localCol, String externalTable, String externalCol) {
        try {
            return TableManager.getInstance().isRefTable(localTable, localCol, externalTable + "." + externalCol)
                    || TableManager.getInstance().isRefTable(externalTable, externalCol, localTable + "." + localCol);
        } catch (TouchstoneException e) {
            return false;
        }
    }

    /**
     * FK_JOIN 节点上 {@link JoinConstraintJoinModel} 的判定（与 {@link #analyzeJoinNode} 中逻辑一致）：仅当存在 schema 参照边且参照键为参照表<strong>完整主键</strong>时为
     * {@link JoinConstraintJoinModel#PK_FK}，否则为 {@link JoinConstraintJoinModel#GENERIC}（计划基数、桶权重、反域等）。
     */
    public static JoinConstraintJoinModel resolveJoinModelForFkJoinNode(
            String localTable, String localCol, String externalTable, String externalCol) {
        boolean declaredRefJoin = isDeclaredRefJoin(localTable, localCol, externalTable, externalCol);
        boolean pkFkJoinModel = declaredRefJoin && refJoinKeyIsExactlyTablePrimaryKey(externalTable, externalCol);
        if (declaredRefJoin && !pkFkJoinModel) {
            logger.info(
                    "FK_JOIN 参照侧 {} 的 join 键 [{}] 非该表完整主键，使用 joinModel=GENERIC（计划基数 targetJoinRows 等）；schema 仍保留外键声明。",
                    externalTable, externalCol);
        }
        return pkFkJoinModel ? JoinConstraintJoinModel.PK_FK : JoinConstraintJoinModel.GENERIC;
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
        LogicNode root = analyzeSelectInfo(node.getInfo());
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
        ConstraintChainAggregateNode aggregateNode = new ConstraintChainAggregateNode(groupKeys, aggProbability);

        if (node.getAggFilter() != null) {
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
        if (localTable.equals(externalTable)) {
            node.setJoinStatus(SKIP_SELF_JOIN);
            logger.error(rb.getString("SkipSelfJoinNode"), node.getInfo());
            return STOP_CONSTRUCT;
        }
        // 如果当前的join节点，不属于之前遍历的节点
        if (!constraintChain.getTableName().equals(localTable) && !constraintChain.getTableName().equals(externalTable)) {
            if (node.getJoinStatus() == SKIP_JOIN_TAG)
                return node.getOutputRows();
            else
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
            if (pkAllRowsInput && fkColIsNotNull && joinIsNotOuterJoin) {
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
            TableManager.getInstance().setTmpForeignKeys(localTable, localCol, externalTable, externalCol);
            if (joinStatus == SKIP_JOIN_TAG && OPEN_SKIP_JOIN_FEATURE) {
                logger.debug(rb.getString("SkipNodeDueToFullPk"), node.getInfo());
                return node.getOutputRows();
            } else if (joinStatus == SKIP_SELF_JOIN) {
                logger.error(rb.getString("SkipSelfJoinNode"), node.getInfo());
                return STOP_CONSTRUCT;
            } else if (constraintChain.hasAggNode()) {
                logger.error("cannot support join {} after aggregation currently", node.getInfo());
                return STOP_CONSTRUCT;
            }
            TableManager.getInstance().setForeignKeys(localTable, localCol, externalTable, externalCol);
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
            fkJoinNode.setJoinModel(resolveJoinModelForFkJoinNode(localTable, localCol, externalTable, externalCol));
            fkJoinNode.setTargetJoinRows(node.getOutputRows());
            if (node.getLeftNode() != null) {
                fkJoinNode.setLeftInputRows(node.getLeftNode().getOutputRows());
            }
            if (node.getRightNode() != null) {
                fkJoinNode.setRightInputRows(node.getRightNode().getOutputRows());
            }
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
                            localCanon, fkJoinNode.getLeftInputRows(), 1));
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
                LogicNode result = analyzeSelectInfo(filterNode.getInfo());
                if (filterNode.isIndexScan()) {
                    result.removeOtherTablesOperation(filterNode.getTableName());
                    int rowsAfterFilter = dbConnector.getRowsAfterFilter(filterNode.getTableName(), result.toString());
                    filterNode.setOutputRows(rowsAfterFilter);
                }
                BigDecimal ratio = computeFilterProbability(filterNode.getOutputRows(), TableManager.getInstance().getTableSize(filterNode.getTableName()));
                constraintChain.addNode(new ConstraintChainFilterNode(ratio, result));
            }
            lastNodeLineCount = filterNode.getOutputRows();
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
            HashSet<ExecutionNode> allNodes = new HashSet<>();
            paths.forEach(allNodes::addAll);
            Set<ExecutionNode> inputNodes = ConcurrentHashMap.newKeySet();
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
        try {
            return abstractAnalyzer.analyzeSelectOperator(operatorInfo);
        } catch (Exception e) {
            throw new UnsupportedSelect(operatorInfo, e);
        }
    }

}
