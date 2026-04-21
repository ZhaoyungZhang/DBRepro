package ruc.db.generator.constraintchain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import ruc.db.utils.CommonUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static ruc.db.utils.CommonUtils.readFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ReadAndWriteJsonTest {
    private static final String dir = "src/test/resources/data/query-instantiation/TPCH/";
    private static final String DISTRIBUTION_DIR = "/distribution";

    @Test
    void writeTestConstraintChain() throws IOException {
        String content = getConstraintChainForAllSQL();
        Map<String, List<ConstraintChain>> query2chains = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(query2chains);
        String normIn = content.replaceAll(System.lineSeparator(), "");
        String normOut = contentWrite.replaceAll(System.lineSeparator(), "");
        JsonNode treeIn = CommonUtils.MAPPER.readTree(normIn);
        JsonNode treeOut = CommonUtils.MAPPER.readTree(normOut);
        assertDeepJsonEquals(treeIn, treeOut, "$");
    }

    /**
     * 深度比较 JSON：数值按 {@link java.math.BigDecimal} 比较（避免 double / 科学计数法与固定小数写法不等价），对象键递归。
     */
    private static void assertDeepJsonEquals(JsonNode expected, JsonNode actual, String path) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            fail(path + ": null mismatch");
        }
        if (expected.isObject() && actual.isObject()) {
            for (var it = expected.fields(); it.hasNext(); ) {
                var e = it.next();
                String k = e.getKey();
                if (!actual.has(k)) {
                    fail(path + ": missing key '" + k + "' in actual");
                }
                assertDeepJsonEquals(e.getValue(), actual.get(k), path + "." + k);
            }
            // 允许 actual 多出黄金文件中未收录的键（如 Parameter 新增 substringStart 等）
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            assertEquals(expected.size(), actual.size(), path + ": array size");
            for (int i = 0; i < expected.size(); i++) {
                assertDeepJsonEquals(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
            return;
        }
        if (expected.isNumber() && actual.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()),
                    path + ": number " + expected + " vs " + actual);
            return;
        }
        assertTrue(expected.equals(actual), path + ": expected " + expected + " got " + actual);
    }

    @Test
    void writeTestStringTemplate() throws IOException {
        String content = readFile(dir + DISTRIBUTION_DIR + "/stringTemplate.json");
        Map<String, Map<Long, boolean[]>> columName2StringTemplate = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(columName2StringTemplate);
        assertEquals(content, contentWrite);
    }

    @Test
    void writeTestDistribution() throws IOException {
        String content = readFile(dir + DISTRIBUTION_DIR + "/distribution.json");
        Map<String, SortedMap<Long, BigDecimal>> bucket2Probabilities = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bucket2Probabilities);
        assertEquals(content, contentWrite);
    }

    @Test
    void writeTestBoundPara() throws IOException {
        String content = readFile(dir + DISTRIBUTION_DIR + "/boundPara.json");
        Map<String, SortedMap<BigDecimal, Long>> boundPara = CommonUtils.MAPPER.readValue(content, new TypeReference<>() {
        });
        String contentWrite = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(boundPara);
        assertEquals(content, contentWrite);
    }

    private String getConstraintChainForAllSQL() throws IOException {
        String path = dir + "/workload";
        File sqlDic = new File(path);
        File[] sqlArray = sqlDic.listFiles();
        assert sqlArray != null;
        StringBuilder result = new StringBuilder();
        for (File file : sqlArray) {
            File[] graphArray = file.listFiles();
            assert graphArray != null;
            for (File file1 : graphArray) {
                if (file1.getName().contains("json")) {
                    String eachCC = readFile(file1.getPath());
                    //去掉前后大括号
                    eachCC = eachCC.substring(1, eachCC.length() - 1);
                    eachCC += ",";
                    result.append(eachCC);
                }
            }
        }
        result = new StringBuilder("{" + result.substring(0, result.length() - 1) + "}");
        return result.toString();
    }
}
