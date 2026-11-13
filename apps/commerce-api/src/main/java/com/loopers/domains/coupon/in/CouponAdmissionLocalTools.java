package com.loopers.domains.coupon.in;

import com.loopers.domains.coupon.app.AdmissionCheckReport;
import com.loopers.domains.coupon.app.CouponAdmissionCheck;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬 전용 쿠폰 수용 재개 도구(D139). 공개 API를 두지 않고 앱 시작 인자로만 받는다.
// --coupon.admission.check : 아무 값도 바꾸지 않고 보고만 한다(D140). --coupon.admission.open : epoch +1로 연다(확인 결과를 강제하지 않음).
// 자동 재개는 없다. 사람이 확인 결과를 보고 연다.
@Slf4j
@Component
@Profile("local")
public class CouponAdmissionLocalTools implements ApplicationRunner {
    private static final String CHECK_OPTION = "coupon.admission.check";
    private static final String OPEN_OPTION = "coupon.admission.open";

    private final CouponAdmissionCheck couponAdmissionCheck;

    public CouponAdmissionLocalTools(CouponAdmissionCheck couponAdmissionCheck) {
        this.couponAdmissionCheck = couponAdmissionCheck;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(CHECK_OPTION)) {
            AdmissionCheckReport report = couponAdmissionCheck.check();
            log.info("쿠폰 수용 확인 1) 상태: open={}, closed_reason={}, closed_at={}, 닫힌 뒤 경과={}", report.open(),
                report.closedReason(), report.closedAt(), report.sinceClosed());
            log.info("쿠폰 수용 확인 2) 시간 기준 {}: {}", mark(report.time()), report.time().detail());
            log.info("쿠폰 수용 확인 3) DB 기준 {}: {}", mark(report.database()), report.database().detail());
            log.info("쿠폰 수용 확인 4) 대조 {}: {}", mark(report.reconcile()), report.reconcile().detail());
            log.info("쿠폰 수용 확인 5) 참고: epoch {} 키의 token 수 = {}", report.epoch(), report.holders());
            log.info("쿠폰 수용 확인 6) 결론: {}", report.safeToOpen() ? "열어도 됨" : "열지 말 것");
        }
        if (args.containsOption(OPEN_OPTION)) {
            CouponAdmissionCheck.Reopened reopened = couponAdmissionCheck.open();
            log.info("쿠폰 수용을 열었다. epoch {} → {}", reopened.previousEpoch(), reopened.newEpoch());
        }
    }

    private static String mark(AdmissionCheckReport.Criterion criterion) {
        return criterion.ok() ? "✓" : "✗";
    }
}
