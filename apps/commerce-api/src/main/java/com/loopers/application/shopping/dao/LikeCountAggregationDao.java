package com.loopers.application.shopping.dao;

import java.util.Map;

// 좋아요 수 집계용 DAO
public interface LikeCountAggregationDao {
    void resetAllCounts();

    void aggregateAllCounts();

    // 상품별 증감분을 집계에 더한다 (행이 없으면 생성, 결과는 0 미만이 되지 않음)
    void addDeltas(Map<Long, Long> deltas);
}
