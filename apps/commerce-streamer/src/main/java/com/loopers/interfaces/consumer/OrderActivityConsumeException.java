package com.loopers.interfaces.consumer;

import lombok.Getter;

/**
 * 구매 활동 listener 의 실패를 container error handler 로 넘기는 안전한 오류. 단계·오류 코드·예외 타입·record 위치만 담는다.
 * 원래 예외는 원인으로 붙이지 않는다. 그 메시지에 SQL·parser 원문이 있을 수 있고 error handler 가 원인 사슬을 로그로 남기기 때문이다.
 */
@Getter
public class OrderActivityConsumeException extends RuntimeException {

    /** 실패한 처리 단계. ACK 실패는 DB 효과가 이미 commit 됐을 수 있다. */
    public enum Stage {
        PARSE, HANDLE, ACK
    }

    private final Stage stage;
    private final String code;

    public OrderActivityConsumeException(Stage stage, String code, String exceptionType, String topic, int partition,
                                         long offset) {
        super("구매 활동 소비 실패 [stage=" + stage + ", code=" + code + ", exceptionType=" + exceptionType
            + ", topic=" + topic + ", partition=" + partition + ", offset=" + offset + "]");
        this.stage = stage;
        this.code = code;
    }
}
