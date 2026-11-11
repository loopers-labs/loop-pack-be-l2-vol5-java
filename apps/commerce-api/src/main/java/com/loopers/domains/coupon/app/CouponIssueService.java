package com.loopers.domains.coupon.app;

import com.loopers.common.error.CapacityExceededException;
import com.loopers.common.error.RuleViolationException;
import com.loopers.domains.coupon.app.port.AdmissionSettings;
import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.app.port.CouponAdmission;
import com.loopers.domains.coupon.app.port.CouponAdmissionSettings;
import com.loopers.domains.coupon.app.port.CouponIssueLookup;
import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.app.port.DuplicateIssueException;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 한정 수량 쿠폰 발급(29절). ① 잠금 없는 조회로 대부분을 끝내고, 새 발급만 ② 수용 설정 → ③ Redis 자리 → ④ 짧은 발급 트랜잭션 →
// ⑤ 자리 반환을 거친다. ③·⑤는 DB 트랜잭션 밖이다(거절될 요청이 DB 연결을 잡지 않고, 커밋 전에 자리가 비지 않는다).
// 발급 판단은 DB와 Redis만 쓴다(JVM 메모리 상태 없음).
@Slf4j
@Service
public class CouponIssueService {
    // 품절·비활성은 409 하나로 묶고 메시지도 하나다(D126).
    static final String NOT_ISSUABLE = "쿠폰을 발급할 수 없습니다. 캠페인이 비활성이거나 수량이 모두 발급되었습니다.";
    // 429·503 메시지에는 나중 발급을 약속하는 표현을 넣지 않는다(D129·D135).
    static final String NO_CAPACITY = "지금은 쿠폰 발급 요청을 받을 자리가 없습니다.";
    static final String ADMISSION_CLOSED = "지금은 쿠폰 발급 요청을 받지 않습니다.";

    public record IssueResult(IssuedCouponView coupon, boolean newlyIssued) { }

    private final CouponRepository couponRepository;
    private final CouponAdmission couponAdmission;
    private final CouponAdmissionSettings couponAdmissionSettings;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CouponIssueService(CouponRepository couponRepository, CouponAdmission couponAdmission,
        CouponAdmissionSettings couponAdmissionSettings, TransactionTemplate transactionTemplate, Clock clock) {
        this.couponRepository = couponRepository;
        this.couponAdmission = couponAdmission;
        this.couponAdmissionSettings = couponAdmissionSettings;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    // 판정 순서: 캠페인 없음(404) → 이미 받은 쿠폰(200, 비활성·품절이어도) → 발급 불가(409) → 발급.
    public IssueResult issue(long campaignId, long userId) {
        CouponIssueLookup lookup = lookup(campaignId, userId);
        if (lookup.coupon().isPresent()) {
            return new IssueResult(IssuedCouponView.from(lookup.coupon().get()), false);
        }
        if (!lookup.campaign().canIssue()) {
            throw new RuleViolationException(NOT_ISSUABLE);
        }
        int capacity = openCapacity();
        String token = UUID.randomUUID().toString();
        acquire(token, capacity);
        try {
            return issueInTransaction(campaignId, userId);
        } finally {
            release(token);
        }
    }

    public List<IssuedCouponView> listMine(long userId) {
        return couponRepository.findByUser(userId).stream().map(IssuedCouponView::from).toList();
    }

    // ② 닫혀 있거나 설정을 읽을 수 없으면 503이다. capacity는 HTTP 입력이 아니라 이 행의 값이다.
    private int openCapacity() {
        AdmissionSettings settings;
        try {
            settings = couponAdmissionSettings.current();
        } catch (AdmissionUnavailableException e) {
            log.error("쿠폰 수용 설정을 읽지 못해 거절한다", e);
            throw new AdmissionClosedException(ADMISSION_CLOSED, e);
        }
        if (!settings.open()) {
            throw new AdmissionClosedException(ADMISSION_CLOSED);
        }
        return settings.capacity();
    }

    // ③ 자리가 없으면 429다. Redis를 확인할 수 없으면 수용을 닫고(이유·시각) 503이며 발급 트랜잭션은 시작하지 않는다(D123).
    private void acquire(String token, int capacity) {
        boolean acquired;
        try {
            acquired = couponAdmission.acquire(token, capacity);
        } catch (AdmissionUnavailableException e) {
            log.error("쿠폰 수용 자리 획득 실패. 수용을 닫는다", e);
            closeAdmission("acquire 실패: " + e.getMessage());
            throw new AdmissionClosedException(ADMISSION_CLOSED, e);
        }
        if (!acquired) {
            throw new CapacityExceededException(NO_CAPACITY);
        }
    }

    // ⑤ 결과가 정해진 뒤라 반환 실패는 응답을 바꾸지 않는다. 로그를 남기고 수용을 닫는다(D134).
    private void release(String token) {
        try {
            couponAdmission.release(token);
        } catch (AdmissionUnavailableException e) {
            log.error("쿠폰 수용 자리 반환 실패. 수용을 닫는다. token={}", token, e);
            closeAdmission("release 실패: " + e.getMessage());
        }
    }

    // 닫기마저 실패해도 원래 결과·오류를 가리지 않는다(D134).
    private void closeAdmission(String reason) {
        try {
            couponAdmissionSettings.close(reason, clock.instant());
        } catch (RuntimeException e) {
            log.error("쿠폰 수용 닫기 실패. reason={}", reason, e);
        }
    }

    private CouponIssueLookup lookup(long campaignId, long userId) {
        return couponRepository.findForIssue(campaignId, userId)
            .orElseThrow(() -> new CouponCampaignNotFoundException(campaignId));
    }

    // 조건부 UPDATE가 0행이면 409다(①과 ④ 사이에 다른 요청이 마지막 수량을 가져갔다).
    // 같은 사용자의 동시 요청은 유일 제약이 하나만 통과시킨다. 충돌한 쪽은 그 트랜잭션이 UPDATE까지 rollback된 뒤,
    // 트랜잭션 밖에서 다시 조회해 쿠폰이 있을 때만 200으로 바꾼다. 없으면 원래 예외를 그대로 올린다.
    private IssueResult issueInTransaction(long campaignId, long userId) {
        try {
            IssuedCoupon issued = transactionTemplate.execute(status -> {
                if (couponRepository.increaseIssued(campaignId) == 0) {
                    throw new RuleViolationException(NOT_ISSUABLE);
                }
                return couponRepository.insert(campaignId, userId, clock.instant());
            });
            return new IssueResult(IssuedCouponView.from(issued), true);
        } catch (DuplicateIssueException e) {
            Optional<IssuedCoupon> existing = couponRepository.findForIssue(campaignId, userId)
                .flatMap(CouponIssueLookup::coupon);
            if (existing.isPresent()) {
                return new IssueResult(IssuedCouponView.from(existing.get()), false);
            }
            throw e;
        }
    }
}
