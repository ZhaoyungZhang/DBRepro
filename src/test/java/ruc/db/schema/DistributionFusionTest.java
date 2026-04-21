package ruc.db.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简单的融合用例：
 * - 从外部统计文件读取 public.part.p_brand 的 MCV 直方图
 * - 将其按 Top-2 + Others 的方式融合到现有 distribution（"1"、"2"、"25" 三个 pv）
 * - 同步给出 boundPara 的建议 offsets："0"→pv2（Top-1），"p2"→pv1（Top-2）
 * - 打印融合后的分布与 offsets（不落盘，仅演示）
 */
public class DistributionFusionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 绝对路径（按用户偏好）
    private static final String EXTERNAL_PART_JSON = "/home/Mirage/experiments/RSGen_test/q16_tpchsf1_gen/data_distributions/public.part.json";
    private static final String BASE_DISTRIBUTION_JSON = "/home/Mirage/src/test/resources/data/query-instantiation/TPCH/distribution/distribution.json";
    private static final String BASE_BOUND_JSON = "/home/Mirage/src/test/resources/data/query-instantiation/TPCH/distribution/boundPara.json";

    @Test
    public void fuseBrandDistributionTop2OthersAndPrint() throws IOException {
        Assumptions.assumeTrue(new File(EXTERNAL_PART_JSON).exists(),
                "Skip when external RSGen distribution is not present: " + EXTERNAL_PART_JSON);
        // 1) 读取现有 distribution 与 bound（仅为获取/对齐结构；本用例不落盘）
        Map<String, Map<String, BigDecimal>> distribution = readDistribution(BASE_DISTRIBUTION_JSON);
        Map<String, Map<String, Long>> bound = readBound(BASE_BOUND_JSON);

        // 2) 读取外部分布，抽取 public.part.p_brand 的 MCV（value,count）
        List<ValueCount> brandCounts = readExternalBrandCounts(EXTERNAL_PART_JSON);
        BigDecimal total = brandCounts.stream()
                .map(vc -> new BigDecimal(vc.count))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(total.compareTo(BigDecimal.ZERO) > 0, "external total count should be > 0");

        // 3) 选 Top-2 值，其余合并为 Others；计算概率
        brandCounts.sort(Comparator.comparingLong((ValueCount vc) -> vc.count).reversed());
        BigDecimal p2 = new BigDecimal(brandCounts.get(0).count).divide(total, new MathContext(20, RoundingMode.HALF_UP));
        BigDecimal p1 = new BigDecimal(brandCounts.get(1).count).divide(total, new MathContext(20, RoundingMode.HALF_UP));
        BigDecimal pOthers = BigDecimal.ONE.subtract(p2).subtract(p1);
        if (pOthers.compareTo(BigDecimal.ZERO) < 0) {
            pOthers = BigDecimal.ZERO;
        }

        // 4) 融合到现有的三桶表示："2"=Top-1, "1"=Top-2, "25"=Others
        String column = "public.part.p_brand";
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        merged.put("1", p1);
        merged.put("2", p2);
        merged.put("25", pOthers);

        // Sanity: 概率和≈1
        BigDecimal sum = merged.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tol = new BigDecimal("1e-9");
        assertTrue(sum.subtract(BigDecimal.ONE).abs().compareTo(tol) <= 0, "sum of probabilities should be ~1");

        // 5) 生成建议的 bound offsets：按累计概率段落铺设
        Map<String, Long> mergedOffsets = new LinkedHashMap<>();
        // 约定：pv=2（Top-1）从 0 开始，pv=1（Top-2）从 p2 开始
        mergedOffsets.put("0", 2L);
        mergedOffsets.put(p2.toPlainString(), 1L);

        // 6) 打印融合后的分布与 offsets（JSON pretty）
        Map<String, Object> printDist = new LinkedHashMap<>();
        printDist.put(column, merged);
        Map<String, Object> printBound = new LinkedHashMap<>();
        printBound.put(column, mergedOffsets);

        System.out.println("Merged distribution for public.part.p_brand:");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(printDist));

        System.out.println("Merged bound offsets for public.part.p_brand:");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(printBound));

        // 7) 可选：与原始结构对齐（不落盘，仅校验键存在）
        // 若原始 distribution 中包含该列，则仅替换该列的三键
        if (distribution.containsKey(column)) {
            distribution.put(column, merged);
        }
        if (bound.containsKey(column)) {
            bound.put(column, mergedOffsets);
        }
    }

    private static Map<String, Map<String, BigDecimal>> readDistribution(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            return new HashMap<>();
        }
        return MAPPER.readValue(file, new TypeReference<Map<String, Map<String, BigDecimal>>>() {});
    }

    private static Map<String, Map<String, Long>> readBound(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            return new HashMap<>();
        }
        return MAPPER.readValue(file, new TypeReference<Map<String, Map<String, Long>>>() {});
    }

    private static List<ValueCount> readExternalBrandCounts(String externalPath) throws IOException {
        File file = new File(externalPath);
        Map<String, Object> root = MAPPER.readValue(file, new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> columns = (Map<String, Object>) root.get("columns");
        Objects.requireNonNull(columns, "columns not found in external distributions");
        @SuppressWarnings("unchecked")
        Map<String, Object> brandNode = (Map<String, Object>) columns.get("public.part.p_brand");
        Objects.requireNonNull(brandNode, "public.part.p_brand not found in external distributions");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) brandNode.get("buckets");
        List<ValueCount> result = new ArrayList<>();
        for (Map<String, Object> b : buckets) {
            String type = String.valueOf(b.get("bucket_type"));
            if (!"MCV".equals(type)) {
                continue; // 仅使用 MCV 桶
            }
            String value = String.valueOf(b.get("low_value"));
            long count = ((Number) b.get("count")).longValue();
            result.add(new ValueCount(value, count));
        }
        return result;
    }

    private static class ValueCount {
        final String value;
        final long count;

        ValueCount(String value, long count) {
            this.value = value;
            this.count = count;
        }
    }
}


