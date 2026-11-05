package com.loopers.outbox.adapter.in.management;

import com.loopers.outbox.application.port.in.OrderOutboxResumeUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.stereotype.Component;

/**
 * BLOCKED 행 재개 진입점 (docs/week7/plan.md D-3, 4단계). 관리 포트(8081)의 actuator에만 있고, local 프로필에서만 노출한다 —
 * 고객·관리자 공개 API(/api, /api-admin)가 아니다 (과제: 공개 관리자 API로 노출하지 않음).
 * <p>
 * {@code POST /actuator/outbox/{eventId}} → BLOCKED를 PENDING(시도 0)으로 돌린다. 보내는 것은 다음 relay tick이다.
 */
@RequiredArgsConstructor
@Component
@Endpoint(id = "outbox")
public class OrderOutboxEndpoint {

    private final OrderOutboxResumeUseCase resumeUseCase;

    @WriteOperation
    public WebEndpointResponse<Object> resume(@Selector String eventId) {
        return new WebEndpointResponse<>(WebEndpointResponse.STATUS_OK);
    }
}
