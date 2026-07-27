package ruc.db.analyzer.online;

import org.junit.jupiter.api.Test;
import ruc.db.analyzer.online.adapter.pg.PgAnalyzer;
import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.ConstraintChainNode;
import ruc.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ruc.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ruc.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ruc.db.generator.constraintchain.join.ConstraintNodeJoinType;
import ruc.db.schema.Column;
import ruc.db.schema.ColumnManager;
import ruc.db.schema.ColumnType;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;
import ruc.db.utils.exception.TouchstoneException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerLocalPlanIntegrationTest {

    @Test
    void q5CurrentPlanFixture_localPlanRecoversHavingAndCrossTableGuards() throws Exception {
        registerQ5FixtureSchema();
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Aggregate",
                    "Strategy": "Plain",
                    "Actual Rows": 1,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Append",
                        "Actual Rows": 2,
                        "Actual Loops": 1,
                        "Plans": [
                          {
                            "Node Type": "Aggregate",
                            "Strategy": "Plain",
                            "Output": ["count(*)"],
                            "Actual Rows": 1,
                            "Actual Loops": 1,
                            "Plans": [
                              {
                                "Node Type": "Seq Scan",
                                "Schema": "sgami_arch",
                                "Relation Name": "a_arch_meter_full_info",
                                "Alias": "info",
                                "Actual Rows": 9141229,
                                "Actual Loops": 1
                              }
                            ]
                          },
                          {
                            "Node Type": "Aggregate",
                            "Strategy": "Plain",
                            "Output": ["(count(*) - count(DISTINCT info_1.mgt_org_code))"],
                            "Actual Rows": 1,
                            "Actual Loops": 1,
                            "Plans": [
                              {
                                "Node Type": "Hash Join",
                                "Join Type": "Inner",
                                "Hash Cond": "(info_1.cust_id = ecc.cust_id)",
                                "Actual Rows": 200,
                                "Actual Loops": 1,
                                "Plans": [
                                  {
                                    "Node Type": "Seq Scan",
                                    "Schema": "sgami_arch",
                                    "Relation Name": "a_arch_meter_full_info",
                                    "Alias": "info_1",
                                    "Actual Rows": 9141229,
                                    "Actual Loops": 1
                                  },
                                  {
                                    "Node Type": "Nested Loop",
                                    "Join Type": "Inner",
                                    "Join Filter": "(sog.group_no = sogm.group_no)",
                                    "Actual Rows": 200,
                                    "Actual Loops": 1,
                                    "Plans": [
                                      {
                                        "Node Type": "Index Scan",
                                        "Schema": "sgami_support",
                                        "Relation Name": "s_obj_group",
                                        "Alias": "sog",
                                        "Actual Rows": 200,
                                        "Actual Loops": 1,
                                        "Index Cond": "(sog.group_no = sogm.group_no)"
                                      },
                                      {
                                        "Node Type": "Hash Join",
                                        "Join Type": "Inner",
                                        "Hash Cond": "(ecc.elec_cons_cust_id = sogm.members_id)",
                                        "Actual Rows": 200,
                                        "Actual Loops": 1,
                                        "Plans": [
                                          {
                                            "Node Type": "Seq Scan",
                                            "Schema": "sg_mis",
                                            "Relation Name": "elec_cons_cust",
                                            "Alias": "ecc",
                                            "Actual Rows": 12345,
                                            "Actual Loops": 1
                                          },
                                          {
                                            "Node Type": "Aggregate",
                                            "Strategy": "Hashed",
                                            "Actual Rows": 200,
                                            "Actual Loops": 1,
                                            "Rows Removed by Filter": 50,
                                            "Filter": "(count(*) > 1)",
                                            "Group Key": ["sogm.members_id"],
                                            "Plans": [
                                              {
                                                "Node Type": "Seq Scan",
                                                "Schema": "sgami_support",
                                                "Relation Name": "s_obj_group_members",
                                                "Alias": "sogm",
                                                "Actual Rows": 1000,
                                                "Actual Loops": 1
                                              }
                                            ]
                                          }
                                        ]
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }]
                """;

        QueryAnalyzer analyzer = new QueryAnalyzer(new PgAnalyzer(), null);
        List<List<ConstraintChain>> chains = assertDoesNotThrow(() -> analyzer.extractQuery("select 1", plan));

        assertEquals(2, chains.size());
        List<ConstraintChain> secondBranch = chains.get(1);
        List<ConstraintChainAggregateNode> aggregateNodes = flatten(secondBranch, ConstraintChainAggregateNode.class);
        List<ConstraintChainFkJoinNode> joinNodes = flatten(secondBranch, ConstraintChainFkJoinNode.class);

        assertTrue(aggregateNodes.stream().anyMatch(node ->
                node.getGroupKey() != null
                        && node.getGroupKey().equals(List.of("sgami_support.s_obj_group_members.members_id"))
                        && node.getInputRows() == 1000L
                        && node.getOutputRows() == 200L));
        assertTrue(aggregateNodes.stream().anyMatch(node ->
                node.getGroupKey() != null
                        && node.getGroupKey().equals(List.of("sgami_arch.a_arch_meter_full_info.mgt_org_code"))
                        && node.getInputRows() == 200L
                        && node.getOutputRows() == 200L));
        assertTrue(joinNodes.stream().anyMatch(node ->
                "sgami_support.s_obj_group_members.members_id".equals(node.getLocalCols())
                        && "sg_mis.elec_cons_cust.elec_cons_cust_id".equals(node.getRefCols())));
        assertTrue(joinNodes.stream().anyMatch(node ->
                "sgami_support.s_obj_group_members.group_no".equals(node.getLocalCols())
                        && "sgami_support.s_obj_group.group_no".equals(node.getRefCols())));
    }

    @Test
    void q5SecondBranchFixture_avoidsAliasCollapseAndKeepsDistinctAggregatePositive() throws Exception {
        registerQ5FixtureSchema();
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Aggregate",
                    "Strategy": "Plain",
                    "Output": ["(count(*) - count(DISTINCT info_1.mgt_org_code))"],
                    "Actual Rows": 1,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Hash Join",
                        "Join Type": "Inner",
                        "Hash Cond": "((ecc.cust_id = info_1.cust_id) AND (ecc.mgt_org_code = info_1.mgt_org_code))",
                        "Actual Rows": 2,
                        "Actual Loops": 1,
                        "Plans": [
                          {
                            "Node Type": "Hash Join",
                            "Join Type": "Inner",
                            "Hash Cond": "(ecc.elec_cons_cust_id = s_obj_group_members.members_id)",
                            "Actual Rows": 1,
                            "Actual Loops": 1,
                            "Plans": [
                              {
                                "Node Type": "Seq Scan",
                                "Schema": "sg_mis",
                                "Relation Name": "elec_cons_cust",
                                "Alias": "ecc",
                                "Actual Rows": 1,
                                "Actual Loops": 1
                              },
                              {
                                "Node Type": "Nested Loop",
                                "Join Type": "Inner",
                                "Join Filter": "(s_obj_group_members.members_id = sogm.members_id)",
                                "Actual Rows": 2,
                                "Actual Loops": 1,
                                "Plans": [
                                  {
                                    "Node Type": "Index Scan",
                                    "Schema": "sgami_support",
                                    "Relation Name": "s_obj_group_members",
                                    "Alias": "s_obj_group_members",
                                    "Actual Rows": 8,
                                    "Actual Loops": 1,
                                    "Index Cond": "(s_obj_group_members.group_no = sog.group_no)"
                                  },
                                  {
                                    "Node Type": "Aggregate",
                                    "Strategy": "Hashed",
                                    "Actual Rows": 8,
                                    "Actual Loops": 1,
                                    "Rows Removed by Filter": 857,
                                    "Filter": "(count(*) > 1)",
                                    "Group Key": ["sogm.members_id"],
                                    "Plans": [
                                      {
                                        "Node Type": "Seq Scan",
                                        "Schema": "sgami_support",
                                        "Relation Name": "s_obj_group_members",
                                        "Alias": "sogm",
                                        "Actual Rows": 865,
                                        "Actual Loops": 1
                                      }
                                    ]
                                  }
                                ]
                              }
                            ]
                          },
                          {
                            "Node Type": "Append",
                            "Actual Rows": 2,
                            "Actual Loops": 1,
                            "Plans": [
                              {
                                "Node Type": "Seq Scan",
                                "Schema": "sgami_arch",
                                "Relation Name": "a_arch_meter_full_info",
                                "Alias": "info_1",
                                "Actual Rows": 0,
                                "Actual Loops": 1
                              },
                              {
                                "Node Type": "Seq Scan",
                                "Schema": "sgami_arch",
                                "Relation Name": "a_arch_meter_full_info",
                                "Alias": "info_1",
                                "Actual Rows": 2,
                                "Actual Loops": 1
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }]
                """;

        QueryAnalyzer analyzer = new QueryAnalyzer(new PgAnalyzer(), null);
        List<List<ConstraintChain>> chains = assertDoesNotThrow(() -> analyzer.extractQuery("select 1", plan));

        assertEquals(1, chains.size());
        List<ConstraintChain> secondBranch = chains.getFirst();
        List<ConstraintChainAggregateNode> aggregateNodes = flatten(secondBranch, ConstraintChainAggregateNode.class);
        List<ConstraintChainFkJoinNode> joinNodes = flatten(secondBranch, ConstraintChainFkJoinNode.class);

        assertFalse(joinNodes.stream().anyMatch(node ->
                "sgami_support.s_obj_group_members.members_id".equals(node.getLocalCols())
                        && "sgami_support.s_obj_group_members.members_id".equals(node.getRefCols())));
        assertTrue(joinNodes.stream().anyMatch(node ->
                "sgami_support.s_obj_group_members.members_id".equals(node.getLocalCols())
                        && "sg_mis.elec_cons_cust.elec_cons_cust_id".equals(node.getRefCols())));
        assertTrue(joinNodes.stream().anyMatch(node ->
                "sg_mis.elec_cons_cust.cust_id,mgt_org_code".equals(node.getLocalCols())
                        && "sgami_arch.a_arch_meter_full_info.cust_id,mgt_org_code".equals(node.getRefCols())));
        assertTrue(aggregateNodes.stream().anyMatch(node ->
                node.getGroupKey() != null
                        && node.getGroupKey().equals(List.of("sgami_support.s_obj_group_members.members_id"))
                        && node.getInputRows() == 865L
                        && node.getOutputRows() == 8L));
        assertTrue(aggregateNodes.stream().anyMatch(node ->
                node.getGroupKey() != null
                        && node.getGroupKey().equals(List.of("sgami_arch.a_arch_meter_full_info.mgt_org_code"))
                        && node.getInputRows() != null
                        && node.getInputRows() > 0
                        && node.getOutputRows() != null
                        && node.getOutputRows() > 0));
    }

    @Test
    void q5FirstBranchFixture_recoversSingleTableCastFilterWithoutParserError() throws Exception {
        registerQ5FixtureSchema();
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Aggregate",
                    "Strategy": "Plain",
                    "Output": ["count(*)"],
                    "Actual Rows": 1,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Nested Loop",
                        "Join Type": "Inner",
                        "Actual Rows": 9141229,
                        "Actual Loops": 1,
                        "Index Cond": "((info.mgt_org_code)::text = (amoc.child_mgt_org_code)::text)",
                        "Plans": [
                          {
                            "Node Type": "Seq Scan",
                            "Schema": "sgami_arch",
                            "Relation Name": "a_arch_meter_full_info",
                            "Alias": "info",
                            "Actual Rows": 9141229,
                            "Actual Loops": 1
                          },
                          {
                            "Node Type": "Index Only Scan",
                            "Schema": "sgami_arch",
                            "Relation Name": "a_mgt_org_childs",
                            "Alias": "amoc",
                            "Actual Rows": 202,
                            "Actual Loops": 1,
                            "Index Cond": "(((amoc.mgt_org_code)::text = '51401'::text) AND ((amoc.child_mgt_org_code)::text = (info.mgt_org_code)::text))"
                          }
                        ]
                      }
                    ]
                  }
                }]
                """;

        QueryAnalyzer analyzer = new QueryAnalyzer(new PgAnalyzer(), null);
        List<List<ConstraintChain>> chains = assertDoesNotThrow(() -> analyzer.extractQuery("select 1", plan));

        List<ConstraintChainFilterNode> filters = flatten(chains.getFirst(), ConstraintChainFilterNode.class);
        List<ConstraintChainFkJoinNode> joins = flatten(chains.getFirst(), ConstraintChainFkJoinNode.class);

        assertTrue(filters.stream().anyMatch(node -> node.toString().contains("mgt_org_code = '51401'")));
        assertTrue(joins.stream().anyMatch(node ->
                "sgami_arch.a_mgt_org_childs.child_mgt_org_code".equals(node.getRefCols())
                        && "sgami_arch.a_arch_meter_full_info.mgt_org_code".equals(node.getLocalCols())));
    }

    @Test
    void q10CurrentPlanFixture_localPlanRetainsOuterJoinSemiJoinAndParentJoin() throws Exception {
        registerQ10FixtureSchema();
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Nested Loop",
                    "Join Type": "Left",
                    "Actual Rows": 101,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Merge Join",
                        "Join Type": "Left",
                        "Merge Cond": "(mb.dev_id = mcdr.asset_id)",
                        "Actual Rows": 101,
                        "Actual Loops": 1,
                        "Plans": [
                          {
                            "Node Type": "Seq Scan",
                            "Schema": "sg_mis",
                            "Relation Name": "meter_box",
                            "Alias": "mb",
                            "Actual Rows": 115,
                            "Actual Loops": 1
                          },
                          {
                            "Node Type": "Seq Scan",
                            "Schema": "sg_mis",
                            "Relation Name": "meter_cntr_dev_run",
                            "Alias": "mcdr",
                            "Actual Rows": 2188869,
                            "Actual Loops": 1,
                            "Rows Removed by Filter": 1280771,
                            "Filter": "(alternatives: SubPlan 5 or hashed SubPlan 6)",
                            "Plans": [
                              {
                                "Node Type": "Index Only Scan",
                                "Schema": "sgami_arch",
                                "Relation Name": "a_mgt_org_childs",
                                "Alias": "amoc",
                                "Actual Rows": 0,
                                "Actual Loops": 0,
                                "Index Cond": "(((mgt_org_code)::text = '51401'::text) AND ((child_mgt_org_code)::text = (mcdr.mgt_org_code)::text))"
                              },
                              {
                                "Node Type": "Index Only Scan",
                                "Schema": "sgami_arch",
                                "Relation Name": "a_mgt_org_childs",
                                "Alias": "amoc_1",
                                "Actual Rows": 202,
                                "Actual Loops": 101,
                                "Index Cond": "(((mgt_org_code)::text = '51401'::text) AND ((child_mgt_org_code)::text = (mcdr.mgt_org_code)::text))"
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "Node Type": "Index Scan",
                        "Schema": "sgami_arch",
                        "Relation Name": "a_mgt_org_parents",
                        "Alias": "po",
                        "Actual Rows": 101,
                        "Actual Loops": 101,
                        "Index Cond": "((po.mgt_org_code)::text = (mcdr.mgt_org_code)::text)"
                      }
                    ]
                  }
                }]
                """;

        QueryAnalyzer analyzer = new QueryAnalyzer(new PgAnalyzer(), null);
        List<List<ConstraintChain>> chains = assertDoesNotThrow(() -> analyzer.extractQuery("select 1", plan));

        List<ConstraintChain> branch = chains.getFirst();
        List<ConstraintChainFkJoinNode> joins = flatten(branch, ConstraintChainFkJoinNode.class);

        assertTrue(joins.stream().anyMatch(node ->
                "sg_mis.meter_box.dev_id".equals(node.getLocalCols())
                        && "sg_mis.meter_cntr_dev_run.asset_id".equals(node.getRefCols())
                        && node.getType() == ConstraintNodeJoinType.OUTER_JOIN));
        assertTrue(joins.stream().anyMatch(node ->
                "sg_mis.meter_cntr_dev_run.mgt_org_code".equals(node.getLocalCols())
                        && "sgami_arch.a_mgt_org_childs.child_mgt_org_code".equals(node.getRefCols())
                        && node.getType() == ConstraintNodeJoinType.SEMI_JOIN
                        && node.getRefInputRows() != null
                        && node.getRefInputRows() > 0
                        && node.getRightInputRows() != null
                        && node.getRightInputRows() > 0));
        assertTrue(joins.stream().anyMatch(node ->
                "sg_mis.meter_cntr_dev_run.mgt_org_code".equals(node.getLocalCols())
                        && "sgami_arch.a_mgt_org_parents.mgt_org_code".equals(node.getRefCols())));
        assertFalse(branch.stream().anyMatch(chain ->
                "sgami_arch.a_mgt_org_childs".equals(chain.getTableName())
                        && chain.getNodes().stream()
                        .filter(ConstraintChainFkJoinNode.class::isInstance)
                        .map(ConstraintChainFkJoinNode.class::cast)
                        .anyMatch(node -> node.getType() == ConstraintNodeJoinType.SEMI_JOIN)));
    }

    private static <T extends ConstraintChainNode> List<T> flatten(List<ConstraintChain> chains, Class<T> clazz) {
        List<T> out = new ArrayList<>();
        for (ConstraintChain chain : chains) {
            for (ConstraintChainNode node : chain.getNodes()) {
                if (clazz.isInstance(node)) {
                    out.add(clazz.cast(node));
                }
            }
        }
        return out;
    }

    private static void registerQ5FixtureSchema() throws TouchstoneException {
        addTable("sgami_arch.a_arch_meter_full_info",
                List.of("sgami_arch.a_arch_meter_full_info.cust_id", "sgami_arch.a_arch_meter_full_info.mgt_org_code"),
                9_141_229L);
        addTable("sg_mis.elec_cons_cust",
                List.of("sg_mis.elec_cons_cust.cust_id", "sg_mis.elec_cons_cust.elec_cons_cust_id"),
                12_345L);
        addTable("sgami_support.s_obj_group_members",
                List.of("sgami_support.s_obj_group_members.members_id", "sgami_support.s_obj_group_members.group_no"),
                1_000L);
        addTable("sgami_support.s_obj_group",
                List.of("sgami_support.s_obj_group.group_no"),
                200L);
        addTable("sgami_arch.a_mgt_org_childs",
                List.of("sgami_arch.a_mgt_org_childs.child_mgt_org_code", "sgami_arch.a_mgt_org_childs.mgt_org_code"),
                202L);

        addColumn("sgami_arch.a_arch_meter_full_info.cust_id", ColumnType.INTEGER, 20_000);
        addColumn("sgami_arch.a_arch_meter_full_info.mgt_org_code", ColumnType.VARCHAR, 201);
        addColumn("sg_mis.elec_cons_cust.cust_id", ColumnType.INTEGER, 10_000);
        addColumn("sg_mis.elec_cons_cust.elec_cons_cust_id", ColumnType.INTEGER, 12_345);
        addColumn("sgami_support.s_obj_group_members.members_id", ColumnType.INTEGER, 200);
        addColumn("sgami_support.s_obj_group_members.group_no", ColumnType.INTEGER, 200);
        addColumn("sgami_support.s_obj_group.group_no", ColumnType.INTEGER, 200);
        addColumn("sgami_arch.a_mgt_org_childs.child_mgt_org_code", ColumnType.VARCHAR, 202);
        addColumn("sgami_arch.a_mgt_org_childs.mgt_org_code", ColumnType.VARCHAR, 1);
    }

    private static void registerQ10FixtureSchema() throws TouchstoneException {
        addTable("sg_mis.meter_box", List.of("sg_mis.meter_box.dev_id"), 115L);
        addTable("sg_mis.meter_cntr_dev_run",
                List.of("sg_mis.meter_cntr_dev_run.asset_id", "sg_mis.meter_cntr_dev_run.mgt_org_code"),
                2_188_869L);
        addTable("sgami_arch.a_mgt_org_childs",
                List.of("sgami_arch.a_mgt_org_childs.child_mgt_org_code", "sgami_arch.a_mgt_org_childs.mgt_org_code"),
                202L);
        addTable("sgami_arch.a_mgt_org_parents",
                List.of("sgami_arch.a_mgt_org_parents.mgt_org_code"),
                101L);

        addColumn("sg_mis.meter_box.dev_id", ColumnType.INTEGER, 115);
        addColumn("sg_mis.meter_cntr_dev_run.asset_id", ColumnType.INTEGER, 50_000);
        addColumn("sg_mis.meter_cntr_dev_run.mgt_org_code", ColumnType.VARCHAR, 101);
        addColumn("sgami_arch.a_mgt_org_childs.child_mgt_org_code", ColumnType.VARCHAR, 202);
        addColumn("sgami_arch.a_mgt_org_childs.mgt_org_code", ColumnType.VARCHAR, 1);
        addColumn("sgami_arch.a_mgt_org_parents.mgt_org_code", ColumnType.VARCHAR, 101);
    }

    private static void addTable(String name, List<String> columns, long size) {
        if (!TableManager.getInstance().containSchema(name)) {
            TableManager.getInstance().addSchema(name, new Table(columns, size));
        }
    }

    private static void addColumn(String name, ColumnType type, int range) throws TouchstoneException {
        if (ColumnManager.getInstance().getColumn(name) == null) {
            Column column = new Column(type);
            column.setRange(range);
            ColumnManager.getInstance().addColumn(name, column);
        }
    }
}
