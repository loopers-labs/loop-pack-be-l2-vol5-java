package com.loopers.application.shopping.dao;

import java.util.Map;

// 좋아요 수 집계용 DAO
public interface LikeCountAggregationDao {
    // 전체 좋아요 관계를 다시 세어 상품의 좋아요 수를 맞춘다 (관계가 없는 상품은 0)
    void recountAll();

    // 상품별 증감분을 상품의 좋아요 수에 더한다 (없는 상품은 무시, 결과는 0 미만이 되지 않음)
    void addDeltas(Map<Long, Long> deltas);
}
