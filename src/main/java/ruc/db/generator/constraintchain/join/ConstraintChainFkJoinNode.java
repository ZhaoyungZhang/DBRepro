package ruc.db.generator.constraintchain.join;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
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
     * 当前约束链本表/参照表在该 JOIN 节点处的输入行数。执行计划左右孩子不一定等同于
     * local/ref 方向，例如 Nested Loop 的左侧可能是小表 AMOC，右侧才是当前生成表 MLR。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long localInputRows;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long refInputRows;

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

    /**
     * Historical constraint-chain JSON did not record joinModel; treat null as the original PK/FK behavior.
     */
    @JsonIgnore
    public boolean requiresPhysicalForeignKeyGeneration() {
        return joinModel == null || joinModel == JoinConstraintJoinModel.PK_FK;
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

    public Long getLocalInputRows() {
        return localInputRows;
    }

    public void setLocalInputRows(Long localInputRows) {
        this.localInputRows = localInputRows;
    }

    public Long getRefInputRows() {
        return refInputRows;
    }

    public void setRefInputRows(Long refInputRows) {
        this.refInputRows = refInputRows;
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
     * 写入 CP 的 JOIN 基数目标：GENERIC 且带 {@link #targetJoinRows} 时，按当前 batch 的
     * {@code filterSize / localInputRows} 缩放计划全局行数。历史 JSON 没有 localInputRows 时
     * 回退到 leftInputRows。参照侧行数不是 inner join 输出上界；例如 202 个组织编码可以匹配数百万 AMFI 行。
     */
    public long computeJoinCardinalityTargetForCp(long filterSize) {
        if (joinModel == JoinConstraintJoinModel.GENERIC && targetJoinRows != null) {
            long target;
            Long scaleInputRows = localInputRows != null && localInputRows > 0 ? localInputRows : leftInputRows;
            if (scaleInputRows != null && scaleInputRows > 0) {
                BigDecimal scaled = BigDecimal.valueOf(filterSize)
                        .multiply(BigDecimal.valueOf(targetJoinRows))
                        .divide(BigDecimal.valueOf(scaleInputRows), 0, RoundingMode.HALF_UP);
                target = scaled.longValue();
            } else if (probability != null) {
                target = BigDecimal.valueOf(filterSize).multiply(probability)
                        .setScale(0, RoundingMode.HALF_UP).longValue();
            } else {
                target = targetJoinRows;
            }
            return Math.max(0L, Math.min(target, filterSize));
        }
        BigDecimal b = BigDecimal.valueOf(filterSize).multiply(probability);
        return b.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    long computeFailFilterJoinCardinalityTargetForCp(long unFilterSize) {
        if (probabilityWithFailFilter == null) {
            return 0L;
        }
        BigDecimal target = BigDecimal.valueOf(Math.max(0L, unFilterSize)).multiply(probabilityWithFailFilter);
        return target.setScale(0, RoundingMode.HALF_UP).longValue();
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
        indexJoinSize = computeFailFilterJoinCardinalityTargetForCp(unFilterSize);

        if (indexJoinSize == 0) {
            cpModel.addJoinCardinalityConstraint(0, 0);
            logger.info("IndexJoin fail-filter目标为0，使用精确0约束，joinStatusIndex={}, joinStatusLocation={}",
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
        // EXISTS / SEMI JOIN 不参与 distinct/share-key 建模，只保留后续的 matched-row cardinality。
        if (type.isSemi()) {
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

        long distinctBaseSize = filterSize;
        if (joinModel == JoinConstraintJoinModel.GENERIC && targetJoinRows != null) {
            distinctBaseSize = computeJoinCardinalityTargetForCp(filterSize);
            logger.info("GENERIC join distinct目标按匹配基数缩放: inputFilterSize={}, joinTargetForBatch={}, pkDistinctProbability={}, localCols={}, refCols={}",
                    filterSize, distinctBaseSize, pkDistinctProbability, localCols, refCols);
        }
        var bPkSize = BigDecimal.valueOf(distinctBaseSize).multiply(pkDistinctProbability);
        long pkSize = bPkSize.setScale(0, RoundingMode.HALF_UP).longValue();
        // logger.info("添加Distinct约束: filterSize={}, pkDistinctProbability={}, 计算后pkSize={}", 
        //     filterSize, pkDistinctProbability, pkSize);
        // logger.info(rb.getString("addDistinctConstraint"), this, pkSize);
        // OUTER JOIN 的主基数约束由 addJoinCardinalityConstraint 处理；这里的 distinct 只应限制
        // “最多使用多少个参照侧 key”。否则右表 key 非唯一、多个左表行共享同一右表 key 时，会错误要求
        // matched rows 与 distinct keys 一一对应，导致 Q4 这类 GENERIC LEFT JOIN infeasible。
        if (type == ConstraintNodeJoinType.OUTER_JOIN) {
            cpModel.addJoinCardinalityConstraint(0, pkSize);
        } else {
            cpModel.addJoinCardinalityConstraint(pkSize);
        }

    }

    public long addJoinCardinalityConstraint(ConstructCpModel cpModel, long filterSize, long unFilerSize, boolean[][] canBeInput) {
        // EXISTS/SEMI JOIN 仍然需要收缩当前链上的存活行数，只是不应该再附带“精确 distinct 右键数量”约束。
        // 对 Q10 这类 correlated EXISTS，如果这里直接放过，后续节点会继续拿未过滤的 localInputRows 去缩放，
        // 导致 outer join 目标从 101 被放大成 276 一类错误值。
        if (type == ConstraintNodeJoinType.SEMI_JOIN) {
            return addJoinCardinalityConstraint(cpModel, filterSize, canBeInput);
        }
        if (type == ConstraintNodeJoinType.ANTI_SEMI_JOIN) {
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
