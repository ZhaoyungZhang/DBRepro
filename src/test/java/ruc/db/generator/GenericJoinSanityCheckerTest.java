package ruc.db.generator;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJoinSanityCheckerTest {

    @Test
    void countFks_skipsMinValue() {
        assertEquals(2L, GenericJoinSanityChecker.countFksInReferenceSet(
                new long[]{1L, Long.MIN_VALUE, 2L, 3L}, Set.of(1L, 2L)));
    }

    @Test
    void withinTolerance_halfPercentBand() {
        assertTrue(GenericJoinSanityChecker.withinJoinCardinalityTolerance(100L, 100L));
        assertTrue(GenericJoinSanityChecker.withinJoinCardinalityTolerance(1004L, 1000L));
        assertTrue(GenericJoinSanityChecker.withinJoinCardinalityTolerance(995L, 1000L));
        assertFalse(GenericJoinSanityChecker.withinJoinCardinalityTolerance(994L, 1000L));
    }
}
