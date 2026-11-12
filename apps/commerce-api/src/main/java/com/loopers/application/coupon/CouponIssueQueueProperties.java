package com.loopers.application.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 쿠폰 발급 대기열 설정. 미완료 작업(대기, 처리 중, 결과 불명) 한도와 worker 실행 방식을 정한다.
 */
@ConfigurationProperties("coupon.issue.queue")
public record CouponIssueQueueProperties(Integer capacity, Worker worker) {

    public CouponIssueQueueProperties {
        if (capacity != null && capacity < 1) {
            throw new IllegalArgumentException("쿠폰 발급 대기열 한도는 1 이상이어야 합니다.");
        }
        if (worker == null) {
            worker = new Worker(false, null, null, null);
        }
    }

    public int requireCapacity() {
        if (capacity == null) {
            throw new IllegalStateException("쿠폰 발급 대기열 한도가 설정되지 않았습니다.");
        }
        return capacity;
    }

    /**
     * worker 실행 설정. 자동 실행은 기본으로 꺼져 있고, 켜도 신규 수용이 열린 동안에만 작업을 가져온다.
     *
     * @param enabled         앱 시작 뒤 전용 스레드로 worker를 실행할지 여부
     * @param concurrency     동시에 실행하는 전용 스레드 수
     * @param pollInterval    대기열이 비었거나 실행 자리가 없을 때 다시 확인하기까지 기다리는 시간
     * @param shutdownTimeout 종료할 때 실행 중인 작업을 기다리는 최대 시간
     */
    public record Worker(boolean enabled, Integer concurrency, Duration pollInterval, Duration shutdownTimeout) {

        public Worker {
            if (concurrency == null) {
                concurrency = 3;
            }
            if (pollInterval == null) {
                pollInterval = Duration.ofMillis(100);
            }
            if (shutdownTimeout == null) {
                shutdownTimeout = Duration.ofSeconds(30);
            }
            if (concurrency < 1) {
                throw new IllegalArgumentException("쿠폰 발급 대기열 worker 수는 1 이상이어야 합니다.");
            }
            if (pollInterval.isNegative() || pollInterval.isZero()) {
                throw new IllegalArgumentException("쿠폰 발급 대기열 확인 주기는 0보다 커야 합니다.");
            }
            if (shutdownTimeout.isNegative()) {
                throw new IllegalArgumentException("쿠폰 발급 대기열 종료 대기 시간은 음수일 수 없습니다.");
            }
        }
    }
}
