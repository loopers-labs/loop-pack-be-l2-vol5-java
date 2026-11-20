package com.loopers.job.ranking;

import com.loopers.domain.ranking.ProductScore;
import org.springframework.batch.core.ItemProcessListener;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicLong;

/** 모든 주간 랭킹 batch 테스트가 같은 컨텍스트를 공유하도록 함께 import 하는 설정. 실패 리스너 하나를 둔다. */
@TestConfiguration
public class RankingTestConfig {

    /**
     * 지정한 productId 의 processor 직전({@link ItemProcessListener#beforeProcess})에 한 번만 IllegalStateException 을 던진다
     * (W6·W7 hook 예외 방식). JobParameters 에 실패 스위치를 넣지 않기 위한 장치다. 제품에는 이 타입의 빈이 없고
     * WeeklyRankingJobConfig 가 ObjectProvider 로 선택 주입받아 Step 2 에 등록한다.
     */
    public static final class FailOnceProcessListener implements ItemProcessListener<Long, ProductScore> {

        private static final long DISARMED = Long.MIN_VALUE;

        private final AtomicLong armed = new AtomicLong(DISARMED);

        public void armFailOnce(long productId) {
            armed.set(productId);
        }

        @Override
        public void beforeProcess(Long productId) {
            if (armed.compareAndSet(productId, DISARMED)) {
                throw new IllegalStateException("before third product");
            }
        }

        @Override
        public void afterProcess(Long item, ProductScore result) {
        }

        @Override
        public void onProcessError(Long item, Exception e) {
        }
    }

    @Bean
    public FailOnceProcessListener failOnceProcessListener() {
        return new FailOnceProcessListener();
    }
}
