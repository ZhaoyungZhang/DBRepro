package ruc.db.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;

import ruc.db.LanguageManager;
import ruc.db.generator.joininfo.JoinStatus;

public class ConstructCpModel {

    private static final double DISTINCT_FK_SKEW = 2;
    private final Logger logger = LoggerFactory.getLogger(ConstructCpModel.class);
    private final CpModel model = new CpModel();
    private final CpSolver solver = new CpSolver();
    private IntVar[][] vars;
    private final Map<Integer, IntVar[][]> fkDistinctVars = new HashMap<>();
    private final List<IntVar> involvedVars = new LinkedList<>();

    private final Map<Integer, Map<Integer, Set<IntVar>>> fkSharePkVars = new HashMap<>();

    private final Map<Integer, List<List<IntVar>>> fkDistinctInvolvedVars = new HashMap<>();

    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    // 用于调试的statusHistogram
    private Map<JoinStatus, Long> debugStatusHistogram;

    // 用于检测重复约束：记录已添加的约束（变量集合的字符串表示 -> 约束值）
    private final Map<String, Long> existingConstraints = new HashMap<>();
    
    // 记录filter状态约束的总和，用于检测冲突
    private final Map<Integer, Long> filterStateTotals = new HashMap<>();

    static {
        Loader.loadNativeLibraries();
    }

    public long[][] solve() {
        logger.debug("num of vars is {}", model.model().getVariablesCount());
        logger.debug("num of constraints is {}", model.model().getConstraintsCount());
        solver.getParameters().setEnumerateAllSolutions(false);
        solver.getParameters().setNumWorkers(Runtime.getRuntime().availableProcessors());
        CpSolverStatus status = solver.solve(model);
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            logger.info(rb.getString("constructCpModelCostTime"), solver.wallTime() * 1000);
            int filterStatusCount = vars.length;
            int pkStatusCount = vars[0].length;
            long[][] rowCountForEachStatus = new long[filterStatusCount][pkStatusCount];
            for (int filterIndex = 0; filterIndex < filterStatusCount; filterIndex++) {
                for (int pkStatusIndex = 0; pkStatusIndex < pkStatusCount; pkStatusIndex++) {
                    rowCountForEachStatus[filterIndex][pkStatusIndex] = solver.value(vars[filterIndex][pkStatusIndex]);
                }
            }
            return rowCountForEachStatus;
        } else {
            // 提供更详细的错误信息
            String statusStr = status.toString();
            logger.error("CP求解失败 - 状态: {}, 变量数: {}, 约束数: {}, 求解时间: {}ms", 
                    statusStr, model.model().getVariablesCount(), 
                    model.model().getConstraintsCount(), solver.wallTime() * 1000);
            
            // 记录模型统计信息
            if (vars != null) {
                logger.error("模型维度 - filter状态数: {}, PK状态数: {}", vars.length, vars[0].length);
                logger.error("FK distinct约束数: {}", fkDistinctVars.size());
                logger.error("FK共享约束数: {}", fkDistinctInvolvedVars.size());
            }
            
            // 记录详细约束信息用于调试
            logger.error("详细约束分析:");
            try {
                // 记录每个约束的详细信息
                // logger.error("当前模型变量详情:");
                // for (int i = 0; i < vars.length; i++) {
                //     for (int j = 0; j < vars[i].length; j++) {
                //         IntVar var = vars[i][j];
                //         logger.error("  变量 vars[{}][{}]: 范围 [{}, {}], 名称: {}",
                //             i, j, var.getDomain().min(), var.getDomain().max(), var.getName());
                //     }
                // }

                // 尝试输出实际的约束方程
                logger.error("尝试输出CP模型约束方程:");
                // try {
                //     // 获取模型的约束信息
                //     var modelProto = model.model();
                //     for (int i = 0; i < modelProto.getConstraintsCount(); i++) {
                //         var constraint = modelProto.getConstraints(i);
                //         logger.error("  约束 {}: {}", i, constraint.toString());
                //     }
                // } catch (Exception e) {
                //     logger.error("无法输出约束方程详情: {}", e.getMessage());
                // }

                logger.error("当前模型约束数量: {}", model.model().getConstraintsCount());

                // 记录Filter状态约束（等式约束）
                // if (vars != null && debugStatusHistogram != null) {
                //     int filterIndex = 0;
                //     for (Map.Entry<JoinStatus, Long> entry : debugStatusHistogram.entrySet()) {
                //         IntVar[] rowVars = vars[filterIndex];
                //         String varNames = Arrays.stream(rowVars)
                //             .map(IntVar::getName)
                //             .collect(java.util.stream.Collectors.joining(" + "));
                //         long expectedRows = entry.getValue();
                //         logger.error("  Filter状态约束 {}: {} = {} (状态: {})",
                //             filterIndex, varNames, expectedRows,
                //             java.util.Arrays.toString(entry.getKey().status()));
                //         filterIndex++;
                //     }
                // }
                // 记录其他变量信息
                logger.error("FK distinct约束数: {}", fkDistinctVars.size());
                logger.error("FK共享约束数: {}", fkDistinctInvolvedVars.size());

            } catch (Exception e) {
                logger.error("记录约束详情时出错: {}", e.getMessage());
            }

            throw new UnsupportedOperationException(
                String.format("No solution found. Status: %s, Variables: %d, Constraints: %d",
                    statusStr, model.model().getVariablesCount(),
                    model.model().getConstraintsCount())
            );
        }
    }

    public FkRange[][] getDistinctResult(int fkColIndex) {
        var involvedFkVars = fkDistinctInvolvedVars.remove(fkColIndex);
        FkRange[][] fkRanges = new FkRange[vars.length][vars[0].length];
        for (List<IntVar> samePkStatusVars : involvedFkVars) {
            int start = 0;
            for (IntVar samePkStatusVar : samePkStatusVars) {
                int range = (int) solver.value(samePkStatusVar);
                String[] tags = samePkStatusVar.getName().split("-");
                int filterIndex = Integer.parseInt(tags[1]);
                int pkIndex = Integer.parseInt(tags[2]);
                fkRanges[filterIndex][pkIndex] = new FkRange(start, range);
                start += range;
            }
        }
        for (int filterIndex = 0; filterIndex < vars.length; filterIndex++) {
            for (int pkIndex = 0; pkIndex < vars[0].length; pkIndex++) {
                if (fkRanges[filterIndex][pkIndex] == null) {
                    fkRanges[filterIndex][pkIndex] = new FkRange(-1, -1);
                }
            }
        }
        return fkRanges;
    }

    public void initDistinctModel(int fkColIndex, long fkColCardinality, long fkTableSize) {
        IntVar[][] distinctVars = new IntVar[vars.length][vars[0].length];
        // todo 用fk的最大重复次数来替代
        fkTableSize = (long) (fkTableSize * DISTINCT_FK_SKEW);
        for (int filterIndex = 0; filterIndex < distinctVars.length; filterIndex++) {
            for (int pkIndex = 0; pkIndex < distinctVars[0].length; pkIndex++) {
                IntVar numVar = vars[filterIndex][pkIndex];
                String varName = fkColIndex + "-" + filterIndex + "-" + pkIndex;
                IntVar distinctVar = model.newIntVarFromDomain(numVar.getDomain(), varName);
                model.addLessOrEqual(distinctVar, numVar);
                // distinct的外键均匀分布与每个range中, i.e., x / tableSize <= d/fkColCardinality
                model.addLessOrEqual(LinearExpr.term(numVar, fkColCardinality), LinearExpr.term(distinctVar, fkTableSize));
                distinctVars[filterIndex][pkIndex] = distinctVar;
            }
        }
        fkSharePkVars.put(fkColIndex, new HashMap<>());
        fkDistinctVars.put(fkColIndex, distinctVars);
    }

    public void applyFKShareConstraint(int fkColIndex, Map<ArrayList<Integer>, Long> samePkStatusIndexes2Limitations) {
        var pkIndex2IntVar = fkSharePkVars.remove(fkColIndex);
        fkDistinctInvolvedVars.put(fkColIndex, new ArrayList<>());
        for (var pkIndexes2Limitation : samePkStatusIndexes2Limitations.entrySet()) {
            var samePkStatusIndexes = pkIndexes2Limitation.getKey();
            List<IntVar> sharedFk = new ArrayList<>();
            for (Integer samePkStatusIndex : samePkStatusIndexes) {
                if (pkIndex2IntVar.containsKey(samePkStatusIndex)) {
                    sharedFk.addAll(pkIndex2IntVar.get(samePkStatusIndex));
                }
            }
            if (!sharedFk.isEmpty()) {
                fkDistinctInvolvedVars.get(fkColIndex).add(sharedFk);
                model.addLessOrEqual(LinearExpr.sum(sharedFk.toArray(new IntVar[0])), pkIndexes2Limitation.getValue());
            }
        }
    }


    /**
     * 根据join info table计算不同status的填充数量
     *
     * @param filterHistogram  filter status的统计直方图
     * @param pkJointStatusNum 所有联合主键的数量
     * @param range            每个填充方案的的上界
     */
    /**
     * {@link #initModel} 之后可用：filter×PK 状态变量矩阵，供高级 JOIN 约束（如 GENERIC 加权基数）与测试使用。
     */
    public IntVar[][] getStatusVars() {
        return vars;
    }

    public void initModel(Map<JoinStatus, Long> filterHistogram, int pkJointStatusNum, int range) {
        vars = new IntVar[filterHistogram.size()][pkJointStatusNum];
        for (int i = 0; i < filterHistogram.size(); i++) {
            for (int j = 0; j < pkJointStatusNum; j++) {
                vars[i][j] = model.newIntVar(0, range, i + "-" + j);
            }
        }
        int i = 0;
        logger.info("初始化CP模型: filter状态数={}, PK状态数={}, range={}", 
            filterHistogram.size(), pkJointStatusNum, range);
        for (Map.Entry<JoinStatus, Long> status2Size : filterHistogram.entrySet()) {
            long totalSize = status2Size.getValue();
            logger.info("  添加filter状态约束: filterIndex={}, 状态={}, 行数={}", 
                i, java.util.Arrays.toString(status2Size.getKey().status()), totalSize);
            model.addEquality(LinearExpr.sum(vars[i]), totalSize);
            // 记录filter状态约束的总和，用于后续冲突检测
            filterStateTotals.put(i, totalSize);
            i++;
        }
    }

    public void addJoinDistinctValidVar(int fkColIndex, int filterIndex, int pkStatusIndex) {
        IntVar fkVar = fkDistinctVars.get(fkColIndex)[filterIndex][pkStatusIndex];
        involvedVars.add(fkVar);
        var pkIndex2IntVar = fkSharePkVars.get(fkColIndex);
        pkIndex2IntVar.computeIfAbsent(pkStatusIndex, v -> new HashSet<>());
        pkIndex2IntVar.get(pkStatusIndex).add(fkVar);
    }

    public void addJoinCardinalityValidVar(int filterIndex, int pkStatusIndex) {
        involvedVars.add(vars[filterIndex][pkStatusIndex]);
    }

    public void addJoinCardinalityConstraint(long eqJoinSize) {
        // 添加10%的容差以改善求解器可行性
        long tolerance = (long) (eqJoinSize * 0.05);
        addJoinCardinalityConstraint(eqJoinSize - tolerance, eqJoinSize + tolerance);
    }

    /**
     * 非 PK/FK 多谓词分解时的加权基数约束：{@code sum_i (weights[i] * vars[i])} 落在目标值 ±8% 容差内（与 {@link #addJoinCardinalityConstraint(long)} 一致）。
     *
     * @param vars      与 weights 等长的决策变量
     * @param weights   非负权重（例如各 PF 桶系数）
     * @param targetSum 计划给出的 JOIN 基数目标（如 JCC）
     */
    /**
     * 为 GENERIC 多桶 PF 等场景在已有 CP 模型上增加辅助整数变量（与 {@link #initModel} 后的约束共存）。
     *
     * @param prefix 变量名前缀（需唯一）
     * @param count  变量个数
     * @param upper  每个变量上界（含），将裁剪到适合 CP-SAT 的范围
     */
    public IntVar[] newAuxiliaryIntVars(String prefix, int count, long upper) {
        if (count <= 0) {
            return new IntVar[0];
        }
        int ub = (int) Math.min(Math.max(1L, upper), 10_000_000L);
        ub = Math.min(ub, Integer.MAX_VALUE / 4);
        IntVar[] r = new IntVar[count];
        for (int i = 0; i < count; i++) {
            r[i] = model.newIntVar(0, ub, prefix + i);
        }
        return r;
    }

    public void addWeightedJoinCardinalityConstraint(IntVar[] vars, long[] weights, long targetSum) {
        if (vars == null || weights == null || vars.length != weights.length) {
            throw new IllegalArgumentException("vars and weights must be non-null and of equal length");
        }
        if (vars.length == 0) {
            return;
        }
        LinearExpr expr = LinearExpr.weightedSum(vars, weights);
        long tolerance = (long) (targetSum * 0.08);
        if (tolerance < 1) {
            tolerance = 1;
        }
        model.addLinearConstraint(expr, targetSum - tolerance, targetSum + tolerance);
    }

    public void addJoinCardinalityConstraint(long lowerBound, long upperBound) {
        // int varCount = involvedVars.size();
        // if (varCount == 0) {
        //     logger.warn("尝试添加JOIN基数约束，但involvedVars为空，跳过");
        //     return;
        // }
        
        // 创建变量集合的唯一标识（排序后连接）
        String varKey = involvedVars.stream()
            .map(IntVar::getName)
            .sorted()
            .collect(java.util.stream.Collectors.joining(","));

        // 区分两类变量：
        // 1) 普通JOIN变量: "filterIndex-pkIndex"
        // 2) FK distinct变量: "fkColIndex-filterIndex-pkIndex"
        // 二者的“覆盖所有filter状态/与filter状态约束总和冲突”的判定逻辑完全不同，
        // 不能混用，否则会错误跳过/调整distinct约束，导致模型不稳定甚至INFEASIBLE。
        int minSeg = Integer.MAX_VALUE;
        int maxSeg = Integer.MIN_VALUE;
        for (IntVar v : involvedVars) {
            int seg = v.getName().split("-").length;
            minSeg = Math.min(minSeg, seg);
            maxSeg = Math.max(maxSeg, seg);
        }
        // boolean isFkDistinctConstraint = (minSeg == 3 && maxSeg == 3);
        // boolean isNormalJoinConstraint = (minSeg == 2 && maxSeg == 2);
        // if (!isFkDistinctConstraint && !isNormalJoinConstraint) {
        //     logger.warn("JOIN基数约束变量命名不一致（可能混合distinct/normal），跳过filter冲突检测逻辑以避免误判。变量={}",
        //         involvedVars.stream().map(IntVar::getName).collect(java.util.stream.Collectors.joining(", ")));
        // }
        
        // 仅对“普通JOIN变量”做 filter 状态约束冲突/冗余检测；
        // 对 distinct 变量，该检测会把“distinct总量”误当成“总行数”，导致误判。
        // if (isNormalJoinConstraint) {
        //     // 检查是否与filter状态约束冲突
        //     // 如果JOIN约束使用了某个filter状态的所有变量，检查是否与filter状态约束冲突
        //     int filterIndex = -1;
        //     int totalPkStates = vars[0].length;
        //     int totalFilterStates = vars.length;
        //     int totalVars = totalFilterStates * totalPkStates;

        //     // 检查是否覆盖了所有filter状态的所有变量
        //     boolean coversAllFilterStates = (varCount == totalVars);

        //     if (varCount == totalPkStates) {
        //         // 使用了某个filter状态的所有变量，提取filterIndex
        //         String firstVarName = involvedVars.get(0).getName();
        //         String[] parts = firstVarName.split("-");
        //         if (parts.length >= 1) {
        //             filterIndex = Integer.parseInt(parts[0]);
        //         }
        //     }

        //     // 检查是否覆盖了所有filter状态的所有变量（冗余约束）
        //     if (coversAllFilterStates && lowerBound == upperBound) {
        //         // 计算所有filter状态约束的总和
        //         long totalFilterStateSum = filterStateTotals.values().stream()
        //             .mapToLong(Long::longValue)
        //             .sum();

        //         if (lowerBound == totalFilterStateSum) {
        //             // 这个约束与所有filter状态约束的总和等价，是冗余的，跳过
        //             logger.warn("跳过冗余的JOIN基数约束：覆盖了所有filter状态的所有变量，且值等于所有filter状态约束的总和({})。该约束与filter状态约束等价，跳过以避免冲突。",
        //                 totalFilterStateSum);
        //             involvedVars.clear();
        //             return;
        //         } else {
        //             // 值与总和不同，可能是冲突
        //             long diff = Math.abs(lowerBound - totalFilterStateSum);
        //             if (diff <= totalFilterStateSum * 0.01) {  // 允许1%的误差
        //                 logger.warn("调整JOIN基数约束：覆盖所有filter状态，与总和有差异。JOIN约束要求总和={}, filter状态约束总和={}, 差异={}。使用filter状态约束的总和。",
        //                     lowerBound, totalFilterStateSum, diff);
        //                 lowerBound = totalFilterStateSum;
        //                 upperBound = totalFilterStateSum;
        //             } else {
        //                 // 差异较大，跳过该约束（可能是计算错误）
        //                 logger.warn("跳过JOIN基数约束：覆盖所有filter状态，但与filter状态约束总和冲突。JOIN约束要求总和={}, filter状态约束总和={}, 差异={}",
        //                     lowerBound, totalFilterStateSum, diff);
        //                 involvedVars.clear();
        //                 return;
        //             }
        //         }
        //     }

        //     // // 检查是否与单个filter状态约束冲突
        //     // if (filterIndex >= 0 && filterStateTotals.containsKey(filterIndex)) {
        //     //     long filterTotal = filterStateTotals.get(filterIndex);
        //     //     // 如果JOIN约束覆盖了所有变量，且值与filter状态约束不同，调整JOIN约束值
        //     //     if (lowerBound == upperBound) {
        //     //         long diff = Math.abs(lowerBound - filterTotal);
        //     //         if (diff > 0) {
        //     //             // 如果差异较小（可能是舍入误差），使用filter状态约束的值
        //     //             if (diff <= filterTotal * 0.01) {  // 允许1%的误差
        //     //                 logger.warn("调整JOIN基数约束：与filter状态约束有差异。JOIN约束要求总和={}, filter状态约束要求总和={}, 差异={}, filterIndex={}。使用filter状态约束的值。",
        //     //                     lowerBound, filterTotal, diff, filterIndex);
        //     //                 lowerBound = filterTotal;
        //     //                 upperBound = filterTotal;
        //     //             } else {
        //     //                 // 差异较大，跳过该约束（可能是计算错误）
        //     //                 logger.warn("跳过JOIN基数约束：与filter状态约束冲突。JOIN约束要求总和={}, filter状态约束要求总和={}, 差异={}, filterIndex={}",
        //     //                     lowerBound, filterTotal, diff, filterIndex);
        //     //                 involvedVars.clear();
        //     //                 return;
        //     //             }
        //     //         }
        //     //     }
        //     // }
        // }
        
        // 检查重复约束：如果相同的变量集合已经有约束，且值相同，跳过
        // if (existingConstraints.containsKey(varKey)) {
        //     long existingValue = existingConstraints.get(varKey);
        //     if (lowerBound == upperBound && lowerBound == existingValue) {
        //         //logger.warn("跳过重复的JOIN基数约束：变量集合={}, 值={}", varKey, lowerBound);
        //         involvedVars.clear();
        //         return;
        //     } else if (lowerBound == upperBound && lowerBound != existingValue) {
        //         logger.error("检测到冲突的JOIN基数约束：变量集合={}, 已有值={}, 新值={}。这可能导致INFEASIBLE！",
        //                    varKey, existingValue, lowerBound);
        //         // 选择更合理的值（通常选择较大的值，或者根据具体情况调整）
        //         // 这里我们选择跳过新约束，保留原有约束
        //         logger.warn("保留原有约束，跳过新约束");
        //         involvedVars.clear();
        //         return;
        //     }
        // }
        
        String varNames = involvedVars.stream()
            .map(IntVar::getName)
            .collect(java.util.stream.Collectors.joining(", "));

        // logger.info("=== JOIN基数约束详细方程 ===");
        // //logger.info("约束方程: {} <= Σ{} <= {}", lowerBound, varNames, upperBound);
        // //logger.info("变量含义:");
        // String[] varNamesArray = varNames.split(", ");
        // for (String varName : varNamesArray) {
        //     String[] parts = varName.split("-");
        //     if (parts.length == 3) {
        //         // 3段格式：fkColIndex-filterIndex-pkIndex (FK distinct变量)
        //         int fkColIndex = Integer.parseInt(parts[0]);
        //         int fIndex = Integer.parseInt(parts[1]);
        //         int pkIndex = Integer.parseInt(parts[2]);
        //         // logger.info("  {}: FK列[{}]的Filter状态[{}]的PK状态[{}]对应的distinct行数",
        //         //     varName, fkColIndex, fIndex, pkIndex);
        //     } else if (parts.length >= 2) {
        //         // 2段格式：filterIndex-pkIndex (普通JOIN基数约束变量)
        //         int fIndex = Integer.parseInt(parts[0]);
        //         int pkIndex = Integer.parseInt(parts[1]);
        //         // logger.info("  {}: Filter状态[{}]的PK状态[{}]对应的行数",
        //         //     varName, fIndex, pkIndex);
        //     }
        // }
        // // logger.info("约束解释: 满足此JOIN条件的总行数在[{}, {}]范围内", lowerBound, upperBound);
        // logger.info("=== 约束方程结束 ===\n");

        // logger.info("添加JOIN基数约束: 范围[{}, {}], 变量数={}, 变量={}",
        //    lowerBound, upperBound, varCount, varNames);
        model.addLinearConstraint(LinearExpr.sum(involvedVars.toArray(new IntVar[0])), lowerBound, upperBound);
        
        // 记录已添加的约束（仅记录精确约束，即lowerBound == upperBound）
        if (lowerBound == upperBound) {
            existingConstraints.put(varKey, lowerBound);
        }

        involvedVars.clear();
    }

    /**
     * 获取当前涉及的变量列表（用于放宽约束）
     */
    public List<IntVar> getInvolvedVars() {
        return involvedVars;
    }

    /**
     * 添加线性约束（用于放宽约束）
     */
    public void addLinearConstraint(LinearExpr expr, long lowerBound, long upperBound) {
        model.addLinearConstraint(expr, lowerBound, upperBound);
    }

    public void setStatusHistogramForDebug(Map<JoinStatus, Long> statusHistogram) {
        this.debugStatusHistogram = statusHistogram;
    }
}
