package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;

/**
 * permit을 얻은 뒤 실행한 발급 한 번의 결과.
 * 동기 API는 응답으로 바꾸고, 대기열 worker는 작업 결과로 기록한다.
 */
public sealed interface CouponIssueExecution {

    /** 새로 발급했고 commit이 끝났다. */
    record Issued(CouponIssueInfo coupon) implements CouponIssueExecution {
    }

    /** 같은 사용자의 중복 충돌로 rollback됐고, 새 조회로 기존 쿠폰을 찾았다. */
    record AlreadyIssued(Coupon coupon) implements CouponIssueExecution {
    }

    /**
     * 발급하지 못했거나 결과를 확인하지 못했다.
     * {@code dbWorkDone}이 false이면 DB 작업의 종료를 확인하지 못해 permit을 반환하지 않은 실행이다.
     */
    record Failed(RuntimeException failure, boolean dbWorkDone) implements CouponIssueExecution {
    }
}
