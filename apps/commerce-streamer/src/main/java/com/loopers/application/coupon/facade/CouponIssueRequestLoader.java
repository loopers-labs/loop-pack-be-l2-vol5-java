package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.repository.CouponIssueRequestStore;
import com.loopers.domain.coupon.vo.CouponIssueRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 접수 topic 의 한 묶음을 DB 접수 기록(상태의 원본)에 한 TX 로 적재한다 (8주차 D-132) */
@Slf4j
@RequiredArgsConstructor
@Component
public class CouponIssueRequestLoader {

    private final CouponIssueRequestStore couponIssueRequestStore;

    /** @return 저장된 내용과 어긋난 접수. 다시 받아도 같으므로 재시도하지 않고 기록만 한다 */
    @Transactional
    public List<CouponIssueRequested> load(List<CouponIssueRequested> requests) {
        List<CouponIssueRequested> mismatched = couponIssueRequestStore.loadAll(requests);
        mismatched.forEach(request -> log.error(
            "접수 기록이 저장된 내용과 다름. 순번 배정을 확인할 대상. campaignId={} userId={} sequence={}",
            request.campaignId(), request.userId(), request.sequence()));
        return mismatched;
    }
}
