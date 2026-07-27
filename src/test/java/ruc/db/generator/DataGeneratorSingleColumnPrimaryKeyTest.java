package ruc.db.generator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.joininfo.JoinStatus;
import ruc.db.generator.joininfo.MergedRuleTable;
import ruc.db.generator.joininfo.RuleTableManager;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataGeneratorSingleColumnPrimaryKeyTest {

    @AfterEach
    void tearDown() throws Exception {
        TableManager.getInstance().getSchemas().clear();
        RuleTableManager.getInstance().clear();
        LogicalJoinReferenceRegistry.clear();
        clearColumnManager();
    }

    @Test
    void physicalSingleColumnVarcharPrimaryKeyUsesTypeAwareUniqueValues() throws Exception {
        String tableName = "test_schema.pk_varchar_table";
        String pkColumnName = tableName + ".label_no";

        Table table = new Table(new ArrayList<>(List.of(pkColumnName)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(pkColumnName)));
        TableManager.getInstance().addSchema(tableName, table);

        Column pkColumn = new Column(ColumnType.VARCHAR);
        pkColumn.setOriginalType("character varying(16 char)");
        ColumnManager.getInstance().addColumn(pkColumnName, pkColumn);

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 34L);

        StringBuilder[] rows = invokeGeneratePks(generator, 4, tableName);

        Set<String> values = new HashSet<>();
        for (StringBuilder row : rows) {
            String text = row.toString();
            assertEquals('|', text.charAt(text.length() - 1));
            values.add(text.substring(0, text.length() - 1));
        }
        assertEquals(Set.of("Y", "Z", "10", "11"), values);
    }

    @Test
    void physicalSingleColumnPrimaryKeyRegistersDefaultRuleTableForDownstreamFk() throws Exception {
        String tableName = "sg_mis.elec_meter";
        String pkColumnName = tableName + ".dev_id";

        Table table = new Table(new ArrayList<>(List.of(pkColumnName)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(pkColumnName)));
        TableManager.getInstance().addSchema(tableName, table);

        Column pkColumn = varcharColumn("varchar(32)");
        ColumnManager.getInstance().addColumn(pkColumnName, pkColumn);

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 10L);

        invokeGeneratePks(generator, 5, tableName);

        MergedRuleTable ruleTable = RuleTableManager.getInstance().getRuleTable(pkColumnName, new int[]{0});
        assertEquals(5L, ruleTable.getStatusSize(new JoinStatus(new boolean[]{true})));
    }

    @Test
    void physicalSingleColumnPrimaryKeyDefaultRuleTableUsesReferencedJoinTag() throws Exception {
        String tableName = "sg_mis.elec_meter";
        String pkColumnName = tableName + ".dev_id";

        Table table = new Table(new ArrayList<>(List.of(pkColumnName)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(pkColumnName)));
        TableManager.getInstance().addSchema(tableName, table);
        ColumnManager.getInstance().addColumn(pkColumnName, varcharColumn("varchar(32)"));

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 10L);
        setPrivateObject(generator, "referencedPhysicalPrimaryKeyTags", Map.of(pkColumnName, Set.of(1)));

        invokeGeneratePks(generator, 5, tableName);

        MergedRuleTable ruleTable = RuleTableManager.getInstance().getRuleTable(pkColumnName, new int[]{1});
        assertEquals(5L, ruleTable.getStatusSize(new JoinStatus(new boolean[]{true})));
    }

    @Test
    void physicalSingleColumnPrimaryKeyDefaultRuleTableCoversAllTagsForSameLocalColumn() throws Exception {
        String tableName = "sg_mis.elec_meter";
        String pkColumnName = tableName + ".dev_id";

        Table table = new Table(new ArrayList<>(List.of(pkColumnName)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(pkColumnName)));
        TableManager.getInstance().addSchema(tableName, table);
        ColumnManager.getInstance().addColumn(pkColumnName, varcharColumn("varchar(32)"));

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 10L);
        setPrivateObject(generator, "referencedPhysicalPrimaryKeyTags", Map.of(pkColumnName, Set.of(0, 1)));

        invokeGeneratePks(generator, 5, tableName);

        MergedRuleTable ruleTable = RuleTableManager.getInstance().getRuleTable(pkColumnName, new int[]{0, 1});
        assertEquals(5L, ruleTable.getStatusSize(new JoinStatus(new boolean[]{true, true})));
    }

    @Test
    void physicalReferencePkInheritsAllJoinTagsUsedByTheSameLocalColumn() {
        String refTableName = "sg_mis.elec_meter";
        String refPk = refTableName + ".dev_id";
        String localTableName = "sg_mis.meter_run";
        String localFk = localTableName + ".meter_id";
        String genericRef = "sgami_support.s_meter_label_result.dev_id";

        Table refTable = new Table(new ArrayList<>(List.of(refPk)), 100L);
        refTable.setPrimaryKeys(new ArrayList<>(List.of(refPk)));
        TableManager.getInstance().addSchema(refTableName, refTable);

        Table localTable = new Table(new ArrayList<>(List.of(localFk)), 100L);
        localTable.setForeignKeys(Map.of(localFk, refPk));
        TableManager.getInstance().addSchema(localTableName, localTable);

        ConstraintChain chain = new ConstraintChain(localTableName);
        chain.addNode(new ConstraintChainFkJoinNode(localFk, refPk, 0, BigDecimal.ONE));
        chain.addNode(new ConstraintChainFkJoinNode(localFk, genericRef, 1, BigDecimal.ONE));

        Map<String, Set<Integer>> tags = DataGenerator.collectReferencedPhysicalPrimaryKeyTags(Map.of("q11.sql", List.of(chain)));

        assertEquals(Set.of(0, 1), tags.get(refPk));
    }

    @Test
    void genericReferenceRuleTableUsesActualJoinTagPositionsForSharedLocalColumn() throws Exception {
        String refTableName = "sgami_support.s_meter_label_result";
        String refCol = refTableName + ".dev_id";
        String localFk = "sg_mis.meter_run.meter_id";

        Table refTable = new Table(new ArrayList<>(List.of(refCol)), 100L);
        TableManager.getInstance().addSchema(refTableName, refTable);
        Column refColumn = varcharColumn("varchar(32)");
        refColumn.setColumnActualData(new Object[]{"M1", "M2", "M3"});
        ColumnManager.getInstance().addColumn(refCol, refColumn);

        ConstraintChain refChain = new ConstraintChain(refTableName);
        ConstraintChain localChain = new ConstraintChain("sg_mis.meter_run");
        localChain.addNode(new ConstraintChainFkJoinNode(localFk, "sg_mis.elec_meter.dev_id", 0, BigDecimal.ONE));
        ConstraintChainFkJoinNode genericJoin = new ConstraintChainFkJoinNode(localFk, refCol, 1, BigDecimal.ONE);
        genericJoin.setJoinModel(JoinConstraintJoinModel.GENERIC);
        localChain.addNode(genericJoin);

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 0L);
        setPrivateObject(generator, "localJoinColumnTags", Map.of(localFk, Set.of(0, 1)));
        Method method = DataGenerator.class.getDeclaredMethod(
                "registerGenericReferenceRuleTables",
                String.class,
                List.class,
                boolean[][].class,
                int.class);
        method.setAccessible(true);

        LogicalJoinReferenceRegistry.register(localFk, refCol, 1, null);
        method.invoke(generator, refTableName, List.of(refChain, localChain),
                new boolean[][]{{true, true}, {false, true}, {true, false}},
                3);

        MergedRuleTable ruleTable = RuleTableManager.getInstance().getRuleTable(refCol, new int[]{0, 1});
        assertEquals(3L, ruleTable.getStatusSize(new JoinStatus(new boolean[]{true, true})));
    }

    @Test
    void genericReferenceMatchDomainUsesJoinTagInsteadOfReferenceListIndex() throws Exception {
        String refTableName = "sgami_support.s_meter_label_result";
        String refCol = refTableName + ".dev_id";
        String localFk = "sg_mis.meter_run.meter_id";

        LogicalJoinReferenceRegistry.register(localFk, refCol, 1, null);
        List<LogicalJoinReferenceRegistry.Reference> refs =
                LogicalJoinReferenceRegistry.getReferencesForTable(refTableName);

        Method method = DataGenerator.class.getDeclaredMethod(
                "buildGenericMatchValueSets",
                Object[].class,
                JoinStatus[].class,
                List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Set<String>> matchValues = (Map<String, Set<String>>) method.invoke(
                null,
                new Object[]{"A", "B", "C"},
                new JoinStatus[]{
                        new JoinStatus(new boolean[]{true, false}),
                        new JoinStatus(new boolean[]{false, true}),
                        new JoinStatus(new boolean[]{true, true})
                },
                refs);

        assertEquals(Set.of("B", "C"), matchValues.get(localFk));

        Method countsMethod = DataGenerator.class.getDeclaredMethod(
                "buildGenericMatchValueCounts",
                Object[].class,
                JoinStatus[].class,
                List.class);
        countsMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Long>> matchCounts = (Map<String, Map<String, Long>>) countsMethod.invoke(
                null,
                new Object[]{"A", "B", "C"},
                new JoinStatus[]{
                        new JoinStatus(new boolean[]{true, false}),
                        new JoinStatus(new boolean[]{false, true}),
                        new JoinStatus(new boolean[]{true, true})
                },
                refs);

        assertEquals(Map.of("B", 1L, "C", 1L), matchCounts.get(localFk));
    }

    @Test
    void singleColumnAggregateGroupKeyDistinctnessOverridesGeneratedColumnValues() throws Exception {
        String tableName = "sgami_arch.a_mgt_org_childs";
        String groupKey = tableName + ".child_mgt_org_code";

        Table table = new Table(new ArrayList<>(List.of(groupKey)), 5L);
        TableManager.getInstance().addSchema(tableName, table);
        Column groupColumn = varcharColumn("varchar(16)");
        groupColumn.setColumnActualData(new Object[]{"OLD", "OLD", "OLD", "OLD", "OLD"});
        ColumnManager.getInstance().addColumn(groupKey, groupColumn);

        ConstraintChain chain = new ConstraintChain(tableName);
        chain.setChainIndex(0);
        chain.addNode(new ConstraintChainAggregateNode(List.of(groupKey), BigDecimal.ONE, 5L, 4L));

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 0L);

        Method method = DataGenerator.class.getDeclaredMethod(
                "enforceSingleColumnAggregateGroupKeyDistinctness",
                String.class,
                List.class,
                boolean[][].class,
                int.class);
        method.setAccessible(true);
        method.invoke(generator, tableName, List.of(chain),
                new boolean[][]{{true}, {true}, {true}, {true}, {true}},
                5);

        Object[] actual = groupColumn.getColumnActualData();
        assertEquals(5, actual.length);
        assertEquals(4, java.util.Arrays.stream(actual).map(String::valueOf).collect(java.util.stream.Collectors.toSet()).size());
    }

    @Test
    void physicalForeignKeyOutputUsesReferencedPrimaryKeyFormatter() throws Exception {
        String refTableName = "sg_mis.elec_meter";
        String refPk = refTableName + ".dev_id";
        String localTableName = "sg_mis.meter_run";
        String localFk = localTableName + ".meter_id";

        Table refTable = new Table(new ArrayList<>(List.of(refPk)), 100L);
        refTable.setPrimaryKeys(new ArrayList<>(List.of(refPk)));
        TableManager.getInstance().addSchema(refTableName, refTable);
        ColumnManager.getInstance().addColumn(refPk, varcharColumn("varchar(32)"));

        Table localTable = new Table(new ArrayList<>(List.of(localFk)), 100L);
        localTable.setForeignKeys(Map.of(localFk, refPk));
        TableManager.getInstance().addSchema(localTableName, localTable);

        assertEquals("A", DataGenerator.formatForeignKeyOutput(localFk, 10L));
        assertEquals("\\N", DataGenerator.formatForeignKeyOutput(localFk, Long.MIN_VALUE));
    }

    @Test
    void physicalCompositePrimaryKeyUsesTypeAwareUniqueTupleValues() throws Exception {
        String tableName = "test_schema.pk_composite_table";
        String pkVarchar = tableName + ".org_code";
        String pkDate = tableName + ".biz_date";
        String payload = tableName + ".payload";

        Table table = new Table(new ArrayList<>(List.of(pkVarchar, pkDate, payload)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(pkVarchar, pkDate)));
        TableManager.getInstance().addSchema(tableName, table);

        Column varcharColumn = new Column(ColumnType.VARCHAR);
        varcharColumn.setOriginalType("varchar(16)");
        ColumnManager.getInstance().addColumn(pkVarchar, varcharColumn);
        Column dateColumn = new Column(ColumnType.DATE);
        dateColumn.setOriginalType("date");
        ColumnManager.getInstance().addColumn(pkDate, dateColumn);

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 0L);

        StringBuilder[] rows = invokeGeneratePks(generator, 4, tableName);

        Set<String> tuples = new HashSet<>();
        for (StringBuilder row : rows) {
            String text = row.toString();
            String[] fields = text.split("\\|", -1);
            assertEquals(3, fields.length);
            assertTrue(fields[0].length() <= 16);
            assertTrue(fields[1].matches("\\d{4}-\\d{2}-\\d{2}"));
            tuples.add(fields[0] + "|" + fields[1]);
        }
        assertEquals(4, tuples.size());
    }

    @Test
    void compositePrimaryKeyWithFilteredComponentRepairsDuplicateTupleUsingUnconstrainedComponent() throws Exception {
        String tableName = "sgami_arch.a_mgt_org_childs";
        String mgtOrgCode = tableName + ".mgt_org_code";
        String distLv = tableName + ".dist_lv";
        String childCode = tableName + ".child_mgt_org_code";

        Table table = new Table(new ArrayList<>(List.of(mgtOrgCode, distLv, childCode)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(mgtOrgCode, distLv)));
        TableManager.getInstance().addSchema(tableName, table);

        Column mgtColumn = varcharColumn("varchar(16)");
        mgtColumn.setColumnActualData(new Object[]{"51101", "51101", "51101"});
        Column distColumn = varcharColumn("varchar(16)");
        distColumn.setColumnActualData(new Object[]{"01", "01", "01"});
        ColumnManager.getInstance().addColumn(mgtOrgCode, mgtColumn);
        ColumnManager.getInstance().addColumn(distLv, distColumn);

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 0L);

        StringBuilder[] rows = invokeGeneratePks(generator, 3, tableName, Set.of(mgtOrgCode), Set.of(mgtOrgCode));

        Set<String> tuples = new HashSet<>();
        for (StringBuilder row : rows) {
            String[] fields = row.toString().split("\\|", -1);
            assertEquals("51101", fields[0]);
            tuples.add(fields[0] + "|" + fields[1]);
        }
        assertEquals(3, tuples.size());
    }

    @Test
    void compositePrimaryKeyWithAllCriticalDuplicateComponentsFailsClearly() throws Exception {
        String tableName = "sgami_arch.a_mgt_org_childs";
        String mgtOrgCode = tableName + ".mgt_org_code";
        String distLv = tableName + ".dist_lv";

        Table table = new Table(new ArrayList<>(List.of(mgtOrgCode, distLv)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(mgtOrgCode, distLv)));
        TableManager.getInstance().addSchema(tableName, table);

        Column mgtColumn = varcharColumn("varchar(16)");
        mgtColumn.setColumnActualData(new Object[]{"51101", "51101"});
        Column distColumn = varcharColumn("varchar(16)");
        distColumn.setColumnActualData(new Object[]{"01", "01"});
        ColumnManager.getInstance().addColumn(mgtOrgCode, mgtColumn);
        ColumnManager.getInstance().addColumn(distLv, distColumn);

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 0L);

        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> invokeGeneratePks(generator, 2, tableName, Set.of(mgtOrgCode, distLv), Set.of(mgtOrgCode, distLv)));
        assertTrue(e.getCause() instanceof IllegalStateException);
    }

    @Test
    void singleColumnPrimaryKeyWithFilteredDuplicateValuesFailsClearly() throws Exception {
        String tableName = "test_schema.filtered_single_pk";
        String pkColumnName = tableName + ".label_no";

        Table table = new Table(new ArrayList<>(List.of(pkColumnName)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(pkColumnName)));
        TableManager.getInstance().addSchema(tableName, table);

        Column pkColumn = varcharColumn("varchar(16)");
        pkColumn.setColumnActualData(new Object[]{"A", "A"});
        ColumnManager.getInstance().addColumn(pkColumnName, pkColumn);

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 0L);

        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> invokeGeneratePks(generator, 2, tableName, Set.of(pkColumnName), Set.of(pkColumnName)));
        assertTrue(e.getCause() instanceof IllegalStateException);
    }

    @Test
    void compositePrimaryKeyRuleTableJoinFailsUntilTupleCoordinatedGenerationExists() throws Exception {
        String tableName = "test_schema.composite_rule_table_pk";
        String pkA = tableName + ".pk_a";
        String pkB = tableName + ".pk_b";

        Table table = new Table(new ArrayList<>(List.of(pkA, pkB)), 100L);
        table.setPrimaryKeys(new ArrayList<>(List.of(pkA, pkB)));
        TableManager.getInstance().addSchema(tableName, table);
        ColumnManager.getInstance().addColumn(pkA, varcharColumn("varchar(16)"));
        ColumnManager.getInstance().addColumn(pkB, varcharColumn("varchar(16)"));

        DataGenerator generator = new DataGenerator();
        setPrivateLong(generator, "batchStart", 0L);

        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> invokeGeneratePks(generator, new boolean[2][1], new int[]{0}, tableName, Set.of(), Set.of()));
        assertTrue(e.getCause() instanceof UnsupportedOperationException);
        assertTrue(e.getCause().getMessage().contains("tuple-coordinated FK generation"));
    }

    @Test
    void planCriticalColumnsIncludeJoinAndAggregateGroupKeys() throws Exception {
        String tableName = "sgami_arch.a_mgt_org_childs";
        String localJoin = tableName + ".child_mgt_org_code";
        String refJoin = "sgami_support.s_meter_label_result.mgt_org_code";
        String groupKey = tableName + ".child_mgt_org_code";

        ConstraintChain chain = new ConstraintChain(tableName);
        chain.addNode(new ConstraintChainFkJoinNode(localJoin, refJoin, 0, BigDecimal.ONE));
        chain.addNode(new ConstraintChainAggregateNode(List.of(groupKey), BigDecimal.ONE, 1297L, 1296L));

        Method method = DataGenerator.class.getDeclaredMethod(
                "collectPlanCriticalCanonicalColumnsForTable",
                String.class,
                List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<String> columns = (Set<String>) method.invoke(null, tableName, List.of(chain));

        assertTrue(columns.contains(localJoin));
        assertTrue(columns.contains(groupKey));
        assertTrue(columns.stream().noneMatch(refJoin::equals));
    }

    private static StringBuilder[] invokeGeneratePks(DataGenerator generator, int range, String schemaName) throws Exception {
        return invokeGeneratePks(generator, range, schemaName, Set.of(), Set.of());
    }

    private static StringBuilder[] invokeGeneratePks(DataGenerator generator, int range, String schemaName,
                                                     Set<String> filterColumns,
                                                     Set<String> planCriticalColumns) throws Exception {
        return invokeGeneratePks(generator, new boolean[range][0], new int[0], schemaName,
                filterColumns, planCriticalColumns);
    }

    private static StringBuilder[] invokeGeneratePks(DataGenerator generator,
                                                     boolean[][] statusVector,
                                                     int[] pkStatusChainIndexes,
                                                     String schemaName,
                                                     Set<String> filterColumns,
                                                     Set<String> planCriticalColumns) throws Exception {
        Method method = DataGenerator.class.getDeclaredMethod(
                "generatePks",
                boolean[][].class,
                int[].class,
                String.class,
                String.class,
                Set.class,
                Set.class);
        method.setAccessible(true);
        return (StringBuilder[]) method.invoke(generator, statusVector, pkStatusChainIndexes, "", schemaName,
                filterColumns, planCriticalColumns);
    }

    private static void setPrivateLong(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static void setPrivateObject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Column varcharColumn(String originalType) {
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType(originalType);
        return column;
    }

    @SuppressWarnings("unchecked")
    private static void clearColumnManager() throws Exception {
        Field columns = ColumnManager.class.getDeclaredField("columns");
        columns.setAccessible(true);
        ((Map<String, Column>) columns.get(ColumnManager.getInstance())).clear();
        Field attributeColumns = ColumnManager.class.getDeclaredField("attributeColumns");
        attributeColumns.setAccessible(true);
        ((List<Column>) attributeColumns.get(ColumnManager.getInstance())).clear();
    }
}
