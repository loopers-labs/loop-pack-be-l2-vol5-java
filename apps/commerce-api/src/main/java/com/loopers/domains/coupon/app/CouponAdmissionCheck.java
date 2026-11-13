package com.loopers.domains.coupon.app;

import com.loopers.domains.coupon.app.AdmissionCheckReport.Criterion;
import com.loopers.domains.coupon.app.port.AdmissionStatus;
import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.app.port.CouponAdmission;
import com.loopers.domains.coupon.app.port.CouponAdmissionInspection;
import com.loopers.domains.coupon.app.port.CouponAdmissionSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// 재개 확인·열기(D139·D140). 확인은 아무 값도 바꾸지 않고 보고만 한다. 열기는 확인 결과를 강제하지 않는다(사람이 판단).
// 시간 기준의 실행 최대 시간은 한 발급 실행이 걸릴 수 있는 상한(잠금 대기 3초 + 연결 대기 3초 + 여유)이다.
@Service
public class CouponAdmissionCheck {
    public record Reopened(long previousEpoch, long newEpoch, boolean changed) { }

    private final CouponAdmissionSettings couponAdmissionSettings;
    private final CouponAdmissionInspection couponAdmissionInspection;
    private final CouponAdmission couponAdmission;
    private final Clock clock;
    private final Duration maxExecution;

    public CouponAdmissionCheck(CouponAdmissionSettings couponAdmissionSettings,
        CouponAdmissionInspection couponAdmissionInspection, CouponAdmission couponAdmission, Clock clock,
        @Value("${commerce.coupon.admission.max-execution}") Duration maxExecution) {
        this.couponAdmissionSettings = couponAdmissionSettings;
        this.couponAdmissionInspection = couponAdmissionInspection;
        this.couponAdmission = couponAdmission;
        this.clock = clock;
        this.maxExecution = maxExecution;
    }

    // 시간·DB·대조가 모두 ✓일 때만 열어도 된다. 조회가 실패한 기준은 ✗다(모르면 열지 않는다).
    public AdmissionCheckReport check() {
        AdmissionStatus status = couponAdmissionSettings.status();
        Instant now = clock.instant();
        Duration sinceClosed = status.closedAt() == null ? null : Duration.between(status.closedAt(), now);
        Criterion time = timeCriterion(sinceClosed);
        Criterion database = databaseCriterion();
        Criterion reconcile = reconcileCriterion();
        Long holders = holders(status.epoch());
        boolean safeToOpen = time.ok() && database.ok() && reconcile.ok();
        return new AdmissionCheckReport(status.open(), status.closedReason(), status.closedAt(), sinceClosed, status.epoch(),
            time, database, reconcile, holders, safeToOpen);
    }

    public Reopened open() {
        long previous = couponAdmissionSettings.status().epoch();
        boolean changed = couponAdmissionSettings.open(clock.instant());
        return new Reopened(previous, couponAdmissionSettings.status().epoch(), changed);
    }

    private Criterion timeCriterion(Duration sinceClosed) {
        if (sinceClosed == null) {
            return new Criterion(false, "닫힌 시각 없음");
        }
        boolean ok = sinceClosed.compareTo(maxExecution) >= 0;
        return new Criterion(ok, "경과 " + sinceClosed.toSeconds() + "초 / 기준 " + maxExecution.toSeconds() + "초");
    }

    private Criterion databaseCriterion() {
        try {
            int transactions = couponAdmissionInspection.openCouponLockTransactions();
            return new Criterion(transactions == 0, "쿠폰 잠금을 쥔 열린 트랜잭션 " + transactions + "개");
        } catch (AdmissionUnavailableException e) {
            return new Criterion(false, "조회 실패(열지 말 것): " + e.getMessage());
        }
    }

    private Criterion reconcileCriterion() {
        try {
            List<Long> mismatches = couponAdmissionInspection.issuedCountMismatches();
            return new Criterion(mismatches.isEmpty(), mismatches.isEmpty() ? "발급 수 일치" : "발급 수 불일치 캠페인 " + mismatches);
        } catch (AdmissionUnavailableException e) {
            return new Criterion(false, "조회 실패(열지 말 것): " + e.getMessage());
        }
    }

    // 참고 값이라 조회하지 못하면 비워 둔다.
    private Long holders(long epoch) {
        try {
            return couponAdmission.holders(epoch);
        } catch (AdmissionUnavailableException e) {
            return null;
        }
    }
}
