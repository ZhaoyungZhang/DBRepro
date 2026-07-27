package ruc.db.rsgen;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DDLSQLGeneratorMirageLayoutTest {

    @Test
    @SuppressWarnings("unchecked")
    void mirageGenerateColumnOrderSkipsSyntheticPrimaryKey() throws Exception {
        String old = System.getProperty(DDLSQLGenerator.IMPORT_DATA_LAYOUT_PROPERTY);
        System.setProperty(DDLSQLGenerator.IMPORT_DATA_LAYOUT_PROPERTY, "mirage_generate");
        try {
            String table = "sgami_arch.a_arch_meter_full_info";
            Map<String, Object> tableInfo = new HashMap<>();
            tableInfo.put("primaryKeys", List.of(table + ".meter_id", table + ".tableid"));
            tableInfo.put("foreignKeys", Map.of());
            tableInfo.put("canonicalColumnNames", List.of(
                    table + ".meter_id",
                    table + ".grid_mp_name",
                    table + ".inst_id"));
            Map<String, Object> schemaData = Map.of(table, tableInfo);

            DDLSQLGenerator generator = new DDLSQLGenerator();
            Method m = DDLSQLGenerator.class.getDeclaredMethod(
                    "mirageGenerateColumnShortNamesOrder", String.class, Map.class);
            m.setAccessible(true);

            List<String> order = (List<String>) m.invoke(generator, table, schemaData);
            assertEquals(List.of("meter_id", "grid_mp_name", "inst_id"), order);
        } finally {
            if (old == null) {
                System.clearProperty(DDLSQLGenerator.IMPORT_DATA_LAYOUT_PROPERTY);
            } else {
                System.setProperty(DDLSQLGenerator.IMPORT_DATA_LAYOUT_PROPERTY, old);
            }
        }
    }
}
