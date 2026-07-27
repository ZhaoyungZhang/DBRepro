package ruc.db.generator;

import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.JoinConstraintJoinModel;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;

/**
 * GENERIC 反域启发式：在参照列值域上取一段偏移，使部分随机填充键远离主域中心，降低与左视图键偶然重合（完整「不在左键集」需统计阶段配合）。
 */
public final class GenericJoinAntiDomain {

    private GenericJoinAntiDomain() {
    }

    /**
     * 对整数型参照列，用值域长度的 1/4 作为填充偏移上界（至少为 1）。
     */
    public static long estimateOffsetForRefColumn(String refCanonicalCol) {
        if (refCanonicalCol == null || refCanonicalCol.contains(",")) {
            return 0L;
        }
        Column c = ColumnManager.getInstance().getColumn(refCanonicalCol);
        if (c == null || c.getColumnType() != ColumnType.INTEGER) {
            return 0L;
        }
        long span = Math.max(1L, c.getRange());
        return Math.max(1L, span / 4);
    }

    /**
     * CP 选择了“全 false”合成状态时，RuleTable 中没有真实参照键。这里生成稳定的反域虚拟键，
     * 使本地列可以表达“不匹配任何参照行”，从而满足 GENERIC inner/outer join 的匹配基数。
     */
    public static long syntheticAntiDomainKey(int rowId, int fkColIndex) {
        long base = (long) rowId + 1L + ((long) fkColIndex * 1_000_000_000L);
        return -base;
    }

    /**
     * 对 {@link MergedRuleTable#getKey} 的采样结果按需加偏移（仅部分行），供 {@link FkGenerator} populate 路径调用。
     */
    public static long maybeBiasGenericSample(long rawKey, int rowId, int fkColIndex, ConstraintChainFkJoinNode meta) {
        if (rawKey == Long.MIN_VALUE && meta != null && meta.getJoinModel() == JoinConstraintJoinModel.GENERIC) {
            return syntheticAntiDomainKey(rowId, fkColIndex);
        }
        if (meta == null || meta.getJoinModel() != JoinConstraintJoinModel.GENERIC) {
            return rawKey;
        }
        Long offObj = meta.getGenericAntiDomainOffset();
        if (offObj == null || offObj <= 0) {
            return rawKey;
        }
        long off = offObj;
        if (((rowId * 31L + fkColIndex) % 3) != 0) {
            return rawKey;
        }
        if (rawKey > Long.MAX_VALUE - off) {
            return rawKey;
        }
        return rawKey + off;
    }
}
