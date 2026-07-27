package ruc.db.generator.constraintchain.agg;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregateValueModelTest {

    @Test
    void countStarBySingleGroupKeyTracksDuplicateDegree() {
        Map<String, Long> counts = AggregateValueModel.countByGroup(new Object[]{"A", "A", "B", null});

        assertEquals(Map.of("A", 2L, "B", 1L), counts);
    }

    @Test
    void sumCoalescedCountModelsQ11LeftJoinAggregateSemantics() {
        Map<String, Long> mrCountByMeter = Map.of("M1", 3L, "M2", 1L);
        Object[] mlrDevIds = {"M1", "MISSING", "M2", null};

        long total = AggregateValueModel.sumCoalescedCountForLeftJoin(mlrDevIds, mrCountByMeter, 1L);

        assertEquals(6L, total);
    }

    @Test
    void averageCountPerGroupUsesExplicitAggregateRows() {
        ConstraintChainAggregateNode node = new ConstraintChainAggregateNode(
                java.util.List.of("sg_mis.meter_run.meter_id"),
                BigDecimal.ONE,
                18_435_547L,
                18_427_403L);

        assertEquals(new BigDecimal("1.000442"),
                node.computeAverageCountPerGroup(6));
    }
}
