package ruc.db.generator;

import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintNodeJoinType;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.generator.joininfo.JoinStatus;
import ruc.db.generator.joininfo.MergedRuleTable;
import ruc.db.generator.joininfo.RuleTableManager;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.TableManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static ruc.db.utils.CommonUtils.DECIMAL_DIVIDE_SCALE;

public class FkGenerator {
    private static final Logger logger = LoggerFactory.getLogger(FkGenerator.class);
    private final long tableSize;

    private final Map<Integer, Long> distinctFkIndex2Cardinality = new HashMap<>();
    private final Set<Integer> aggregateDistinctFkIndexes = new HashSet<>();
    private final int[] involvedChainIndexes;
    private final List<List<ConstraintChainNode>> chainNodesList = new LinkedList<>();

    private final JoinStatus[][] jointPkStatus;

    private final JoinStatus[] outputStatusForEachPk;

    private final MergedRuleTable[] ruleTables;

    /** fk 列索引 → 该列上代表 FK 链路的节点元数据（GENERIC 反域等）。 */
    private final ConstraintChainFkJoinNode[] fkJoinMetaByColIndex;

    private static final int CORE_NUM = Runtime.getRuntime().availableProcessors();

    private static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(CORE_NUM);

    private long populateFKTime = 0;

    private long constructHistogram = 0;

    private long solveCPTime = 0;

    public long getPopulateFKTime() {
        return populateFKTime;
    }

    public long getSolveCPTime() {
        return solveCPTime;
    }

    public long getConstructHistogram() {
        return constructHistogram;
    }

    FkGenerator(List<ConstraintChain> fkConstrainChains, List<String> fkGroup, long tableSize) {
        this.tableSize = tableSize;
        List<Integer> involvedChainIndexesList = new ArrayList<>();
        for (ConstraintChain fkConstrainChain : fkConstrainChains) {
            var involvedNodes = fkConstrainChain.getInvolvedJoinKeyNodes(fkGroup);
            if (!involvedNodes.isEmpty()) {
                involvedChainIndexesList.add(fkConstrainChain.getChainIndex());
                chainNodesList.add(involvedNodes);
            }
        }
        involvedChainIndexes = involvedChainIndexesList.stream().mapToInt(Integer::intValue).toArray();
        logger.info("FK组 {} 使用的约束链索引: {}, 约束链数量: {}", 
            fkGroup, java.util.Arrays.toString(involvedChainIndexes), involvedChainIndexes.length);
        // 获得每个fk列的status, 标记外键对应的index
        LinkedHashMap<String, int[]> involvedFkCol2JoinTags = generateFkIndex(fkGroup, chainNodesList);
        fkJoinMetaByColIndex = new ConstraintChainFkJoinNode[fkGroup.size()];
        for (List<ConstraintChainNode> chain : chainNodesList) {
            for (ConstraintChainNode n : chain) {
                if (n instanceof ConstraintChainFkJoinNode fk) {
                    int j = fk.joinStatusIndex;
                    if (j >= 0 && j < fkJoinMetaByColIndex.length) {
                        ConstraintChainFkJoinNode existing = fkJoinMetaByColIndex[j];
                        if (shouldPreferAsLocalColumnMeta(existing, fk)) {
                            fkJoinMetaByColIndex[j] = fk;
                        }
                    }
                }
            }
        }
        // 对于每一个外键组，确定主键状态
        JoinStatus[][] pkCol2AllStatus = new JoinStatus[involvedFkCol2JoinTags.size()][];
        int i = 0;
        ruleTables = new MergedRuleTable[involvedFkCol2JoinTags.size()];
        for (Map.Entry<String, int[]> involvedFk2JoinTag : involvedFkCol2JoinTags.entrySet()) {
            String pkCol = chooseReferenceRuleTableKey(involvedFk2JoinTag.getKey());
            ruleTables[i] = RuleTableManager.getInstance().getRuleTable(pkCol, involvedFk2JoinTag.getValue());
            boolean withNull = ColumnManager.getInstance().getNullPercentage(involvedFk2JoinTag.getKey()).compareTo(BigDecimal.ZERO) > 0;
            boolean withGenericAntiStatus = needsSyntheticFalseStatusForGenericJoin(involvedFk2JoinTag.getKey());
            pkCol2AllStatus[i] = ruleTables[i].getPkStatus(withNull || withGenericAntiStatus);
            if (withGenericAntiStatus) {
                logger.info("GENERIC FK列 {} 启用合成 all-false 反域状态，用于满足非匹配JOIN基数", involvedFk2JoinTag.getKey());
            }
            i++;
        }
        // 计算联合status
        jointPkStatus = getPkJointStatus(pkCol2AllStatus);
        chainNodesList.stream().flatMap(Collection::stream)
                .filter(ConstraintChainFkJoinNode.class::isInstance)
                .map(ConstraintChainFkJoinNode.class::cast).forEach(node -> node.initJoinResultStatus(jointPkStatus));
        // 计算输出的status
        outputStatusForEachPk = computeOutputStatus(fkConstrainChains.size());
        for (Map.Entry<Integer, Long> fkIndex2Cardinality : distinctFkIndex2Cardinality.entrySet()) {
            long fkColCardinality = ColumnManager.getInstance().getNdv(fkGroup.get(fkIndex2Cardinality.getKey()));
            fkIndex2Cardinality.setValue(fkColCardinality);
        }
    }

    private boolean shouldPreferAsLocalColumnMeta(ConstraintChainFkJoinNode existing,
                                                  ConstraintChainFkJoinNode candidate) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        boolean existingGeneric = existing.getJoinModel() == JoinConstraintJoinModel.GENERIC;
        boolean candidateGeneric = candidate.getJoinModel() == JoinConstraintJoinModel.GENERIC;
        if (candidateGeneric != existingGeneric) {
            return candidateGeneric;
        }
        return false;
    }

    private String chooseReferenceRuleTableKey(String localCol) {
        String logicalRef = LogicalJoinReferenceRegistry.getRefKey(localCol);
        String physicalRef = TableManager.getInstance().getRefKey(localCol);
        if (hasGenericJoinForLocalColumn(localCol) && logicalRef != null
                && RuleTableManager.getInstance().hasRuleTable(logicalRef)) {
            if (physicalRef != null && !physicalRef.equals(logicalRef)) {
                logger.info("共享JOIN列 {} 同时存在物理参照 {} 与 GENERIC 参照 {}，FK生成优先使用 GENERIC RuleTable",
                        localCol, physicalRef, logicalRef);
            }
            return logicalRef;
        }
        if (physicalRef != null) {
            return physicalRef;
        }
        return logicalRef;
    }

    private boolean hasGenericJoinForLocalColumn(String localCol) {
        for (List<ConstraintChainNode> chain : chainNodesList) {
            for (ConstraintChainNode node : chain) {
                if (!(node instanceof ConstraintChainFkJoinNode fk)) {
                    continue;
                }
                if (!localCol.equals(fk.getLocalCols())) {
                    continue;
                }
                if (fk.getJoinModel() == JoinConstraintJoinModel.GENERIC) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean needsSyntheticFalseStatusForGenericJoin(String localCol) {
        for (List<ConstraintChainNode> chain : chainNodesList) {
            for (ConstraintChainNode node : chain) {
                if (!(node instanceof ConstraintChainFkJoinNode fk)) {
                    continue;
                }
                if (fk.getJoinModel() != JoinConstraintJoinModel.GENERIC) {
                    continue;
                }
                if (!localCol.equals(fk.getLocalCols())) {
                    continue;
                }
                Long target = fk.getTargetJoinRows();
                Long left = fk.getLeftInputRows();
                if (target != null && left != null && left > 0 && target < left) {
                    return true;
                }
                if (fk.getProbability() != null && fk.getProbability().compareTo(BigDecimal.ONE) < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applySharePkConstraint(ConstructCpModel cpModel, int range) {
        BigDecimal batchPercentage = BigDecimal.valueOf(range).divide(BigDecimal.valueOf(tableSize), DECIMAL_DIVIDE_SCALE, RoundingMode.HALF_UP);
        logger.info("计算FK共享约束: range={}, tableSize={}, batchPercentage={}", range, tableSize, batchPercentage);
        for (var distinctFKIndex : distinctFkIndex2Cardinality.keySet()) {
            Map<JoinStatus, ArrayList<Integer>> status2PkIndex = new HashMap<>();
            for (int i = 0; i < jointPkStatus.length; i++) {
                JoinStatus status = jointPkStatus[i][distinctFKIndex];
                status2PkIndex.computeIfAbsent(status, v -> new ArrayList<>());
                status2PkIndex.get(status).add(i);
            }
            Map<ArrayList<Integer>, Long> pkIndex2Size = new HashMap<>();
            MergedRuleTable ruleTable = ruleTables[distinctFKIndex];
            for (Map.Entry<JoinStatus, ArrayList<Integer>> statusArrayListEntry : status2PkIndex.entrySet()) {
                JoinStatus shareStatus = statusArrayListEntry.getKey();
                if (!ruleTable.containsStatus(shareStatus)) {
                    logger.debug("跳过FK共享约束: FK列[{}], JoinStatus={} 为合成反域状态，不受真实RuleTable容量限制", distinctFKIndex, shareStatus);
                    continue;
                }
                long pkStatusSize = ruleTable.getStatusSize(shareStatus);
                BigDecimal bBatchPkStatusSize = BigDecimal.valueOf(pkStatusSize).multiply(batchPercentage);
                long batchPkStatusSize = bBatchPkStatusSize.setScale(0, RoundingMode.UP).longValue();
                
                // 记录详细信息用于调试
                logger.info("FK共享约束计算: FK列[{}], JoinStatus={}, pkStatusSize={}, batchPercentage={}, 计算值={}, 向上取整后={}",
                    distinctFKIndex, shareStatus, pkStatusSize, batchPercentage, bBatchPkStatusSize, batchPkStatusSize);
                
                // 警告：如果batchPkStatusSize太小，可能导致与其他distinct约束冲突
                if (batchPkStatusSize <= 1 && pkStatusSize > 1) {
                    logger.warn("FK共享约束值可能过小: FK列[{}], pkStatusSize={}, batchPkStatusSize={}, 这可能导致与其他distinct约束冲突",
                        distinctFKIndex, pkStatusSize, batchPkStatusSize);
                }
                
                pkIndex2Size.put(statusArrayListEntry.getValue(), batchPkStatusSize);
            }
            cpModel.applyFKShareConstraint(distinctFKIndex, pkIndex2Size);
        }
    }

    private ConstructCpModel constructConstraintProblem(Map<JoinStatus, Long> statusHistogram, int range) {
        logger.info("构建CP模型: range={}, filter状态数={}, PK状态数={}", 
            range, statusHistogram.size(), jointPkStatus.length);
        logger.info("FK生成使用的约束链索引: {}", java.util.Arrays.toString(involvedChainIndexes));
        logger.info("FK生成使用的约束链数量: {}, 总约束链数量: {}", involvedChainIndexes.length, 
            chainNodesList.size());
        ConstructCpModel constructCpModel = new ConstructCpModel();
        constructCpModel.initModel(statusHistogram, jointPkStatus.length, range);
        Map<ConstraintChainFkJoinNode, Long> genericInputRowsByNode = new IdentityHashMap<>();
        for (var distinctFkCol2Cardinality : distinctFkIndex2Cardinality.entrySet()) {
            constructCpModel.initDistinctModel(distinctFkCol2Cardinality.getKey(), distinctFkCol2Cardinality.getValue(), tableSize);
        }
        for (int chainIndex = 0; chainIndex < chainNodesList.size(); chainIndex++) {
            boolean[][] canBeInput = new boolean[statusHistogram.size()][jointPkStatus.length];
            int i = 0;
            long filterSize = 0;
            for (var status2Size : statusHistogram.entrySet()) {
                boolean filterStatus = status2Size.getKey().status()[chainIndex];
                filterSize += filterStatus ? status2Size.getValue() : 0;
                Arrays.fill(canBeInput[i++], filterStatus);
            }
            long unFilterSize = range - filterSize;
            for (ConstraintChainNode constraintChainNode : chainNodesList.get(chainIndex)) {
                if (constraintChainNode instanceof ConstraintChainFkJoinNode fkJoinNode) {
                    if (fkJoinNode.getJoinModel() == JoinConstraintJoinModel.GENERIC && fkJoinNode.getTargetJoinRows() != null) {
                        logger.debug("GENERIC FK join node: targetJoinRows={}, leftIn={}, rightIn={}",
                                fkJoinNode.getTargetJoinRows(), fkJoinNode.getLeftInputRows(), fkJoinNode.getRightInputRows());
                        genericInputRowsByNode.put(fkJoinNode, filterSize);
                    }
                    fkJoinNode.addJoinDistinctConstraint(constructCpModel, filterSize, canBeInput);
                    filterSize = fkJoinNode.addJoinCardinalityConstraint(constructCpModel, filterSize, unFilterSize, canBeInput);
                    unFilterSize = -1;
                } else if (constraintChainNode instanceof ConstraintChainAggregateNode aggregateNode) {
                    aggregateNode.addJoinDistinctConstraint(constructCpModel, filterSize, canBeInput);
                }
            }
        }
        GenericJoinPfConstraints.appendTo(constructCpModel, chainNodesList, tableSize, genericInputRowsByNode);
        applySharePkConstraint(constructCpModel, range);
        return constructCpModel;
    }

    static void staticsStatusHistogram(boolean[][] statusVectorOfEachRow, JoinStatus[] involvedStatuses,
                                       int[] chainIndexes, Map<JoinStatus, Long> statusHistogram) {
        int range = statusVectorOfEachRow.length;
        int histogramStaticsRange = range / CORE_NUM + 1;
        int rangeStart = 0;
        List<Future<Map<JoinStatus, AtomicLong>>> allStatusHistograms = new ArrayList<>();
        for (int i = 0; i < CORE_NUM; i++) {
            int finalRangeStart = rangeStart;
            allStatusHistograms.add(THREAD_POOL.submit(() -> {
                Map<JoinStatus, AtomicLong> selfStatusHistogram = new HashMap<>();
                int endRange = Math.min(finalRangeStart + histogramStaticsRange, range);
                for (int rowId = finalRangeStart; rowId < endRange; rowId++) {
                    JoinStatus chooseCorrespondingStatus = FkGenerator.chooseCorrespondingStatus(statusVectorOfEachRow[rowId], chainIndexes);
                    selfStatusHistogram.computeIfAbsent(chooseCorrespondingStatus, v -> new AtomicLong(0));
                    selfStatusHistogram.get(chooseCorrespondingStatus).incrementAndGet();
                    involvedStatuses[rowId] = chooseCorrespondingStatus;
                }
                return selfStatusHistogram;
            }));
            rangeStart += histogramStaticsRange;
            if (rangeStart >= range) {
                break;
            }
        }
        for (Future<Map<JoinStatus, AtomicLong>> allStatusHistogram : allStatusHistograms) {
            try {
                var tmp = allStatusHistogram.get();
                for (Map.Entry<JoinStatus, AtomicLong> selfStatusHistogram : tmp.entrySet()) {
                    statusHistogram.putIfAbsent(selfStatusHistogram.getKey(), 0L);
                    long totalNum = selfStatusHistogram.getValue().get() + statusHistogram.get(selfStatusHistogram.getKey());
                    statusHistogram.put(selfStatusHistogram.getKey(), totalNum);
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }



    /**
     * 计算CP问题的解
     *
     * @param statusVectorOfEachRow 每一行数据的filter status
     * @param pkStatuses            此行数据需要填充的pkStatus
     * @param fkIndex2Range         每个FK列对应的JDC的解
     * @param filterIndexes         记录每行数据对应的status
     */
    private void solveCP(boolean[][] statusVectorOfEachRow, int[] pkStatuses, int[] filterIndexes,
                         Map<Integer, FkRange[][]> fkIndex2Range) {
        long startConstructHistogram = System.currentTimeMillis();
        int range = statusVectorOfEachRow.length;
        JoinStatus[] involvedStatuses = new JoinStatus[range];
        // 根据右表状态计算统计直方图
        Map<JoinStatus, Long> statusHistogram = new LinkedHashMap<>();
        logger.info("计算StatusHistogram: 使用约束链索引={}, statusVectorOfEachRow列数={}", 
            java.util.Arrays.toString(involvedChainIndexes), statusVectorOfEachRow[0].length);
        staticsStatusHistogram(statusVectorOfEachRow, involvedStatuses, involvedChainIndexes, statusHistogram);
        // 标记直方图状态的位置
        HashMap<JoinStatus, Integer> status2Index = new HashMap<>();
        int i = 0;
        for (JoinStatus joinStatus : statusHistogram.keySet()) {
            status2Index.put(joinStatus, i++);
        }
        // 为每一行数据记录位置
        IntStream.range(0, range).parallel().forEach(rowId -> filterIndexes[rowId] = status2Index.get(involvedStatuses[rowId]));
        long endConstruction = System.currentTimeMillis();
        constructHistogram += endConstruction - startConstructHistogram;
        // 给定一个populateSolution，计算每一行数据需要填充的主键状态，以及剩余未填充的数据量
        int[] filterStatusPkPopulatedIndex = new int[statusHistogram.size()];
        // 构建并求解CP问题
        // 记录 statusHistogram 详细信息用于调试
        logger.info("StatusHistogram 详细信息 (range={}):", range);
        long totalFilterRows = 0;
        for (Map.Entry<JoinStatus, Long> entry : statusHistogram.entrySet()) {
            long rows = entry.getValue();
            totalFilterRows += rows;
            logger.info("  Filter状态: {}, 行数: {}",
                java.util.Arrays.toString(entry.getKey().status()), rows);
        }
        logger.info("StatusHistogram 总行数: {} (range={})", totalFilterRows, range);

        // 传递statusHistogram给cpModel用于调试
        ConstructCpModel cpModel = constructConstraintProblem(statusHistogram, range);
        cpModel.setStatusHistogramForDebug(statusHistogram);
        long[][] populateSolution = cpModel.solve();
        // 记录JDC的解
        for (Integer fkIndex : distinctFkIndex2Cardinality.keySet()) {
            fkIndex2Range.put(fkIndex, cpModel.getDistinctResult(fkIndex));
        }
        Arrays.fill(filterStatusPkPopulatedIndex, 0);
        for (int rowId = 0; rowId < range; rowId++) {
            int filterIndex = filterIndexes[rowId];
            int pkStatusIndex = filterStatusPkPopulatedIndex[filterIndex];
            int maxPk = populateSolution[filterIndex].length;
            while (pkStatusIndex < maxPk && populateSolution[filterIndex][pkStatusIndex] == 0) {
                pkStatusIndex++;
                filterStatusPkPopulatedIndex[filterIndex] = pkStatusIndex;
            }
            if (pkStatusIndex >= maxPk || populateSolution[filterIndex][pkStatusIndex] == 0) {
                logger.error(
                        "FK/CP 行分配失败: filterIndex={} 无可用 pkStatus（该 filter 上 CP 解全为 0，常与谓词选择率 0、StatusHistogram 与 CP 不一致或不可行有关），rowId={}，populateSolution[{}]={}",
                        filterIndex, rowId, filterIndex, Arrays.toString(populateSolution[filterIndex]));
                throw new IllegalStateException(
                        "CP populateSolution has no positive slot for filterIndex=" + filterIndex
                                + " (often 0% filter selectivity vs PK join); rowId=" + rowId);
            }
            pkStatuses[rowId] = pkStatusIndex;
            populateSolution[filterIndex][pkStatusIndex]--;
        }
        solveCPTime += System.currentTimeMillis() - endConstruction;
    }


    private long[] populateFkForJDC(int fkColIndex, MergedRuleTable ruleTable, int[] pkStatuses,
                                    int[] filterIndexes, FkRange[][] fkRangeForFk) {
        ruleTable.refreshRuleCounter();
        int range = pkStatuses.length;
        long[] fkCol = new long[range];
        ConstraintChainFkJoinNode meta = fkJoinMetaByColIndex != null && fkColIndex < fkJoinMetaByColIndex.length
                ? fkJoinMetaByColIndex[fkColIndex] : null;
        long matchedRows = countMatchedRows(meta, fkColIndex, pkStatuses);
        long allowedExtraFanout = computeAllowedGenericOuterJoinExtraFanout(meta, fkColIndex, pkStatuses);
        long usedExtraFanout = 0L;
        long consumedMatchedRows = 0L;
        for (int rowId = 0; rowId < range; rowId++) {
            int pkStatusIndex = pkStatuses[rowId];
            JoinStatus populateStatus = jointPkStatus[pkStatusIndex][fkColIndex];
            int filterIndex = filterIndexes[rowId];
            FkRange fkRange = fkRangeForFk[filterIndex][pkStatusIndex];
            int index = fkRange.start + fkRange.range - 1;
            if (fkRange.range > 1) {
                fkRange.range--;
            } else if (fkRange.range != fkRange.totalRange) {
                fkRange.range = fkRange.totalRange;
            }
            long raw = ruleTable.getKey(populateStatus, index);
            if (meta != null && meta.getJoinModel() == JoinConstraintJoinModel.GENERIC
                    && !aggregateDistinctFkIndexes.contains(fkColIndex)
                    && populateStatus.status()[meta.joinStatusLocation]) {
                if (isHighMatchGenericOuterJoin(meta)) {
                    long remainingExtraFanout = Math.max(0L, allowedExtraFanout - usedExtraFanout);
                    long remainingMatchedRows = Math.max(1L, matchedRows - consumedMatchedRows);
                    raw = LogicalJoinReferenceRegistry.remapToControlledMultiplicityReferenceKey(
                            meta.getLocalCols(), raw, rowId, remainingExtraFanout, remainingMatchedRows);
                    usedExtraFanout += Math.max(0L,
                            LogicalJoinReferenceRegistry.referenceMultiplicity(meta.getLocalCols(), raw) - 1L);
                } else {
                    raw = LogicalJoinReferenceRegistry.remapToLowMultiplicityReferenceKey(meta.getLocalCols(), raw, rowId);
                }
                consumedMatchedRows++;
            }
            fkCol[rowId] = raw;
        }
        return fkCol;
    }

    private long computeAllowedGenericOuterJoinExtraFanout(ConstraintChainFkJoinNode meta, int fkColIndex,
                                                           int[] pkStatuses) {
        if (!isHighMatchGenericOuterJoin(meta) || pkStatuses == null) {
            return 0L;
        }
        long matchedRows = countMatchedRows(meta, fkColIndex, pkStatuses);
        if (matchedRows <= 0L) {
            return 0L;
        }
        BigDecimal scaledTarget = BigDecimal.valueOf(matchedRows)
                .multiply(BigDecimal.valueOf(meta.getTargetJoinRows()))
                .divide(BigDecimal.valueOf(meta.getLocalInputRows()), 0, RoundingMode.HALF_UP);
        return Math.max(0L, scaledTarget.longValue() - matchedRows);
    }

    private long countMatchedRows(ConstraintChainFkJoinNode meta, int fkColIndex, int[] pkStatuses) {
        if (meta == null || pkStatuses == null) {
            return 0L;
        }
        long matchedRows = 0L;
        for (int pkStatusIndex : pkStatuses) {
            if (pkStatusIndex < 0 || pkStatusIndex >= jointPkStatus.length) {
                continue;
            }
            JoinStatus status = jointPkStatus[pkStatusIndex][fkColIndex];
            if (status.status()[meta.joinStatusLocation]) {
                matchedRows++;
            }
        }
        return matchedRows;
    }

    private boolean isHighMatchGenericOuterJoin(ConstraintChainFkJoinNode meta) {
        if (meta == null || meta.getJoinModel() != JoinConstraintJoinModel.GENERIC
                || meta.getType() != ConstraintNodeJoinType.OUTER_JOIN
                || meta.getTargetJoinRows() == null || meta.getLocalInputRows() == null
                || meta.getLocalInputRows() <= 0L) {
            return false;
        }
        long localRows = meta.getLocalInputRows();
        long targetRows = meta.getTargetJoinRows();
        if (targetRows < localRows) {
            return false;
        }
        BigDecimal ratio = BigDecimal.valueOf(targetRows)
                .divide(BigDecimal.valueOf(localRows), 4, RoundingMode.HALF_UP);
        return ratio.compareTo(new BigDecimal("1.25")) <= 0;
    }

    private long[] populateFkForJCC(int fkColIndex, MergedRuleTable ruleTable, int[] pkStatuses) {
        int range = pkStatuses.length;
        long[] fkCol = new long[range];
        ConstraintChainFkJoinNode meta = fkJoinMetaByColIndex != null && fkColIndex < fkJoinMetaByColIndex.length
                ? fkJoinMetaByColIndex[fkColIndex] : null;
        IntStream.range(0, range).parallel().forEach(rowId -> {
            int pkStatusIndex = pkStatuses[rowId];
            JoinStatus populateStatus = jointPkStatus[pkStatusIndex][fkColIndex];
            long raw = ruleTable.getKey(populateStatus, -1);
            fkCol[rowId] = GenericJoinAntiDomain.maybeBiasGenericSample(raw, rowId, fkColIndex, meta);
        });
        return fkCol;
    }

    public long[][] generateFK(boolean[][] statusVectorOfEachRow) {
        // 统计每种状态的数据量
        if (involvedChainIndexes.length == 0) {
            return new long[0][0];
        }
        int range = statusVectorOfEachRow.length;
        int[] pkStatuses = new int[range];
        // 记录每行数据对应的status
        int[] filterIndexes = new int[range];
        Map<Integer, FkRange[][]> fkIndex2Range = new HashMap<>();

        solveCP(statusVectorOfEachRow, pkStatuses, filterIndexes, fkIndex2Range);

        long startPopulateFK = System.currentTimeMillis();
        int fkColNum = jointPkStatus[0].length;
        long[][] fkColValues = new long[fkColNum][range];
        List<Future<long[]>> futureFkCols = new ArrayList<>();
        for (int fkColIndex = 0; fkColIndex < fkColNum; fkColIndex++) {
            MergedRuleTable ruleTable = ruleTables[fkColIndex];
            int finalFkColIndex = fkColIndex;
            if (fkIndex2Range.containsKey(fkColIndex)) {
                futureFkCols.add(THREAD_POOL.submit(() ->
                        populateFkForJDC(finalFkColIndex, ruleTable, pkStatuses, filterIndexes, fkIndex2Range.get(finalFkColIndex))));
            } else {
                futureFkCols.add(THREAD_POOL.submit(() -> populateFkForJCC(finalFkColIndex, ruleTable, pkStatuses)));
            }
        }
        for (int fkColIndex = 0; fkColIndex < fkColValues.length; fkColIndex++) {
            try {
                fkColValues[fkColIndex] = futureFkCols.get(fkColIndex).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        // 计算每一行数据的输出状态
        int chainSize = statusVectorOfEachRow[0].length;
        IntStream.range(0, range).parallel().forEach(rowId -> {
            boolean[] outputStatus = outputStatusForEachPk[pkStatuses[rowId]].status();
            for (int fkColIndex = 0; fkColIndex < chainSize; fkColIndex++) {
                statusVectorOfEachRow[rowId][fkColIndex] &= outputStatus[fkColIndex];
            }
        });
        populateFKTime += System.currentTimeMillis() - startPopulateFK;
        return fkColValues;
    }

    /**
     * 输入约束链，返回涉及到所有状态 组织结构如下
     * pkCol1 ---- pkCol2 ----- pkCol3
     * status1 --- status2 ---- status3
     * status11--- status22---- status33
     * ......
     *
     * @param pkCol2AllStatus 涉及到参照主键，组织为主键列 -> status（boolean[]）
     * @return 所有可能的状态组 组织为 -> joint status -> 各列主键
     */
    private JoinStatus[][] getPkJointStatus(JoinStatus[][] pkCol2AllStatus) {
        int allStatusSize = 1;
        for (JoinStatus[] pkStatus : pkCol2AllStatus) {
            allStatusSize *= pkStatus.length;
        }
        int[] loopForEachPk = new int[pkCol2AllStatus.length];
        int currentSize = 1;
        for (int i = 0; i < pkCol2AllStatus.length; i++) {
            currentSize = pkCol2AllStatus[i].length * currentSize;
            loopForEachPk[i] = allStatusSize / currentSize;
        }
        JoinStatus[][] allDiffStatus = new JoinStatus[allStatusSize][];
        for (int index = 0; index < allStatusSize; index++) {
            JoinStatus[] result = new JoinStatus[pkCol2AllStatus.length];
            for (int colIndex = 0; colIndex < pkCol2AllStatus.length; colIndex++) {
                JoinStatus[] pkStatus = pkCol2AllStatus[colIndex];
                result[colIndex] = pkStatus[index / loopForEachPk[colIndex] % pkStatus.length];
            }
            allDiffStatus[index] = result;
        }
        return allDiffStatus;
    }

    public static JoinStatus chooseCorrespondingStatus(boolean[] originStatus, int[] involvedChainIndexes) {
        boolean[] ret = new boolean[involvedChainIndexes.length];
        int i = 0;
        for (int involvedChainIndex : involvedChainIndexes) {
            if (involvedChainIndex < 0 || involvedChainIndex >= originStatus.length) {
                throw new IllegalArgumentException(String.format(
                        "RuleTable status length %d cannot satisfy requested join tag/index %d; status=%s, requested=%s",
                        originStatus.length,
                        involvedChainIndex,
                        Arrays.toString(originStatus),
                        Arrays.toString(involvedChainIndexes)));
            }
            ret[i++] = originStatus[involvedChainIndex];
        }
        return new JoinStatus(ret);
    }

    private JoinStatus[] computeOutputStatus(int allChainSize) {
        JoinStatus[] outputStatus = new JoinStatus[jointPkStatus.length];
        for (int j = 0; j < outputStatus.length; j++) {
            boolean[] status = new boolean[allChainSize];
            Arrays.fill(status, true);
            outputStatus[j] = new JoinStatus(status);
        }
        for (int pkStatusIndex = 0; pkStatusIndex < outputStatus.length; pkStatusIndex++) {
            for (int currentChainIndex = 0; currentChainIndex < involvedChainIndexes.length; currentChainIndex++) {
                List<ConstraintChainFkJoinNode> chainFkJoinNodes = chainNodesList.get(currentChainIndex).stream()
                        .filter(ConstraintChainFkJoinNode.class::isInstance).map(ConstraintChainFkJoinNode.class::cast).toList();
                JoinStatus[] pkStatusOfRow = jointPkStatus[pkStatusIndex];
                boolean status = true;
                for (ConstraintChainFkJoinNode chainFkJoinNode : chainFkJoinNodes) {
                    status &= pkStatusOfRow[chainFkJoinNode.joinStatusIndex].status()[chainFkJoinNode.joinStatusLocation];
                }
                int chainIndex = involvedChainIndexes[currentChainIndex];
                outputStatus[pkStatusIndex].status()[chainIndex] = status;
            }
        }
        return outputStatus;
    }

    /**
     * 返回所有约束链的参照表和参照的Tag
     *
     * @param chainNodesList 需要分析的约束链
     */
    private LinkedHashMap<String, int[]> generateFkIndex(List<String> fkCols, List<List<ConstraintChainNode>> chainNodesList) {
        // 找到涉及到的参照表和参照的tag
        LinkedHashMap<String, List<Integer>> involvedFkCol2JoinTags = new LinkedHashMap<>();
        for (String fkCol : fkCols) {
            involvedFkCol2JoinTags.put(fkCol, new ArrayList<>());
        }
        List<ConstraintChainFkJoinNode> involvedFkNodes = chainNodesList.stream().flatMap(Collection::stream)
                .filter(ConstraintChainFkJoinNode.class::isInstance).map(ConstraintChainFkJoinNode.class::cast).toList();
        for (ConstraintChainFkJoinNode fkNode : involvedFkNodes) {
            involvedFkCol2JoinTags.get(fkNode.getLocalCols()).add(fkNode.getPkTag());
            fkNode.joinStatusIndex = fkCols.indexOf(fkNode.getLocalCols());
            if (fkNode.getPkDistinctProbability() != null && !fkNode.getType().isSemi()) {
                distinctFkIndex2Cardinality.put(fkNode.joinStatusIndex, 0L);
            }
        }
        //对所有的位置进行排序
        involvedFkCol2JoinTags.values().forEach(Collections::sort);
        //标记约束链对应的status的位置
        for (ConstraintChainFkJoinNode fkJoinNode : involvedFkNodes) {
            fkJoinNode.joinStatusLocation = involvedFkCol2JoinTags.get(fkJoinNode.getLocalCols()).indexOf(fkJoinNode.getPkTag());
        }
        // 绑定Aggregation到相关的Fk上
        List<ConstraintChainAggregateNode> involvedAggNodes = chainNodesList.stream().flatMap(Collection::stream)
                .filter(ConstraintChainAggregateNode.class::isInstance).map(ConstraintChainAggregateNode.class::cast).toList();
        for (ConstraintChainAggregateNode aggregateNode : involvedAggNodes) {
            if (aggregateNode.getGroupKey() == null || aggregateNode.getGroupKey().size() != 1) {
                logger.warn("Skip unsupported aggregate distinct constraint with groupKey={}", aggregateNode.getGroupKey());
                continue;
            }
            String groupKey = normalizeColumnExpression(aggregateNode.getGroupKey().get(0));
            var fkNode = involvedFkNodes.stream()
                    .filter(node -> normalizeColumnExpression(node.getLocalCols()).equals(groupKey))
                    .findAny();
            if (fkNode.isPresent()) {
                aggregateNode.joinStatusIndex = fkNode.get().joinStatusIndex;
                distinctFkIndex2Cardinality.put(aggregateNode.joinStatusIndex, 0L);
                aggregateDistinctFkIndexes.add(aggregateNode.joinStatusIndex);
            } else {
                logger.warn("Skip aggregate distinct constraint that cannot bind to a local join key: {}", aggregateNode);
            }
        }
        LinkedHashMap<String, int[]> ret = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : involvedFkCol2JoinTags.entrySet()) {
            ret.put(entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        return ret;
    }

    private static String normalizeColumnExpression(String expression) {
        if (expression == null) {
            return null;
        }
        String normalized = expression.trim();
        while (normalized.startsWith("(") && normalized.endsWith(")") && hasBalancedOuterParentheses(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        int castIndex = normalized.indexOf("::");
        if (castIndex >= 0) {
            normalized = normalized.substring(0, castIndex).trim();
            while (normalized.startsWith("(") && normalized.endsWith(")") && hasBalancedOuterParentheses(normalized)) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
        }
        return normalized;
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
