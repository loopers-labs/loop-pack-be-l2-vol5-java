package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgPaymentRequest;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.payment.UnknownReason;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 테스트 PG 응답 본문을 관찰 결과로 바꾸는 규칙 (docs/week5/pg-contract.md 4-1·4-4절).
 *
 * <p>본문은 문자열로 받아 {@link JsonNode}로 읽고 필드마다 타입을 직접 검사한다. record·DTO로 바로 바인딩하지 않는다.
 * Jackson 바인딩은 문자열 {@code "false"}를 boolean {@code false}로 읽고, 공용 ObjectMapper는 객체 하나를 원소 1개 목록으로도 읽는다.
 * 그러면 계약을 어긴 422가 "효과 0건"으로 판정되어, PG에 거래가 남아 있을 수 있는데 재고를 돌려주게 된다 (명세서 13절 실험 E6).
 */
final class TestPgResponses {

    static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private static final ObjectMapper JSON = new ObjectMapper();

    private TestPgResponses() {
    }

    /**
     * 생성 응답을 관찰 결과로 바꾼다. 202와 정확한 422 말고는 모두 UNKNOWN이다.
     * 이유코드는 운영 기록용이라, 여러 조건에 걸리면 아래 검사 순서에서 먼저 걸린 것을 쓴다.
     */
    static PgCreateResult createResult(int status, String body, PgPaymentRequest request) {
        if (status == 202) {
            return accepted(body, request);
        }
        if (status == 422) {
            return rejection(body, request);
        }
        return new PgCreateResult.Unknown(UnknownReason.UNEXPECTED_STATUS);
    }

    /** 거래 객체 하나를 읽는다. 필드가 하나라도 계약과 다르면 비어 있다. 정해 둔 필드 밖의 필드는 무시한다. */
    static Optional<PgTransaction> transaction(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode key = node.get("transactionKey");
        JsonNode orderId = node.get("orderId");
        JsonNode amount = node.get("amount");
        Optional<PgTransactionStatus> status = status(node.get("status"));
        if (!isNonBlankText(key) || !isNonBlankText(orderId) || !isPositiveLong(amount) || status.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PgTransaction(key.asText(), orderId.asText(), amount.asLong(), status.get()));
    }

    /** 주문별 조회 본문을 읽는다. 배열이 아니거나 원소 하나라도 거래 객체 형식이 아니면 비어 있다. */
    static Optional<List<PgTransaction>> transactions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Optional.empty();
        }
        List<PgTransaction> list = new ArrayList<>();
        for (JsonNode element : node) {
            Optional<PgTransaction> transaction = transaction(element);
            if (transaction.isEmpty()) {
                return Optional.empty();
            }
            list.add(transaction.get());
        }
        return Optional.of(list);
    }

    /** 본문을 JSON으로 읽는다. 비어 있거나 올바른 JSON이 아니면 null이다. */
    static JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    // 본문의 status는 형식만 검사하고 판단에 쓰지 않는다. 생성 수락과 최종 성공을 구분하기 위해서다
    private static PgCreateResult accepted(String body, PgPaymentRequest request) {
        Optional<PgTransaction> transaction = transaction(readJson(body));
        if (transaction.isEmpty()) {
            return new PgCreateResult.Unknown(UnknownReason.INVALID_BODY);
        }
        if (!transaction.get().providerOrderId().equals(request.providerOrderId()) || transaction.get().amount() != request.amount()) {
            return new PgCreateResult.Unknown(UnknownReason.ECHO_MISMATCH);
        }
        return new PgCreateResult.Accepted(transaction.get().transactionKey());
    }

    // 422 + 정확한 code + boolean false + 요청 orderId·amount 일치를 모두 만족할 때만 효과 0건이다
    private static PgCreateResult rejection(String body, PgPaymentRequest request) {
        if (body == null || body.isBlank()) {
            return new PgCreateResult.Unknown(UnknownReason.UNEXPECTED_STATUS);
        }
        JsonNode json = readJson(body);
        if (json == null || !json.isObject()) {
            return new PgCreateResult.Unknown(UnknownReason.INVALID_BODY);
        }
        JsonNode code = json.get("code");
        if (!isNonBlankText(code) || !code.asText().equals(REJECTED_BEFORE_CREATE)) {
            return new PgCreateResult.Unknown(UnknownReason.UNEXPECTED_STATUS);
        }
        JsonNode created = json.get("transactionCreated");
        if (created == null || !created.isBoolean() || created.booleanValue()) {
            return new PgCreateResult.Unknown(UnknownReason.UNEXPECTED_STATUS);
        }
        JsonNode orderId = json.get("orderId");
        JsonNode amount = json.get("amount");
        if (!isNonBlankText(orderId) || !isPositiveLong(amount)) {
            return new PgCreateResult.Unknown(UnknownReason.INVALID_BODY);
        }
        if (!orderId.asText().equals(request.providerOrderId()) || amount.asLong() != request.amount()) {
            return new PgCreateResult.Unknown(UnknownReason.ECHO_MISMATCH);
        }
        return new PgCreateResult.RejectedNoEffect();
    }

    private static Optional<PgTransactionStatus> status(JsonNode node) {
        if (!isNonBlankText(node)) {
            return Optional.empty();
        }
        for (PgTransactionStatus status : PgTransactionStatus.values()) {
            if (status.name().equals(node.asText())) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private static boolean isPositiveLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0;
    }
}
