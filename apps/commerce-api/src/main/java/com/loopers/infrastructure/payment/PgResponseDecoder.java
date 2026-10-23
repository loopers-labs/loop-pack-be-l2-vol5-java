package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.domain.payment.ConflictEvidence;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentConflictCode;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PgTransactionStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * PG 응답 해석(2.8·5.5). HTTP 상태를 먼저 보고, 계약 상태일 때만 본문의 필수 필드·타입·형식을 검증한다.
 * 저장 상태·기대 금액·저장 거래 키와의 비교는 하지 않는다(TX B 책임). 요청과 응답 사이의 직접 대상 비교만 한다.
 * 해석 불가는 회복 대상 예외로, 검증 뒤 관찰 생성에서 난 오류는 프로그램 오류로 구분한다. 예외 메시지에 원문을 넣지 않는다.
 */
@Component
public class PgResponseDecoder {

    static final String REJECTED_BEFORE_CREATE = "TEST_PG_REJECTED_BEFORE_CREATE";

    private static final Pattern TRANSACTION_KEY = Pattern.compile("[A-Za-z0-9_-]{1,255}");

    private final ObjectMapper objectMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    PgCreateResult decodeCreate(PgHttpResponse response, PaymentRequest request) {
        return switch (response.status()) {
            case 202 -> createdTransaction(response, request, PgTransactionStatus.PENDING);
            case 200 -> createdTransaction(response, request, PgTransactionStatus.SUCCESS);
            case 422 -> rejectedBeforeCreate(response, request);
            default -> throw new PgCreateUnavailableException("생성 계약 외 HTTP 상태: " + response.status());
        };
    }

    PgLookupResult decodeLookupByKey(PgHttpResponse response, String requestedKey) {
        if (response.status() == 404) {
            // 고정 PG 단건 조회의 계약상 거래 부재. 통신 성공이며 거래를 확인하지 못한 관찰이다.
            return new PgLookupResult(new PaymentObservation.Unavailable(ObservationSource.QUERY), false);
        }
        requireLookupOk(response);
        PaymentObservation.TransactionObserved transaction =
            parseTransaction(readTree(response, PgLookupUnavailableException::new), ObservationSource.QUERY,
                PgLookupUnavailableException::new);
        if (!transaction.transactionKey().equals(requestedKey)) {
            return new PgLookupResult(conflict(PaymentConflictCode.IDENTITY_MISMATCH,
                () -> ConflictEvidence.of(transaction)), true);
        }
        return new PgLookupResult(transaction, false);
    }

    PgLookupResult decodeLookupByOrder(PgHttpResponse response, String requestedProviderOrderId) {
        requireLookupOk(response);
        JsonNode root = readTree(response, PgLookupUnavailableException::new);
        if (!root.isArray()) {
            throw new PgLookupUnavailableException("주문별 조회 응답이 배열이 아님");
        }
        List<PaymentObservation.TransactionObserved> transactions = new ArrayList<>();
        for (JsonNode element : root) {
            transactions.add(parseTransaction(element, ObservationSource.QUERY, PgLookupUnavailableException::new));
        }
        boolean otherOrderIncluded = transactions.stream()
            .anyMatch(transaction -> !transaction.providerOrderId().equals(requestedProviderOrderId));
        if (transactions.isEmpty()) {
            return new PgLookupResult(new PaymentObservation.Unavailable(ObservationSource.QUERY), false);
        }
        if (transactions.size() == 1) {
            PaymentObservation.TransactionObserved only = transactions.get(0);
            if (otherOrderIncluded) {
                return new PgLookupResult(conflict(PaymentConflictCode.IDENTITY_MISMATCH,
                    () -> ConflictEvidence.of(only)), true);
            }
            return new PgLookupResult(only, false);
        }
        // 2건 이상은 업무상 MULTIPLE_TRANSACTIONS 가 우선이고, 다른 주문이 섞였는지만 실패 집계 표시로 남긴다.
        return new PgLookupResult(conflict(PaymentConflictCode.MULTIPLE_TRANSACTIONS,
            () -> ConflictEvidence.ofCount(transactions.size())), otherOrderIncluded);
    }

    private PgCreateResult createdTransaction(PgHttpResponse response, PaymentRequest request,
                                              PgTransactionStatus expectedStatus) {
        PaymentObservation.TransactionObserved transaction =
            parseTransaction(readTree(response, PgCreateUnavailableException::new), ObservationSource.CREATE,
                PgCreateUnavailableException::new);
        if (transaction.status() != expectedStatus) {
            throw new PgCreateUnavailableException("생성 응답의 HTTP 상태와 거래 상태가 계약과 다름");
        }
        // 식별 불일치가 금액 불일치보다 앞선다(3.6). 저장 키 비교는 TX B 가 다시 한다.
        if (!transaction.providerOrderId().equals(request.providerOrderId())) {
            return new PgCreateResult(createConflict(PaymentConflictCode.IDENTITY_MISMATCH, transaction), true);
        }
        if (transaction.amount() != request.amount()) {
            return new PgCreateResult(createConflict(PaymentConflictCode.AMOUNT_MISMATCH, transaction), true);
        }
        return new PgCreateResult(transaction, false);
    }

    /** 422·정확한 code·boolean false·요청 주문/금액 일치를 모두 만족할 때만 효과 0건으로 본다(5.5). 그 밖은 확인 불가다. */
    private PgCreateResult rejectedBeforeCreate(PgHttpResponse response, PaymentRequest request) {
        JsonNode root = readTree(response, PgCreateUnavailableException::new);
        JsonNode code = root.get("code");
        JsonNode orderId = root.get("orderId");
        JsonNode amount = root.get("amount");
        JsonNode transactionCreated = root.get("transactionCreated");
        boolean exact = root.isObject()
            && code != null && code.isTextual() && REJECTED_BEFORE_CREATE.equals(code.textValue())
            && orderId != null && orderId.isTextual() && request.providerOrderId().equals(orderId.textValue())
            && amount != null && amount.isIntegralNumber() && amount.canConvertToLong()
            && amount.longValue() == request.amount()
            && transactionCreated != null && transactionCreated.isBoolean() && !transactionCreated.booleanValue();
        if (!exact) {
            throw new PgCreateUnavailableException("정확한 생성 전 거절 계약이 아닌 422");
        }
        return new PgCreateResult(observe(() -> new PaymentObservation.RejectedNoEffect(
            request.providerOrderId(), request.amount())), false);
    }

    private PaymentObservation createConflict(PaymentConflictCode code, PaymentObservation.TransactionObserved transaction) {
        return observe(() -> new PaymentObservation.Conflict(ObservationSource.CREATE, code,
            ConflictEvidence.of(transaction)));
    }

    private PaymentObservation conflict(PaymentConflictCode code, Supplier<ConflictEvidence> evidence) {
        return observe(() -> new PaymentObservation.Conflict(ObservationSource.QUERY, code, evidence.get()));
    }

    /** 200 이외 2xx·3xx 를 포함한 모든 비 200 상태는 본문을 해석하지 않고 회복 대상 실패다(2.8). */
    private static void requireLookupOk(PgHttpResponse response) {
        if (response.status() != 200) {
            throw new PgLookupUnavailableException("조회 계약 외 HTTP 상태: " + response.status());
        }
    }

    private <E extends RuntimeException> JsonNode readTree(PgHttpResponse response, Function<String, E> unavailable) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw unavailable.apply("응답 JSON 해석 불가");
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw unavailable.apply("응답 본문 없음");
        }
        return root;
    }

    private <E extends RuntimeException> PaymentObservation.TransactionObserved parseTransaction(
        JsonNode node, ObservationSource source, Function<String, E> unavailable) {
        if (!node.isObject()) {
            throw unavailable.apply("거래 객체가 아님");
        }
        String transactionKey = requireText(node, "transactionKey", unavailable);
        if (!TRANSACTION_KEY.matcher(transactionKey).matches()) {
            throw unavailable.apply("거래 키 형식 오류");
        }
        String orderId = requireText(node, "orderId", unavailable);
        if (orderId.isEmpty()) {
            throw unavailable.apply("orderId 형식 오류");
        }
        JsonNode amount = node.get("amount");
        if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() <= 0) {
            throw unavailable.apply("amount 형식 오류");
        }
        PgTransactionStatus status = parseStatus(requireText(node, "status", unavailable), unavailable);
        return observe(() -> new PaymentObservation.TransactionObserved(
            source, transactionKey, orderId, amount.longValue(), status));
    }

    private static <E extends RuntimeException> String requireText(JsonNode node, String field,
                                                                   Function<String, E> unavailable) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw unavailable.apply(field + " 누락 또는 타입 오류");
        }
        return value.textValue();
    }

    private static <E extends RuntimeException> PgTransactionStatus parseStatus(String value,
                                                                              Function<String, E> unavailable) {
        for (PgTransactionStatus status : PgTransactionStatus.values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw unavailable.apply("알 수 없는 거래 상태");
    }

    /** 계약 검증을 통과한 값으로 관찰을 만들다 실패하면 응답 문제가 아니라 우리 코드 결함이다. */
    private static <T> T observe(Supplier<T> construction) {
        try {
            return construction.get();
        } catch (RuntimeException e) {
            throw new PgAdapterProgrammingException("검증한 PG 응답으로 관찰 생성 실패", e);
        }
    }
}
