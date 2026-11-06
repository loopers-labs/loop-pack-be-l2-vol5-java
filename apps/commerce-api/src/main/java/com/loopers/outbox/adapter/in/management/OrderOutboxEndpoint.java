package com.loopers.outbox.adapter.in.management;

import com.loopers.outbox.application.port.in.OrderOutboxResumeUseCase;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * BLOCKED 행 재개 진입점 (docs/week7/plan.md D-3, 4단계). 관리 포트(8081)의 actuator에만 있고, local 프로필에서만 노출한다 —
 * 고객·관리자 공개 API(/api, /api-admin)가 아니다 (과제: 공개 관리자 API로 노출하지 않음).
 * <p>
 * {@code POST /actuator/outbox/{eventId}} → BLOCKED를 PENDING(시도 0)으로 돌린다. 보내는 것은 다음 relay tick이다.
 * 응답: 200 재개한 행 · 404 없는 eventId · 409 BLOCKED가 아님(값 그대로).
 */
@RequiredArgsConstructor
@Component
@Endpoint(id = "outbox")
public class OrderOutboxEndpoint {

    private final OrderOutboxResumeUseCase resumeUseCase;

    @WriteOperation
    public WebEndpointResponse<Object> resume(@Selector String eventId) {
        try {
            return new WebEndpointResponse<>(resumeUseCase.resume(eventId), WebEndpointResponse.STATUS_OK);
        } catch (CoreException e) {
            int status = e.getErrorType() == ErrorType.NOT_FOUND ? WebEndpointResponse.STATUS_NOT_FOUND : 409;
            return new WebEndpointResponse<>(Map.of("error", e.getErrorType().name(), "message", e.getMessage()), status);
        }
    }
}
