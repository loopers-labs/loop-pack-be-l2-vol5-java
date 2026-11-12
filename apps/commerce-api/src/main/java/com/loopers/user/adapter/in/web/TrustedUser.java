package com.loopers.user.adapter.in.web;

/**
 * 인증 계층이 이미 검증한 요청자 (W8, docs/week8/plan.md 구현하며 정한 것).
 * 실무에서는 로그인 토큰(JWT)의 서명을 확인해 사용자를 알고, DB에 다시 묻지 않는다. 이 과제에는 토큰이 없어서
 * X-USER-ID 헤더를 "토큰으로 검증된 id"로 보고 형식만 확인한다(없거나 숫자가 아니면 401).
 * <p>
 * {@link LoginUser}(USR-01)와 다른 점: 사용자가 DB에 있는지 묻지 않는다. 100만 명이 몰리는 쿠폰 대기열 API에서
 * 요청마다 DB를 보지 않으려고 쓴다. 그 밖의 API는 계속 LoginUser를 쓴다.
 */
public record TrustedUser(Long id) {}
