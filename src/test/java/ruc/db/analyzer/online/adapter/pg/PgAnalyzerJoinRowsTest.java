package ruc.db.analyzer.online.adapter.pg;

import org.junit.jupiter.api.Test;
import ruc.db.analyzer.online.node.AggNode;
import ruc.db.analyzer.online.node.ExecutionNode;
import ruc.db.analyzer.online.node.ExecutionNodeType;
import ruc.db.analyzer.online.node.FilterNode;
import ruc.db.analyzer.online.node.JoinNode;
import ruc.db.generator.constraintchain.filter.LogicNode;
import ruc.db.schema.Table;
import ruc.db.schema.TableManager;

import java.util.Collections;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgAnalyzerJoinRowsTest {

    @Test
    void leftOuterJoinKeepsPlanOutputRowsInsteadOfRightInputRows() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Hash Join",
                    "Join Type": "Left",
                    "Hash Cond": "((amfi.trml_addr_code)::text = (he.tmnl_comm_addr)::text)",
                    "Actual Rows": 9141228,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Hash Join",
                        "Join Type": "Inner",
                        "Hash Cond": "((amfi.mgt_org_code)::text = (org_child.child_mgt_org_code)::text)",
                        "Actual Rows": 9141228,
                        "Actual Loops": 1
                      },
                      {
                        "Node Type": "Hash",
                        "Actual Rows": 630271,
                        "Actual Loops": 1,
                        "Plans": [
                          {
                            "Node Type": "Seq Scan",
                            "Actual Rows": 630271,
                            "Actual Loops": 1,
                            "Alias": "he",
                            "Schema": "sgami_stat",
                            "Relation Name": "a_arch_tmnl_comm_addr_he"
                          }
                        ]
                      }
                    ]
                  }
                }]
                """;
        PgJsonReader.setReadContext(plan);
        StringBuilder root = PgJsonReader.getRootPath();

        PgAnalyzer analyzer = new PgAnalyzer();
        Method getJoinNode = PgAnalyzer.class.getDeclaredMethod("getJoinNode", StringBuilder.class, int.class);
        getJoinNode.setAccessible(true);

        JoinNode node = (JoinNode) getJoinNode.invoke(analyzer, root, PgJsonReader.readRowCount(root));

        assertEquals(9_141_228L, node.getOutputRows());
        assertEquals(0, BigDecimal.ONE.compareTo(node.getPkDistinctSize()));
    }

    @Test
    void leftOuterJoinUsesAppendRowsWhenFirstPartitionHasZeroRows() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Nested Loop",
                    "Join Type": "Left",
                    "Actual Rows": 10,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Seq Scan",
                        "Actual Rows": 10,
                        "Actual Loops": 1,
                        "Alias": "a",
                        "Schema": "sg_mis",
                        "Relation Name": "dev_inst_rmv_wk_rec"
                      },
                      {
                        "Node Type": "Append",
                        "Actual Rows": 1,
                        "Actual Loops": 10,
                        "Plans": [
                          {
                            "Node Type": "Index Scan",
                            "Index Cond": "(aamfi.inst_id = a.inst_id)",
                            "Actual Rows": 0,
                            "Actual Loops": 10,
                            "Alias": "aamfi",
                            "Schema": "sgami_arch",
                            "Relation Name": "a_arch_meter_full_info_pmin"
                          },
                          {
                            "Node Type": "Index Scan",
                            "Index Cond": "(aamfi.inst_id = a.inst_id)",
                            "Actual Rows": 1,
                            "Actual Loops": 10,
                            "Alias": "aamfi_1",
                            "Schema": "sgami_arch",
                            "Relation Name": "a_arch_meter_full_info_p51401"
                          }
                        ]
                      }
                    ]
                  }
                }]
                """;
        PgJsonReader.setReadContext(plan);
        StringBuilder root = PgJsonReader.getRootPath();

        PgAnalyzer analyzer = new PgAnalyzer();
        Method getJoinNode = PgAnalyzer.class.getDeclaredMethod("getJoinNode", StringBuilder.class, int.class);
        getJoinNode.setAccessible(true);

        JoinNode node = (JoinNode) getJoinNode.invoke(analyzer, root, PgJsonReader.readRowCount(root));

        assertEquals(10L, node.getOutputRows());
        assertEquals(10L, node.getLeftInputRows());
        assertEquals(10L, node.getRightInputRows());
        assertEquals(0, BigDecimal.ONE.compareTo(node.getPkDistinctSize()));
    }

    @Test
    void countPlanNodeIsSkippedAsPaginationWrapper() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Count",
                    "Actual Rows": 100,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Aggregate",
                        "Strategy": "Sorted",
                        "Actual Rows": 101,
                        "Actual Loops": 1,
                        "Group Key": ["a.inst_id"],
                        "Plans": [
                          {
                            "Node Type": "Seq Scan",
                            "Actual Rows": 76966,
                            "Actual Loops": 1,
                            "Alias": "a",
                            "Schema": "sg_mis",
                            "Relation Name": "dev_inst_rmv_wk_rec",
                            "Output": ["a.inst_id"]
                          }
                        ]
                      }
                    ]
                  }
                }]
                """;

        PgAnalyzer analyzer = new PgAnalyzer();
        ExecutionNode node = analyzer.getExecutionTree(Collections.singletonList(new String[]{plan}));

        assertEquals(ExecutionNodeType.AGGREGATE, node.getType());
        assertEquals("sg_mis.dev_inst_rmv_wk_rec.inst_id", ((AggNode) node).getInfo());
    }

    @Test
    void bitmapHeapScanMergesChildBitmapIndexCondWithFilter() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Bitmap Heap Scan",
                    "Schema": "sg_mis",
                    "Relation Name": "dev_inst_rmv_wk_rec",
                    "Alias": "a",
                    "Actual Rows": 76950,
                    "Actual Loops": 1,
                    "Rows Removed by Filter": 33453,
                    "Filter": "((a.dev_cls)::text = '01'::text)",
                    "Plans": [
                      {
                        "Node Type": "Bitmap Index Scan",
                        "Index Name": "idx_devinstrmvwkrec_instrmvdate_273699f6",
                        "Actual Rows": 110403,
                        "Actual Loops": 1,
                        "Index Cond": "((a.inst_rmv_date >= '2024-06-01'::date) AND (a.inst_rmv_date <= '2024-06-30'::date))"
                      }
                    ]
                  }
                }]
                """;

        PgAnalyzer analyzer = new PgAnalyzer();
        ExecutionNode node = analyzer.getExecutionTree(Collections.singletonList(new String[]{plan}));

        assertEquals(ExecutionNodeType.FILTER, node.getType());
        String info = ((FilterNode) node).getInfo();
        assertTrue(info.contains("sg_mis.dev_inst_rmv_wk_rec.dev_cls"), info);
        assertTrue(info.contains("sg_mis.dev_inst_rmv_wk_rec.inst_rmv_date"), info);
        assertTrue(info.contains(">="), info);
        assertTrue(info.contains("<="), info);
    }

    @Test
    void globalIndexScanIsHandledAsIndexFilterNode() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Global Index Scan",
                    "Schema": "sgami_arch",
                    "Relation Name": "a_arch_meter_full_info",
                    "Alias": "info",
                    "Actual Rows": 9141229,
                    "Actual Loops": 1,
                    "Index Cond": "((info.mgt_org_code)::text = ANY ((ARRAY[$0])::text[]))"
                  }
                }]
                """;

        TableManager.getInstance().addSchema(
                "sgami_arch.a_arch_meter_full_info",
                new Table(List.of("sgami_arch.a_arch_meter_full_info.mgt_org_code"), 9_141_229L));

        PgAnalyzer analyzer = new PgAnalyzer();
        ExecutionNode node = analyzer.getExecutionTree(Collections.singletonList(new String[]{plan}));

        assertEquals(ExecutionNodeType.FILTER, node.getType());
        FilterNode filterNode = (FilterNode) node;
        assertEquals("sgami_arch.a_arch_meter_full_info", filterNode.getTableName());
        assertEquals(9_141_229L, filterNode.getOutputRows());
    }

    @Test
    void initPlanTextArrayAnyPredicateIsElidedButOtherFilterRemains() throws Exception {
        PgAnalyzer analyzer = new PgAnalyzer();
        LogicNode node = analyzer.analyzeSelectOperator("""
                ((sgami_support.s_meter_label_result.label_no = '1876811694555557989'::text)
                 AND ((sgami_support.s_meter_label_result.mgt_org_code)::text = ANY ((ARRAY[$0])::text[])))
                """);

        assertEquals("label_no = '1876811694555557989'",
                node.toString().replaceAll(System.lineSeparator(), " "));
    }

    @Test
    void derivedAggregateAliasJoinColumnResolvesToGroupedSourceColumn() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Hash Join",
                    "Join Type": "Left",
                    "Hash Cond": "(mlr.dev_id = mr_agg.meter_id)",
                    "Actual Rows": 100,
                    "Actual Loops": 1,
                    "Plans": [
                      {
                        "Node Type": "Seq Scan",
                        "Schema": "sgami_support",
                        "Relation Name": "s_meter_label_result",
                        "Alias": "mlr",
                        "Actual Rows": 100,
                        "Actual Loops": 1,
                        "Output": ["mlr.dev_id"]
                      },
                      {
                        "Node Type": "Hash",
                        "Actual Rows": 10,
                        "Actual Loops": 1,
                        "Plans": [
                          {
                            "Node Type": "Subquery Scan",
                            "Alias": "mr_agg",
                            "Actual Rows": 10,
                            "Actual Loops": 1,
                            "Output": ["mr_agg.meter_id", "mr_agg.mr_count"],
                            "Plans": [
                              {
                                "Node Type": "Aggregate",
                                "Actual Rows": 10,
                                "Actual Loops": 1,
                                "Output": ["mr.meter_id", "count(1)"],
                                "Group Key": ["mr.meter_id"],
                                "Plans": [
                                  {
                                    "Node Type": "Merge Join",
                                    "Join Type": "Inner",
                                    "Merge Cond": "(mr.meter_id = em.dev_id)",
                                    "Actual Rows": 15,
                                    "Actual Loops": 1,
                                    "Plans": [
                                      {
                                        "Node Type": "Seq Scan",
                                        "Schema": "sg_mis",
                                        "Relation Name": "meter_run",
                                        "Alias": "mr",
                                        "Actual Rows": 15,
                                        "Actual Loops": 1,
                                        "Output": ["mr.meter_id"]
                                      },
                                      {
                                        "Node Type": "Seq Scan",
                                        "Schema": "sg_mis",
                                        "Relation Name": "elec_meter",
                                        "Alias": "em",
                                        "Actual Rows": 20,
                                        "Actual Loops": 1,
                                        "Output": ["em.dev_id"]
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

        PgAnalyzer analyzer = new PgAnalyzer();
        analyzer.getExecutionTree(Collections.singletonList(new String[]{plan}));

        String[] result = new String[4];
        analyzer.analyzeJoinInfo("Hash Cond: (mlr.dev_id = mr_agg.meter_id)", result);

        assertEquals("sgami_support.s_meter_label_result", result[0]);
        assertEquals("dev_id", result[1]);
        assertEquals("sg_mis.meter_run", result[2]);
        assertEquals("meter_id", result[3]);
    }

    @Test
    void aggregateGroupKeyCastsAreNormalizedToCanonicalColumnName() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Aggregate",
                    "Strategy": "Hashed",
                    "Actual Rows": 1296,
                    "Actual Loops": 1,
                    "Output": ["(amoc.child_mgt_org_code)::text"],
                    "Group Key": ["(amoc.child_mgt_org_code)::text"],
                    "Plans": [
                      {
                        "Node Type": "Seq Scan",
                        "Schema": "sgami_arch",
                        "Relation Name": "a_mgt_org_childs",
                        "Alias": "amoc",
                        "Actual Rows": 1297,
                        "Actual Loops": 1,
                        "Output": ["amoc.child_mgt_org_code"]
                      }
                    ]
                  }
                }]
                """;

        PgAnalyzer analyzer = new PgAnalyzer();
        ExecutionNode node = analyzer.getExecutionTree(Collections.singletonList(new String[]{plan}));

        assertEquals("sgami_arch.a_mgt_org_childs.child_mgt_org_code", ((AggNode) node).getInfo());
    }

    @Test
    void subPlanAlternativePlaceholderFallsBackToFilterWhenCorrelationCannotBeRecovered() throws Exception {
        String plan = """
                [{
                  "Plan": {
                    "Node Type": "Seq Scan",
                    "Schema": "sg_mis",
                    "Relation Name": "meter_cntr_dev_run",
                    "Alias": "mcdr",
                    "Actual Rows": 2188869,
                    "Actual Loops": 1,
                    "Filter": "(alternatives: SubPlan 5 or hashed SubPlan 6)",
                    "Plans": [
                      {
                        "Node Type": "Index Only Scan",
                        "Schema": "sgami_arch",
                        "Relation Name": "a_mgt_org_childs",
                        "Alias": "amoc",
                        "Actual Rows": 0,
                        "Actual Loops": 0,
                        "Index Cond": "(((mgt_org_code)::text = '51401'::text) AND ((child_mgt_org_code)::text = '51402'::text))"
                      },
                      {
                        "Node Type": "Index Only Scan",
                        "Schema": "sgami_arch",
                        "Relation Name": "a_mgt_org_childs",
                        "Alias": "amoc_1",
                        "Actual Rows": 202,
                        "Actual Loops": 1,
                        "Index Cond": "(((mgt_org_code)::text = '51401'::text) AND ((child_mgt_org_code)::text = '51402'::text))"
                      }
                    ]
                  }
                }]
                """;

        PgAnalyzer analyzer = new PgAnalyzer();
        ExecutionNode node = analyzer.getExecutionTree(Collections.singletonList(new String[]{plan}));

        assertEquals(ExecutionNodeType.FILTER, node.getType());
        assertEquals("sg_mis.meter_cntr_dev_run", node.getTableName());
        assertNull(node.getInfo());
        assertEquals(2_188_869L, node.getOutputRows());
    }

    @Test
    void subPlanAlternativePlaceholderRecoversSemiJoinExecutionTree() throws Exception {
        String plan = """
                [{
                  "Plan": {
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
                }]
                """;

        PgAnalyzer analyzer = new PgAnalyzer();
        ExecutionNode node = analyzer.getExecutionTree(Collections.singletonList(new String[]{plan}));

        assertEquals(ExecutionNodeType.JOIN, node.getType());
        JoinNode joinNode = (JoinNode) node;
        assertTrue(joinNode.isSemiJoin());
        assertFalse(joinNode.isAntiJoin());
        assertEquals("Index Cond: (amoc_1.child_mgt_org_code = mcdr.mgt_org_code)", joinNode.getInfo());
        assertNotNull(joinNode.getLeftNode());
        assertEquals("sg_mis.meter_cntr_dev_run", joinNode.getLeftNode().getTableName());
        assertEquals(3_469_640L, joinNode.getLeftNode().getOutputRows());
        assertNotNull(joinNode.getRightNode());
        assertEquals("sgami_arch.a_mgt_org_childs", joinNode.getRightNode().getTableName());
    }

    @Test
    void splitQueryPlanSplitsTopLevelUnionAllAppendBranches() {
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
                            "Actual Rows": 1,
                            "Actual Loops": 1,
                            "Plans": [
                              {
                                "Node Type": "Hash Join",
                                "Join Type": "Inner",
                                "Hash Cond": "((ecc.cust_id)::text = (info.cust_id)::text)",
                                "Actual Rows": 12345,
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
                                    "Node Type": "Seq Scan",
                                    "Schema": "sgami_arch",
                                    "Relation Name": "a_arch_meter_full_info",
                                    "Alias": "info",
                                    "Actual Rows": 9141229,
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
                }]
                """;

        PgAnalyzer analyzer = new PgAnalyzer();
        List<List<String[]>> split = analyzer.splitQueryPlan(Collections.singletonList(new String[]{plan}));

        assertEquals(2, split.size());
        assertTrue(split.get(0).getFirst()[0].contains("\"Relation Name\":\"a_arch_meter_full_info\""));
        assertTrue(split.get(1).getFirst()[0].contains("\"Relation Name\":\"elec_cons_cust\""));
    }
}
