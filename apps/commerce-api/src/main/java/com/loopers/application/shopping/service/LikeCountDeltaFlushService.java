package com.loopers.application.shopping.service;

import com.loopers.application.shopping.dao.LikeCountAggregationDao;
import com.loopers.application.shopping.usecase.FlushLikeCountDeltaUseCase;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 누적된 좋아요 증감분을 집계 테이블에 반영하는 유스케이스 구현체
public class LikeCountDeltaFlushService implements FlushLikeCountDeltaUseCase {
    private final LikeCountDeltaBuffer buffer;
    private final LikeCountAggregationDao aggregationDao;

    // 버퍼를 비워 배치 upsert로 반영하고, 실패하면 증감분을 버퍼에 되돌린 뒤 예외를 전파
    @Override
    @Transactional
    public void execute() {
        Map<Long, Long> deltas = buffer.drain();
        if (deltas.isEmpty()) {
            return;
        }
        try {
            aggregationDao.addDeltas(deltas);
        } catch (RuntimeException exception) {
            buffer.restore(deltas);
            throw exception;
        }
    }
}
