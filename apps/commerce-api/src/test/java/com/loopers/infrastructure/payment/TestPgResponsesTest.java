package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PgCreateResult;
import com.loopers.domain.payment.PgPaymentRequest;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.domain.payment.UnknownReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 본문 해석 규칙(docs/week5/pg-contract.md 4-1·4-4절)을 HTTP 없이 확인한다.
 * 테스트 PG fixture로는 만들기 어려운 형식(배열이 아닌 목록, 소수 금액 등)도 여기서 다룬다.
 */
class TestPgResponsesTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final PgPaymentRequest REQUEST = new PgPaymentRequest(ORDER_ID, "TEST", "0000-0000-0000-0000", 7000);
    private static final String VALID_TRANSACTION =
        "{\"transactionKey\":\"TEST-TX-0001\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000,\"status\":\"PENDING\"}";
    private static final String EXACT_REJECTION =
        "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + ORDER_ID + "\",\"amount\":7000,\"transactionCreated\":false}";

    @DisplayName("거래 객체를 읽을 때, ")
    @Nested
    class TransactionObject {

        @DisplayName("필드가 모두 계약의 타입이면 거래로 읽고, 정해 둔 필드 밖의 필드는 무시한다.")
        @Test
        void readsValidTransaction_ignoringUnknownFields() {
            // act
            var result = TestPgResponses.transaction(TestPgResponses.readJson(VALID_TRANSACTION.replace("}", ",\"extra\":1}")));

            // assert
            assertThat(result).contains(new PgTransaction("TEST-TX-0001", ORDER_ID, 7000, PgTransactionStatus.PENDING));
        }

        @DisplayName("금액이 문자열·소수·0 이하이거나, 상태가 세 값 밖이거나, 필드가 없으면 거래로 읽지 않는다.")
        @Test
        void rejectsTransaction_whenAnyFieldBreaksContract() {
            // arrange
            Map<String, String> invalid = Map.of(
                "금액이 문자열", VALID_TRANSACTION.replace("7000", "\"7000\""),
                "금액이 소수", VALID_TRANSACTION.replace("7000", "7000.0"),
                "금액이 0", VALID_TRANSACTION.replace("7000", "0"),
                "상태가 세 값 밖", VALID_TRANSACTION.replace("PENDING", "CANCELED"),
                "상태가 소문자", VALID_TRANSACTION.replace("PENDING", "pending"),
                "거래 키 없음", VALID_TRANSACTION.replace("\"transactionKey\":\"TEST-TX-0001\",", ""),
                "객체가 아님", "[" + VALID_TRANSACTION + "]"
            );

            // act & assert
            invalid.forEach((label, body) ->
                assertThat(TestPgResponses.transaction(TestPgResponses.readJson(body))).as(label).isEmpty());
        }
    }

    @DisplayName("주문별 조회 본문을 읽을 때, ")
    @Nested
    class TransactionList {

        @DisplayName("배열이면 순서대로 읽고, 빈 배열은 0건이다.")
        @Test
        void readsArrayInOrder() {
            // arrange
            String second = VALID_TRANSACTION.replace("TEST-TX-0001", "TEST-TX-0002");

            // act
            var two = TestPgResponses.transactions(TestPgResponses.readJson("[" + VALID_TRANSACTION + "," + second + "]"));
            var none = TestPgResponses.transactions(TestPgResponses.readJson("[]"));

            // assert
            assertThat(two).get().extracting(list -> list.stream().map(PgTransaction::transactionKey).toList())
                .isEqualTo(List.of("TEST-TX-0001", "TEST-TX-0002"));
            assertThat(none).contains(List.of());
        }

        @DisplayName("배열이 아닌 객체 하나는 원소 1개 목록으로 읽지 않고, 원소 하나라도 형식이 틀리면 전체를 읽지 않는다.")
        @Test
        void rejectsNonArrayOrInvalidElement() {
            // act & assert
            assertThat(TestPgResponses.transactions(TestPgResponses.readJson(VALID_TRANSACTION))).isEmpty();
            assertThat(TestPgResponses.transactions(TestPgResponses.readJson("[" + VALID_TRANSACTION + ",{\"transactionKey\":1}]"))).isEmpty();
            assertThat(TestPgResponses.transactions(TestPgResponses.readJson("{"))).isEmpty();
        }
    }

    @DisplayName("생성 응답을 해석할 때, ")
    @Nested
    class CreateResponse {

        @DisplayName("202이고 요청한 주문·금액을 그대로 돌려주면 접수로 본다. 본문의 status는 판단에 쓰지 않는다.")
        @Test
        void acceptsMatching202_regardlessOfStatusValue() {
            // act
            PgCreateResult pending = TestPgResponses.createResult(202, VALID_TRANSACTION, REQUEST);
            PgCreateResult success = TestPgResponses.createResult(202, VALID_TRANSACTION.replace("PENDING", "SUCCESS"), REQUEST);

            // assert
            assertThat(pending).isEqualTo(new PgCreateResult.Accepted("TEST-TX-0001"));
            assertThat(success).isEqualTo(new PgCreateResult.Accepted("TEST-TX-0001"));
        }

        @DisplayName("202라도 형식이 틀리면 INVALID_BODY, 주문·금액이 요청과 다르면 ECHO_MISMATCH인 결과 미확인이다.")
        @Test
        void treatsMalformedOrMismatched202AsUnknown() {
            // act & assert
            assertThat(TestPgResponses.createResult(202, "{", REQUEST)).isEqualTo(unknown(UnknownReason.INVALID_BODY));
            assertThat(TestPgResponses.createResult(202, VALID_TRANSACTION.replace("7000", "\"7000\""), REQUEST))
                .isEqualTo(unknown(UnknownReason.INVALID_BODY));
            assertThat(TestPgResponses.createResult(202, VALID_TRANSACTION.replace("7000", "9000"), REQUEST))
                .isEqualTo(unknown(UnknownReason.ECHO_MISMATCH));
        }

        @DisplayName("422 + 정확한 code + boolean false + 주문·금액 일치를 모두 만족할 때만 효과 0건 거절로 본다.")
        @Test
        void treatsOnlyExact422AsRejectedNoEffect() {
            // act & assert
            assertThat(TestPgResponses.createResult(422, EXACT_REJECTION, REQUEST)).isEqualTo(new PgCreateResult.RejectedNoEffect());
        }

        @DisplayName("422의 변형은 모두 결과 미확인이다. 타입 바인딩이면 false로 읽혔을 문자열 \"false\"·누락도 마찬가지다.")
        @Test
        void treats422VariantsAsUnknown() {
            // arrange
            Map<String, PgCreateResult> expected = Map.of(
                "", unknown(UnknownReason.UNEXPECTED_STATUS),
                EXACT_REJECTION.replace("TEST_PG_REJECTED_BEFORE_CREATE", "OTHER"), unknown(UnknownReason.UNEXPECTED_STATUS),
                EXACT_REJECTION.replace("false", "true"), unknown(UnknownReason.UNEXPECTED_STATUS),
                EXACT_REJECTION.replace("false", "\"false\""), unknown(UnknownReason.UNEXPECTED_STATUS),
                EXACT_REJECTION.replace(",\"transactionCreated\":false", ""), unknown(UnknownReason.UNEXPECTED_STATUS),
                EXACT_REJECTION.replace("7000", "\"7000\""), unknown(UnknownReason.INVALID_BODY),
                EXACT_REJECTION.replace(ORDER_ID, "LP-ORD-0000000099"), unknown(UnknownReason.ECHO_MISMATCH),
                EXACT_REJECTION.replace("7000", "9000"), unknown(UnknownReason.ECHO_MISMATCH),
                "not json", unknown(UnknownReason.INVALID_BODY)
            );

            // act & assert
            expected.forEach((body, result) ->
                assertThat(TestPgResponses.createResult(422, body, REQUEST)).as(body).isEqualTo(result));
        }

        @DisplayName("202·422 밖의 상태 코드는 본문과 상관없이 결과 미확인이다.")
        @Test
        void treatsOtherStatusesAsUnknown() {
            // act & assert
            for (int status : new int[] {200, 400, 404, 500, 503}) {
                assertThat(TestPgResponses.createResult(status, EXACT_REJECTION, REQUEST)).as("status " + status)
                    .isEqualTo(unknown(UnknownReason.UNEXPECTED_STATUS));
            }
        }
    }

    private static PgCreateResult unknown(UnknownReason reason) {
        return new PgCreateResult.Unknown(reason);
    }
}
