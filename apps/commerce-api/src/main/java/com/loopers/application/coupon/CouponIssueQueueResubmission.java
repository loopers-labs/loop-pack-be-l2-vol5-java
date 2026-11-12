package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponIssueRequest;

/**
 * 시스템 실패로 끝난 작업을 사용자가 다시 접수했을 때의 처리 결과.
 * 작업이 여전히 같은 사유의 실패일 때만 바꾼다.
 */
public sealed interface CouponIssueQueueResubmission {

    /** 같은 작업 ID를 대기 상태로 바꾸고 대기 List 끝에 추가했다. */
    record Requeued(CouponIssueRequest request) implements CouponIssueQueueResubmission {
    }

    /** 그사이 발급된 쿠폰을 확인해 같은 작업을 성공으로 바꿨다. */
    record Succeeded(CouponIssueRequest request) implements CouponIssueQueueResubmission {
    }

    /** 상태나 사유가 그사이 바뀌어 손대지 않았다. 현재 작업을 함께 돌려준다. */
    record Changed(CouponIssueRequest request) implements CouponIssueQueueResubmission {
    }

    /** 미완료 작업이 한도에 차서 다시 대기시키지 않았다. */
    record Full(CouponIssueRequest request) implements CouponIssueQueueResubmission {
    }
}
