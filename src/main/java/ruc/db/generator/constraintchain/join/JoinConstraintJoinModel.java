package ruc.db.generator.constraintchain.join;

/**
 * JOIN 约束链上外键侧节点的语义模型：库中可解析的 PK/FK 参照，或通用非 PK-FK 等值连接（GENERIC）。
 */
public enum JoinConstraintJoinModel {
    PK_FK,
    GENERIC
}
