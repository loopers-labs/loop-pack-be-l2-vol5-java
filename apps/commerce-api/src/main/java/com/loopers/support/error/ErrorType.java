package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorType implements ErrorCode {
    /** 범용 에러 */
    INTERNAL_ERROR("일시적인 오류가 발생했습니다."),
    BAD_REQUEST("잘못된 요청입니다."),
    UNAUTHENTICATED("요청자를 확인할 수 없습니다."),
    NOT_FOUND("존재하지 않는 요청입니다."),
    CONFLICT("이미 존재하는 리소스입니다."),
    /** 비관적 잠금을 얻지 못함(잠금 대기 시간 초과 · 교착). 원인은 로그로 구분함 (3주차 설계 4.5) */
    LOCK_ACQUISITION_FAILED("요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    /** 낙관적 잠금 충돌이 재시도 한도(총 3회)를 넘음. 재고 · 잔액 부족과 구분함 (3주차 설계 4.4) */
    CONCURRENT_UPDATE_CONFLICT("다른 요청과 동시에 처리되어 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
