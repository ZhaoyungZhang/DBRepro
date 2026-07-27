package ruc.db.analyzer;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ruc.db.schema.Table;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskConfiguratorDistributionColumnsTest {

    @Test
    void resolveDistributionColumns_filtersOutColumnsMissingFromTableMetadata() {
        String tableName = "sg_mis.elec_cons_cust";
        Table table = new Table(List.of(
                tableName + ".elec_cons_cust_id",
                tableName + ".cust_id",
                tableName + ".mgt_org_code"), 100L);
        Set<String> involvedCols = new LinkedHashSet<>(List.of(
                tableName + ".cust_id",
                tableName + ".members_id",
                "sg_mis.ecc.cust_id",
                tableName + ".mgt_org_code"));

        List<String> resolved = TaskConfigurator.resolveDistributionColumns(
                tableName, table, involvedCols, LoggerFactory.getLogger(getClass()));

        assertEquals(List.of(
                tableName + ".cust_id",
                tableName + ".mgt_org_code"), resolved);
    }

    @Test
    void resolveDistributionColumns_fallsBackToAllColumnsWhenNoInvolvedColumns() {
        String tableName = "sg_mis.elec_cons_cust";
        List<String> canonicalColumns = List.of(
                tableName + ".elec_cons_cust_id",
                tableName + ".cust_id",
                tableName + ".mgt_org_code");
        Table table = new Table(canonicalColumns, 100L);

        List<String> resolved = TaskConfigurator.resolveDistributionColumns(
                tableName, table, null, LoggerFactory.getLogger(getClass()));

        assertEquals(canonicalColumns, resolved);
    }

    @Test
    void resolveDistributionColumns_fallsBackToAllColumnsWhenAllInvolvedColumnsAreInvalid() {
        String tableName = "sg_mis.elec_cons_cust";
        List<String> canonicalColumns = List.of(
                tableName + ".elec_cons_cust_id",
                tableName + ".cust_id",
                tableName + ".mgt_org_code");
        Table table = new Table(canonicalColumns, 100L);
        Set<String> involvedCols = Set.of(
                tableName + ".members_id",
                "sg_mis.ecc.group_no");

        List<String> resolved = TaskConfigurator.resolveDistributionColumns(
                tableName, table, involvedCols, LoggerFactory.getLogger(getClass()));

        assertEquals(canonicalColumns, resolved);
    }
}
