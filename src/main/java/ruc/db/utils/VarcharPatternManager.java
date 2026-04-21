package ruc.db.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import ruc.db.LanguageManager;
import ruc.db.schema.ColumnManager;

/**
 * VarcharPatternManager
 *
 * 读取 distribution/varcharpatterns.json，用于识别和解析“varchar 特殊模式列”，例如：
 * - public.part.p_brand: MFGR#<num>
 *
 * 目前支持 type=prefix_int：
 * - prefix: 固定前缀
 * - number: {min,max}（可选，仅用于校验/生成时兜底）
 */
public final class VarcharPatternManager {
    private static final Logger logger = LoggerFactory.getLogger(VarcharPatternManager.class);
    private static final LanguageManager LM = LanguageManager.getInstance();

    private static final String DEFAULT_FILE_NAME = "varcharpatterns.json";

    // 缓存：columnName -> spec
    private static final Map<String, PrefixIntSpec> prefixIntSpecs = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    /** 已成功解析的 varcharpatterns.json 所在目录（绝对路径），用于同目录跳过重载 */
    private static volatile String loadedDistributionDirAbs = null;

    private VarcharPatternManager() {}

    /**
     * 按当前运行目录加载：优先 {@code ColumnManager.resultDir/distribution/varcharpatterns.json}，
     * 若不存在再尝试 {@code user.dir/distribution/}（与 cdfConstraints 同级），避免后者覆盖前者。
     */
    public static void tryLoadForCurrentRun() {
        String base = ColumnManager.getInstance().getResultDirPath();
        if (base != null && !base.isBlank()) {
            File inResult = new File(new File(base, "distribution"), DEFAULT_FILE_NAME);
            if (inResult.isFile()) {
                tryLoadFromDistributionDir(inResult.getParentFile().getPath());
                return;
            }
        }
        File cwdFile = new File(new File(System.getProperty("user.dir"), "distribution"), DEFAULT_FILE_NAME);
        if (cwdFile.isFile()) {
            tryLoadFromDistributionDir(cwdFile.getParentFile().getPath());
        }
    }

    public static void tryLoadFromDistributionDir(String distributionDir) {
        if (distributionDir == null || distributionDir.isBlank()) return;

        File f = new File(distributionDir, DEFAULT_FILE_NAME);
        if (!f.isFile()) {
            return;
        }
        String absDir = f.getParentFile().getAbsolutePath();
        if (loaded && absDir.equals(loadedDistributionDirAbs)) {
            return;
        }
        prefixIntSpecs.clear();
        loaded = false;
        loadedDistributionDirAbs = null;

        try {
            String json = CommonUtils.readFile(f.getAbsolutePath());
            Map<String, Object> root = CommonUtils.MAPPER.readValue(json, new TypeReference<>() {});
            Object patternsObj = root.get("patterns");
            if (!(patternsObj instanceof Map)) {
                loaded = true;
                loadedDistributionDirAbs = absDir;
                logger.warn(LM.formatBilingual("VarcharPatternsMissingPatterns", f.getAbsolutePath()));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> patterns = (Map<String, Object>) patternsObj;
            for (Map.Entry<String, Object> e : patterns.entrySet()) {
                String column = e.getKey();
                if (!(e.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> spec = (Map<String, Object>) e.getValue();
                String type = Objects.toString(spec.get("type"), "");
                if (!"prefix_int".equalsIgnoreCase(type)) continue;

                String prefix = Objects.toString(spec.get("prefix"), "");
                if (prefix.isBlank()) continue;

                Integer min = null, max = null;
                Object numObj = spec.get("number");
                if (numObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> num = (Map<String, Object>) numObj;
                    Object minObj = num.get("min");
                    Object maxObj = num.get("max");
                    if (minObj instanceof Number) min = ((Number) minObj).intValue();
                    if (maxObj instanceof Number) max = ((Number) maxObj).intValue();
                }
                PrefixIntSpec parsed = new PrefixIntSpec(prefix, min, max);

                // 可选读取：virtualStats（suffixColumn 的 mcv/histogram）
                Object vsObj = spec.get("virtualStats");
                if (vsObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vs = (Map<String, Object>) vsObj;
                    Object suffixObj = vs.get("suffixColumn");
                    if (suffixObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> suffix = (Map<String, Object>) suffixObj;
                        // mcv.values / mcv.frequencies
                        Object mcvObj = suffix.get("mcv");
                        if (mcvObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> mcv = (Map<String, Object>) mcvObj;
                            Object valuesObj = mcv.get("values");
                            Object freqsObj = mcv.get("frequencies");
                            if (valuesObj instanceof List && freqsObj instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Object> vals = (List<Object>) valuesObj;
                                @SuppressWarnings("unchecked")
                                List<Object> freqs = (List<Object>) freqsObj;
                                List<Integer> mcvValues = new ArrayList<>();
                                List<Double> mcvFreqs = new ArrayList<>();
                                int n = Math.min(vals.size(), freqs.size());
                                for (int i = 0; i < n; i++) {
                                    try {
                                        mcvValues.add(Integer.parseInt(String.valueOf(vals.get(i)).trim()));
                                        mcvFreqs.add(Double.parseDouble(String.valueOf(freqs.get(i)).trim()));
                                    } catch (Exception ignore) {
                                        // skip bad entry
                                    }
                                }
                                if (!mcvValues.isEmpty()) {
                                    parsed.mcvValues = mcvValues;
                                    parsed.mcvFrequencies = mcvFreqs;
                                }
                            }
                        }
                        // histogram.bounds
                        Object histObj = suffix.get("histogram");
                        if (histObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> hist = (Map<String, Object>) histObj;
                            Object boundsObj = hist.get("bounds");
                            if (boundsObj instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Object> bs = (List<Object>) boundsObj;
                                List<Integer> bounds = new ArrayList<>();
                                for (Object b : bs) {
                                    try {
                                        bounds.add(Integer.parseInt(String.valueOf(b).trim()));
                                    } catch (Exception ignore) {
                                        // skip
                                    }
                                }
                                if (!bounds.isEmpty()) {
                                    parsed.histogramBounds = bounds;
                                }
                            }
                        }
                    }
                }

                prefixIntSpecs.put(column, parsed);
            }
            loaded = true;
            loadedDistributionDirAbs = absDir;
            logger.info(LM.formatBilingual("VarcharPatternsLoaded", prefixIntSpecs.size(), f.getAbsolutePath()));
        } catch (Exception ex) {
            loaded = true;
            loadedDistributionDirAbs = absDir;
            logger.warn(LM.formatBilingual("VarcharPatternsLoadFailed", f.getAbsolutePath(), ex.getMessage()));
        }
    }

    public static PrefixIntSpec getPrefixIntSpec(String canonicalColumnName) {
        if (canonicalColumnName == null) return null;
        return prefixIntSpecs.get(canonicalColumnName);
    }

    /**
     * 解析 prefix_int 的数值后缀。
     * - 支持值两侧空格
     * - 严格要求以 prefix 开头且后面为纯数字
     */
    public static Integer parsePrefixIntSuffix(String rawValue, PrefixIntSpec spec) {
        if (rawValue == null || spec == null) return null;
        String v = rawValue.trim();
        if (!v.startsWith(spec.prefix)) return null;
        String suffix = v.substring(spec.prefix.length()).trim();
        if (suffix.isEmpty()) return null;
        // 只允许数字
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static final class PrefixIntSpec {
        public final String prefix;
        public final Integer min;
        public final Integer max;
        // 来自 varcharpatterns.json 物化的虚拟子串列统计信息（可选）
        public List<Integer> mcvValues = null;        // 后缀 int 的 topK
        public List<Double> mcvFrequencies = null;    // 对应频率（占原表比例）
        public List<Integer> histogramBounds = null;  // 101 个 bounds（100 bins）
        
        // 数字部分的格式化位数（用于确保字符串比较与数值比较一致）
        private int suffixWidth = -1;

        private PrefixIntSpec(String prefix, Integer min, Integer max) {
            this.prefix = prefix;
            this.min = min;
            this.max = max;
            // 计算需要的位数：取 min 和 max 中位数更多的那个
            if (min != null && max != null) {
                int minWidth = String.valueOf(min).length();
                int maxWidth = String.valueOf(max).length();
                this.suffixWidth = Math.max(minWidth, maxWidth);
            } else if (max != null) {
                this.suffixWidth = String.valueOf(max).length();
            } else if (min != null) {
                this.suffixWidth = String.valueOf(min).length();
            } else {
                this.suffixWidth = 4; // 默认4位
            }
        }
        
        /**
         * 格式化后缀数字为固定位数（确保字符串比较与数值比较一致）
         * 例如：999 -> "0999" (如果 width=4)
         */
        public String formatSuffix(int suffix) {
            return String.format("%0" + suffixWidth + "d", suffix);
        }
        
        /**
         * 将后缀数字格式化为完整值（prefix + 格式化后的后缀）
         */
        public String formatValue(int suffix) {
            return prefix + formatSuffix(suffix);
        }
    }
}


