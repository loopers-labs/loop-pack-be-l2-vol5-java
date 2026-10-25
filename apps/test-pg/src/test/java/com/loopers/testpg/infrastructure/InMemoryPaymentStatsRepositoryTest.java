package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.PaymentStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPaymentStatsRepositoryTest {

    @DisplayName("주문별로 생성·조회 요청과 callback 시도를 따로 센다.")
    @Test
    void countsRequestsPerOrder() {
        InMemoryPaymentStatsRepository repository = new InMemoryPaymentStatsRepository();

        repository.recordCreateRequest("LP-ORD-0000000001");
        repository.recordLookupRequest("LP-ORD-0000000001");
        repository.recordLookupRequest("LP-ORD-0000000001");
        repository.recordCallbackAttempt("LP-ORD-0000000001");
        repository.recordCreateRequest("LP-ORD-0000000002");

        assertThat(repository.find("LP-ORD-0000000001")).isEqualTo(new PaymentStats(1, 2, 1));
        assertThat(repository.find("LP-ORD-0000000002")).isEqualTo(new PaymentStats(1, 0, 0));
        assertThat(repository.find("LP-ORD-0000000003")).isEqualTo(new PaymentStats(0, 0, 0));
    }

    @DisplayName("동시에 기록해도 호출 수를 잃지 않는다.")
    @Test
    void keepsCountsUnderConcurrentRecording() throws Exception {
        InMemoryPaymentStatsRepository repository = new InMemoryPaymentStatsRepository();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                executor.submit(() -> repository.recordCreateRequest("LP-ORD-0000000001"));
            }
        }

        assertThat(repository.find("LP-ORD-0000000001").createRequests()).isEqualTo(200);
    }
}
