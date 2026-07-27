package ruc.db.generator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintNodeJoinType;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.generator.joininfo.JoinStatus;
import ruc.db.generator.joininfo.MergedRuleTable;
import ruc.db.generator.joininfo.RuleTableManager;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.schema.TableManager;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJoinLogicalRuleTableTest {

    @BeforeEach
    void clearGlobalState() {
        LogicalJoinReferenceRegistry.clear();
        RuleTableManager.getInstance().clear();
        TableManager.getInstance().getSchemas().clear();
        clearColumnManager();
    }

    private static void addVarcharColumnWithValues(String col, Object[] values) throws Exception {
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(32)");
        column.init();
        column.setColumnActualData(values);
        ColumnManager.getInstance().addColumn(col, column);
    }


    @Test
    void genericJoinKeyGeneratedOnlyAfterReferencedRuleTableExists() throws Exception {
        String localCol = "public.fact.mgt_org_code";
        String refCol = "public.org_child.child_mgt_org_code";
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, BigDecimal.ONE);
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        assertTrue(!DataGenerator.canGenerateJoinKey(join));

        Object[] refValues = new Object[10];
        for (int i = 0; i < refValues.length; i++) {
            refValues[i] = "51401" + i;
        }
        addVarcharColumnWithValues(refCol, refValues);
        LogicalJoinReferenceRegistry.rememberReferenceValues(refCol, refValues, 1L);

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 10L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        assertTrue(DataGenerator.canGenerateJoinKey(join));
    }

    @Test
    void genericJoinDiagnosticsAccountForReferenceMultiplicity() {
        String localCol = "public.fact.trml_addr_code";
        String refCol = "public.he.tmnl_comm_addr";

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"A", "A", "B"},
                1L,
                Map.of(localCol, java.util.Set.of("A", "B")),
                Map.of(localCol, Map.of("A", 2L, "B", 1L)));

        Object[] localValues = {"A", "B", "C", null};

        assertEquals(2L, LogicalJoinReferenceRegistry.countMatchingReferenceValues(localCol, localValues));
        assertEquals(3L, LogicalJoinReferenceRegistry.countInnerJoinRowsWithReferenceMultiplicity(localCol, localValues));
        assertEquals(5L, LogicalJoinReferenceRegistry.countLeftJoinRowsWithReferenceMultiplicity(localCol, localValues));
        assertEquals(Map.of("A", 2L, "B", 1L), LogicalJoinReferenceRegistry.countRowsPerMatchedGroup(localCol));
        assertEquals(5L, LogicalJoinReferenceRegistry.sumCoalescedCountForLeftJoin(localCol, localValues, 1L));
    }

    @Test
    void genericJoinCanRemapHotReferenceKeyToLowMultiplicityKey() {
        String localCol = "public.fact.trml_addr_code";
        String refCol = "public.he.tmnl_comm_addr";

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"HOT", "HOT", "HOT", "COLD"},
                1L,
                Map.of(localCol, java.util.Set.of("HOT", "COLD")),
                Map.of(localCol, Map.of("HOT", 3L, "COLD", 1L)));

        long remapped = LogicalJoinReferenceRegistry.remapToLowMultiplicityReferenceKey(localCol, 1L, 0);

        assertEquals(4L, remapped);
    }

    @Test
    void lowMultiplicityRemapCacheIsThreadSafeForSharedLocalColumn() throws Exception {
        String localCol = "public.fact.q10_mgt_org_code";
        String refCol = "public.org_child.child_mgt_org_code";

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"HOT", "HOT", "HOT", "COLD_A", "COLD_B", "COLD_C"},
                1L,
                Map.of(localCol, Set.of("HOT", "COLD_A", "COLD_B", "COLD_C")),
                Map.of(localCol, Map.of("HOT", 3L, "COLD_A", 1L, "COLD_B", 1L, "COLD_C", 1L)));

        int threadCount = 8;
        int iterationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        long remapped = LogicalJoinReferenceRegistry.remapToLowMultiplicityReferenceKey(localCol, 1L, i);
                        assertTrue(remapped >= 4L && remapped <= 6L, "remapped=" + remapped);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logicalRegistryRejectsTwoGenericReferencesForSameLocalColumn() {
        String localCol = "public.fact.q10_mgt_org_code";
        String semiJoinRefCol = "public.org_child.child_mgt_org_code";
        String parentJoinRefCol = "public.org_parent.mgt_org_code";

        LogicalJoinReferenceRegistry.register(localCol, semiJoinRefCol, 0, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> LogicalJoinReferenceRegistry.register(localCol, parentJoinRefCol, 1, null));

        assertTrue(ex.getMessage().contains(localCol));
        assertTrue(ex.getMessage().contains(semiJoinRefCol));
        assertTrue(ex.getMessage().contains(parentJoinRefCol));
    }

    @Test
    void genericMaterializationOverwritesSharedLocalColumnWithSingleReferenceDomain() throws Exception {
        String tableName = "public.fact";
        String localCol = tableName + ".q10_mgt_org_code";
        String semiJoinRefCol = "public.org_child.child_mgt_org_code";
        String parentJoinRefCol = "public.org_parent.mgt_org_code";

        addVarcharColumnWithValues(localCol, new Object[]{"PARENT_1", "PARENT_2", "PARENT_3", "PARENT_4"});

        ConstraintChain factChain = new ConstraintChain(tableName);
        factChain.setChainIndex(0);

        ConstraintChainFkJoinNode semiJoin = new ConstraintChainFkJoinNode(
                localCol, semiJoinRefCol, 0, new BigDecimal("0.50"));
        semiJoin.setType(ConstraintNodeJoinType.SEMI_JOIN);
        semiJoin.setJoinModel(JoinConstraintJoinModel.GENERIC);
        semiJoin.setTargetJoinRows(4L);
        semiJoin.setLeftInputRows(4L);
        semiJoin.setLocalInputRows(4L);
        semiJoin.setRightInputRows(2L);
        semiJoin.setRefInputRows(2L);
        semiJoin.setPkDistinctProbability(BigDecimal.ONE);
        factChain.addNode(semiJoin);

        ConstraintChainFkJoinNode parentJoin = new ConstraintChainFkJoinNode(
                localCol, parentJoinRefCol, 1, BigDecimal.ONE);
        parentJoin.setType(ConstraintNodeJoinType.OUTER_JOIN);
        factChain.addNode(parentJoin);

        LogicalJoinReferenceRegistry.register(localCol, semiJoinRefCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                semiJoinRefCol,
                new Object[]{"CHILD_A", "CHILD_B", "CHILD_A", "CHILD_B"},
                1L,
                Map.of(localCol, Set.of("CHILD_A", "CHILD_B")),
                Map.of(localCol, Map.of("CHILD_A", 2L, "CHILD_B", 2L)));

        Map<String, long[]> fkCol2Values = new LinkedHashMap<>();
        fkCol2Values.put(localCol, new long[]{1L, 2L, 3L, 4L});

        Method method = DataGenerator.class.getDeclaredMethod(
                "materializeGenericLocalJoinColumns", String.class, List.class, Map.class);
        method.setAccessible(true);
        method.invoke(new DataGenerator(), tableName, List.of(factChain), fkCol2Values);

        Object[] actual = ColumnManager.getInstance().getColumn(localCol).getColumnActualData();
        Set<Object> distinctActual = new HashSet<>(Arrays.asList(actual));

        assertEquals(Set.of("CHILD_A", "CHILD_B"), distinctActual);
        assertTrue(Arrays.stream(actual).allMatch(v -> "CHILD_A".equals(v) || "CHILD_B".equals(v)),
                "actual=" + Arrays.toString(actual));
        assertTrue(Arrays.stream(actual).noneMatch(v -> String.valueOf(v).startsWith("PARENT_")),
                "actual=" + Arrays.toString(actual));
        assertTrue(!fkCol2Values.containsKey(localCol), "generic local column should be consumed during materialization");
    }

    @Test
    void genericReferenceSnapshotUsesFinalTupleValuesInsteadOfStaleIntermediateColumnData() throws Exception {
        String refTable = "public.org_child";
        String localCol = "public.fact.q10_mgt_org_code";
        String refCol = refTable + ".child_mgt_org_code";
        String filterCol = refTable + ".mgt_org_code";

        addVarcharColumnWithValues(refCol, new Object[]{"STALE_A", "STALE_B", "STALE_C"});
        addVarcharColumnWithValues(filterCol, new Object[]{"51401", "51401", "99999"});

        ConstraintChain refChain = new ConstraintChain(refTable);
        refChain.setChainIndex(0);
        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, refChain);

        boolean[][] statusVector = new boolean[][]{
                {true},
                {true},
                {false}
        };

        Method registerMethod = DataGenerator.class.getDeclaredMethod(
                "registerGenericReferenceRuleTables", String.class, List.class, boolean[][].class, int.class);
        registerMethod.setAccessible(true);
        registerMethod.invoke(new DataGenerator(), refTable, List.of(refChain), statusVector, 3);

        assertEquals(0, LogicalJoinReferenceRegistry.matchingReferenceDomainSize(localCol),
                "early registration should not persist stale witness values anymore");

        Map<String, Object[]> finalizedPkOutputs = new LinkedHashMap<>();
        finalizedPkOutputs.put(refCol, new Object[]{"CHILD_A", "CHILD_B", "CHILD_C"});
        finalizedPkOutputs.put(filterCol, new Object[]{"51401", "51401", "99999"});

        Method finalizeMethod = DataGenerator.class.getDeclaredMethod(
                "finalizeGenericReferenceSnapshots", String.class, List.class, boolean[][].class, int.class, Map.class);
        finalizeMethod.setAccessible(true);
        finalizeMethod.invoke(new DataGenerator(), refTable, List.of(refChain), statusVector, 3, finalizedPkOutputs);

        assertEquals(2, LogicalJoinReferenceRegistry.matchingReferenceDomainSize(localCol));
        assertEquals(Map.of("CHILD_A", 1L, "CHILD_B", 1L),
                LogicalJoinReferenceRegistry.countRowsPerMatchedGroup(localCol));
        assertEquals(0L, LogicalJoinReferenceRegistry.countMatchingReferenceValues(localCol,
                new Object[]{"STALE_A", "STALE_B"}));
        assertEquals(2L, LogicalJoinReferenceRegistry.countMatchingReferenceValues(localCol,
                new Object[]{"CHILD_A", "CHILD_B"}));
    }

    @Test
    void sharedLocalColumnPrefersGenericRuleTableOverPhysicalReferenceRuleTable() throws Exception {
        String localTable = "public.fact";
        String localCol = localTable + ".q10_mgt_org_code";
        String physicalRefCol = "public.org_parent.mgt_org_code";
        String genericRefCol = "public.org_child.child_mgt_org_code";

        TableManager.getInstance().addSchema("public.org_parent", new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(physicalRefCol)), 100L));
        TableManager.getInstance().addSchema("public.org_child", new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(genericRefCol)), 5L));
        ruc.db.schema.Table localSchema = new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(localCol)), 20L);
        localSchema.setForeignKeys(Map.of(localCol, physicalRefCol));
        TableManager.getInstance().addSchema(localTable, localSchema);

        addVarcharColumnWithValues(localCol, new Object[20]);

        ConstraintChain chain = new ConstraintChain(localTable);
        chain.setChainIndex(0);
        ConstraintChainFkJoinNode generic = new ConstraintChainFkJoinNode(
                localCol, genericRefCol, 0, new BigDecimal("0.50"));
        generic.setType(ConstraintNodeJoinType.SEMI_JOIN);
        generic.setJoinModel(JoinConstraintJoinModel.GENERIC);
        generic.setTargetJoinRows(10L);
        generic.setLeftInputRows(20L);
        generic.setLocalInputRows(20L);
        generic.setRightInputRows(5L);
        generic.setRefInputRows(5L);
        chain.addNode(generic);

        ConstraintChainFkJoinNode physical = new ConstraintChainFkJoinNode(
                localCol, physicalRefCol, 1, BigDecimal.ONE);
        physical.setType(ConstraintNodeJoinType.OUTER_JOIN);
        chain.addNode(physical);

        LogicalJoinReferenceRegistry.register(localCol, genericRefCol, 0, null);
        RuleTableManager.getInstance().addRuleTable(
                physicalRefCol,
                Map.of(new JoinStatus(new boolean[]{true, true}), 100L),
                1L);
        RuleTableManager.getInstance().addRuleTable(
                genericRefCol,
                Map.of(new JoinStatus(new boolean[]{true, true}), 5L),
                1L);

        FkGenerator generator = new FkGenerator(List.of(chain), List.of(localCol), 20L);

        Field ruleTablesField = FkGenerator.class.getDeclaredField("ruleTables");
        ruleTablesField.setAccessible(true);
        MergedRuleTable[] selectedRuleTables = (MergedRuleTable[]) ruleTablesField.get(generator);
        assertEquals(5L, selectedRuleTables[0].getStatusSize(new JoinStatus(new boolean[]{true, true})));
    }

    @Test
    void sharedLocalColumnPrefersGenericMetaEvenIfPhysicalJoinComesLater() throws Exception {
        String localTable = "public.fact";
        String localCol = localTable + ".q10_mgt_org_code";
        String physicalRefCol = "public.org_parent.mgt_org_code";
        String genericRefCol = "public.org_child.child_mgt_org_code";

        TableManager.getInstance().addSchema("public.org_parent", new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(physicalRefCol)), 10L));
        TableManager.getInstance().addSchema("public.org_child", new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(genericRefCol)), 5L));
        ruc.db.schema.Table localSchema = new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(localCol)), 10L);
        localSchema.setForeignKeys(Map.of(localCol, physicalRefCol));
        TableManager.getInstance().addSchema(localTable, localSchema);

        ConstraintChain chain = new ConstraintChain(localTable);
        chain.setChainIndex(0);
        ConstraintChainFkJoinNode generic = new ConstraintChainFkJoinNode(
                localCol, genericRefCol, 0, new BigDecimal("0.50"));
        generic.setJoinModel(JoinConstraintJoinModel.GENERIC);
        generic.setType(ConstraintNodeJoinType.SEMI_JOIN);
        chain.addNode(generic);

        ConstraintChainFkJoinNode physical = new ConstraintChainFkJoinNode(
                localCol, physicalRefCol, 1, BigDecimal.ONE);
        physical.setType(ConstraintNodeJoinType.OUTER_JOIN);
        chain.addNode(physical);

        LogicalJoinReferenceRegistry.register(localCol, genericRefCol, 0, null);
        RuleTableManager.getInstance().addRuleTable(
                genericRefCol,
                Map.of(new JoinStatus(new boolean[]{true, true}), 5L),
                1L);

        FkGenerator generator = new FkGenerator(List.of(chain), List.of(localCol), 10L);

        Field metaField = FkGenerator.class.getDeclaredField("fkJoinMetaByColIndex");
        metaField.setAccessible(true);
        ConstraintChainFkJoinNode[] meta = (ConstraintChainFkJoinNode[]) metaField.get(generator);

        assertEquals(JoinConstraintJoinModel.GENERIC, meta[0].getJoinModel());
        assertEquals(ConstraintNodeJoinType.SEMI_JOIN, meta[0].getType());
    }

    @Test
    void sharedLocalPhysicalFkExportsMaterializedGenericWitnessDomain() throws Exception {
        String localTable = "public.fact";
        String localCol = localTable + ".q10_mgt_org_code";
        String physicalRefCol = "public.org_parent.mgt_org_code";
        String genericRefCol = "public.org_child.child_mgt_org_code";

        TableManager.getInstance().addSchema("public.org_parent",
                new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(physicalRefCol)), 100L));
        TableManager.getInstance().addSchema("public.org_child",
                new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(genericRefCol)), 4L));
        ruc.db.schema.Table localSchema =
                new ruc.db.schema.Table(new java.util.ArrayList<>(List.of(localCol)), 4L);
        localSchema.setForeignKeys(Map.of(localCol, physicalRefCol));
        TableManager.getInstance().addSchema(localTable, localSchema);

        addVarcharColumnWithValues(localCol, new Object[]{"PARENT_1", "PARENT_2", "PARENT_3", "PARENT_4"});

        ConstraintChain chain = new ConstraintChain(localTable);
        chain.setChainIndex(0);
        ConstraintChainFkJoinNode generic = new ConstraintChainFkJoinNode(
                localCol, genericRefCol, 0, new BigDecimal("0.50"));
        generic.setJoinModel(JoinConstraintJoinModel.GENERIC);
        generic.setType(ConstraintNodeJoinType.SEMI_JOIN);
        generic.setTargetJoinRows(4L);
        generic.setLeftInputRows(4L);
        generic.setLocalInputRows(4L);
        generic.setRightInputRows(2L);
        generic.setRefInputRows(2L);
        chain.addNode(generic);

        ConstraintChainFkJoinNode physical = new ConstraintChainFkJoinNode(
                localCol, physicalRefCol, 1, BigDecimal.ONE);
        physical.setType(ConstraintNodeJoinType.OUTER_JOIN);
        chain.addNode(physical);

        LogicalJoinReferenceRegistry.register(localCol, genericRefCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                genericRefCol,
                new Object[]{"CHILD_A", "CHILD_B", "CHILD_A", "CHILD_B"},
                1L,
                Map.of(localCol, Set.of("CHILD_A", "CHILD_B")),
                Map.of(localCol, Map.of("CHILD_A", 2L, "CHILD_B", 2L)));

        Map<String, long[]> fkCol2Values = new LinkedHashMap<>();
        fkCol2Values.put(localCol, new long[]{1L, 2L, 3L, 4L});

        Method materializeMethod = DataGenerator.class.getDeclaredMethod(
                "materializeGenericLocalJoinColumns", String.class, List.class, Map.class);
        materializeMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> materialized = (Set<String>) materializeMethod.invoke(
                new DataGenerator(), localTable, List.of(chain), fkCol2Values);

        Method fillMissingMethod = DataGenerator.class.getDeclaredMethod(
                "generateFksNoConstraints", Map.class, java.util.SortedMap.class, int.class, Set.class);
        fillMissingMethod.setAccessible(true);
        fillMissingMethod.invoke(new DataGenerator(), fkCol2Values, localSchema.getFk2PkTableSize(), 4, materialized);

        assertTrue(materialized.contains(localCol));
        assertTrue(!fkCol2Values.containsKey(localCol),
                "materialized generic local physical FK should not be backfilled by plain physical FK generation");

        Object[] actual = ColumnManager.getInstance().getColumn(localCol).getColumnActualData();
        Set<Object> actualDomain = new HashSet<>(Arrays.asList(actual));
        assertEquals(Set.of("CHILD_A", "CHILD_B"), actualDomain);

        Set<String> exportedDomain = new HashSet<>();
        for (int i = 0; i < actual.length; i++) {
            exportedDomain.add(DataGenerator.formatMaterializedLocalColumnOutput(localCol, i));
        }
        assertEquals(Set.of("CHILD_A", "CHILD_B"), exportedDomain);
    }

    @Test
    void controlledMultiplicityRemapPrefersSmallestSufficientExtraBeforeHotKeys() {
        String localCol = "public.fact.q14_pref_inst_id";
        String refCol = "public.aamfi.q14_pref_inst_id";

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"HOT", "HOT", "HOT", "MID", "MID", "U1", "U2"},
                1L,
                Map.of(localCol, Set.of("HOT", "MID", "U1", "U2")),
                Map.of(localCol, Map.of("HOT", 3L, "MID", 2L, "U1", 1L, "U2", 1L)));

        long remapped = LogicalJoinReferenceRegistry.remapToControlledMultiplicityReferenceKey(localCol, 1L, 0, 2L, 2L);

        assertEquals(2L, LogicalJoinReferenceRegistry.referenceMultiplicity(localCol, remapped));
    }

    @Test
    void unresolvedPositiveLogicalKeysFallbackToMatchedReferenceValues() {
        String localCol = "public.fact.q14_fallback_inst_id";
        String refCol = "public.aamfi.q14_fallback_inst_id";

        LogicalJoinReferenceRegistry.setMaxRetainedValuesPerRefForTesting(2L);
        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"X0", "X1", "U1", "U2", "U3", "U4"},
                1L,
                Map.of(localCol, Set.of("U1", "U2", "U3", "U4")),
                Map.of(localCol, Map.of("U1", 1L, "U2", 1L, "U3", 1L, "U4", 1L)));

        long[] logicalKeys = {3L, 4L, 5L, 6L};
        Object[] actual = new Object[logicalKeys.length];
        long matchedOrdinal = 0L;
        for (int i = 0; i < logicalKeys.length; i++) {
            Object resolved = LogicalJoinReferenceRegistry.resolveReferenceValue(localCol, logicalKeys[i]);
            if (resolved != null) {
                actual[i] = resolved;
                matchedOrdinal++;
                continue;
            }
            Object fallback = LogicalJoinReferenceRegistry.fallbackMatchedReferenceValue(localCol, matchedOrdinal);
            actual[i] = fallback != null ? fallback : logicalKeys[i];
            matchedOrdinal++;
        }

        assertEquals(4L, LogicalJoinReferenceRegistry.countMatchingReferenceValues(localCol, actual));
        assertEquals(4L, LogicalJoinReferenceRegistry.countLeftJoinRowsWithReferenceMultiplicity(localCol, actual));
    }

    @Test
    void unresolvedPositiveLogicalKeysRespectSmallOuterJoinExtraBudget() {
        String localCol = "public.fact.q14_budget_inst_id";
        String refCol = "public.aamfi.q14_budget_inst_id";

        LogicalJoinReferenceRegistry.setMaxRetainedValuesPerRefForTesting(2L);
        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"X0", "X1", "U1", "U2", "U3", "D1", "D1"},
                1L,
                Map.of(localCol, Set.of("U1", "U2", "U3", "D1")),
                Map.of(localCol, Map.of("U1", 1L, "U2", 1L, "U3", 1L, "D1", 2L)));

        long[] logicalKeys = {3L, 4L, 5L, 6L};
        Object[] actual = new Object[logicalKeys.length];
        long matchedOrdinal = 0L;
        long usedExtraRows = 0L;
        long allowedExtraRows = 1L;
        long matchedRowsTarget = logicalKeys.length;
        for (int i = 0; i < logicalKeys.length; i++) {
            long remainingExtraRows = Math.max(0L, allowedExtraRows - usedExtraRows);
            long remainingMatchedRows = Math.max(1L, matchedRowsTarget - matchedOrdinal);
            Object fallback = LogicalJoinReferenceRegistry.fallbackMatchedReferenceValue(
                    localCol, matchedOrdinal, remainingExtraRows, remainingMatchedRows);
            actual[i] = fallback;
            usedExtraRows += Math.max(0L,
                    LogicalJoinReferenceRegistry.matchedValueMultiplicity(localCol, fallback) - 1L);
            matchedOrdinal++;
        }

        assertEquals(4L, LogicalJoinReferenceRegistry.countMatchingReferenceValues(localCol, actual));
        assertEquals(5L, LogicalJoinReferenceRegistry.countLeftJoinRowsWithReferenceMultiplicity(localCol, actual));
    }

    @Test
    void genericJoinUsesLogicalRuleTableToSatisfyJoinCardinality() {
        String localCol = "public.fact.mgt_org_code";
        String refCol = "public.org_child.child_mgt_org_code";

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, new BigDecimal("0.60"));
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setTargetJoinRows(60L);
        join.setLeftInputRows(100L);
        join.setRightInputRows(10L);
        factChain.addNode(join);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 10L);
        refHistogram.put(new JoinStatus(new boolean[]{false}), 10L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 100L);
        boolean[][] statusVector = new boolean[100][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);

        int keysInReferenceSet = 0;
        for (long key : generated[0]) {
            if (key >= 1L && key <= 10L) {
                keysInReferenceSet++;
            }
        }

        assertTrue(keysInReferenceSet >= 57 && keysInReferenceSet <= 63,
                "keysInReferenceSet=" + keysInReferenceSet);
    }

    @Test
    void genericSemiJoinTreatsExistsAsMembershipFilterNotHugeDistinctDemand() throws Exception {
        String localCol = "public.fact.q10_mgt_org_code";
        String refCol = "public.org_child.q10_child_mgt_org_code";

        addVarcharColumnWithValues(localCol, new Object[100]);

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode semi = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, new BigDecimal("0.60"));
        semi.setType(ConstraintNodeJoinType.SEMI_JOIN);
        semi.setJoinModel(JoinConstraintJoinModel.GENERIC);
        semi.setTargetJoinRows(60L);
        semi.setLeftInputRows(100L);
        semi.setLocalInputRows(100L);
        semi.setRightInputRows(2L);
        semi.setRefInputRows(2L);
        semi.setPkDistinctProbability(new BigDecimal("0.50"));
        factChain.addNode(semi);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"ORG_A", "ORG_B"},
                1L,
                Map.of(localCol, Set.of("ORG_A", "ORG_B")),
                Map.of(localCol, Map.of("ORG_A", 1L, "ORG_B", 1L)));

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 2L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 100L);
        boolean[][] statusVector = new boolean[100][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = assertDoesNotThrow(() -> generator.generateFK(statusVector));

        Object[] actual = new Object[generated[0].length];
        Set<Object> distinctValues = new HashSet<>();
        long matchedOrdinal = 0L;
        for (int i = 0; i < generated[0].length; i++) {
            Object resolved = LogicalJoinReferenceRegistry.resolveReferenceValue(localCol, generated[0][i]);
            if (resolved != null) {
                actual[i] = resolved;
                distinctValues.add(resolved);
                matchedOrdinal++;
                continue;
            }
            if (generated[0][i] >= 0L) {
                Object fallback = LogicalJoinReferenceRegistry.fallbackMatchedReferenceValue(localCol, matchedOrdinal);
                actual[i] = fallback != null ? fallback : generated[0][i];
                if (fallback != null) {
                    distinctValues.add(fallback);
                    matchedOrdinal++;
                }
                continue;
            }
            actual[i] = generated[0][i];
        }

        long survivedRows = 0L;
        for (boolean[] rowStatus : statusVector) {
            if (rowStatus[0]) {
                survivedRows++;
            }
        }

        long matchedRows = LogicalJoinReferenceRegistry.countMatchingReferenceValues(localCol, actual);
        assertTrue(survivedRows >= 59L && survivedRows <= 61L, "survivedRows=" + survivedRows);
        assertEquals(survivedRows, matchedRows);
        assertTrue(distinctValues.size() <= 2, "distinctValues=" + distinctValues);
    }

    @Test
    void genericOuterJoinDistinctUsesMatchedTargetInsteadOfInputRows() throws Exception {
        String localCol = "public.fact.outer_trml_addr";
        String refCol = "public.he.outer_tmnl_addr";

        addVarcharColumnWithValues(localCol, new Object[100]);

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, new BigDecimal("0.20"));
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setType(ConstraintNodeJoinType.OUTER_JOIN);
        join.setTargetJoinRows(20L);
        join.setLeftInputRows(100L);
        join.setRightInputRows(20L);
        join.setPkDistinctProbability(BigDecimal.ONE);
        factChain.addNode(join);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 20L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 100L);
        boolean[][] statusVector = new boolean[100][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);

        int keysInReferenceSet = 0;
        int syntheticAntiKeys = 0;
        for (long key : generated[0]) {
            if (key >= 1L && key <= 20L) {
                keysInReferenceSet++;
            }
            if (key < 0L) {
                syntheticAntiKeys++;
            }
        }

        assertTrue(keysInReferenceSet >= 18 && keysInReferenceSet <= 22,
                "keysInReferenceSet=" + keysInReferenceSet);
        assertTrue(syntheticAntiKeys >= 78 && syntheticAntiKeys <= 82,
                "syntheticAntiKeys=" + syntheticAntiKeys);
    }

    @Test
    void genericOuterJoinAllowsManyLocalRowsToShareSmallReferenceDomain() throws Exception {
        String localCol = "public.fact.q4_trml_addr_code";
        String refCol = "public.he.q4_tmnl_comm_addr";

        addVarcharColumnWithValues(localCol, new Object[100]);

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, BigDecimal.ONE);
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setType(ConstraintNodeJoinType.OUTER_JOIN);
        join.setTargetJoinRows(100L);
        join.setLeftInputRows(100L);
        join.setRightInputRows(2L);
        join.setPkDistinctProbability(BigDecimal.ONE);
        factChain.addNode(join);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 2L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 100L);
        boolean[][] statusVector = new boolean[20][1];
        for (int i = 0; i < 15; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);

        int keysInReferenceSet = 0;
        for (long key : generated[0]) {
            if (key >= 1L && key <= 2L) {
                keysInReferenceSet++;
            }
        }

        assertTrue(keysInReferenceSet >= 15,
                "keysInReferenceSet=" + keysInReferenceSet);
    }

    @Test
    void genericOuterJoinGenerationAvoidsHotReferenceValues() throws Exception {
        String localCol = "public.fact.hot_trml_addr_code";
        String refCol = "public.he.hot_tmnl_comm_addr";

        addVarcharColumnWithValues(localCol, new Object[10]);

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, BigDecimal.ONE);
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setType(ConstraintNodeJoinType.OUTER_JOIN);
        join.setTargetJoinRows(10L);
        join.setLeftInputRows(10L);
        join.setRightInputRows(4L);
        join.setPkDistinctProbability(BigDecimal.ONE);
        factChain.addNode(join);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"HOT", "HOT", "HOT", "COLD"},
                1L,
                Map.of(localCol, java.util.Set.of("HOT", "COLD")),
                Map.of(localCol, Map.of("HOT", 3L, "COLD", 1L)));

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 4L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 10L);
        boolean[][] statusVector = new boolean[10][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);
        Object[] actual = new Object[generated[0].length];
        for (int i = 0; i < generated[0].length; i++) {
            actual[i] = LogicalJoinReferenceRegistry.resolveReferenceValue(localCol, generated[0][i]);
        }

        assertEquals(10L, LogicalJoinReferenceRegistry.countInnerJoinRowsWithReferenceMultiplicity(localCol, actual));
    }

    @Test
    void highMatchGenericOuterJoinControlsSmallReferenceSideFanout() throws Exception {
        String localCol = "public.fact.q14_inst_id";
        String refCol = "public.aamfi.q14_inst_id";

        addVarcharColumnWithValues(localCol, new Object[10]);

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, BigDecimal.ONE);
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setType(ConstraintNodeJoinType.OUTER_JOIN);
        join.setTargetJoinRows(12L);
        join.setLeftInputRows(10L);
        join.setRightInputRows(6L);
        join.setLocalInputRows(10L);
        join.setRefInputRows(6L);
        join.setPkDistinctProbability(BigDecimal.ONE);
        factChain.addNode(join);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"HOT", "HOT", "HOT", "WARM", "WARM", "COLD"},
                1L,
                Map.of(localCol, Set.of("HOT", "WARM", "COLD")),
                Map.of(localCol, Map.of("HOT", 3L, "WARM", 2L, "COLD", 1L)));

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 6L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 10L);
        boolean[][] statusVector = new boolean[10][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);
        Object[] actual = new Object[generated[0].length];
        for (int i = 0; i < generated[0].length; i++) {
            actual[i] = LogicalJoinReferenceRegistry.resolveReferenceValue(localCol, generated[0][i]);
        }

        assertEquals(12L, LogicalJoinReferenceRegistry.countLeftJoinRowsWithReferenceMultiplicity(localCol, actual),
                "generated keys=" + java.util.Arrays.toString(generated[0]));
    }

    @Test
    void highMatchGenericOuterJoinSpreadsDuplicateBudgetAcrossDistinctReferenceValues() throws Exception {
        String localCol = "public.fact.q14_distinct_inst_id";
        String refCol = "public.aamfi.q14_distinct_inst_id";

        addVarcharColumnWithValues(localCol, new Object[10]);

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, BigDecimal.ONE);
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setType(ConstraintNodeJoinType.OUTER_JOIN);
        join.setTargetJoinRows(12L);
        join.setLeftInputRows(10L);
        join.setRightInputRows(12L);
        join.setLocalInputRows(10L);
        join.setRefInputRows(12L);
        join.setPkDistinctProbability(BigDecimal.ONE);
        factChain.addNode(join);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"U1", "U2", "U3", "U4", "U5", "U6", "U7", "U8", "D1", "D1", "D2", "D2"},
                1L,
                Map.of(localCol, Set.of("U1", "U2", "U3", "U4", "U5", "U6", "U7", "U8", "D1", "D2")),
                Map.of(localCol, Map.of(
                        "U1", 1L, "U2", 1L, "U3", 1L, "U4", 1L,
                        "U5", 1L, "U6", 1L, "U7", 1L, "U8", 1L,
                        "D1", 2L, "D2", 2L)));

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 12L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 10L);
        boolean[][] statusVector = new boolean[10][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);
        Object[] actual = new Object[generated[0].length];
        Set<Object> distinctActual = new HashSet<>();
        for (int i = 0; i < generated[0].length; i++) {
            actual[i] = LogicalJoinReferenceRegistry.resolveReferenceValue(localCol, generated[0][i]);
            distinctActual.add(actual[i]);
        }

        assertEquals(12L, LogicalJoinReferenceRegistry.countLeftJoinRowsWithReferenceMultiplicity(localCol, actual),
                "generated keys=" + Arrays.toString(generated[0]));
        assertEquals(10, distinctActual.size(),
                "resolved values should spread across all available low-fanout reference values: "
                        + Arrays.toString(actual));
    }

    @Test
    void genericJoinWithAggregateGroupKeyPreservesDistinctFkKeys() throws Exception {
        String localCol = "public.fact.meter_id";
        String refCol = "public.dim.dev_id";

        addVarcharColumnWithValues(localCol, new Object[4]);

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, BigDecimal.ONE);
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setType(ConstraintNodeJoinType.OUTER_JOIN);
        join.setTargetJoinRows(4L);
        join.setLeftInputRows(4L);
        join.setRightInputRows(4L);
        join.setPkDistinctProbability(BigDecimal.ONE);
        factChain.addNode(join);
        factChain.addNode(new ConstraintChainAggregateNode(List.of(localCol), BigDecimal.ONE, 4L, 4L));

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);
        LogicalJoinReferenceRegistry.rememberReferenceValues(
                refCol,
                new Object[]{"HOT", "HOT", "HOT", "COLD"},
                1L,
                Map.of(localCol, Set.of("HOT", "COLD")),
                Map.of(localCol, Map.of("HOT", 3L, "COLD", 1L)));

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 4L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 4L);
        boolean[][] statusVector = new boolean[4][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);

        Set<Long> distinctKeys = new HashSet<>();
        for (long key : generated[0]) {
            distinctKeys.add(key);
        }
        assertEquals(4, distinctKeys.size(), "generated keys=" + java.util.Arrays.toString(generated[0]));
    }

    @Test
    void genericJoinAddsSyntheticFalseStatusWhenReferenceOnlyHasMatches() throws Exception {
        String localCol = "public.fact.trml_addr_code";
        String refCol = "public.he.tmnl_comm_addr";

        ConstraintChain factChain = new ConstraintChain("public.fact");
        factChain.setChainIndex(0);
        ConstraintChainFkJoinNode join = new ConstraintChainFkJoinNode(
                localCol, refCol, 0, new BigDecimal("0.60"));
        join.setJoinModel(JoinConstraintJoinModel.GENERIC);
        join.setTargetJoinRows(60L);
        join.setLeftInputRows(100L);
        join.setRightInputRows(10L);
        factChain.addNode(join);

        LogicalJoinReferenceRegistry.register(localCol, refCol, 0, null);

        Map<JoinStatus, Long> refHistogram = new LinkedHashMap<>();
        refHistogram.put(new JoinStatus(new boolean[]{true}), 10L);
        RuleTableManager.getInstance().addRuleTable(refCol, refHistogram, 1L);

        FkGenerator generator = new FkGenerator(List.of(factChain), List.of(localCol), 100L);
        boolean[][] statusVector = new boolean[100][1];
        for (int i = 0; i < statusVector.length; i++) {
            statusVector[i][0] = true;
        }

        long[][] generated = generator.generateFK(statusVector);

        int keysInReferenceSet = 0;
        int syntheticAntiKeys = 0;
        for (long key : generated[0]) {
            if (key >= 1L && key <= 10L) {
                keysInReferenceSet++;
            }
            if (key < 0L) {
                syntheticAntiKeys++;
            }
        }

        assertTrue(keysInReferenceSet >= 57 && keysInReferenceSet <= 63,
                "keysInReferenceSet=" + keysInReferenceSet);
        assertTrue(syntheticAntiKeys >= 37 && syntheticAntiKeys <= 43,
                "syntheticAntiKeys=" + syntheticAntiKeys);
    }

    @SuppressWarnings("unchecked")
    private static void clearColumnManager() {
        try {
            Field columns = ColumnManager.class.getDeclaredField("columns");
            columns.setAccessible(true);
            ((Map<String, Column>) columns.get(ColumnManager.getInstance())).clear();
            Field attributeColumns = ColumnManager.class.getDeclaredField("attributeColumns");
            attributeColumns.setAccessible(true);
            ((List<Column>) attributeColumns.get(ColumnManager.getInstance())).clear();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
