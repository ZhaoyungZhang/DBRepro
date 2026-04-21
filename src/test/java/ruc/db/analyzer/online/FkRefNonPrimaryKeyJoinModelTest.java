package ruc.db.analyzer.online;

import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.utils.exception.TouchstoneException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 外键列指向参照表<strong>非主键</strong>列（如 tmnl_comm_addr → trml_addr_code）时：
 * joinModel 应为 {@link JoinConstraintJoinModel#GENERIC}，且 CP 基数目标应走 targetJoinRows 路径。
 */
class FkRefNonPrimaryKeyJoinModelTest {

    @Test
    void tmnlCommAddr_fkToTrmlAddrCode_resolvesToGeneric() throws TouchstoneException {
        String u = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String arch = "sgami_arch.a_arch_meter_full_info_" + u;
        String stat = "sgami_stat.a_arch_tmnl_comm_addr_he_" + u;
        String pk1 = arch + ".tableid";
        String pk2 = arch + ".meter_id";
        String trml = arch + ".trml_addr_code";
        String fkLocal = stat + ".tmnl_comm_addr";

        Table archTab = new Table(new ArrayList<>(Arrays.asList(pk1, pk2, trml)), 9_141_228L);
        archTab.setPrimaryKeys(new ArrayList<>(Arrays.asList(pk1, pk2)));
        TableManager.getInstance().addSchema(arch, archTab);

        Table statTab = new Table(new ArrayList<>(Arrays.asList(fkLocal, stat + ".payload")), 630_271L);
        TableManager.getInstance().addSchema(stat, statTab);
        TableManager.getInstance().setForeignKeys(stat, "tmnl_comm_addr", arch, "trml_addr_code");

        JoinConstraintJoinModel model = QueryAnalyzer.resolveJoinModelForFkJoinNode(stat, "tmnl_comm_addr", arch, "trml_addr_code");
        assertEquals(JoinConstraintJoinModel.GENERIC, model);
    }

    @Test
    void fkToSingleColumnPrimaryKey_resolvesToPkFk() throws TouchstoneException {
        String u = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String arch = "sgami_arch.a_meter_singlepk_" + u;
        String stat = "sgami_stat.a_child_" + u;
        String pk1 = arch + ".meter_id";
        String fkLocal = stat + ".ref_mid";

        Table archTab = new Table(new ArrayList<>(Arrays.asList(pk1, arch + ".extra")), 500_000L);
        archTab.setPrimaryKeys(new ArrayList<>(List.of(pk1)));
        TableManager.getInstance().addSchema(arch, archTab);

        Table statTab = new Table(new ArrayList<>(List.of(fkLocal)), 100L);
        TableManager.getInstance().addSchema(stat, statTab);
        TableManager.getInstance().setForeignKeys(stat, "ref_mid", arch, "meter_id");

        assertEquals(JoinConstraintJoinModel.PK_FK,
                QueryAnalyzer.resolveJoinModelForFkJoinNode(stat, "ref_mid", arch, "meter_id"));
    }

    @Test
    void genericJoinModel_computeJoinCardinalityTarget_clampsToTargetJoinRows() {
        ConstraintChainFkJoinNode n = new ConstraintChainFkJoinNode(
                "sgami_stat.a_arch_tmnl_comm_addr_he.tmnl_comm_addr",
                "sgami_arch.a_arch_meter_full_info.trml_addr_code",
                0,
                BigDecimal.ONE);
        n.setJoinModel(JoinConstraintJoinModel.GENERIC);
        n.setTargetJoinRows(630_271L);
        n.setLeftInputRows(9_141_228L);
        n.setRightInputRows(630_271L);
        long filterSize = 1_000_000L;
        assertEquals(630_271L, n.computeJoinCardinalityTargetForCp(filterSize));
    }
}
