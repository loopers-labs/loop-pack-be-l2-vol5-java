package com.loopers.interfaces.api;

/**
 * X-USER-ID로 식별된 요청자 (USR-01).
 * 컨트롤러 메서드 파라미터로 선언하면 LoginUserArgumentResolver가 식별을 마친 값을 넣어 준다.
 */
public record LoginUser(Long id) {}
