package ruc.db.generator;

import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.utils.exception.TouchstoneException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericJoinAntiDomainTest {

    @Test
    void estimateOffsetQuarterSpanForIntegerRef() throws TouchstoneException {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String col = "gja.r" + u + ".rid";
        ColumnManager.getInstance().addColumn(col, new Column(ColumnType.INTEGER));
        ColumnManager.getInstance().getColumn(col).setRange(100);

        assertEquals(25L, GenericJoinAntiDomain.estimateOffsetForRefColumn(col));
    }

    @Test
    void maybeBias_appliesOnOneThirdOfRows() {
        ConstraintChainFkJoinNode fk = new ConstraintChainFkJoinNode("a.x", "b.y", 0, BigDecimal.ONE);
        fk.setJoinModel(JoinConstraintJoinModel.GENERIC);
        fk.setGenericAntiDomainOffset(100L);

        long b0 = GenericJoinAntiDomain.maybeBiasGenericSample(5L, 0, 0, fk);
        long b1 = GenericJoinAntiDomain.maybeBiasGenericSample(5L, 1, 0, fk);
        assertEquals(105L, b0);
        assertEquals(5L, b1);
    }

    @Test
    void minValueForGenericBecomesStableSyntheticAntiKey() {
        ConstraintChainFkJoinNode fk = new ConstraintChainFkJoinNode("a.x", "b.y", 0, BigDecimal.ONE);
        fk.setJoinModel(JoinConstraintJoinModel.GENERIC);

        assertEquals(-4L, GenericJoinAntiDomain.maybeBiasGenericSample(Long.MIN_VALUE, 3, 0, fk));
    }

    @Test
    void maybeBias_skippedForPkFk() {
        ConstraintChainFkJoinNode fk = new ConstraintChainFkJoinNode("a.x", "b.y", 0, BigDecimal.ONE);
        fk.setJoinModel(JoinConstraintJoinModel.PK_FK);
        fk.setGenericAntiDomainOffset(99L);
        assertEquals(7L, GenericJoinAntiDomain.maybeBiasGenericSample(7L, 0, 0, fk));
    }

    @Test
    void maybeBias_nullMeta() {
        assertEquals(3L, GenericJoinAntiDomain.maybeBiasGenericSample(3L, 0, 0, null));
    }
}
