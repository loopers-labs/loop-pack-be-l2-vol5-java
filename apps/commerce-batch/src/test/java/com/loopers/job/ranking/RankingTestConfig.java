package com.loopers.job.ranking;

import com.loopers.domain.ranking.ScoreProcessingHook;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicReference;

/** 모든 주간 랭킹 batch 테스트가 같은 컨텍스트를 공유하도록 함께 import 하는 설정. 실패 hook 하나를 둔다. */
@TestConfiguration
public class RankingTestConfig {

    /**
     * 지정한 (snapshotId, productId) 의 처리 직전에 한 번만 IllegalStateException 을 던진다 (W6·W7 hook 예외 방식).
     * JobParameters 에 실패 스위치를 넣지 않기 위한 장치다. 제품 no-op 빈보다 우선하도록 @Primary.
     */
    public static final class FailOnceScoreHook implements ScoreProcessingHook {

        private record Target(String snapshotId, long productId) {}

        private final AtomicReference<Target> armed = new AtomicReference<>();

        public void armFailOnce(String snapshotId, long productId) {
            armed.set(new Target(snapshotId, productId));
        }

        @Override
        public void beforeProcess(String snapshotId, long productId) {
            Target target = armed.get();
            if (target != null && target.snapshotId().equals(snapshotId) && target.productId() == productId
                && armed.compareAndSet(target, null)) {
                throw new IllegalStateException("before third product");
            }
        }
    }

    @Primary
    @Bean
    public FailOnceScoreHook failOnceScoreHook() {
        return new FailOnceScoreHook();
    }
}
