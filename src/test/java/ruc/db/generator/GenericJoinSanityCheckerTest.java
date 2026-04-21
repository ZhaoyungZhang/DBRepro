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
    void withinTolerance_eightPercentBand() {
        assertTrue(GenericJoinSanityChecker.withinJoinCardinalityTolerance(100L, 100L));
        assertTrue(GenericJoinSanityChecker.withinJoinCardinalityTolerance(93L, 100L));
        assertTrue(GenericJoinSanityChecker.withinJoinCardinalityTolerance(107L, 100L));
        assertFalse(GenericJoinSanityChecker.withinJoinCardinalityTolerance(80L, 100L));
    }
}
