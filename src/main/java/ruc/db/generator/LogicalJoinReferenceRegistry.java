package ruc.db.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import ruc.db.generator.constraintchain.ConstraintChain;
import ruc.db.generator.constraintchain.agg.AggregateValueModel;

/**
 * Generation-only references for non PK/FK equality joins.
 *
 * <p>These edges are intentionally kept out of schema foreignKeys so they do not
 * affect physical DDL/topological dependency sorting, while still giving the
 * join-key generator a referenced RuleTable/domain.</p>
 */
public final class LogicalJoinReferenceRegistry {
    private static final long DEFAULT_MAX_RETAINED_VALUES_PER_REF = 2_000_000L;
    private static final Map<String, String> LOCAL_TO_REF = new LinkedHashMap<>();
    private static final Map<String, List<Reference>> REF_TO_REFERENCES = new LinkedHashMap<>();
    private static final Map<String, List<ValueBlock>> REF_VALUES = new LinkedHashMap<>();
    private static final Map<String, java.util.Set<String>> LOCAL_MATCH_VALUES = new LinkedHashMap<>();
    private static final Map<String, Map<String, Long>> LOCAL_MATCH_VALUE_COUNTS = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> LOCAL_MATCH_VALUE_REPRESENTATIVES = new LinkedHashMap<>();
    // These caches are populated lazily during parallel FK generation, so they must be thread-safe.
    private static final ConcurrentMap<String, MultiplicityKeys> LOCAL_MULTIPLICITY_KEYS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, MatchValueCatalog> LOCAL_MATCH_VALUE_CATALOGS = new ConcurrentHashMap<>();
    private static long maxRetainedValuesPerRef = DEFAULT_MAX_RETAINED_VALUES_PER_REF;

    private LogicalJoinReferenceRegistry() {
    }

    public static void clear() {
        LOCAL_TO_REF.clear();
        REF_TO_REFERENCES.clear();
        REF_VALUES.clear();
        LOCAL_MATCH_VALUES.clear();
        LOCAL_MATCH_VALUE_COUNTS.clear();
        LOCAL_MATCH_VALUE_REPRESENTATIVES.clear();
        LOCAL_MULTIPLICITY_KEYS.clear();
        LOCAL_MATCH_VALUE_CATALOGS.clear();
        maxRetainedValuesPerRef = DEFAULT_MAX_RETAINED_VALUES_PER_REF;
    }

    static void setMaxRetainedValuesPerRefForTesting(long value) {
        maxRetainedValuesPerRef = Math.max(1L, value);
    }

    public static void register(String localCol, String refCol, int joinTag, ConstraintChain refChain) {
        if (localCol == null || localCol.isBlank() || refCol == null || refCol.isBlank()) {
            return;
        }
        String previous = LOCAL_TO_REF.putIfAbsent(localCol, refCol);
        if (previous != null && !previous.equals(refCol)) {
            throw new IllegalStateException("Logical join local column " + localCol
                    + " references both " + previous + " and " + refCol);
        }
        List<Reference> refs = REF_TO_REFERENCES.computeIfAbsent(refCol, k -> new ArrayList<>());
        Reference candidate = new Reference(localCol, refCol, joinTag, refChain);
        if (!refs.contains(candidate)) {
            refs.add(candidate);
            refs.sort((a, b) -> Integer.compare(a.joinTag(), b.joinTag()));
        }
    }

    public static String getRefKey(String localCol) {
        return LOCAL_TO_REF.get(localCol);
    }

    public static void rememberReferenceValues(String refCol, Object[] values, long indexStart) {
        rememberReferenceValues(refCol, values, indexStart, Map.of());
    }

    public static void rememberReferenceValues(String refCol, Object[] values, long indexStart,
                                               Map<String, java.util.Set<String>> matchValuesByLocalCol) {
        rememberReferenceValues(refCol, values, indexStart, matchValuesByLocalCol, Map.of());
    }

    public static void rememberReferenceValues(String refCol, Object[] values, long indexStart,
                                               Map<String, java.util.Set<String>> matchValuesByLocalCol,
                                               Map<String, Map<String, Long>> matchValueCountsByLocalCol) {
        if (matchValuesByLocalCol != null) {
            for (Map.Entry<String, java.util.Set<String>> e : matchValuesByLocalCol.entrySet()) {
                LOCAL_MATCH_VALUES.computeIfAbsent(e.getKey(), k -> new java.util.HashSet<>()).addAll(e.getValue());
            }
        }
        if (matchValueCountsByLocalCol != null) {
            for (Map.Entry<String, Map<String, Long>> e : matchValueCountsByLocalCol.entrySet()) {
                Map<String, Long> counts = LOCAL_MATCH_VALUE_COUNTS.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
                for (Map.Entry<String, Long> valueCount : e.getValue().entrySet()) {
                    counts.merge(valueCount.getKey(), valueCount.getValue(), Long::sum);
                }
                rememberRepresentativeValues(e.getKey(), values, e.getValue());
                LOCAL_MULTIPLICITY_KEYS.remove(e.getKey());
                LOCAL_MATCH_VALUE_CATALOGS.remove(e.getKey());
            }
        }
        if (refCol == null || values == null || values.length == 0) {
            return;
        }
        List<ValueBlock> blocks = REF_VALUES.computeIfAbsent(refCol, k -> new ArrayList<>());
        long retained = 0L;
        for (ValueBlock block : blocks) {
            retained += block.values.length;
        }
        if (retained >= maxRetainedValuesPerRef) {
            return;
        }
        int keep = (int) Math.min(values.length, maxRetainedValuesPerRef - retained);
        Object[] snapshot = new Object[keep];
        System.arraycopy(values, 0, snapshot, 0, keep);
        blocks.add(new ValueBlock(indexStart, snapshot));
        if (matchValueCountsByLocalCol != null) {
            for (String localCol : matchValueCountsByLocalCol.keySet()) {
                LOCAL_MULTIPLICITY_KEYS.remove(localCol);
            }
        }
    }

    public static Object resolveReferenceValue(String localCol, long logicalKey) {
        String refCol = getRefKey(localCol);
        if (refCol == null || logicalKey < 0) {
            return null;
        }
        List<ValueBlock> blocks = REF_VALUES.get(refCol);
        if (blocks == null) {
            return null;
        }
        for (ValueBlock block : blocks) {
            long offset = logicalKey - block.indexStart;
            if (offset >= 0 && offset < block.values.length) {
                return block.values[(int) offset];
            }
        }
        return null;
    }

    public static Object fallbackMatchedReferenceValue(String localCol, long ordinal) {
        MatchValueCatalog catalog = LOCAL_MATCH_VALUE_CATALOGS.computeIfAbsent(localCol,
                LogicalJoinReferenceRegistry::buildMatchValueCatalog);
        if (catalog.values().length == 0) {
            return null;
        }
        int idx = Math.floorMod(ordinal, catalog.values().length);
        return catalog.values()[idx];
    }

    public static Object fallbackMatchedReferenceValue(String localCol, long ordinal,
                                                       long remainingExtraRows, long remainingMatchedRows) {
        MatchValueCatalog catalog = LOCAL_MATCH_VALUE_CATALOGS.computeIfAbsent(localCol,
                LogicalJoinReferenceRegistry::buildMatchValueCatalog);
        if (catalog.values().length == 0) {
            return null;
        }
        if (remainingMatchedRows <= 0L) {
            return fallbackMatchedReferenceValue(localCol, ordinal);
        }
        long desiredExtra = remainingExtraRows <= 0L
                ? 0L
                : Math.max(1L, (remainingExtraRows + remainingMatchedRows - 1L) / remainingMatchedRows);
        Object candidate = chooseCatalogValue(catalog, ordinal, desiredExtra, remainingExtraRows);
        if (candidate != null) {
            return candidate;
        }
        if (remainingExtraRows > 0L) {
            candidate = chooseCatalogValue(catalog, ordinal, 1L, remainingExtraRows);
            if (candidate != null) {
                return candidate;
            }
        }
        candidate = chooseCatalogValue(catalog, ordinal, 0L, 0L);
        return candidate != null ? candidate : fallbackMatchedReferenceValue(localCol, ordinal);
    }

    public static long remapToLowMultiplicityReferenceKey(String localCol, long logicalKey, int rowId) {
        String refCol = getRefKey(localCol);
        Map<String, Long> counts = LOCAL_MATCH_VALUE_COUNTS.get(localCol);
        if (refCol == null || counts == null || counts.isEmpty() || logicalKey < 0) {
            return logicalKey;
        }
        long currentMultiplicity = referenceMultiplicity(localCol, logicalKey);
        MultiplicityKeys keys = LOCAL_MULTIPLICITY_KEYS.computeIfAbsent(localCol,
                k -> buildMultiplicityKeys(refCol, counts));
        if (keys.keys().length == 0 || keys.lowMultiplicityKeyCount() <= 0) {
            return logicalKey;
        }
        int idx = Math.floorMod(rowId, keys.lowMultiplicityKeyCount());
        if (currentMultiplicity <= keys.minMultiplicity()) {
            return keys.keys()[idx];
        }
        return keys.keys()[idx];
    }

    public static long remapToControlledMultiplicityReferenceKey(String localCol, long logicalKey, int rowId,
                                                                 long remainingExtraRows, long remainingMatchedRows) {
        String refCol = getRefKey(localCol);
        Map<String, Long> counts = LOCAL_MATCH_VALUE_COUNTS.get(localCol);
        if (refCol == null || counts == null || counts.isEmpty() || logicalKey < 0) {
            return logicalKey;
        }
        MultiplicityKeys keys = LOCAL_MULTIPLICITY_KEYS.computeIfAbsent(localCol,
                k -> buildMultiplicityKeys(refCol, counts));
        if (keys.keys().length == 0) {
            return logicalKey;
        }
        if (remainingExtraRows <= 0L || remainingMatchedRows <= 0L) {
            return remapToLowMultiplicityReferenceKey(localCol, logicalKey, rowId);
        }

        List<Long> zeroExtraKeys = new ArrayList<>();
        long desiredExtra = Math.max(1L,
                (remainingExtraRows + remainingMatchedRows - 1L) / remainingMatchedRows);
        long bestExtra = Long.MAX_VALUE;
        List<Long> bestKeys = new ArrayList<>();
        for (int i = 0; i < keys.keys().length; i++) {
            long multiplicity = keys.multiplicities()[i];
            long extra = Math.max(0L, multiplicity - 1L);
            if (extra == 0L) {
                zeroExtraKeys.add(keys.keys()[i]);
                continue;
            }
            if (extra < desiredExtra || extra > remainingExtraRows) {
                continue;
            }
            if (extra < bestExtra) {
                bestExtra = extra;
                bestKeys.clear();
                bestKeys.add(keys.keys()[i]);
            } else if (extra == bestExtra) {
                bestKeys.add(keys.keys()[i]);
            }
        }
        if (bestKeys.isEmpty()) {
            for (int i = 0; i < keys.keys().length; i++) {
                long multiplicity = keys.multiplicities()[i];
                long extra = Math.max(0L, multiplicity - 1L);
                if (extra <= 0L || extra > remainingExtraRows) {
                    continue;
                }
                if (extra < bestExtra) {
                    bestExtra = extra;
                    bestKeys.clear();
                    bestKeys.add(keys.keys()[i]);
                } else if (extra == bestExtra) {
                    bestKeys.add(keys.keys()[i]);
                }
            }
        }
        if (!bestKeys.isEmpty()) {
            int idx = Math.floorMod(rowId, bestKeys.size());
            return bestKeys.get(idx);
        }
        if (!zeroExtraKeys.isEmpty()) {
            int idx = Math.floorMod(rowId, zeroExtraKeys.size());
            return zeroExtraKeys.get(idx);
        }
        return remapToLowMultiplicityReferenceKey(localCol, logicalKey, rowId);
    }

    public static long referenceMultiplicity(String localCol, long logicalKey) {
        Map<String, Long> counts = LOCAL_MATCH_VALUE_COUNTS.get(localCol);
        if (counts == null || counts.isEmpty() || logicalKey < 0) {
            return 0L;
        }
        Object value = resolveReferenceValue(localCol, logicalKey);
        return value == null ? 0L : counts.getOrDefault(String.valueOf(value), 0L);
    }

    public static long matchedValueMultiplicity(String localCol, Object value) {
        Map<String, Long> counts = LOCAL_MATCH_VALUE_COUNTS.get(localCol);
        if (counts == null || counts.isEmpty() || value == null) {
            return 0L;
        }
        return counts.getOrDefault(String.valueOf(value), 0L);
    }

    private static MultiplicityKeys buildMultiplicityKeys(String refCol, Map<String, Long> counts) {
        List<ValueBlock> blocks = REF_VALUES.get(refCol);
        if (blocks == null || blocks.isEmpty()) {
            return new MultiplicityKeys(new long[0], new long[0], Long.MAX_VALUE, 0);
        }
        List<MultiplicityKey> candidates = new ArrayList<>();
        Map<String, Long> representativeKeyByValue = new LinkedHashMap<>();
        long min = Long.MAX_VALUE;
        for (ValueBlock block : blocks) {
            for (int i = 0; i < block.values.length; i++) {
                Object value = block.values[i];
                if (value == null) {
                    continue;
                }
                long multiplicity = counts.getOrDefault(String.valueOf(value), 0L);
                if (multiplicity <= 0L) {
                    continue;
                }
                String valueKey = String.valueOf(value);
                long logicalKey = block.indexStart + i;
                representativeKeyByValue.putIfAbsent(valueKey, logicalKey);
            }
        }
        for (Map.Entry<String, Long> entry : representativeKeyByValue.entrySet()) {
            long multiplicity = counts.getOrDefault(entry.getKey(), 0L);
            if (multiplicity <= 0L) {
                continue;
            }
            candidates.add(new MultiplicityKey(entry.getValue(), multiplicity));
            min = Math.min(min, multiplicity);
        }
        candidates.sort((a, b) -> {
            int c = Long.compare(a.multiplicity(), b.multiplicity());
            return c != 0 ? c : Long.compare(a.key(), b.key());
        });
        long[] keys = new long[candidates.size()];
        long[] multiplicities = new long[candidates.size()];
        int lowCount = 0;
        for (int i = 0; i < candidates.size(); i++) {
            MultiplicityKey candidate = candidates.get(i);
            keys[i] = candidate.key();
            multiplicities[i] = candidate.multiplicity();
            if (candidate.multiplicity() == min) {
                lowCount++;
            }
        }
        return new MultiplicityKeys(keys, multiplicities, min, lowCount);
    }


    public static long countMatchingReferenceValues(String localCol, Object[] values) {
        java.util.Set<String> matchValues = LOCAL_MATCH_VALUES.get(localCol);
        if (matchValues == null || values == null) {
            return 0L;
        }
        long count = 0L;
        for (Object value : values) {
            if (value != null && matchValues.contains(String.valueOf(value))) {
                count++;
            }
        }
        return count;
    }

    public static int matchingReferenceDomainSize(String localCol) {
        java.util.Set<String> matchValues = LOCAL_MATCH_VALUES.get(localCol);
        return matchValues == null ? 0 : matchValues.size();
    }

    public static long countInnerJoinRowsWithReferenceMultiplicity(String localCol, Object[] values) {
        return AggregateValueModel.sumCountForInnerJoin(values, LOCAL_MATCH_VALUE_COUNTS.get(localCol));
    }

    public static long countLeftJoinRowsWithReferenceMultiplicity(String localCol, Object[] values) {
        return sumCoalescedCountForLeftJoin(localCol, values, 1L);
    }

    public static long sumCoalescedCountForLeftJoin(String localCol, Object[] values, long defaultValue) {
        return AggregateValueModel.sumCoalescedCountForLeftJoin(
                values,
                LOCAL_MATCH_VALUE_COUNTS.get(localCol),
                defaultValue);
    }

    public static Map<String, Long> countRowsPerMatchedGroup(String localCol) {
        Map<String, Long> counts = LOCAL_MATCH_VALUE_COUNTS.get(localCol);
        return counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    public static List<Reference> getReferencesForTable(String tableName) {
        if (tableName == null) {
            return List.of();
        }
        List<Reference> out = new ArrayList<>();
        for (Map.Entry<String, List<Reference>> e : REF_TO_REFERENCES.entrySet()) {
            if (tableName.equals(canonicalTableName(e.getKey()))) {
                out.addAll(e.getValue());
            }
        }
        out.sort((a, b) -> {
            int c = a.refCol().compareTo(b.refCol());
            return c != 0 ? c : Integer.compare(a.joinTag(), b.joinTag());
        });
        return Collections.unmodifiableList(out);
    }

    public static String canonicalTableName(String canonicalColumnName) {
        if (canonicalColumnName == null) {
            return null;
        }
        String[] parts = canonicalColumnName.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        return parts[0] + "." + parts[1];
    }

    private record ValueBlock(long indexStart, Object[] values) {
    }

    private record MultiplicityKey(long key, long multiplicity) {
    }

    private record MultiplicityKeys(long[] keys, long[] multiplicities, long minMultiplicity,
                                    int lowMultiplicityKeyCount) {
    }

    private record MatchValue(long multiplicity, String valueKey, Object value) {
    }

    private record MatchValueCatalog(Object[] values, long[] multiplicities) {
    }

    private static void rememberRepresentativeValues(String localCol, Object[] values, Map<String, Long> counts) {
        if (localCol == null || values == null || counts == null || counts.isEmpty()) {
            return;
        }
        Map<String, Object> representatives =
                LOCAL_MATCH_VALUE_REPRESENTATIVES.computeIfAbsent(localCol, k -> new LinkedHashMap<>());
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String valueKey = String.valueOf(value);
            if (counts.containsKey(valueKey)) {
                representatives.putIfAbsent(valueKey, value);
            }
        }
    }

    private static MatchValueCatalog buildMatchValueCatalog(String localCol) {
        Map<String, Long> counts = LOCAL_MATCH_VALUE_COUNTS.get(localCol);
        Map<String, Object> representatives = LOCAL_MATCH_VALUE_REPRESENTATIVES.get(localCol);
        if (counts == null || counts.isEmpty() || representatives == null || representatives.isEmpty()) {
            return new MatchValueCatalog(new Object[0], new long[0]);
        }
        List<MatchValue> candidates = new ArrayList<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            Object value = representatives.get(entry.getKey());
            if (value == null) {
                continue;
            }
            candidates.add(new MatchValue(entry.getValue(), entry.getKey(), value));
        }
        candidates.sort((a, b) -> {
            int c = Long.compare(a.multiplicity(), b.multiplicity());
            return c != 0 ? c : a.valueKey().compareTo(b.valueKey());
        });
        Object[] values = new Object[candidates.size()];
        long[] multiplicities = new long[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            values[i] = candidates.get(i).value();
            multiplicities[i] = candidates.get(i).multiplicity();
        }
        return new MatchValueCatalog(values, multiplicities);
    }

    private static Object chooseCatalogValue(MatchValueCatalog catalog, long ordinal,
                                             long minExtra, long maxExtra) {
        int count = 0;
        for (int i = 0; i < catalog.values().length; i++) {
            long extra = Math.max(0L, catalog.multiplicities()[i] - 1L);
            if (extra >= minExtra && extra <= maxExtra) {
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        int target = Math.floorMod(ordinal, count);
        int seen = 0;
        for (int i = 0; i < catalog.values().length; i++) {
            long extra = Math.max(0L, catalog.multiplicities()[i] - 1L);
            if (extra < minExtra || extra > maxExtra) {
                continue;
            }
            if (seen == target) {
                return catalog.values()[i];
            }
            seen++;
        }
        return null;
    }

    public static final class Reference {
        private final String localCol;
        private final String refCol;
        private final int joinTag;
        private final ConstraintChain refChain;

        private Reference(String localCol, String refCol, int joinTag, ConstraintChain refChain) {
            this.localCol = localCol;
            this.refCol = refCol;
            this.joinTag = joinTag;
            this.refChain = refChain;
        }

        public String localCol() {
            return localCol;
        }

        public String refCol() {
            return refCol;
        }

        public int joinTag() {
            return joinTag;
        }

        public ConstraintChain refChain() {
            return refChain;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Reference that)) {
                return false;
            }
            return joinTag == that.joinTag
                    && Objects.equals(localCol, that.localCol)
                    && Objects.equals(refCol, that.refCol)
                    && refChain == that.refChain;
        }

        @Override
        public int hashCode() {
            return Objects.hash(localCol, refCol, joinTag, System.identityHashCode(refChain));
        }
    }
}
