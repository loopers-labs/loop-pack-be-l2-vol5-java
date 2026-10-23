package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.ConflictEvidence;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentConflictCode;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PgTransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PG 응답 decoder 는 HTTP 상태를 먼저 보고 계약 응답만 관찰로 해석한다.")
class PgResponseDecoderTest {

    private static final String KEY = "tx-A_1";
    private static final String ORDER = "LP-ORD-0000000042";
    private static final PaymentRequest REQUEST = new PaymentRequest(ORDER, 7000, "TEST", "0000-0000-0000-0000");

    private final PgResponseDecoder decoder = new PgResponseDecoder();

    @DisplayName("거래 단건 조회를 해석할 때,")
    @Nested
    class LookupByKey {

        @DisplayName("200 의 요청 거래면 QUERY 거래 관찰이고 대상 위반이 아니다.")
        @Test
        void validTransaction() {
            PgLookupResult result = decoder.decodeLookupByKey(response(200, tx(KEY, ORDER, 7000, "FAILED")), KEY);

            assertThat(result).isEqualTo(new PgLookupResult(new PaymentObservation.TransactionObserved(
                ObservationSource.QUERY, KEY, ORDER, 7000, PgTransactionStatus.FAILED), false));
        }

        @DisplayName("200 이지만 다른 거래 키면 IDENTITY_MISMATCH 충돌과 대상 위반 표시를 함께 돌려준다.")
        @Test
        void otherTransactionKey() {
            PgLookupResult result = decoder.decodeLookupByKey(response(200, tx("tx-B", ORDER, 7000, "SUCCESS")), KEY);

            assertThat(result).isEqualTo(new PgLookupResult(new PaymentObservation.Conflict(ObservationSource.QUERY,
                PaymentConflictCode.IDENTITY_MISMATCH,
                new ConflictEvidence(ORDER, "tx-B", 7000L, PgTransactionStatus.SUCCESS, null)), true));
        }

        @DisplayName("요청 거래 키가 맞으면 다른 주문 ID·금액이어도 대상 위반이 아니다(내부 비교는 TX B).")
        @Test
        void requestedKeyWithOtherOrder() {
            PgLookupResult result = decoder.decodeLookupByKey(response(200, tx(KEY, "LP-ORD-0000000099", 1, "SUCCESS")), KEY);

            assertThat(result.directLookupTargetMismatch()).isFalse();
            assertThat(result.observation()).isInstanceOf(PaymentObservation.TransactionObserved.class);
        }

        @DisplayName("계약상 404 는 예외가 아닌 QUERY 확인 불가다.")
        @Test
        void notFound() {
            assertThat(decoder.decodeLookupByKey(response(404, "{\"code\":\"x\"}"), KEY)).isEqualTo(
                new PgLookupResult(new PaymentObservation.Unavailable(ObservationSource.QUERY), false));
        }

        @DisplayName("200 외 상태와 해석 불가 본문은 회복 대상 조회 실패다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.infrastructure.payment.PgResponseDecoderTest#unusableLookupResponses")
        void unusable(String description, int status, String body) {
            assertThatThrownBy(() -> decoder.decodeLookupByKey(response(status, body), KEY))
                .isInstanceOf(PgLookupUnavailableException.class);
        }
    }

    @DisplayName("주문별 조회를 해석할 때,")
    @Nested
    class LookupByOrder {

        @DisplayName("200 빈 배열은 QUERY 확인 불가이고 대상 위반이 아니다.")
        @Test
        void empty() {
            assertThat(decoder.decodeLookupByOrder(response(200, "[]"), ORDER)).isEqualTo(
                new PgLookupResult(new PaymentObservation.Unavailable(ObservationSource.QUERY), false));
        }

        @DisplayName("요청 주문의 1건이면 거래 관찰이다.")
        @Test
        void single() {
            PgLookupResult result = decoder.decodeLookupByOrder(response(200, "[" + tx(KEY, ORDER, 7000, "PENDING") + "]"), ORDER);

            assertThat(result).isEqualTo(new PgLookupResult(new PaymentObservation.TransactionObserved(
                ObservationSource.QUERY, KEY, ORDER, 7000, PgTransactionStatus.PENDING), false));
        }

        @DisplayName("다른 주문의 1건이면 IDENTITY_MISMATCH 와 대상 위반이다.")
        @Test
        void singleOtherOrder() {
            PgLookupResult result = decoder.decodeLookupByOrder(
                response(200, "[" + tx(KEY, "LP-ORD-0000000099", 7000, "SUCCESS") + "]"), ORDER);

            assertThat(result).isEqualTo(new PgLookupResult(new PaymentObservation.Conflict(ObservationSource.QUERY,
                PaymentConflictCode.IDENTITY_MISMATCH,
                new ConflictEvidence("LP-ORD-0000000099", KEY, 7000L, PgTransactionStatus.SUCCESS, null)), true));
        }

        @DisplayName("요청 주문의 복수 거래는 MULTIPLE_TRANSACTIONS(건수만)이고 대상 위반이 아니다.")
        @Test
        void exactMultiple() {
            PgLookupResult result = decoder.decodeLookupByOrder(response(200,
                "[" + tx("k1", ORDER, 7000, "PENDING") + "," + tx("k2", ORDER, 7000, "SUCCESS") + ","
                    + tx("k3", ORDER, 7000, "FAILED") + "]"), ORDER);

            assertThat(result).isEqualTo(new PgLookupResult(new PaymentObservation.Conflict(ObservationSource.QUERY,
                PaymentConflictCode.MULTIPLE_TRANSACTIONS, new ConflictEvidence(null, null, null, null, 3)), false));
        }

        @DisplayName("복수 거래에 다른 주문이 섞이면 위치와 관계없이 MULTIPLE_TRANSACTIONS 와 대상 위반이다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 1})
        void mixedMultiple(int otherIndex) {
            String own = tx("k1", ORDER, 7000, "PENDING");
            String other = tx("k2", "LP-ORD-0000000099", 7000, "PENDING");
            String body = otherIndex == 0 ? "[" + other + "," + own + "]" : "[" + own + "," + other + "]";

            PgLookupResult result = decoder.decodeLookupByOrder(response(200, body), ORDER);

            assertThat(result).isEqualTo(new PgLookupResult(new PaymentObservation.Conflict(ObservationSource.QUERY,
                PaymentConflictCode.MULTIPLE_TRANSACTIONS, new ConflictEvidence(null, null, null, null, 2)), true));
        }

        @DisplayName("배열이 아니거나 원소 하나라도 해석할 수 없으면 회복 대상 조회 실패다.")
        @ParameterizedTest
        @ValueSource(strings = {"{}", "null", "", "[null]", "[1]", "[{\"transactionKey\":\"k1\"}]"})
        void unusableArray(String body) {
            String withValid = body.startsWith("[{") ? "[" + tx("k0", ORDER, 7000, "PENDING") + "," + body.substring(1) : body;

            assertThatThrownBy(() -> decoder.decodeLookupByOrder(response(200, withValid), ORDER))
                .isInstanceOf(PgLookupUnavailableException.class);
        }

        @DisplayName("주문별 조회의 404 는 계약상 부재가 아니라 실패다.")
        @Test
        void notFoundIsFailure() {
            assertThatThrownBy(() -> decoder.decodeLookupByOrder(response(404, ""), ORDER))
                .isInstanceOf(PgLookupUnavailableException.class);
        }
    }

    @DisplayName("생성 응답을 해석할 때,")
    @Nested
    class Create {

        @DisplayName("202 PENDING·200 SUCCESS 의 요청 주문·금액이면 CREATE 거래 관찰이다.")
        @Test
        void created() {
            assertThat(decoder.decodeCreate(response(202, tx(KEY, ORDER, 7000, "PENDING")), REQUEST)).isEqualTo(
                new PgCreateResult(new PaymentObservation.TransactionObserved(
                    ObservationSource.CREATE, KEY, ORDER, 7000, PgTransactionStatus.PENDING), false));
            assertThat(decoder.decodeCreate(response(200, tx(KEY, ORDER, 7000, "SUCCESS")), REQUEST).observation())
                .isEqualTo(new PaymentObservation.TransactionObserved(
                    ObservationSource.CREATE, KEY, ORDER, 7000, PgTransactionStatus.SUCCESS));
        }

        @DisplayName("주문 ID 가 다르면 금액 차이보다 앞서 IDENTITY_MISMATCH, 금액만 다르면 AMOUNT_MISMATCH 이고 둘 다 직접 위반이다.")
        @Test
        void mismatchedCreateResponse() {
            PgCreateResult otherOrder = decoder.decodeCreate(response(202, tx(KEY, "LP-ORD-0000000099", 1, "PENDING")), REQUEST);
            PgCreateResult otherAmount = decoder.decodeCreate(response(202, tx(KEY, ORDER, 7001, "PENDING")), REQUEST);

            assertThat(otherOrder).isEqualTo(new PgCreateResult(new PaymentObservation.Conflict(ObservationSource.CREATE,
                PaymentConflictCode.IDENTITY_MISMATCH,
                new ConflictEvidence("LP-ORD-0000000099", KEY, 1L, PgTransactionStatus.PENDING, null)), true));
            assertThat(otherAmount).isEqualTo(new PgCreateResult(new PaymentObservation.Conflict(ObservationSource.CREATE,
                PaymentConflictCode.AMOUNT_MISMATCH,
                new ConflictEvidence(ORDER, KEY, 7001L, PgTransactionStatus.PENDING, null)), true));
        }

        @DisplayName("정확한 422 는 효과 0건 거절이다.")
        @Test
        void exactReject() {
            String body = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + ORDER
                + "\",\"amount\":7000,\"transactionCreated\":false}";

            assertThat(decoder.decodeCreate(response(422, body), REQUEST)).isEqualTo(
                new PgCreateResult(new PaymentObservation.RejectedNoEffect(ORDER, 7000), false));
        }

        @DisplayName("정확한 계약이 아닌 422·계약 외 상태·해석 불가는 생성 확인 불가 실패다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.loopers.infrastructure.payment.PgResponseDecoderTest#unusableCreateResponses")
        void unusable(String description, int status, String body) {
            assertThatThrownBy(() -> decoder.decodeCreate(response(status, body), REQUEST))
                .isInstanceOf(PgCreateUnavailableException.class);
        }
    }

    static Stream<Arguments> unusableLookupResponses() {
        String valid = tx(KEY, ORDER, 7000, "SUCCESS");
        return Stream.of(
            Arguments.of("201", 201, valid),
            Arguments.of("202", 202, valid),
            Arguments.of("204", 204, ""),
            Arguments.of("301", 301, valid),
            Arguments.of("302", 302, valid),
            Arguments.of("303", 303, valid),
            Arguments.of("307", 307, valid),
            Arguments.of("308", 308, valid),
            Arguments.of("400", 400, ""),
            Arguments.of("401", 401, ""),
            Arguments.of("403", 403, ""),
            Arguments.of("405", 405, ""),
            Arguments.of("409", 409, ""),
            Arguments.of("422", 422, ""),
            Arguments.of("429", 429, ""),
            Arguments.of("500 + SUCCESS 본문", 500, valid),
            Arguments.of("501", 501, ""),
            Arguments.of("503", 503, ""),
            Arguments.of("505", 505, ""),
            Arguments.of("깨진 JSON", 200, "{\"transactionKey\":"),
            Arguments.of("빈 본문", 200, ""),
            Arguments.of("JSON null", 200, "null"),
            Arguments.of("배열", 200, "[" + valid + "]"),
            Arguments.of("중복 필드", 200, valid.replace("{", "{\"status\":\"FAILED\",")),
            Arguments.of("뒤에 추가 값", 200, valid + "{}"),
            Arguments.of("거래 키 누락", 200, "{\"orderId\":\"" + ORDER + "\",\"amount\":7000,\"status\":\"SUCCESS\"}"),
            Arguments.of("거래 키 형식", 200, tx("tx A", ORDER, 7000, "SUCCESS")),
            Arguments.of("거래 키 256자", 200, tx("k".repeat(256), ORDER, 7000, "SUCCESS")),
            Arguments.of("빈 주문 ID", 200, tx(KEY, "", 7000, "SUCCESS")),
            Arguments.of("금액 문자열", 200, valid.replace("\"amount\":7000", "\"amount\":\"7000\"")),
            Arguments.of("금액 소수", 200, valid.replace("\"amount\":7000", "\"amount\":7000.5")),
            Arguments.of("금액 0", 200, valid.replace("\"amount\":7000", "\"amount\":0")),
            Arguments.of("금액 long 초과", 200, valid.replace("\"amount\":7000", "\"amount\":92233720368547758070")),
            Arguments.of("알 수 없는 상태", 200, tx(KEY, ORDER, 7000, "CANCELED")),
            Arguments.of("상태 소문자", 200, tx(KEY, ORDER, 7000, "success"))
        );
    }

    static Stream<Arguments> unusableCreateResponses() {
        String exact = "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + ORDER
            + "\",\"amount\":7000,\"transactionCreated\":false}";
        return Stream.of(
            Arguments.of("본문 없는 422", 422, ""),
            Arguments.of("다른 code 422", 422, exact.replace("TEST_PG_REJECTED_BEFORE_CREATE", "REJECTED")),
            Arguments.of("transactionCreated true", 422, exact.replace("false", "true")),
            Arguments.of("transactionCreated 문자열", 422, exact.replace("false", "\"false\"")),
            Arguments.of("422 주문 불일치", 422, exact.replace(ORDER, "LP-ORD-0000000099")),
            Arguments.of("422 금액 불일치", 422, exact.replace("7000", "7001")),
            Arguments.of("202 SUCCESS", 202, tx(KEY, ORDER, 7000, "SUCCESS")),
            Arguments.of("200 PENDING", 200, tx(KEY, ORDER, 7000, "PENDING")),
            Arguments.of("202 FAILED", 202, tx(KEY, ORDER, 7000, "FAILED")),
            Arguments.of("201", 201, tx(KEY, ORDER, 7000, "PENDING")),
            Arguments.of("302", 302, ""),
            Arguments.of("400", 400, ""),
            Arguments.of("409", 409, ""),
            Arguments.of("500", 500, ""),
            Arguments.of("202 깨진 JSON", 202, "{"),
            Arguments.of("202 금액 누락", 202, "{\"transactionKey\":\"k\",\"orderId\":\"" + ORDER + "\",\"status\":\"PENDING\"}")
        );
    }

    private static PgHttpResponse response(int status, String body) {
        return new PgHttpResponse(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String tx(String key, String orderId, long amount, String status) {
        return "{\"transactionKey\":\"" + key + "\",\"orderId\":\"" + orderId + "\",\"amount\":" + amount
            + ",\"status\":\"" + status + "\"}";
    }
}
