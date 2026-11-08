package com.loopers.domain.order;

import java.util.concurrent.CompletionStage;

/**
 * 저장된 key·payload 를 그대로 전송하는 포트(설계 W7.3-2·W7.3-5). 동기 호출 오류는 그대로 던지고 비동기 실패는 반환 stage 의
 * 예외 완료로 전파한다. 성공으로 바꾸거나 삼키지 않는다. 대기·재시도·DB 상태 변경은 하지 않으며 반환 stage 의 완료는 PUBLISHED 저장이 아니다.
 */
public interface OrderOutboxSender {

    CompletionStage<OrderOutboxReceipt> send(OrderOutboxAttempt attempt);
}
