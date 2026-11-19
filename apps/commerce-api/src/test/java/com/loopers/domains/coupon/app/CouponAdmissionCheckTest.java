package com.loopers.domains.coupon.app;

import com.loopers.domains.coupon.app.port.AdmissionSettings;
import com.loopers.domains.coupon.app.port.AdmissionStatus;
import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.app.port.CouponAdmission;
import com.loopers.domains.coupon.app.port.CouponAdmissionInspection;
import com.loopers.domains.coupon.app.port.CouponAdmissionSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 재개 확인 모드의 판정(D140). 포트만 fake로 두고 판정 계산을 본다. 확인은 아무 값도 바꾸지 않는다.
class CouponAdmissionCheckTest {
    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    private static final Duration MAX_EXECUTION = Duration.ofSeconds(10);

    @Test
    @DisplayName("닫힌 뒤 실행 최대 시간이 지나지 않았으면 시간 기준이 ✗라 열면 안 된다")
    void notEnoughTimeSinceClosed() {
        Fakes fakes = new Fakes(NOW.minusSeconds(3), 0, List.of(), false);

        AdmissionCheckReport report = fakes.check().check();

        assertThat(report.time().ok()).isFalse();
        assertThat(report.sinceClosed()).isEqualTo(Duration.ofSeconds(3));
        assertThat(report.safeToOpen()).isFalse();
        assertThat(fakes.writes.get()).isZero();
    }

    @Test
    @DisplayName("쿠폰 테이블 잠금을 쥔 열린 트랜잭션이 있으면 DB 기준이 ✗라 열면 안 된다")
    void openTransactionHoldsCouponLock() {
        Fakes fakes = new Fakes(NOW.minusSeconds(60), 1, List.of(), false);

        AdmissionCheckReport report = fakes.check().check();

        assertThat(report.time().ok()).isTrue();
        assertThat(report.database().ok()).isFalse();
        assertThat(report.safeToOpen()).isFalse();
        assertThat(fakes.writes.get()).isZero();
    }

    @Test
    @DisplayName("잠금 조회가 실패하면(권한 포함) DB 기준을 ✗로 보고 열지 말라고 한다")
    void lockQueryFailureIsNotSafe() {
        Fakes fakes = new Fakes(NOW.minusSeconds(60), 0, List.of(), true);

        AdmissionCheckReport report = fakes.check().check();

        assertThat(report.database().ok()).isFalse();
        assertThat(report.database().detail()).contains("조회 실패");
        assertThat(report.safeToOpen()).isFalse();
    }

    @Test
    @DisplayName("발급 수와 실제 쿠폰 수가 다른 캠페인이 있으면 대조 기준이 ✗라 열면 안 된다")
    void issuedCountMismatchIsNotSafe() {
        Fakes fakes = new Fakes(NOW.minusSeconds(60), 0, List.of(7L), false);

        AdmissionCheckReport report = fakes.check().check();

        assertThat(report.reconcile().ok()).isFalse();
        assertThat(report.reconcile().detail()).contains("7");
        assertThat(report.safeToOpen()).isFalse();
    }

    @Test
    @DisplayName("시간·DB·대조가 모두 ✓이면 열어도 된다고 보고하고 현재 epoch 키의 token 수를 참고로 보이며 아무 값도 바꾸지 않는다")
    void allCriteriaPassIsSafe() {
        Fakes fakes = new Fakes(NOW.minusSeconds(60), 0, List.of(), false);

        AdmissionCheckReport report = fakes.check().check();

        assertThat(report.time().ok()).isTrue();
        assertThat(report.database().ok()).isTrue();
        assertThat(report.reconcile().ok()).isTrue();
        assertThat(report.epoch()).isEqualTo(4);
        assertThat(report.holders()).isEqualTo(2L);
        assertThat(report.closedReason()).isEqualTo("acquire 실패");
        assertThat(report.safeToOpen()).isTrue();
        assertThat(fakes.writes.get()).isZero();
    }

    private static class Fakes implements CouponAdmissionSettings, CouponAdmissionInspection, CouponAdmission {
        private final Instant closedAt;
        private final int lockTransactions;
        private final List<Long> mismatches;
        private final boolean lockQueryFails;
        private final AtomicInteger writes = new AtomicInteger();

        Fakes(Instant closedAt, int lockTransactions, List<Long> mismatches, boolean lockQueryFails) {
            this.closedAt = closedAt;
            this.lockTransactions = lockTransactions;
            this.mismatches = mismatches;
            this.lockQueryFails = lockQueryFails;
        }

        CouponAdmissionCheck check() {
            return new CouponAdmissionCheck(this, this, this, Clock.fixed(NOW, ZoneOffset.UTC), MAX_EXECUTION);
        }

        @Override
        public AdmissionSettings current() {
            return new AdmissionSettings(3, false, 4);
        }

        @Override
        public AdmissionStatus status() {
            return new AdmissionStatus(false, "acquire 실패", closedAt, 4);
        }

        @Override
        public void close(String reason, Instant at) {
            writes.incrementAndGet();
        }

        @Override
        public boolean open(Instant at) {
            writes.incrementAndGet();
            return true;
        }

        @Override
        public int openCouponLockTransactions() {
            if (lockQueryFails) {
                throw new AdmissionUnavailableException("주입된 잠금 조회 실패");
            }
            return lockTransactions;
        }

        @Override
        public List<Long> issuedCountMismatches() {
            return mismatches;
        }

        @Override
        public boolean acquire(long epoch, String token, int capacity) {
            writes.incrementAndGet();
            return true;
        }

        @Override
        public void release(long epoch, String token) {
            writes.incrementAndGet();
        }

        @Override
        public long holders(long epoch) {
            return epoch == 4 ? 2 : 0;
        }
    }
}
