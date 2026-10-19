package com.loopers.application.product;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * product.list.cache 카운터 6개(result 태그별)의 현재 값. 두 스냅샷의 차이로 "그 사이에 어떤 항목이 몇 번 늘었는지"를 본다.
 */
record CacheCounterSnapshot(Map<String, Double> counts) {

    static final String METRIC = "product.list.cache";
    static final List<String> RESULTS = List.of("hit", "miss", "hit_check_failed", "find_error", "save_error", "evict_error");

    static CacheCounterSnapshot take(MeterRegistry registry) {
        Map<String, Double> counts = new LinkedHashMap<>();
        for (String result : RESULTS) {
            counts.put(result, registry.counter(METRIC, "result", result).count());
        }
        return new CacheCounterSnapshot(counts);
    }

    /** 기대 변화량: 주어진 항목만 1, 나머지 0. */
    static Map<String, Double> only(String... results) {
        Map<String, Double> expected = none();
        for (String result : results) {
            expected.put(result, expected.get(result) + 1.0);
        }
        return expected;
    }

    static Map<String, Double> none() {
        Map<String, Double> expected = new LinkedHashMap<>();
        for (String result : RESULTS) {
            expected.put(result, 0.0);
        }
        return expected;
    }

    Map<String, Double> deltaTo(CacheCounterSnapshot after) {
        Map<String, Double> delta = new LinkedHashMap<>();
        for (String result : RESULTS) {
            delta.put(result, after.counts.get(result) - counts.get(result));
        }
        return delta;
    }
}
