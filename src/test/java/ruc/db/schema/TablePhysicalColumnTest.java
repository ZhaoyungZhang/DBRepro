package ruc.db.schema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablePhysicalColumnTest {

    @Test
    void syntheticPrimaryKeyIsKeptLogicalButExcludedFromPhysicalOutput() {
        String table = "sgami_arch.a_arch_meter_full_info";
        String meterId = table + ".meter_id";
        String tableId = table + ".tableid";
        String gridName = table + ".grid_mp_name";

        Table t = new Table(new ArrayList<>(List.of(meterId, gridName)), 10L);
        t.setPrimaryKeys(new ArrayList<>(List.of(meterId, tableId)));

        assertEquals(List.of(meterId, tableId), t.getCompletePrimaryKeysList());
        assertEquals(List.of(meterId), t.getPhysicalPrimaryKeysList());
        assertTrue(t.isPrimaryKeyColumn(tableId));
        assertFalse(t.isPhysicalColumn(tableId));
        assertEquals(List.of(gridName), t.getAttributeColumnNames());
        assertEquals(List.of(meterId, gridName), t.getPhysicalColumnOutputOrder());
    }
}
