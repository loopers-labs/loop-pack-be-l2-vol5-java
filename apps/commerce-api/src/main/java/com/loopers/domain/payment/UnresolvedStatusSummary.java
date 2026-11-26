package com.loopers.domain.payment;

import java.time.ZonedDateTime;

/**
 * 미종결 결제 시도의 상태별 요약 (10주차 업무 지표 입력).
 *
 * @param status          미종결 상태. SUCCESS·FAILED 는 오지 않는다.
 * @param count           그 상태의 건수. 0 은 조회에 성공했고 실제로 없다는 사실이다.
 * @param oldestCreatedAt 그 상태의 미종결 시도 중 가장 먼저 만들어진 시도의 생성 시각. count 가 0 이면 null 이다.
 *                        <p>
 *                        기준이 updatedAt 이 아니라 createdAt 인 이유: recordConflict 는 상태를 바꾸지 않으면서
 *                        updatedAt 을 갱신하므로 updatedAt 은 상태 진입 시각이 아니다 (10주차 설계 3-1-2).
 *                        그래서 이 값은 "그 상태에 머문 시간"이 아니라 "결제 시도가 끝나지 않은 채 흐른 시간"의 기준이다.
 */
public record UnresolvedStatusSummary(
    PaymentAttempt.Status status,
    long count,
    ZonedDateTime oldestCreatedAt
) {
}
