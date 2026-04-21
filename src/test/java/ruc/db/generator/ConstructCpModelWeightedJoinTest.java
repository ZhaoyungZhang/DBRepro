package ruc.db.generator;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import ruc.db.generator.joininfo.JoinStatus;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructCpModelWeightedJoinTest {

    static {
        Loader.loadNativeLibraries();
    }

    @Test
    void addWeightedJoinCardinalityConstraint_withInitModel_solves() {
        ConstructCpModel cp = new ConstructCpModel();
        Map<JoinStatus, Long> hist = new LinkedHashMap<>();
        hist.put(new JoinStatus(new boolean[]{true}), 5L);
        hist.put(new JoinStatus(new boolean[]{false}), 5L);
        cp.initModel(hist, 2, 20);
        IntVar[][] v = cp.getStatusVars();
        cp.addWeightedJoinCardinalityConstraint(new IntVar[]{v[0][0], v[1][0]}, new long[]{1, 2}, 7);

        long[][] sol = cp.solve();
        assertTrue(sol != null && sol.length >= 2);
        assertEquals(5L, sol[0][0] + sol[0][1]);
        assertEquals(5L, sol[1][0] + sol[1][1]);
        long weighted = sol[0][0] + 2 * sol[1][0];
        long tol = Math.max(1L, (long) (7 * 0.08));
        assertTrue(weighted >= 7 - tol && weighted <= 7 + tol);
    }
}
