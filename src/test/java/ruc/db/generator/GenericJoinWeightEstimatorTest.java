package ruc.db.generator;

import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.utils.exception.TouchstoneException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJoinWeightEstimatorTest {

    @Test
    void ndvCapsBucketCountAndWeightsAreOnes() throws TouchstoneException {
        String u = UUID.randomUUID().toString().substring(0, 8);
        String col = "gjw.t" + u + ".c1";
        ColumnManager.getInstance().addColumn(col, new Column(ColumnType.INTEGER));
        ColumnManager.getInstance().getColumn(col).setRange(20);

        long[] w = GenericJoinWeightEstimator.estimateUniformBucketWeights(col, 1000L, 8);
        assertEquals(8, w.length);
        for (long x : w) {
            assertEquals(1L, x);
        }
    }

    @Test
    void compositeLocalColumnFallsBackToSingleBucket() {
        long[] w = GenericJoinWeightEstimator.estimateUniformBucketWeights("a.b.c,d", 100L, 64);
        assertEquals(1, w.length);
        assertEquals(1L, w[0]);
    }

    @Test
    void unknownColumnUsesNdvOne() throws TouchstoneException {
        long[] w = GenericJoinWeightEstimator.estimateUniformBucketWeights("x.y.zcol", null, 64);
        assertTrue(w.length >= 1);
    }
}
