package com.loopers.application.shopping.service;

import com.loopers.application.shopping.dao.LikeCountAggregationDao;
import com.loopers.application.shopping.usecase.LikeCountAggregationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 좋아요 수 전체 재집계 유스케이스 구현체
public class LikeCountAggregationService implements LikeCountAggregationUseCase {
    private final LikeCountAggregationDao aggregationDao;

    // 전체 재집계
    @Override
    @Transactional
    public void execute() {
        aggregationDao.recountAll();
    }
}
