package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentCallbackInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZonedDateTime;

/**
 * callback 수신 — 원문을 수신함에 저장만 한다(docs/week5/design.md D-020). 조회와 반영은 회복 작업이 나중에 한다.
 * 저장이 끝나면 처리 결과와 상관없이 "받았다"(200)다. 저장이 실패하면 예외가 그대로 올라가 실패 응답이 된다.
 * 본문에서는 결제 시도를 찾을 키만 꺼낸다. 본문의 상태는 원문에만 남기고 판단에 쓰지 않는다.
 */
@RequiredArgsConstructor
@Component
public class PaymentCallbackFacade {

    private final PaymentCallbackInboxService paymentCallbackInboxService;
    private final ObjectMapper objectMapper;

    public void receive(String rawPayload) {
        JsonNode body = parse(rawPayload);
        paymentCallbackInboxService.receive(rawPayload, text(body, "transactionKey"), text(body, "providerOrderId"), ZonedDateTime.now());
    }

    private JsonNode parse(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawPayload);
        } catch (IOException e) {
            // 깨진 본문도 원문은 남긴다 — 키가 없으면 회복 작업이 매칭 실패로 정리한다.
            return null;
        }
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field) || !body.get(field).isTextual() || body.get(field).asText().isBlank()) {
            return null;
        }
        return body.get(field).asText();
    }
}
