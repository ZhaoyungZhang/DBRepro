package ruc.db.utils;

import ruc.db.generator.constraintchain.filter.Parameter;
import ruc.db.generator.constraintchain.filter.Parameter.ParameterType;
import ruc.db.generator.constraintchain.filter.operation.CompareOperator;
import ruc.db.generator.constraintchain.filter.operation.UniVarFilterOperation;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnCDF;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.schema.EnhancedColumnStatistics;
import ruc.db.utils.exception.TouchstoneException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证 LIKE 谓词在生成阶段从 {@link ColumnCDF.ParameterConstraint} / cdfConstraints 恢复字面量，
 * 避免误用 {@code dataIndex2ActualValue}（如 514040008）替代 cdf 目标值（如 514013202）。
 */
class CdfLikeParameterConstraintTest {

    private static String uniqueCol() {
        return "test.lk." + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_mgt";
    }

    /** 仅 MCV、无 histogram，走 ColumnCDF 简单累积路径，避免拉 Bucket 与外部目录 */
    private static EnhancedColumnStatistics varcharMcvOnly(String shortName, List<String> mcvs, List<Double> freqs) {
        EnhancedColumnStatistics s = new EnhancedColumnStatistics();
        s.setColumnName(shortName);
        s.setTableName("t");
        s.setDataType("character varying");
        s.setMostCommonValues(new ArrayList<>(mcvs));
        s.setMostCommonFrequencies(new ArrayList<>(freqs));
        s.setMcvCount(mcvs.size());
        s.setTableSize(1_000_000L);
        return s;
    }

    private static Map<String, Object> likeUpdateMcvEntry(String patternKey, String selectivity) {
        Map<String, Object> valuesMap = new HashMap<>();
        Map<String, Object> one = new HashMap<>();
        one.put("operator", "LIKE");
        one.put("selectivity", selectivity);
        one.put("constraintType", "UPDATE_MCV");
        valuesMap.put(patternKey, one);
        return valuesMap;
    }

    @Test
    void mergeLikeFromValuesMap_setsParameterConstraint() {
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(32)");
        column.init();
        column.buildCDFFromStatistics(
                varcharMcvOnly("mgt_code", List.of("514040008", "514013202"), List.of(0.4, 0.6)));

        CdfConstraintsApplier.mergeLikeParameterConstraintFromValuesMap(
                column, likeUpdateMcvEntry("514013202", "0.2260559971"));

        ColumnCDF.ParameterConstraint pc = column.getColumnCDF().getParameterConstraint();
        assertNotNull(pc);
        assertEquals(CompareOperator.LIKE, pc.getOperatorForValue("514013202"));
    }


    @Test
    void mergeEqFromValuesMap_setsParameterConstraint() {
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(16)");
        column.init();
        column.buildCDFFromStatistics(
                varcharMcvOnly("mgt_code", List.of("514030701", "51401"), List.of(0.97, 0.03)));

        Map<String, Object> valuesMap = new HashMap<>();
        Map<String, Object> one = new HashMap<>();
        one.put("operator", "EQ");
        one.put("selectivity", "0.0288489003");
        one.put("constraintType", "UPDATE_MCV");
        valuesMap.put("51401", one);

        CdfConstraintsApplier.mergeLikeParameterConstraintFromValuesMap(column, valuesMap);

        ColumnCDF.ParameterConstraint pc = column.getColumnCDF().getParameterConstraint();
        assertNotNull(pc);
        assertEquals(CompareOperator.EQ, pc.getOperatorForValue("51401"));
    }

    @Test
    void rebuildCdf_preservesParameterConstraint() {
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(32)");
        column.init();
        column.buildCDFFromStatistics(
                varcharMcvOnly("c1", List.of("514040008", "514013202"), List.of(0.5, 0.5)));
        column.getColumnCDF().setParameterConstraint(
                new ColumnCDF.ParameterConstraint("514013202", new BigDecimal("0.226"), CompareOperator.LIKE));

        column.buildCDFFromStatistics(
                varcharMcvOnly("c1", List.of("aaaa", "bbbb"), List.of(0.5, 0.5)));

        ColumnCDF.ParameterConstraint pc = column.getColumnCDF().getParameterConstraint();
        assertNotNull(pc);
        assertEquals(CompareOperator.LIKE, pc.getOperatorForValue("514013202"));
    }




    @Test
    void applyOldNeAddMcvConstraintUsesComplementFrequency() {
        EnhancedColumnStatistics stats = varcharMcvOnly("meter", List.of("123456"), List.of(0.99));
        Map<String, Object> valuesMap = new HashMap<>();
        Map<String, Object> one = new HashMap<>();
        one.put("operator", "NE");
        one.put("selectivity", "0.9999999752");
        one.put("constraintType", "ADD_MCV");
        valuesMap.put("915767", one);

        CdfConstraintsApplier.applyConstraintToStatistics(stats, valuesMap);

        int idx = stats.getMostCommonValues().indexOf("915767");
        assertEquals(1, idx);
        assertEquals(0.0000000248, stats.getMostCommonFrequencies().get(idx), 1e-12);
    }

    @Test
    void applyOldNeUpdateMcvConstraintUsesComplementFrequency() {
        EnhancedColumnStatistics stats = varcharMcvOnly("meter", List.of("915767", "123456"), List.of(0.01, 0.99));
        Map<String, Object> valuesMap = new HashMap<>();
        Map<String, Object> one = new HashMap<>();
        one.put("operator", "NE");
        one.put("selectivity", "0.9999999752");
        one.put("constraintType", "UPDATE_MCV");
        valuesMap.put("915767", one);

        CdfConstraintsApplier.applyConstraintToStatistics(stats, valuesMap);

        int idx = stats.getMostCommonValues().indexOf("915767");
        assertEquals(0.0000000248, stats.getMostCommonFrequencies().get(idx), 1e-8);
    }

    @Test
    void amendParameters_neFallsBackToEqStoredConstraint() throws TouchstoneException {
        String col = uniqueCol();
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(16)");
        column.init();
        column.buildCDFFromStatistics(
                varcharMcvOnly("meter", List.of("915767", "123456"), List.of(0.01, 0.99)));
        column.getColumnCDF().setParameterConstraint(
                new ColumnCDF.ParameterConstraint("915767", new BigDecimal("0.0000000248"), CompareOperator.EQ));
        column.getDataIndex2ActualValue().put(1L, "123456");

        ColumnManager.getInstance().addColumn(col, column);

        Parameter p = new Parameter();
        p.setId(0);
        p.setData(1L);
        p.setDataValue(null);

        UniVarFilterOperation op = new UniVarFilterOperation(col, CompareOperator.NE, List.of(p));
        op.amendParameters();

        assertEquals("915767", p.getDataValue());
    }

    @Test
    void amendParameters_eqPrefersParameterConstraintOverWrongDataIndexMapping() throws TouchstoneException {
        String col = uniqueCol();
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(16)");
        column.init();
        column.buildCDFFromStatistics(
                varcharMcvOnly("mgt", List.of("514030701", "51401"), List.of(0.97, 0.03)));
        column.getColumnCDF().setParameterConstraint(
                new ColumnCDF.ParameterConstraint("51401", new BigDecimal("0.0288489003"), CompareOperator.EQ));
        column.getDataIndex2ActualValue().put(1L, "514030701");

        ColumnManager.getInstance().addColumn(col, column);

        Parameter p = new Parameter();
        p.setId(1);
        p.setData(1L);
        p.setDataValue(null);

        UniVarFilterOperation op = new UniVarFilterOperation(col, CompareOperator.EQ, List.of(p));
        op.amendParameters();

        assertEquals("51401", p.getDataValue());
    }

    @Test
    void amendParameters_prefersParameterConstraintOverWrongDataIndexMapping() throws TouchstoneException {
        String col = uniqueCol();
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(32)");
        column.init();
        column.buildCDFFromStatistics(
                varcharMcvOnly("mgt", List.of("514040008", "514013202"), List.of(0.3, 0.7)));
        CdfConstraintsApplier.mergeLikeParameterConstraintFromValuesMap(
                column, likeUpdateMcvEntry("514013202", "0.2260559971"));
        column.getDataIndex2ActualValue().put(1L, "514040008");

        ColumnManager.getInstance().addColumn(col, column);

        Parameter p = new Parameter();
        p.setId(0);
        p.setData(1L);
        p.setDataValue(null);

        UniVarFilterOperation op = new UniVarFilterOperation(col, CompareOperator.LIKE, List.of(p));
        op.amendParameters();

        assertEquals("%514013202", p.getDataValue());
    }

    @Test
    void parameterSetDataValue_likeDoesNotDoublePrefixPercent() {
        Parameter p = new Parameter();
        p.setType(ParameterType.LIKE);
        p.setDataValue("%514013202");
        assertEquals("%514013202", p.getDataValue());
        p.setDataValue("514013202");
        assertEquals("%514013202", p.getDataValue());
    }

    @Test
    void amendParameters_likeFallsBackToEqStoredConstraint() throws TouchstoneException {
        String col = uniqueCol();
        Column column = new Column(ColumnType.VARCHAR);
        column.setOriginalType("varchar(32)");
        column.init();
        column.buildCDFFromStatistics(
                varcharMcvOnly("mgt", List.of("514040008", "514013202"), List.of(0.3, 0.7)));
        column.getColumnCDF().setParameterConstraint(
                new ColumnCDF.ParameterConstraint("514013202", new BigDecimal("0.226"), CompareOperator.EQ));
        column.getDataIndex2ActualValue().put(1L, "514040008");

        ColumnManager.getInstance().addColumn(col, column);

        Parameter p = new Parameter();
        p.setId(0);
        p.setData(1L);
        p.setDataValue("");

        UniVarFilterOperation op = new UniVarFilterOperation(col, CompareOperator.LIKE, List.of(p));
        op.amendParameters();

        assertEquals("%514013202", p.getDataValue());
    }
}
