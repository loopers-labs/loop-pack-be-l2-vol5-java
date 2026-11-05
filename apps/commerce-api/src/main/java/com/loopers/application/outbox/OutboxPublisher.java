package com.loopers.application.outbox;

import com.loopers.domain.outbox.Outbox;

/** relay 가 Outbox 를 브로커로 보내는 포트. 이를 쓰는 application 이 소유하고 infrastructure 가 구현한다. */
public interface OutboxPublisher {

    /**
     * 저장된 key·payload 를 그대로 보내고, 브로커가 받았음을 확인한 뒤에 반환한다.
     *
     * @throws OutboxPublishException 전송에 실패했거나 제한 시간 안에 확인하지 못했을 때. 브로커가 이미 받았을 수도 있다.
     */
    void publish(Outbox outbox);

    /** 지금 전송을 시도해도 되는지. 브로커 장애로 전송을 멈춘 동안에는 거짓이다. */
    default boolean canPublish() {
        return true;
    }
}
