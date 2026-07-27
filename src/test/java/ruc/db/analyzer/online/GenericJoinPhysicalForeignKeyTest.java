package ruc.db.analyzer.online;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.utils.exception.TouchstoneException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJoinPhysicalForeignKeyTest {

    @BeforeEach
    void clearSchemas() {
        TableManager.getInstance().getSchemas().clear();
    }

    @Test
    void genericJoinDoesNotRegisterPhysicalForeignKeyForTopologicalOrder() throws TouchstoneException {
        String u = suffix();
        String meter = "sgami_arch.a_arch_meter_full_info_" + u;
        String child = "sgami_arch.a_mgt_org_childs_" + u;

        addTable(meter, List.of("id", "mgt_org_code"), List.of("id"));
        addTable(child, List.of("id", "child_mgt_org_code"), List.of("id"));

        JoinConstraintJoinModel childToMeter = QueryAnalyzer.resolveJoinModelForFkJoinNode(
                child, "child_mgt_org_code", meter, "mgt_org_code");
        QueryAnalyzer.registerJoinReferenceForGeneration(
                child, "child_mgt_org_code", meter, "mgt_org_code", childToMeter);

        JoinConstraintJoinModel meterToChild = QueryAnalyzer.resolveJoinModelForFkJoinNode(
                meter, "mgt_org_code", child, "child_mgt_org_code");
        QueryAnalyzer.registerJoinReferenceForGeneration(
                meter, "mgt_org_code", child, "child_mgt_org_code", meterToChild);

        TableManager.getInstance().adjustFks();

        assertFalse(TableManager.getInstance().getSchema(child).getForeignKeys()
                .containsKey(child + ".child_mgt_org_code"));
        assertFalse(TableManager.getInstance().getSchema(meter).getForeignKeys()
                .containsKey(meter + ".mgt_org_code"));
        assertDoesNotThrow(() -> TableManager.getInstance().createTopologicalOrder());
    }

    @Test
    void pkFkJoinStillRegistersPhysicalForeignKeyForTopologicalOrder() throws TouchstoneException {
        String u = suffix();
        String parent = "public.parent_" + u;
        String child = "public.child_" + u;

        addTable(parent, List.of("id", "payload"), List.of("id"));
        addTable(child, List.of("id", "parent_id"), List.of("id"));

        JoinConstraintJoinModel model = QueryAnalyzer.resolveJoinModelForFkJoinNode(
                child, "parent_id", parent, "id");
        QueryAnalyzer.registerJoinReferenceForGeneration(child, "parent_id", parent, "id", model);
        TableManager.getInstance().adjustFks();

        assertTrue(TableManager.getInstance().getSchema(child).getForeignKeys()
                .containsKey(child + ".parent_id"));
        List<String> order = TableManager.getInstance().createTopologicalOrder();
        assertTrue(order.indexOf(parent) >= 0 && order.indexOf(child) >= 0);
        assertTrue(order.indexOf(parent) < order.indexOf(child));
    }

    private static void addTable(String tableName, List<String> shortColumns, List<String> shortPrimaryKeys) {
        List<String> columns = new ArrayList<>();
        for (String column : shortColumns) {
            columns.add(tableName + "." + column);
        }
        List<String> primaryKeys = new ArrayList<>();
        for (String pk : shortPrimaryKeys) {
            primaryKeys.add(tableName + "." + pk);
        }
        Table table = new Table(columns, 100L);
        table.setPrimaryKeys(primaryKeys);
        TableManager.getInstance().addSchema(tableName, table);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
