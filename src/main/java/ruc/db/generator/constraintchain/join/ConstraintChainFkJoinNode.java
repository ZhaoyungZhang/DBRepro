package ruc.db.generator.constraintchain.join;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;

import ruc.db.LanguageManager;
import ruc.db.generator.ConstructCpModel;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.ConstraintChainNodeType;
import ruc.db.generator.joininfo.JoinStatus;

/**
 * @author wangqingshuai
 */
public class ConstraintChainFkJoinNode extends ConstraintChainNode {
    @JsonIgnore
    public int joinStatusIndex;
    @JsonIgnore
    public int joinStatusLocation;
    private String refCols;
    private String localCols;
    private int pkTag;
    private BigDecimal probability;
    private BigDecimal probabilityWithFailFilter;
    private BigDecimal pkDistinctProbability;
    private ConstraintNodeJoinType type = ConstraintNodeJoinType.INNER_JOIN;

    /**
     * 非 PK/FK 等值连接时为 {@link JoinConstraintJoinModel#GENERIC}；null 表示历史 JSON 未标注（按 PK_FK 语义兼容）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JoinConstraintJoinModel joinModel;

    /** 计划给出的 JOIN 输出行数（JCC），GENERIC 路径诊断与后续 CP 加权目标用。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long targetJoinRows;

    /** JOIN 左右子计划估计行数（输入侧规模）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long leftInputRows;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long rightInputRows;

    /**
     * 可选：GENERIC 多桶缩放因子 \(w_k\)（与 {@link #targetJoinRows} 配合
     * {@link ruc.db.generator.ConstructCpModel#addWeightedJoinCardinalityConstraint}）。
     * 未设置时仅用语义上的计划基数 {@link #computeJoinCardinalityTargetForCp(long)}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private long[] genericBucketWeights;

    /**
     * GENERIC 反域启发式：对 {@link ruc.db.generator.GenericJoinAntiDomain#maybeBiasGenericSample} 使用的参照列值域偏移。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long genericAntiDomainOffset;

    @JsonIgnore
    private boolean[] joinResultStatus;

    @JsonIgnore
    private final Logger logger = LoggerFactory.getLogger(ConstraintChainFkJoinNode.class);

    public ConstraintChainFkJoinNode() {
        super(ConstraintChainNodeType.FK_JOIN);
    }

    private final ResourceBundle rb = LanguageManager.getInstance().getRb();

    public ConstraintChainFkJoinNode(String localCols, String refCols, int pkTag, BigDecimal probability) {
        super(ConstraintChainNodeType.FK_JOIN);
        this.refCols = refCols;
        this.pkTag = pkTag;
        this.localCols = localCols;
        this.probability = probability;
    }

    public ConstraintNodeJoinType getType() {
        return type;
    }

    public void setType(ConstraintNodeJoinType type) {
        this.type = type;
    }

    public JoinConstraintJoinModel getJoinModel() {
        return joinModel;
    }

    public void setJoinModel(JoinConstraintJoinModel joinModel) {
        this.joinModel = joinModel;
    }

    public Long getTargetJoinRows() {
        return targetJoinRows;
    }

    public void setTargetJoinRows(Long targetJoinRows) {
        this.targetJoinRows = targetJoinRows;
    }

    public Long getLeftInputRows() {
        return leftInputRows;
    }

    public void setLeftInputRows(Long leftInputRows) {
        this.leftInputRows = leftInputRows;
    }

    public Long getRightInputRows() {
        return rightInputRows;
    }

    public void setRightInputRows(Long rightInputRows) {
        this.rightInputRows = rightInputRows;
    }

    public long[] getGenericBucketWeights() {
        return genericBucketWeights;
    }

    public void setGenericBucketWeights(long[] genericBucketWeights) {
        this.genericBucketWeights = genericBucketWeights;
    }

    public Long getGenericAntiDomainOffset() {
        return genericAntiDomainOffset;
    }

    public void setGenericAntiDomainOffset(Long genericAntiDomainOffset) {
        this.genericAntiDomainOffset = genericAntiDomainOffset;
    }

    /**
     * 写入 CP 的 JOIN 基数目标：GENERIC 且带 {@link #targetJoinRows} 时用计划行数并夹在 min(左右输入) 内；否则为 filterSize×probability。
     */
    public long computeJoinCardinalityTargetForCp(long filterSize) {
        if (joinModel == JoinConstraintJoinModel.GENERIC && targetJoinRows != null) {
            long upper = Long.MAX_VALUE;
            if (leftInputRows != null) {
                upper = Math.min(upper, leftInputRows);
            }
            if (rightInputRows != null) {
                upper = Math.min(upper, rightInputRows);
            }
            upper = Math.min(upper, filterSize);
            return Math.max(0L, Math.min(targetJoinRows, upper));
        }
        BigDecimal b = BigDecimal.valueOf(filterSize).multiply(probability);
        return b.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public BigDecimal getPkDistinctProbability() {
        return pkDistinctProbability;
    }

    public void setPkDistinctProbability(BigDecimal pkDistinctProbability) {
        this.pkDistinctProbability = pkDistinctProbability;
    }

    @Override
    public String toString() {
        return String.format("{pkTag:%d,refCols:%s,probability:%s,pkDistinctProbability:%f,probabilityWithFailFilter:%s}", pkTag, refCols, probability, pkDistinctProbability, probabilityWithFailFilter);
    }

    public int getPkTag() {
        return pkTag;
    }

    public void setPkTag(int pkTag) {
        this.pkTag = pkTag;
    }

    public BigDecimal getProbability() {
        return probability;
    }

    public void setProbability(BigDecimal probability) {
        this.probability = probability.stripTrailingZeros();
    }

    public String getLocalCols() {
        return localCols;
    }

    public void setLocalCols(String localCols) {
        this.localCols = localCols;
    }

    public String getRefCols() {
        return refCols;
    }

    public void setRefCols(String refCols) {
        this.refCols = refCols;
    }

    public BigDecimal getProbabilityWithFailFilter() {
        return probabilityWithFailFilter;
    }

    public void setProbabilityWithFailFilter(BigDecimal probabilityWithFailFilter) {
        this.probabilityWithFailFilter = probabilityWithFailFilter.stripTrailingZeros();
    }

    private void addIndexJoinCardinalityConstraint(ConstructCpModel cpModel, long unFilterSize, boolean[][] canBeInput) {
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (!canBeInput[filterIndex][pkStatusIndex] && joinResultStatus[pkStatusIndex]) {
                    cpModel.addJoinCardinalityValidVar(filterIndex, pkStatusIndex);
                }
            }
        }
        long indexJoinSize;
        if (joinModel == JoinConstraintJoinModel.GENERIC && targetJoinRows != null) {
            indexJoinSize = Math.max(0L, Math.min(targetJoinRows, unFilterSize));
        } else {
            BigDecimal bIndexJoinSize = BigDecimal.valueOf(unFilterSize).multiply(probabilityWithFailFilter);
            indexJoinSize = bIndexJoinSize.setScale(0, RoundingMode.HALF_UP).longValue();
        }

        // 放宽IndexJoin约束：当indexJoinSize=0时允许[0,1]范围，避免INFEASIBLE
        if (indexJoinSize == 0) {
            cpModel.addLinearConstraint(LinearExpr.sum(cpModel.getInvolvedVars().toArray(new IntVar[0])), 0, 1);
            logger.info("放宽IndexJoin约束: 原始indexJoinSize=0, 允许范围[0,1]以避免INFEASIBLE, joinStatusIndex={}, joinStatusLocation={}",
                       joinStatusIndex, joinStatusLocation);
        } else {
            cpModel.addJoinCardinalityConstraint(indexJoinSize);
            logger.info(rb.getString("indexJoinInfo"), indexJoinSize, joinStatusIndex, joinStatusLocation);
        }
    }

    private long addJoinCardinalityConstraint(ConstructCpModel cpModel, long filterSize, boolean[][] canBeInput) {
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (canBeInput[filterIndex][pkStatusIndex] && joinResultStatus[pkStatusIndex]) {
                    cpModel.addJoinCardinalityValidVar(filterIndex, pkStatusIndex);
                } else {
                    canBeInput[filterIndex][pkStatusIndex] = false;
                }
            }
        }
        long originalFilterSize = filterSize;
        filterSize = computeJoinCardinalityTargetForCp(filterSize);

        // 冲突检测：检查是否有其他约束已经使用了相同的变量
        StringBuilder conflictInfo = new StringBuilder();
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (canBeInput[filterIndex][pkStatusIndex] && joinResultStatus[pkStatusIndex]) {
                    conflictInfo.append("变量").append(filterIndex).append("-").append(pkStatusIndex).append(", ");
                }
            }
        }
        if (conflictInfo.length() > 0) {
            conflictInfo.setLength(conflictInfo.length() - 2); // 移除最后的", "
            // logger.warn("JOIN约束冲突检测: joinStatusIndex={}, joinStatusLocation={}, 使用变量: {}, filterSize={}",
            //     joinStatusIndex, joinStatusLocation, conflictInfo.toString(), filterSize);
        }

        // logger.info("添加JOIN基数约束: 输入filterSize={}, probability={}, join基数目标={}, joinStatusLocation={}, joinStatusIndex={}",
        //     originalFilterSize, probability, filterSize, joinStatusLocation, joinStatusIndex);
        cpModel.addJoinCardinalityConstraint(filterSize);
        // logger.info(rb.getString("statusDataOutput"), filterSize, joinStatusLocation, joinStatusIndex);
        return filterSize;
    }

    public void addJoinDistinctConstraint(ConstructCpModel cpModel, long filterSize, boolean[][] canBeInput) {
        if (!type.hasCardinalityConstraint()) {
            return;
        }
        // 获取join对应的位置
        for (int filterIndex = 0; filterIndex < canBeInput.length; filterIndex++) {
            for (int pkStatusIndex = 0; pkStatusIndex < canBeInput[0].length; pkStatusIndex++) {
                if (canBeInput[filterIndex][pkStatusIndex] && joinResultStatus[pkStatusIndex]) {
                    cpModel.addJoinDistinctValidVar(joinStatusIndex, filterIndex, pkStatusIndex);
                }
            }
        }

        var bPkSize = BigDecimal.valueOf(filterSize).multiply(pkDistinctProbability);
        long pkSize = bPkSize.setScale(0, RoundingMode.HALF_UP).longValue();
        // logger.info("添加Distinct约束: filterSize={}, pkDistinctProbability={}, 计算后pkSize={}", 
        //     filterSize, pkDistinctProbability, pkSize);
        // logger.info(rb.getString("addDistinctConstraint"), this, pkSize);
        // 合法性约束，每个pkStatus不能超过提供的数量
        // 注意：这里的 distinct 约束本质上是“上界”约束（distinct 使用的 PK 数量不能超过 pkSize），
        // 而不是必须精确等于 pkSize。使用等式会在 filter 状态增多后非常容易导致 INFEASIBLE。
        // cpModel.addJoinCardinalityConstraint(0, pkSize);
        cpModel.addJoinCardinalityConstraint(pkSize);

    }

    public long addJoinCardinalityConstraint(ConstructCpModel cpModel, long filterSize, long unFilerSize, boolean[][] canBeInput) {
        if (type.isSemi()) {
            return filterSize;
        }
        if (probabilityWithFailFilter != null) {
            addIndexJoinCardinalityConstraint(cpModel, unFilerSize, canBeInput);
        }
        return addJoinCardinalityConstraint(cpModel, filterSize, canBeInput);
    }

    public void initJoinResultStatus(JoinStatus[][] pkJointStatus) {
        joinResultStatus = new boolean[pkJointStatus.length];
        boolean status = !type.isAnti();
        for (int i = 0; i < pkJointStatus.length; i++) {
            joinResultStatus[i] = pkJointStatus[i][joinStatusIndex].status()[joinStatusLocation] == status;
        }
    }
}
