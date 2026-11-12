package com.loopers.application.coupon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponIssueQueuePropertiesTest {

    @DisplayName("worker 설정이 없으면 꺼진 상태로 스레드 3개, 확인 주기 100ms, 종료 대기 30초를 쓴다.")
    @Test
    void usesDefaultWorkerSettings() {
        CouponIssueQueueProperties properties = new CouponIssueQueueProperties(20, null);

        assertThat(properties.worker().enabled()).isFalse();
        assertThat(properties.worker().concurrency()).isEqualTo(3);
        assertThat(properties.worker().pollInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.worker().shutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @DisplayName("worker 수는 1 이상, 확인 주기는 0보다 커야 하고 종료 대기 시간은 음수일 수 없다.")
    @Test
    void rejectsInvalidWorkerSettings() {
        assertThatThrownBy(() -> new CouponIssueQueueProperties.Worker(true, 0, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CouponIssueQueueProperties.Worker(true, 1, Duration.ZERO, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CouponIssueQueueProperties.Worker(true, 1, null, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("대기열 한도는 1 이상이어야 한다.")
    @Test
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> new CouponIssueQueueProperties(0, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
