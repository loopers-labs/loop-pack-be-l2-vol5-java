package com.loopers.coupon.application.port.out;

import java.util.List;

/**
 * 발급 consumer가 재시도를 다 쓰고 보낸 DLT(coupon-issue-requests.DLT)를 처음부터 끝까지 읽는다 (D-2: DLT는 관리자 재처리).
 */
public interface CouponDeadLetterPort {

    List<DeadLetter> readAll();

    record DeadLetter(String key, String value) {}
}
