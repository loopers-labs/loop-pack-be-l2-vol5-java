package com.loopers.application.coupon;

import java.util.Set;

/**
 * 동시 처리 자리 장부 포트(docs/week8/decisions.md INV-803, INV-804, D-3, D-4). 구현은 infrastructure/coupon의 Redis다.
 *
 * <p>열림/닫힘 판단과 오류 처리는 {@link CouponAdmission}이 한다. 이 포트는 장부 명령만 한다 — 장부에 닿지 못했거나 응답이
 * 불확실하면(연결 거절, timeout) 예외를 그대로 던진다. 결과를 추측해 수용·거절로 바꾸지 않는다.
 */
public interface AdmissionPermits {

    /** 같은 token이 이미 있거나 빈자리가 있으면 token을 추가하고 true. 자리가 없으면 false. 확인과 추가는 한 번에 한다. */
    boolean tryAcquire(String token, int capacity);

    /** 그 token만 지운다. 이미 없으면 아무 일도 하지 않는다. */
    void release(String token);

    /** 지금 장부에 있는 token. 운영 확인(D-5)과 테스트가 쓴다. */
    Set<String> holders();
}
